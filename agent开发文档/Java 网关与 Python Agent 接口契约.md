## 1. 文档信息
- 文档名称：Java 网关与 Python Agent 接口契约
- 版本：v1.0
- 日期：2026-05-01
- 适用范围：`nexus-chat-backend`、`nexus-agent-backend`、前端 IM 客户端

---

## 2. 设计原则
1. 对前端只暴露 Java 接口，Python 不直接暴露给前端。
2. 身份以 JWT 为准，`userId` 不允许由前端直接决定权限。
3. Java 与 Python 服务间采用签名头和时间戳防重放。
4. 所有接口默认返回统一响应包。
5. 流式输出采用 SSE，支持中断与重连。
6. 模式A与模式B统一走同一套 Agent 调用协议。

---

## 3. 鉴权与请求头规范

### 3.1 Client -> Java（公开接口）
| Header | 必填 | 说明 |
|---|---|---|
| `Authorization` | 是 | `Bearer <jwt>` |
| `X-Request-Id` | 否 | 客户端请求ID，建议 UUID |
| `X-Client-Message-Id` | 否 | 幂等键，建议 UUID |
| `Accept-Language` | 否 | `zh-CN`/`en-US` |

### 3.2 Java -> Python（内部接口）
| Header | 必填 | 说明 |
|---|---|---|
| `X-Internal-Service` | 是 | 固定值：`nexus-chat-backend` |
| `X-Internal-Timestamp` | 是 | 毫秒时间戳 |
| `X-Internal-Nonce` | 是 | 随机 UUID |
| `X-Internal-Signature` | 是 | HMAC-SHA256(`timestamp.nonce.bodyHash`) |
| `X-Trace-Id` | 是 | 全链路追踪 ID |
| `X-Actor-User-Id` | 是 | 当前用户 ID（由 Java 注入） |
| `Content-Type` | 是 | `application/json` |

### 3.3 Python -> Java Internal Tool API
| Header | 必填 | 说明 |
|---|---|---|
| `Authorization` | 是 | `Bearer <JAVA_INTERNAL_TOKEN>` |
| `X-Trace-Id` | 是 | 透传追踪 ID |
| `X-Agent-Tool` | 是 | 当前工具名 |

---

## 4. 通用数据结构

### 4.1 统一响应包（非流式）
```json
{
  "code": "OK",
  "message": "success",
  "requestId": "a4c5c4f5-5ce3-4d17-8f5c-9d8dc2d3bd42",
  "timestamp": "2026-05-01T10:30:15+08:00",
  "data": {}
}
```

### 4.2 统一错误包
```json
{
  "code": "AGENT_AUTHZ_40301",
  "message": "No permission for this chat",
  "requestId": "a4c5c4f5-5ce3-4d17-8f5c-9d8dc2d3bd42",
  "timestamp": "2026-05-01T10:30:15+08:00",
  "details": {
    "chatId": 10086
  }
}
```

### 4.3 核心枚举
- `operationType`
- `ASSISTANT_CHAT`
- `CHAT_SUMMARY`
- `TODO_EXTRACT`
- `REPLY_SUGGEST`

- `visibility`
- `PRIVATE_ONLY`
- `PUBLISH_TO_CHAT`

- `summaryRangeType`
- `LAST_N_MESSAGES`
- `LAST_24H`

---

## 5. 对外接口（Client -> Java）

## 5.1 创建 Agent 会话
- 方法：`POST`
- 路径：`/api/agent/sessions`

请求体：
```json
{
  "entryMode": "ASSISTANT_CHAT",
  "title": "AI助手",
  "boundChatId": null
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "sessionId": "as_01JTW78XJ2MF2A4JK9Y8C5ZP9A",
    "entryMode": "ASSISTANT_CHAT",
    "title": "AI助手",
    "boundChatId": null,
    "createdAt": "2026-05-01T10:33:00+08:00"
  }
}
```

---

## 5.2 会话聊天（非流式）
- 方法：`POST`
- 路径：`/api/agent/sessions/{sessionId}/chat`

请求体：
```json
{
  "operationType": "ASSISTANT_CHAT",
  "input": "帮我总结一下今天和张三的聊天重点",
  "chatContext": {
    "chatId": 20001
  },
  "options": {
    "visibility": "PRIVATE_ONLY",
    "maxOutputTokens": 1024,
    "temperature": 0.2
  }
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "answer": "今天你和张三主要讨论了三点：...",
    "operationType": "ASSISTANT_CHAT",
    "toolCalls": [
      {
        "toolName": "get_recent_messages",
        "status": "SUCCESS",
        "latencyMs": 86
      }
    ],
    "usage": {
      "inputTokens": 1342,
      "outputTokens": 286,
      "totalTokens": 1628
    },
    "finishReason": "stop"
  }
}
```

---

## 5.3 会话聊天（流式 SSE）
- 方法：`POST`
- 路径：`/api/agent/sessions/{sessionId}/chat/stream`
- `Content-Type: application/json`
- `Accept: text/event-stream`

请求体与 5.2 一致。  
SSE 事件格式见第 8 节。

---

## 5.4 一键总结本会话（模式B）
- 方法：`POST`
- 路径：`/api/agent/chats/{chatId}/summarize`

请求体：
```json
{
  "summaryRangeType": "LAST_N_MESSAGES",
  "rangeValue": 80,
  "outputStyle": "BULLET",
  "visibility": "PRIVATE_ONLY"
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "summary": "1) 需求确认... 2) 风险点... 3) 下一步...",
    "participants": ["u_1001", "u_1002"],
    "messageCount": 80
  }
}
```

---

## 5.5 提炼待办（模式B）
- 方法：`POST`
- 路径：`/api/agent/chats/{chatId}/todo-extract`

请求体：
```json
{
  "summaryRangeType": "LAST_24H",
  "rangeValue": 24,
  "visibility": "PRIVATE_ONLY"
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "todos": [
      {
        "owner": "张三",
        "task": "提交报价单",
        "dueAt": "2026-05-02T18:00:00+08:00",
        "confidence": 0.88
      }
    ]
  }
}
```

---

## 5.6 生成回复建议（模式B）
- 方法：`POST`
- 路径：`/api/agent/chats/{chatId}/reply-suggest`

请求体：
```json
{
  "targetMessageId": 908877,
  "tone": "PROFESSIONAL",
  "length": "SHORT",
  "visibility": "PRIVATE_ONLY"
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "draft": "收到，我会在今天下班前补齐并回传。",
    "alternatives": [
      "明白，我今天17:30前给你最终版本。",
      "好的，已记录，我会在今天内处理完成。"
    ]
  }
}
```

---

## 5.7 发布建议到会话
- 方法：`POST`
- 路径：`/api/agent/chats/{chatId}/reply-publish`

请求体：
```json
{
  "draft": "收到，我会在今天下班前补齐并回传。",
  "source": "AGENT_REPLY_SUGGEST"
}
```

成功响应：
```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "messageId": 9123001,
    "chatId": 20001,
    "publishedAt": "2026-05-01T10:45:00+08:00"
  }
}
```

---

## 6. 内部接口（Java -> Python）

## 6.1 Agent 调用（非流式）
- 方法：`POST`
- 路径：`/v1/agent/invoke`

请求体：
```json
{
  "traceId": "tr_8f3d97b9eabf4a32",
  "actor": {
    "userId": 1001,
    "username": "alice"
  },
  "session": {
    "sessionId": "as_01JTW78XJ2MF2A4JK9Y8C5ZP9A",
    "operationType": "CHAT_SUMMARY"
  },
  "input": {
    "text": "总结本会话最近80条消息",
    "chatId": 20001
  },
  "options": {
    "maxIterations": 6,
    "maxOutputTokens": 1024,
    "temperature": 0.2
  }
}
```

响应体：
```json
{
  "traceId": "tr_8f3d97b9eabf4a32",
  "result": {
    "answer": "总结如下：...",
    "toolCalls": [
      {
        "toolName": "get_recent_messages",
        "status": "SUCCESS",
        "latencyMs": 86
      }
    ],
    "usage": {
      "inputTokens": 1200,
      "outputTokens": 240,
      "totalTokens": 1440
    }
  }
}
```

---

## 6.2 Agent 调用（流式）
- 方法：`POST`
- 路径：`/v1/agent/invoke/stream`
- 响应类型：`text/event-stream`

请求体与 6.1 一致。

---

## 6.3 健康检查
- 方法：`GET`
- 路径：`/v1/agent/health`

响应体：
```json
{
  "status": "UP",
  "modelProvider": "openai",
  "time": "2026-05-01T10:47:10+08:00"
}
```

---

## 7. 工具接口（Python -> Java Internal）

## 7.1 获取会话最近消息
- 方法：`GET`
- 路径：`/internal/agent/chats/{chatId}/recent-messages?limit=80&beforeMessageId=...`

响应体：
```json
{
  "chatId": 20001,
  "messages": [
    {
      "messageId": 908877,
      "senderId": 1002,
      "senderName": "张三",
      "content": "今晚前发你最终文档",
      "createdAt": "2026-05-01T09:20:00+08:00"
    }
  ]
}
```

---

## 7.2 获取会话元信息
- 方法：`GET`
- 路径：`/internal/agent/chats/{chatId}/profile`

响应体：
```json
{
  "chatId": 20001,
  "chatType": "group",
  "chatName": "项目A推进群",
  "memberIds": [1001, 1002, 1003]
}
```

---

## 7.3 获取用户资料（可见字段）
- 方法：`GET`
- 路径：`/internal/agent/users/{userId}/profile`

响应体：
```json
{
  "userId": 1001,
  "nickname": "Alice",
  "timezone": "Asia/Shanghai"
}
```

---

## 7.4 发布 AI 文本到会话
- 方法：`POST`
- 路径：`/internal/agent/messages/publish`

请求体：
```json
{
  "chatId": 20001,
  "senderUserId": 1001,
  "content": "收到，我会在今天下班前补齐并回传。",
  "messageType": "text",
  "source": "AGENT"
}
```

响应体：
```json
{
  "messageId": 9123001,
  "chatId": 20001
}
```

---

## 8. SSE 事件协议（流式）

## 8.1 事件类型
- `meta`：元信息，首包
- `delta`：增量文本
- `tool_call`：工具调用开始
- `tool_result`：工具调用结束
- `usage`：token 统计
- `done`：正常结束
- `error`：异常结束
- `heartbeat`：保活事件

## 8.2 事件示例
```text
event: meta
id: 1
data: {"traceId":"tr_8f3d97b9eabf4a32","sessionId":"as_01JTW78XJ2MF2A4JK9Y8C5ZP9A"}

event: tool_call
id: 2
data: {"toolName":"get_recent_messages","args":{"chatId":20001,"limit":80}}

event: tool_result
id: 3
data: {"toolName":"get_recent_messages","status":"SUCCESS","latencyMs":86}

event: delta
id: 4
data: {"text":"本会话最近重点有三项："}

event: delta
id: 5
data: {"text":"第一，需求范围确认完成。"}

event: usage
id: 6
data: {"inputTokens":1200,"outputTokens":240,"totalTokens":1440}

event: done
id: 7
data: {"finishReason":"stop"}
```

## 8.3 断线重连约定
1. 客户端可携带 `Last-Event-ID` 重连。
2. Java 网关保留最近 60 秒事件缓存用于短重放。
3. 若超出缓存窗口，返回 `AGENT_STREAM_40901`，客户端改走非流式补偿请求。

---

## 9. 错误码定义

| 错误码 | HTTP | 说明 |
|---|---|---|
| `AGENT_AUTH_40101` | 401 | JWT 无效或过期 |
| `AGENT_AUTHZ_40301` | 403 | 用户无会话访问权限 |
| `AGENT_PARAM_40001` | 400 | 参数校验失败 |
| `AGENT_SESSION_40401` | 404 | 会话不存在 |
| `AGENT_TOOL_50201` | 502 | 工具调用失败 |
| `AGENT_MODEL_50202` | 502 | 模型调用失败 |
| `AGENT_TIMEOUT_50401` | 504 | Agent 总超时 |
| `AGENT_STREAM_40901` | 409 | 流式重连窗口失效 |
| `AGENT_RATE_42901` | 429 | 触发限流 |
| `AGENT_SYS_50001` | 500 | 未分类系统错误 |

---

## 10. 幂等与重试

1. 聊天发送接口支持 `X-Client-Message-Id` 幂等去重。
2. Java -> Python 若超时重试，只允许对“可幂等操作”重试一次。
3. `reply-publish` 必须幂等，重复提交返回同一 `messageId`。
4. SSE 中断后优先重连，失败再转非流式补偿请求。

---

## 11. 权限约束（必须实现）

1. 总结、待办、回复建议必须先校验 `userId ∈ chat_members`。
2. Python 不可直接查询数据库，只能走 Java Internal API。
3. Internal API 必须二次校验 `X-Actor-User-Id` 与目标 chat 的关系。
4. 跨会话数据聚合默认禁用，需要白名单开关。

---

## 12. 版本策略

1. 对外接口前缀：`/api/agent/*`，语义版本放 Header：`X-Agent-Api-Version: 1`.
2. 内部接口前缀：`/v1/agent/*`。
3. 重大字段变更通过 `v2` 路径发布，保留 `v1` 至少一个小版本周期。

---

## 13. 测试用例最小清单

1. 正常对话（非流式）返回工具调用与 usage。
2. 正常对话（流式）能收到 `meta -> delta -> done`。
3. 越权总结返回 `403`。
4. 工具超时返回 `AGENT_TOOL_50201`。
5. 幂等消息重复提交返回同一结果。
6. SSE 重连 `Last-Event-ID` 可恢复。

---

## 14. 参考资料
1. OpenAI Responses API: https://platform.openai.com/docs/api-reference/responses  
2. OpenAI Function Calling: https://platform.openai.com/docs/guides/function-calling  
3. OpenAI Streaming: https://platform.openai.com/docs/guides/streaming-responses  
4. FastAPI 自定义响应与流式：https://fastapi.tiangolo.com/advanced/custom-response/  
5. MDN SSE 事件格式：https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events  