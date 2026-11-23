# 工作台运行契约 v1 — 七会话集成基准

状态：协议口径已冻结；T1 实现及回归验收进行中。文档存在、测试文件存在均不是修复完成证明。
来源：用户 2026-11-22 明确改为七会话分工。本会话只实现 T1，兼任服务、验证和补丁集成负责人，最终负责 T7；不自行实施 T2/T3/T4A/T4B/T5/T6。

## 1. 事件身份

每个公开 SSE data 均携带：

| 字段 | 类型与口径 |
|---|---|
| runtimeContractVersion | 整数，v1 为 1；只允许兼容性新增字段，不改已有字段含义 |
| runId | 字符串，逻辑运行身份。resume 保持；retry 创建新 run 并保留 retryOfRunId |
| attemptId | 字符串，本次执行/结果交付身份；普通恢复新建，与前一执行尝试不同 |
| sequence | 正整数，在该 attempt 内从 1 连续递增，所有角色共享排序，不各自计数 |
| at | 非负整数，该尝试的已发生 elapsed ms，不是 UTC 时间、聊天发送时间或截止时间 |

去重键固定为 `(runId, attemptId, sequence)`；SSE `id` 可编码同一三元组，但不得替代 data 身份。旧帧没有 attemptId 时可使用 traceId/显式 legacy-attempt 边界兼容，不能跨恢复尝试用 sequence 单独去重。late/replayed 旧帧不能切换正在执行的尝试或关闭它。

执行节点身份为 `(runId, attemptId, nodeId)`；模型/工具事件有 agentId、nodeId，默认单助手 agentId 为 assistant。parentIds 只表示后端实际确认的依赖，前端不根据到达顺序猜边、不假定 nodeId 的拼接格式。

首帧通常是 meta；早期参数/会话拒绝允许直接 error + done，但仍必须有完整身份。HTTP 鉴权/参数错误不是伪造的模型事件。

## 2. 结果取回与恢复

- unfinished resume：同 run、新 attempt，只续接未完成节点；用户输入不重复写入，已经提交的调用不重复执行。
- finished handover：只交回已保存结果，不创建模型/工具调用。交付使用稳定且与生产尝试不同的 handover attemptId；重复取回的 meta/delta/usage/done 身份完全相同，不能覆盖原生产尝试的节点身份。meta 标明交付模式及 producerAttemptId。
- 执行依赖图以生产者为准；交付尝试不算新的 Agent 或模型节点，不清空原证据/图。
- retry：新 runId，原运行不可被覆盖；retryOfRunId 必须属于同一用户及会话，不靠问题文本认定身份。
- 恢复、结果复用和取回必须重新确认用户/会话归属；T2/T4B 提供实时资源授权校验。scope/授权引用、参数或输入版本变化不能命中旧结果。权限拒绝时不向模型或浏览器交回缓存内容。

## 3. 终态与错误分级

`done.finishReason` 保留真实原始原因；展示/持久化统一按原因映射：stop→done；length/max_tokens/max_output_tokens/max_iterations/truncated/content_filter→truncated；cancelled/canceled→cancelled；interrupted/timeout→interrupted；其他明确失败→error。T5 可显式声明 partial，但不得把缺少必需结果的 partial 当 done。

- done 只表示本尝试完成，必须与持久化结果及真实输出一致；截断/过滤/超时不写成功记忆。
- 已完成执行结果先持久化，再清检查点。清理/传输失败不能抹掉已经保存的执行成功；未完成保存保留可恢复检查点，不重新调用已完成模型。
- Java 和 Python 不宣称跨进程一个事务：Python 是可重放结果源；Java 幂等记录、取回对账。原错误及历史尝试保留，新尝试不继承旧 run.error。
- error.scope 固定 run / agent / tool / transport。仅 run 错误写最终 run.error；agent/tool 错误留在其节点或诊断集合，transport 不把已知终态改成失败。
- optional 分析员失败而 synth 成功不污染 run 终态；必需步骤失败由编排层明确发 run 错误或 partial。
- 兼容旧错误：transport 标记优先；callId 指向工具；非 assistant/synth 角色错误默认局部；剩余才视为 run。新实现须显式 scope，不靠 role 名称猜错误等级。
- stop_requested 不是 cancelled。本地断流、HTTP 请求成功、无活跃运行，都不单独证明资源已释放。

## 4. 调用统计

- `modelCalls`：该 run 自首次执行起累计实际模型调用尝试，包含失败、重试及 synth；4 次调用不得记作 1 个协同节点。取回/节点复用不增加。
- 计数点是请求即将离开进程的那一刻：三个模型适配器在请求构造完成、发送之前 await `on_dispatch()`（`LLMClient.signals_dispatch = True`）。SDK 缺失、客户端构造失败、发送前被拒绝都不计调用。`on_dispatch` 抛出的异常原样交回调用方，不被当作供应商错误，且请求不发出——T3 的 `mark_started` 接在这里。
- `toolCallCount`：累计实际工具执行尝试；`reusedToolCallCount` 单独表示结果复用。legacy `toolCalls` 保留明细数组，不改成数值。
- usage 事件是 run 累计口径（usageScope=run）。供应商未上报的 token 不当成 0；usageReported=false 时前端显示未上报。有部分上报时 usageComplete=false，已知数量只表示已上报部分。
- final.usage 保留 inputTokens/outputTokens/totalTokens 三字段兼容形状；modelCalls、usageReported/usageComplete 和工具计数位于 final 或 usage 事件的独立字段。
- usage 按每次调用的最终已上报计量聚合，不能把重复累计 usage chunk 再相加。SDK 隐藏重试须关闭或显式纳入计数。调用前配置/权限失败不计模型调用。
- 模型/工具耗时和尝试 elapsed 分开；缓存复用保留来源耗时并标 reused，不冒充新网络请求耗时。长时间闲置不算活跃执行耗时。

## 5. 取消接口与确认

- Java BFF：`POST /api/agent/sessions/{sessionId}/runs/{runId}/cancel`。
- Python 内部：`POST /v1/agent/runs/cancel`；沿用 ResumeRequest 身份体（traceId、actor、sessionId、runId），校验签名、body actor/trace 与调用头一致。
- 核心方法：`cancel_run(actor_user_id, session_id, run_id)`；只影响目标所属运行。
- `status=cancelled` 的确认必须发生在全部子模型流、工具调用/会话、排队工作已释放之后。无活跃运行返回 not_active；它不是取消成功。若持久结果已为 cancelled，可返回幂等确认；已完成结果不能被取消改写。
- SSE 发真实 cancelled 终态，resumable 依据已保存检查点和安全复用能力，不硬编码。消费者断开不应成为取消释放的前置条件。
- 前端先显示 stop_requested，等待后端确认再停止/标 cancelled；切换会话、账号及迟到确认不能污染新运行。

## 6. 逐 call 提交与复用

现有回归约定的内部存储接口保留：

```
RunStore.commit(thread_id, kind, call_id, fingerprint, result)
RunStore.committed(thread_id, kind, call_id, fingerprint)
```

thread_id 隔离 actor + run，注册元数据同时固定 session；工具 callId 保持原逻辑调用身份。fingerprint 纳入工具名、规范化参数、角色/生产节点、输入/授权版本等稳定信息，不纳入新的 attemptId、nonce 或 traceId，也不纳入 API key。

工具调用另有逐 call 账本（`agent_call_ledger`，每个 run 每个 callId 一行），上面两个接口继续用于模型计量回执与分析员结果：

```
RunStore.call_state(thread_id, call_id, fingerprint, attempt_id) -> {status, attemptId?, result?}
RunStore.claim_call(thread_id, call_id, fingerprint, attempt_id, *, takeover=False) -> 同上，成功为 claimed
RunStore.finish_call(thread_id, call_id, fingerprint, attempt_id, result) -> bool
RunStore.fail_call(thread_id, call_id, fingerprint, attempt_id) -> bool
RunStore.reject_call(thread_id, call_id, attempt_id) -> bool
```

- status：absent（从未被认领）/ inflight（本尝试持有）/ unknown（更早的尝试认领过但没有记录结局）/ committed / failed / rejected / conflict（同 callId 不同指纹）。absent 与 unknown 是两回事，缓存未命中不等于没执行过。
- 认领是原子的，一个 call 同一时刻只有一个持有者。`takeover=True` 只给已获准重放的调用方使用；已记录的同指纹结果不会被顶替，撤回者不能拿回自己撤回的 call。
- finish/fail 只对当前持有者生效；reject 撤回本尝试的认领及其已记录结果，之后迟到的 finish 无效；别的尝试的 reject 不影响已有结果。
- callId 在 run 内唯一：不发 id 的供应商按步编号（`call_0`、`gem_0_name`），后一步会重复前一步的 id。编排器在记录该模型步之前改写重复 id（追加 `_s<步号>`），此后对话记录、事件、账本、传给工具执行器的 `call_id` 都是同一个值。
- 工具执行器签名：`ToolExecutor.execute(tool_name, args, *, call_id=None)`，`call_id` 是关键字参数，T1 每次真实调用都传。
- 工具指纹含：工具名、参数、sessionId、input、options、agentId、生产节点（发起它的模型步）、授权绑定值（见 6.1）。分析员结果指纹含 messages、options、授权绑定值。
- tool_call / tool_result 事件带 `agentId`、`nodeId`，tool_call 带 `parentIds=[发起它的模型步]`；下一模型步的 `parentIds` 是它读到的那批工具节点。

- first durable commit wins；同身份/指纹重复提交不覆盖结果，指纹、用户、run 或会话不匹配不复用。
- 已成功返回且持久提交的调用，恢复时直接使用原结果并发 tool_result(status=SUCCESS,reused=true)，不重复发真实工具请求；补充来源尝试/节点引用。
- 成功调用在同批另一调用失败时仍保留；未完成或失败调用不写成成功提交。必要时既有分析员完成结果可按 kind=agent 复用，synth 失败只恢复 synth。
- 未知远端结果或潜在副作用不盲目重放；这里不承诺远端写入 exactly-once。T4B 决定副作用、可重试和实时权限策略。
- cache 命中不绕过当前授权；授权回调由 T2/T4B 接入，T1 只负责生命周期与结果正确性。不可用/拒绝时明确失败，不虚构允许。
- 未改变参数的恢复保留模型所需 tool_call_id 及请求顺序；迟到结果只能归所属调用，不能关闭同名其他工具/角色。
- forget/purge 删除对应未完成提交，已封存结果仍按取回接口交付；不得删其他用户/运行的缓存。

## 6.1 已落地授权回调（T2 / T4B 必须接入）

T1 Python 提供两个模块级异步回调槽，应用启动时安装一次，不按请求临时切换：

```
orchestrator.reuse_authorizer(request, kind, call) -> bool
orchestrator.replay_authorizer(request, kind, call) -> bool
```

request 是当前 InvokeRequest；reuse 的 kind 为 tool / agent / resume / handover，工具 call 含 id/name/arguments，分析员 call 含 agentId，恢复/取回 call 含 runId。replay 针对已尝试但未确认提交的工具重放（账本状态为 unknown / failed / rejected / conflict）。回调缺失或拒绝即明确拒绝，不虚构权限。

安装入口只有一个，各方各调一次，后装的只会收窄、不会覆盖：

```
orchestrator.install_authorizers(reuse=None, replay=None, *, binding=None)
```

- 同一个槽里的所有回调都会被询问，全部返回 `True` 才放行；任一方抛异常或返回非 `True` 即拒绝。T2 的 `install_scope_authorizers(..., compose=orchestrator.install_authorizers)` 直接可用。
- `binding(request)` 返回该请求的读取所依据的稳定授权身份，进入工具与分析员指纹；多方的值按安装顺序组成列表。必须是恢复前后不变的内容身份（T2 的 scopeKey + sessionGeneration、T4B 的策略/配置版本），不能用每次恢复都会变的 grantId 或 authorizationVersion，否则每次恢复都会让已提交结果全部失效。
- 绑定值变化后，旧结果在账本里表现为 conflict：不复用，重新发送需要 replay 放行。

T2 提供实时用户/会话/资源 scope 校验，T4B 提供工具/外传/副作用与可重放策略；集成必须将两者组合取交集，不能后安装的回调覆盖前者授权。完成结果取回也要校验当前权限，但不能为了取回再次调用模型。

handover 的 meta.deliveryMode=handover，并携带 producerAttemptId。modelCalls 等计量来自持久调用收据，而不是 UI 角色或阶段数。T1 自身单测使用隔离授权 fixture；生产回调接入及真实权限链路是 T2/T4B 补丁集成门槛，不以 fixture 允许冒充生产已授权。

## 7. 分工、交接与服务纪律

T1 本会话拥有：身份/终态/错误分级/调用统计/取消与 call 提交恢复，以及这些行为必须的 Java/前端协议接线和回归；不改 T6 视觉、不替 T2/T3/T4/T5 实现业务。

其他窗口交接须给出：窗口/分支/工作树、基线 commit、补丁或提交、文件清单、消费/增加的协议字段、迁移与配置要求、实际验证及缺口；不得交付真实 .env、密钥、临时 fixture 或假 UI 数据。

补丁按 T1→T2→T3→T4A/T4B→T5→T6 依赖接入；集成可修必要冲突及字段对齐，但不能独立代写其他窗口功能。T7 由本会话统一收尾。

只有集成负责人启动/重启当前工作树三端（5173/8080/8100），以 `.alma/runtime-mwrs4rwy60qp7zm91md/services.json` 的实际 PID/路径为准。其他窗口不得另起服务或关闭它们。构建、测试、浏览器由集成窗口串行安排，统一使用本工作树 validation gate，不因分七会话就并行跑验证。

## 8. 集成状态（2026-11-23 22:30，集成树，未提交）

已合入并通过单元回归（Python 861 passed / 6 skipped，Java 475，前端 440 + 构建）：T2、T3、T4A、T4B、T5、T6。

已接进运行链路：
- T2 范围：每轮/恢复/取回结果都要当前授权；`install_run_policies()` 在服务启动时通过 `install_authorizers` 安装 T2 的复用/重放判定，授权绑定为 `[scopeKey, sessionGeneration]`。
- 恢复顺序：Java `AgentRunService.resumeWriter(..., authorize)` 先接受恢复，再轮换授权；重复的恢复请求在轮换前被拒绝。
- 工具执行只有一条路径：Nexus 内建工具走范围校验；`srv_*` 托管工具交给 T4A `ManagedMcpExecutor` → `T4BPolicyAdapter` → T4B 守卫；旧式外部 MCP 注册不再提供也不可调用。
- T6 像素工作区读取本契约的 `agentId / nodeId / parentIds / status / reused`；context、knowledge、attachments 阶段事件现在带 `status`。

已合入但运行链路尚未调用（模块与各自回归在，行为未生效）：
- T3 `app/efficiency`（运行预算、事实读取）与 Java `facts/AgentMessageReader`：`get_recent_messages` 仍是 T2 的范围版，未切到分页读取器；切换前需配置 `agent.message-query.storage-zone`。
- T5 `app/subagents`：协同仍走原 `collaboration.py`；`CommittedResults.query` 与 T4B `CallJournal` 还没有接到 `RunStore` 的逐调用台账。
- 运行期托管 MCP：编排器没有给 `ToolExecutor` 传 `McpContext`，`management_policy()` 也没有业务授权/范围/来源/台账适配，所以托管工具的目录为空、调用一律拒绝。管理端（增删改、探测、目录）可用。

未做的真实验证：登录后的整轮对话、撤权、取消、恢复；每个流式事件一次授权校验的耗时；像素区 820 宽度与键盘操作。
