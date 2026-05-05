## 1. 文档信息
- 文档名称：Agent 记忆设计（短期/长期/压缩/治理）
- 版本：v1.0
- 日期：2026-05-01
- 依赖文档：
- `Nexus IM + Agent 架构设计.md`
- `Java 网关与 Python Agent 接口契约.md`
- `Agent 设计说明（推理、工具、记忆、安全）.md`

---

## 2. 设计目标

1. 支持多轮上下文，提升 Agent 在 IM 场景的连续性。
2. 控制 token 成本，避免上下文无限增长。
3. 避免记忆污染和越权引用。
4. 提供可审计、可清理、可回滚的记忆机制。

---

## 3. 记忆分层模型

1. 短期记忆（Short-term Memory）
- 作用：当前会话连续理解
- 存储：Redis
- 特征：高频读写、TTL、可丢失后重建

2. 长期记忆（Long-term Memory）
- 作用：稳定事实和偏好
- 存储：MySQL
- 特征：低频写入、高价值、可审计

3. 会话摘要（Session Summary Memory）
- 作用：长对话压缩
- 存储：MySQL + Redis缓存
- 特征：替代部分原始历史，降 token

---

## 4. 短期记忆设计（Redis）

## 4.1 Key 规范
1. 会话上下文：
- `agent:ctx:{userId}:{sessionId}`

2. 会话工具摘要：
- `agent:tool:{userId}:{sessionId}`

3. 流式断线重连缓存：
- `agent:sse:{userId}:{sessionId}:{traceId}`

## 4.2 数据结构
建议采用 Redis Hash + List：

1. `agent:ctx:*`（Hash）
- `meta`：会话元信息（JSON）
- `summary`：当前窗口摘要
- `updatedAt`

2. `agent:ctx:*:messages`（List）
- 每项为标准消息 JSON：
```json
{
  "role": "user|assistant|tool",
  "content": "text",
  "createdAt": "2026-05-01T10:00:00+08:00",
  "tokens": 85
}
```

## 4.3 TTL 策略
1. 默认 TTL：7 天（`604800s`）。
2. 每次会话活跃自动续期。
3. 可配置按业务分层：
- 高频用户：14 天
- 普通用户：7 天
- 低活跃用户：3 天

---

## 5. 长期记忆设计（MySQL）

## 5.1 表结构：`agent_long_memory`
```sql
CREATE TABLE agent_long_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,        -- PREFERENCE / FACT / HABIT / CONSTRAINT
  content TEXT NOT NULL,                   -- 记忆文本（脱敏后）
  confidence DECIMAL(4,3) NOT NULL,        -- 0.000 ~ 1.000
  source_session_id VARCHAR(64) NOT NULL,
  source_trace_id VARCHAR(64) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_user_type_active (user_id, memory_type, is_active),
  INDEX idx_user_updated (user_id, updated_at)
);
```

## 5.2 表结构：`agent_memory_audit`
```sql
CREATE TABLE agent_memory_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  memory_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,             -- CREATE / UPDATE / DISABLE / DELETE
  operator_type VARCHAR(16) NOT NULL,      -- SYSTEM / USER / ADMIN
  operator_id BIGINT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_memory_action (memory_id, action)
);
```

---

## 6. 记忆写入规则（防污染核心）

## 6.1 允许写入的记忆类型
1. 用户偏好：
- “我喜欢简洁风格回复”
- “回复尽量正式一点”

2. 稳定事实：
- “我在上海时区”
- “我负责项目A”

3. 约束类偏好：
- “不要自动发消息到群里，先给我草稿”

## 6.2 禁止写入内容
1. 一次性噪声：
- “我今天有点累”

2. 敏感原文：
- 手机号、邮箱、身份证号、token、银行卡

3. 未确认推断：
- “你可能是某部门负责人”

## 6.3 写入阈值
1. `confidence >= 0.75` 才可入长期记忆。
2. 同类重复记忆触发合并，不新增重复行。
3. 冲突记忆（新旧相反）保留新值并审计旧值失效。

---

## 7. 记忆读取策略

## 7.1 查询流程
1. 读取短期消息窗口（最近 N 轮）。
2. 读取当前会话摘要（若存在）。
3. 查询长期记忆 Top-K（按置信度 + 时效性）。
4. 合并为 `memory_context` 注入 Prompt。

## 7.2 Top-K 推荐
1. `K = 8`（默认）
2. 权重公式示意：
- `score = 0.7 * confidence + 0.3 * recency_norm`

## 7.3 读取优先级
1. 当前会话短期记忆
2. 当前会话摘要
3. 用户长期记忆
4. 系统默认偏好（最低优先级）

---

## 8. 上下文压缩设计

## 8.1 触发条件
1. 当前 prompt token 估算超过预算阈值（如 12k）。
2. 会话消息轮次超过阈值（如 > 40 轮）。

## 8.2 压缩步骤
1. 先保留：
- 最新 6 轮原文
- 最近一次工具结果
- 高优先长期记忆

2. 压缩中间历史为摘要块：
```json
{
  "summaryVersion": 3,
  "range": "msg#120-#340",
  "topics": ["报价", "交付时间", "责任分工"],
  "decisions": ["周五前交付初稿"],
  "pending": ["张三补报价细项"]
}
```

3. 将摘要块写回：
- Redis：当前会话摘要
- MySQL：`agent_session_summary`

## 8.3 表结构：`agent_session_summary`
```sql
CREATE TABLE agent_session_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  summary_version INT NOT NULL,
  covered_from_msg_id BIGINT NOT NULL,
  covered_to_msg_id BIGINT NOT NULL,
  summary_text TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_session_version (session_id, summary_version),
  INDEX idx_user_session (user_id, session_id)
);
```

---

## 9. 记忆与权限边界

1. 记忆作用域默认是“用户级 + 会话级”。
2. 严禁跨用户共享长期记忆。
3. 群聊总结时，仅使用该用户有权限读到的消息。
4. 任何记忆检索都必须带 `actorUserId` 过滤。

---

## 10. 清理与治理策略

## 10.1 自动清理
1. Redis 短期记忆依赖 TTL 自动过期。
2. MySQL 长期记忆：
- 超过 180 天未命中可降权
- 超过 365 天可归档或软删除

## 10.2 人工清理接口
1. `DELETE /api/agent/sessions/{sessionId}/memory`：清短期
2. `DELETE /api/agent/memory/{memoryId}`：删长期单条
3. `POST /api/agent/memory/reset`：重置用户长期记忆（高危操作）

## 10.3 审计要求
1. 删除、失效、覆盖必须写 `agent_memory_audit`。
2. 审计日志保留至少 180 天。

---

## 11. 隐私与脱敏策略

1. 写长期记忆前执行脱敏：
- 手机号：`138****1234`
- 邮箱：`a***@xx.com`

2. Prompt 注入前二次脱敏，避免敏感信息进入模型上下文。
3. 日志中禁止明文输出完整 message content（保存摘要+hash）。

---

## 12. 与模式A/B的映射

1. 模式A（AI助手会话）
- 强依赖短期记忆
- 长期记忆用于偏好与风格稳定

2. 模式B（会话内工具）
- 主要依赖工具拉取最新消息
- 记忆用于“输出风格”和“用户常用偏好”
- 避免把群聊临时讨论误写为长期事实

---

## 13. 故障降级策略

1. Redis 不可用：
- 退化为无短期记忆单轮调用
- 记录告警并不阻断主流程

2. MySQL 长期记忆不可用：
- 禁止写入长期记忆
- 读取失败时继续用短期上下文

3. 压缩失败：
- 保留最近最小窗口（如最近 4 轮）继续回答
- 返回“上下文过长已简化处理”的提示

---

## 14. 指标与验收标准

## 14.1 指标
1. 记忆命中率（短期/长期）
2. 记忆污染率（人工抽检）
3. 平均上下文 token 降幅
4. 压缩触发成功率
5. 长期记忆误召回率

## 14.2 验收标准
1. 连续 20 轮对话上下文不丢关键约束。
2. 压缩后输出事实一致率 >= 85%。
3. 记忆清理接口可用且有审计。
4. 越权读取记忆测试全部失败（即被拒绝）。

---

## 15. 配置项建议

```env
MEMORY_SHORT_TTL_SEC=604800
MEMORY_SHORT_MAX_TURNS=20
MEMORY_LONG_TOP_K=8
MEMORY_WRITE_CONFIDENCE_THRESHOLD=0.75
MEMORY_ARCHIVE_DAYS=365
MEMORY_DEGRADE_DAYS=180

CONTEXT_MAX_TOKENS=12000
CONTEXT_RECENT_TURNS_KEEP=6
CONTEXT_COMPRESS_TRIGGER_TURNS=40
```

---

## 16. 参考资料
1. LangGraph Memory：https://docs.langchain.com/oss/python/langgraph/add-memory  
2. LangChain Memory Overview：https://docs.langchain.com/oss/python/langchain/short-term-memory  
3. Redis EXPIRE 文档：https://redis.io/docs/latest/commands/expire/  