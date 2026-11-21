# AI 会话工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Independent implementation or review can use available agents; unavailable specialized agent runtimes must not be reported as completed reviews.

**Goal:** 按附件重构现有 AI 会话工作台，保留鼠标跟随人物，实现真实 Thought Ribbon、模型协同、上下文附件/联网、产物及所有会话操作，并通过真实浏览器全链路验收。

**Architecture:** 复用 Vue/Pinia → Java 鉴权网关 → Python/LangGraph/供应商适配器。Python 产生真实推理、工具、角色、来源和产物事件；Java 保存用户/会话隔离的完整运行记录与工作区文件/任务；Vue 从事件投影布局，不模拟执行。普通模式保持单助手，协同模式实际运行三个独立分析角色后汇总。

**Tech Stack:** Vue 3, Pinia, Element Plus, Tailwind, Three.js, Node test runner/Vite SSR, Java/Spring Boot/JPA/MySQL, Python 3.11/FastAPI/LangGraph/httpx/pytest。Markdown 使用禁用原始 HTML 的 markdown-it 并以 DOMPurify 清洗。

**Spec:** `/Users/anglv/Downloads/stitch_ios_style_ai_messenger/DESIGN.md`、`code.html`、`screen.png`，以及本会话已展示的六部分设计。用户选择产物操作作用于“文件与会话任务”，不接入外部 Git/Issue 平台。

## 底座变更（2026-11-21）

主工作区已把底座换成 LangGraph 单引擎 + MCP 工具层，工作树已同步。本文下列描述不再适用，实施时以现有代码为准：

- Task 1 的「改 `orchestrator.py` 的 `Event`/`_Outbox`/公开事件输出」仍然成立。
- 凡提到 `app/tools.py`、`TOOL_SCHEMAS`、`ToolExecutor` 静态定义的地方：这些已删除。工具来自 Java 的 `/internal/agent/mcp`，由 `app/mcp_tools.py` 发现和调用。
- 新增事件 `stage {phase:"tools", status:"unavailable"}`（工具目录取不到时普通对话继续、必须用工具的轮次失败且可继续）；前端 `runView.js` 已把它当作 `skipped` 步骤。
- `Event` 的字段没变（`name`/`data`）；公开事件的 `sequence`/`at`/`runId` 仍需按 Task 1 补。

## Global Constraints

- 先完成并保存此 plan，再实施。用户请求继续按规划执行；不自行提交、推送或创建 PR。
- 保留 `/Users/anglv/Nexus` 已有未提交改动；不删除或覆写其他工作树、分支、运行数据、凭据或服务。
- 人物继续由 `src/components/agent/abyssStage.js` 的 Three.js/WebGL 舞台呈现，保留鼠标跟随；不以静态图片替代。
- 设计使用中性黑白分层、细边界、Geist 和 JetBrains Mono 本地字体、768px 阅读列；禁止照搬示例姓名、模型名、步骤、进度、Tokens、耗时、产物或性能结论。
- 原生 reasoning 是供应商实际提供的流式内容；无 reasoning 时明确说明，不生成伪思考。分析输出、工具记录、模型思考和最终正文分开。
- Java 负责鉴权、凭据、业务、持久化、Python 转发；模型适配、推理、发现/测试统一由 Python 负责。本任务不另建 Java 供应商客户端。
- 只有显式开启联网时才发送搜索查询；附件内容不得混入搜索查询。联网失败明确显示，不返回静态搜索结果。
- 用户文件以附件 ID 引用。文件名只作显示，不成为服务器任意路径；补丁确认后创建新版本，不覆盖原文件、不执行 shell 或模型代码。
- 界面任何“完成”“已复制”“已下载”“已应用”“已创建任务”必须对应成功结果；取消、中断、失败和截断不是成功。
- 新接口必须验证会话/运行/附件/产物所有权；生成内容不执行 HTML/脚本。运行 JSON 不存 API key、内部令牌或请求头。
- 每个实现单元先写可失败的行为测试，确认红灯，再实现并验证绿灯；提交步骤仅在用户另行请求时执行。

---

## 需求到产物矩阵

| 需求 | 实现任务 | 必需验收证据 |
|---|---|---|
| 当前外壳与会话栏、新建、搜索、重命名、删除 | 10 | 浏览器实际 CRUD、稳定排序、空列表/错误状态 |
| 顶部模型选择、就绪、列表折叠、刷新、参数、全屏、导出 | 8, 10 | 操作生效、请求真实参数、退出全屏/取消确认 |
| 保留人物并融入流水线/产物 | 8 | 鼠标移动前后视窗变化，真实节点/产物数量 |
| Thought Ribbon、步骤轴、拓扑、复制、仅看结论 | 1, 7, 8 | 实时 SSE 对应内容、折叠和复制、无黑框终端 |
| 单助手与真实协同切换 | 5, 8 | 三独立调用重叠、汇总在 join 后开始、真实 Tokens |
| 正文 Markdown、代码块、复制、重新推理、溯源 | 6, 7, 8 | 安全渲染、复制实际内容、重试不重复用户问题 |
| 附件、知识库、联网控制 | 3, 4, 8 | 上传内容进入请求、检索返回来源、关闭时零查询 |
| 产物列表和 Raw/Diff/Log/Graph 抽屉 | 6, 9 | 四个标签内容来自当前真实产物/运行 |
| 应用补丁、下载、创建任务、保存拓扑 | 3, 6, 9 | 原文件不变、新版本校验、任务保存/勾选/导出 |
| 历史思考、产物、来源与状态恢复 | 2, 7 | 切换/刷新后内容与状态不丢失、跨用户拒绝 |
| 输入自动增高、字数、Enter/Shift+Enter/IME、停止 | 7, 8 | 浏览器中文输入/长文本/停止测试 |
| 快捷键、抽屉 Escape、移动布局、键盘可达 | 8, 9, 10 | 390px/820px/1440px，焦点恢复、无横向溢出 |
| 测试、构建、独立审查、完整审计与清理 | 11 | 命令输出、真实模型/联网验收、逐项审计 |

## 固定接口与数据形状

### 请求

Java `SessionChatRequest` 在现有字段上增加 `runId`、`attachmentIds`；Options 增加 `collaborationEnabled`、`webSearchEnabled`，仍包含 `temperature`、`maxIterations`、`maxOutputTokens`。在请求边界校验长度/数值范围；Java 解析所属会话的附件，将 `{id, fileName, content, sha256}` 转发为 Python `input.attachments`。每次重试是独立 runId，保留原答案。

### 事件

Python 的公开事件均带 `{runId, sequence, at}`，`at` 为本次运行实际单调时钟毫秒。

- `meta`: traceId、runId、executionMode、真实 provider/model。
- `stage`: phase、status、nodeId、agentId、iteration、parentIds；模型/工具完成记录实际 ms 和已报告 usage。
- `reasoning`: nodeId、agentId、iteration、text；只来自供应商 reasoning chunk。
- `tool_call` / `tool_result`: callId、nodeId、agentId、toolName、args/status/latencyMs/error；相同工具必须按 callId 关联。
- `agent_output`: agentId、nodeId、text；分析角色输出，不进入主答案气泡。
- `sources`: `items: [{id, kind, title, excerpt, url?, attachmentId?, callId?}]`；kind 为 attachment/knowledge/web/tool，id 是稳定的 A1/K1/W1/T1 标识。
- `artifact`: `artifact: {id, name, kind, language, content, sizeBytes, sha256, runId, agentId, nodeId, sourceIds, callIds}`；kind 为 code/json/patch/markdown/graph。
- `delta`、`usage`、`done`、`error`: 保持现有含义并带公共字段。角色局部错误按 agentId 展示，不冒充整个运行已成功。

前端只按事件追加/更新已有节点，不制造不存在的四个角色；普通工具调用不是模型角色。sequence 只用于一次运行内排序/去重，不冒充断点重放保证。

### 持久化

新增 Java `AgentRunRecord`：用户、会话、runId、输入、时间、状态、完整 payloadJson（事件/正文/usage/来源/产物/请求选项/附件引用）。新增 `AgentWorkspace`：用户、会话、attachmentsJson、tasksJson。运行记录按会话顺序构成持久历史，Redis 仍只负责模型短期上下文。清空/删除会话同时处理运行与工作区；未完成运行落为 interrupted，不恢复成 done。

### 工作区操作

- `POST /api/agent/sessions/{sid}/attachments`：multipart file，最多 4 个选中文件，单文件 5MB，支持 UTF-8 文本/源码、PDF、DOCX；二进制提取由 Java 经签名通道调用 Python 的现有解析能力，明确文本长度和截断。
- `GET/DELETE .../attachments[/{id}]`：所属会话附件列表/移除；失败不先清空前端数据。
- `GET /api/agent/sessions/{sid}/runs`：返回拥有者的持久运行；消息历史由它还原，旧的 role/content 历史仅在尚无持久记录时导入。
- `POST .../artifacts/{id}/patch-preview`：选 baseAttachmentId，返回 before/after/diff/baseHash/patchHash，不写入文件。
- `POST .../artifacts/{id}/apply`：显式确认、baseAttachmentId/baseHash/patchHash，重新验证规范补丁后创建新附件版本。
- `POST .../artifacts/{id}/tasks`、`PATCH .../tasks/{id}`：从真实 JSON 任务产物创建会话任务、更新 pending/done；返回保存结果后再提示成功。
- `POST .../artifacts/{id}/save`：将真实产物/拓扑保存为会话附件，返回新 ID。

---

### Task 1: 真实事件与补丁/产物基础

**Files:**
- Modify: `nexus-agent-backend/app/schemas.py`, `app/orchestrator.py` 的 Event/_Outbox/public event 输出。
- Create: `nexus-agent-backend/app/artifacts.py`。
- Test: `nexus-agent-backend/tests/test_workbench_artifacts.py`, `tests/test_workbench_events.py`。

**Interfaces:**
- Produces: `apply_unified_patch(original: str, patch: str, file_name: str) -> str`，`extract_artifacts(text: str, *, run_id: str, agent_id: str, node_id: str, source_ids: list[str], call_ids: list[str]) -> list[dict]`；异常为 `ArtifactError`。
- Produces: 上述公共事件字段；原有普通模式事件语义不变。

- [ ] 写下列行为测试，以及无路径穿越、错误旧行、无效 hunk、重复文件目标、未知旧文件和 fence 未闭合测试。

```python
from app.artifacts import apply_unified_patch, extract_artifacts

def test_patch_applies_exact_uploaded_file():
    patch = '--- a/demo.txt\n+++ b/demo.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n'
    assert apply_unified_patch('a\nb\n', patch, 'demo.txt') == 'a\nc\n'

def test_artifact_bytes_are_real():
    items = extract_artifacts('```json\n{"items":[]}\n```', run_id='run_test_1',
                              agent_id='synth', node_id='model-1', source_ids=[], call_ids=[])
    assert items[0]['content'] == '{"items":[]}'
    assert items[0]['sizeBytes'] == len(items[0]['content'].encode('utf-8'))
```

- [ ] 运行 `.venv311/bin/python -m pytest tests/test_workbench_artifacts.py tests/test_workbench_events.py -q`，确认缺少函数/事件字段的失败。
- [ ] 实现严格 unified diff：匹配目标文件名、禁止绝对/父级路径、hunk 起止和旧行逐项校验；只返回新文本。用 SHA-256/UTF-8 字节生成真实产物元数据；完整 fence 才生成，非法 JSON 保留 code 而非宣称 JSON 有效。
- [ ] 在真实事件发出时添加身份/时间；节点 start/done/error 标明真实执行阶段，工具排队和开始区分。
- [ ] 重跑测试与原有普通模式评测，确认不污染供应商客户端、凭据或短期记忆。

### Task 2: Java 持久运行与所有权

**Files:**
- Create: `nexus-chat-backend/src/main/java/com/nexus/chat/model/agent/AgentRunRecord.java`, `repository/agent/AgentRunRecordRepository.java`, `service/agent/AgentRunService.java`。
- Modify: `service/agent/AgentGatewayService.java`, `service/agent/AgentSessionService.java`, `controller/agent/AgentController.java`, `dto/agent/AgentSessionAndChatDtos.java`。
- Test: `nexus-chat-backend/src/test/java/com/nexus/chat/service/agent/AgentRunServiceTest.java`, `AgentWorkbenchGatewayTest.java`。

**Interfaces:**
- Consumes: Task 1 事件与 runId。
- Produces: `AgentRunService.begin(userId, sessionId, runId, request)`、`record(userId, sessionId, runId, eventName, data)`、`finish(userId, sessionId, runId)`、`list(userId, sessionId)`、`deleteSession(userId, sessionId)`；run list 为稳定字段的 Map 列表。

- [ ] 先测同一运行 reasoning/delta/tool/usage/done 保存后还原，以及其他用户、其他会话、重复 runId 被拒绝。

```java
assertEquals("interrupted", service.finish(7L, "as_test", "run_test_1").get("status"));
assertThrows(AgentException.class, () -> service.list(8L, "as_test"));
```

- [ ] 运行 `mvn -B -ntp test -Dtest=AgentRunServiceTest,AgentWorkbenchGatewayTest` 确认红灯。
- [ ] JPA 保存用户隔离 JSON；Java 在原始 SSE 透传中完整解析帧并记录，UTF-8/多行/拆包正确。阶段/终止立即保存，文本更新节流保存；finally 保存未完成状态，不能将 EOF 当成功。
- [ ] 新增所属会话 runs 接口；清空/删除验证所有权并清理运行。将重试 request 的 provider/选项/附件引用存入运行，而不是明文凭据。
- [ ] 重跑上述测试与现有 Java agent 测试；不得覆盖其他任务新增的 run/resume 测试和接口。

### Task 3: 附件与会话任务工作区

**Files:**
- Create: Java `model/agent/AgentWorkspace.java`, `repository/agent/AgentWorkspaceRepository.java`, `service/agent/AgentWorkspaceService.java`, `controller/agent/AgentWorkspaceController.java`。
- Modify: Java `AgentGatewayService.java`, `AgentSessionAndChatDtos.java`；Python `schemas.py`, `routes.py`, `prompts.py`。
- Create: Python `app/attachments.py`；前端 `src/stores/agentWorkspace.js`。
- Test: Python `tests/test_workbench_attachments.py`；Java `AgentWorkspaceServiceTest.java`。

**Interfaces:**
- Consumes: Task 1 patch/parser contract；Task 2 所属运行/产物查询。
- Produces: Python `extract_attachment(file_name: str, content: bytes) -> dict` 和签名内部 extraction/patch-preview 接口；Java 输出 `{id, fileName, content, sha256, sizeBytes, truncated, sourceId}`。
- Produces: `useAgentWorkspaceStore()` 的 `load(sessionId)`、`upload(sessionId,file)`、`removeAttachment(sessionId,id)`、`previewPatch(sessionId,artifactId,baseAttachmentId)`、`applyPatch(sessionId,artifactId,preview)`、`createTasks(sessionId,artifactId)`、`updateTask(sessionId,id,status)`、`saveArtifact(sessionId,artifactId)`。

- [ ] 测 UTF-8 文本、PDF/DOCX 实际提取、5MB 拒绝、损坏/压缩炸弹拒绝、附件和产物跨用户/会话拒绝。
- [ ] 测 preview 不修改原文、apply 校验两个 hash、旧版本不变、新 ID/version 保存；重复 task 创建幂等且状态修改持久。
- [ ] 运行 Python/Java 对应测试确认红灯。
- [ ] 用现有 Python 文档解析能力提取；只接受 Java 上传字节，不接受客户端服务器路径。校验 multipart 类型/大小；为 prompt 设置显式字符预算和截断元数据。
- [ ] 实现 Java 工作区 JSON 持久化与上述操作，所有更新检查所属会话和 canonical artifact；应用先 preview 后确认，新版本写入成功才返回。
- [ ] 实现前端工作区 store，所有 API 失败传播给界面；组件不自行假造已上传/已创建。
- [ ] 重跑测试。

### Task 4: 真实联网与来源上下文

**Files:**
- Create: Python `app/web_search.py`, `tests/test_workbench_search.py`。
- Modify: Python `orchestrator.py` `_prepare_context`, `prompts.py`, `schemas.py`；Java `AgentGatewayService.java` DTO/请求转发。

**Interfaces:**
- Produces: `async search_web(query: str, *, limit: int = 5) -> list[dict]`，真实结果 `{title,url,snippet}`；明确 `WebSearchError`，空/挑战页不是成功结果。
- Produces: sources event 和 citable A/K/W/T 标识；关闭联网时不调用搜索。

- [ ] 用 httpx MockTransport 测真实格式响应解析、重定向结果 URL 解码、非 HTTP(S) 拒绝、challenge/429/timeout 明确失败；输入附件绝不发送搜索服务。
- [ ] 测 webSearchEnabled=false 调用计数为零、true 注入真实来源并保留检索失败状态。
- [ ] 运行 `.venv311/bin/python -m pytest tests/test_workbench_search.py -q` 确认红灯。
- [ ] 实现固定公网搜索端点的 HTTP 查询/HTML 解析，不访问用户指定内部地址，不请求 arbitrary URL。遇到不可用返回明确信息，不编造结果或静默标就绪。
- [ ] 将真实附件、知识库、检索、工具来源分开输出，提示模型引用来源；未拿到事实不得出现“已扫描仓库/性能压测成功”。
- [ ] 重跑测试；最终 Task 11 必须实际联网验收，不能只以 MockTransport 通过代替。

### Task 5: 真实模型协同

**Files:**
- Create: Python `app/collaboration.py`, `tests/test_workbench_collaboration.py`。
- Modify: Python `orchestrator.py` AgentState/图节点和路由、`schemas.py` Options；Java DTO/请求 options 转发。

**Interfaces:**
- Produces: `async run_collaboration(messages, client, tools, emit, *, run_id, options) -> dict`，返回 `messages`, `usage_in`, `usage_out`, `artifacts`, `sources`, `agent_results`；仅普通 ASSISTANT_CHAT 可启用。
- 三角色 ID 为 code/doc/arch，汇总 ID 为 synth；同一配置的模型用于不同独立调用，界面如实说明，不虚构多供应商。

- [ ] fake streaming client 用 barrier 断言三个角色开始有时间重叠，synth 必须等结果齐备；ordinary 模式没有额外角色请求。

```python
assert len(started_agents) == 3
assert synthesis_started_at >= max(analyst_finished_at)
assert answer_text == synthesis_text
```

- [ ] 测角色 reasoning/text/tool 分流、同名工具 callId 关联、一个角色失败可解释汇总、全部失败不生成成功结论、cancel 关闭全部 provider generators。
- [ ] 运行 `.venv311/bin/python -m pytest tests/test_workbench_collaboration.py -q` 确认红灯。
- [ ] 每角色独立 transcript、有限工具/模型预算，共享已授权工具访问边界；fan-out 并行后 join。analyst reasoning/输出带角色标识，只有 synth delta 写主答案和短期记忆。
- [ ] 保留现有 checkpoint/runtime 边界，存 plain data 和结果，不存 client/keys；累加 usage 不重复累计，队列/进行中/失败状态真实。
- [ ] 将协同接入现有 LangGraph 的普通聊天路径，不改模式 B 的必需工具和结构校验；重跑普通评测/流式适配测试。

### Task 6: 真正产物、任务与来源投影

**Files:**
- Modify: Python `orchestrator.py` 完成/角色输出阶段，`artifacts.py`。
- Create: 前端 `src/components/agent/runView.js`, `src/components/agent/artifactView.js`, `src/components/agent/runView.test.mjs`, `artifactView.test.mjs`。

**Interfaces:**
- Produces: `buildRunView(trace, {live, elapsedMs, usage}) -> {nodes,edges,steps,agents,summary,reasoning,usage,artifacts,sources}`；事件身份/父级依赖生成真实有向图。
- Produces: `formatTraceText(trace) -> string`、`artifactTabs(artifact,run) -> array`、`artifactText(artifact,tab,run) -> string`。

- [ ] 测三个 reasoning deltas 合并为同一个模型思考节点；一个模型+两个工具不能显示“四智能体”；unknown phase 不崩溃；failed/cancelled 永不完成进度。
- [ ] 测 Raw 的 canonical bytes、patch Diff、Log 真实记录、Graph 真实 edges、未知 usage 为未知而非 fabricated count。
- [ ] 运行 `node --test src/components/agent/runView.test.mjs src/components/agent/artifactView.test.mjs` 确认红灯。
- [ ] 实现纯函数，不连接网络或计时制造状态；产物仅由已实际生成的完整内容产生。Graph JSON 是执行溯源，不是装饰拓扑。
- [ ] 重跑测试。

### Task 7: SSE、会话 store 与历史恢复

**Files:**
- Modify: `src/services/agentApi.js`, `src/stores/agent.js`。
- Create: `src/services/agentApi.test.mjs`, `src/stores/agent.test.mjs`。

**Interfaces:**
- `streamAgentChat(sessionId,payload,{onEvent})` 将每个解析后的 `{name,data,id}` 交给 store；返回 `{cancel,done}`。现有专用 handlers 保留实际调用者所需语义，不重复处理。
- `sendMessage(text, options)` 传真实参数和附件 ID；`retryMessage(messageId)` 新运行，保留用户问题与原答案、标明新版本。
- 已保存消息包含 runId、trace、usage、artifacts、sources、provider/options、status、elapsedMs；持久 runs 优先，原 role/content 历史不得覆盖缓存已有 trace。

- [ ] 测分包/CRLF/中文 UTF-8/多行帧、非法 SSE payload、非 SSE 响应、没有 done 的 EOF 为 interrupted。
- [ ] 测同名工具以 callId 更新、reasoning 合并、stale stream 不污染切换后会话、取消不允许新流被旧 finally 关闭。
- [ ] 测持久历史/缓存合并、错误/截断不变 done、清空先服务成功、刷新恢复、重试不追加同一用户问题、跨账号本地缓存隔离。
- [ ] 使用 Vite SSR/Pinia 的既有 Node 测试模式；运行 `node --test src/services/agentApi.test.mjs src/stores/agent.test.mjs` 确认红灯。
- [ ] 实现明确 generation/run identity、终止状态和 API 错误传播；全程实时 reactive 更新，读取服务端 run 记录还原。
- [ ] 重跑新测试与现有 companion tests，保证不破坏酒馆/人物会话。

### Task 8: 顶部融合、Thought Ribbon 与 composer

**Files:**
- Modify: `src/components/agent/AgentChatView.vue`, `AgentTrace.vue`。
- Create: `AgentExecutionBoard.vue`, `AgentRunGraph.vue`, `AgentMarkdown.vue`。
- Modify: `package.json`, `package-lock.json`, `src/locales/zh.json`, `en.json`；仅增加实际 Markdown 依赖。

**Interfaces:**
- `AgentTrace` 继续接收 trace/live/elapsedMs，增加 usage/source/产物按 run view 渲染；copy 复制 formatTraceText，conclusion 折叠。
- `AgentExecutionBoard` 接收 run/模型标签/真实产物，emit preview；`AgentRunGraph` 接收真实 nodes/edges，无定时模拟。
- `AgentMarkdown` 接收 content，禁 HTML、清洗 DOM、复制真实 code；emit source(id) 打开来源。

- [ ] 建立组件挂载/浏览器行为测试：ribbon aria-expanded、长思考折叠/复制、源码脚本不执行、原生文本无思考时说明、图节点基于实际事件。
- [ ] 先运行测试确认旧 UI 无相应控制/结构。
- [ ] 顶部保留人物视窗，旁边布局真实 pipeline/outputs；768px 正文，单独 ribbon 后正文，不以 terminal `<pre>` 包全部日志。
- [ ] 输入控件绑定真实 options：温度、输出预算、工具轮次、协同、联网、附件/KB；输入4000字符边界和计数一致，发送中可停止，中文 IME 不发送。
- [ ] 新增复制/重试/来源/参数、刷新/全屏/导出动作；复制与导出真实内容，URL 对象创建后释放。
- [ ] `npm run build`，启动 dev server 在浏览器测普通/协同/长回复/短窗口；核对人物鼠标跟随而非仅 canvas 存在。

### Task 9: 产物抽屉完整交互

**Files:**
- Create: `src/components/agent/AgentArtifactDrawer.vue`。
- Modify: `AgentChatView.vue`, `agentWorkspace.js`, `agentApi.js`, `zh.json`, `en.json`。

**Interfaces:**
- Drawer props: visible/artifact/run/attachments；emit close、preview-patch、apply-patch、create-tasks、save-artifact；tab 内容来自 Task 6。

- [ ] 先浏览器验证四标签/复制/下载/apply-confirm/任务保存的测试红灯；无源码或补丁类型时不可显示“有效差异”。
- [ ] 使用 Element Plus drawer 维持 focus trap、Escape/遮罩关闭、还原打开按钮焦点；快捷键只在 AI 工作台/抽屉内部生效。
- [ ] 展示真实大小/hash/角色；复制当前 canonical 内容，下载真实 Blob，应用先选择上传源文件并显示 dry-run，明确确认后请求 Java 保存新版本。
- [ ] JSON 任务创建后可见、可勾选、刷新仍存在；Graph 保存产生真实会话附件；所有错误明确显示，不替换按钮为假成功。
- [ ] 实测 Cmd/Ctrl+E、抽屉内部1/2/3切产物、Escape、无产物空态、长文件/移动端滚动。

### Task 10: 会话栏、全局整合与响应式

**Files:**
- Modify: `AgentSessionList.vue`, `src/views/Main.vue`, `src/components/layout/LeftPanel.vue`（仅 AI 面板必要整合），`zh.json`, `en.json`。

**Interfaces:**
- 复用 provide/inject toggleList/listCollapsed/activeView，不重建应用导航。
- session filter 支持标题与真实缓存/持久消息摘要。当前运行状态独立突出，但既有时间排序不随选择重排。

- [ ] 先测试 Cmd+N 创建 AI session、搜索/清空筛选、刷新后标题摘要、键盘 Enter/Space 选中、重命名/删除确认、请求失败不丢会话。
- [ ] 在 Main 的 AI 路径整合搜索/产物快捷键，其他聊天/联系人/群组/酒馆仍保持原快捷键。
- [ ] 桌面持续会话栏，平板紧凑/折叠，移动返回/抽屉；390/820/1440px 和短高度下消息/composer 可见、无横向溢出。
- [ ] 图与步骤的动画仅活动节点使用，支持 prefers-reduced-motion；屏幕阅读器不逐 token 高频播报，状态条用低频 aria-live。
- [ ] 实测其他视图导航与酒馆回归。

### Task 11: 全链路验证、独立审查与完成审计

**Files:** 此 plan 的任务复选框；必要的行为测试。不生成无关报告或批量截图文件。

- [ ] 运行 Python 全套：`.venv311/bin/python -m pytest -q`，保持 conftest 隔离真实 `.env`/向量库。
- [ ] 运行 Java：`mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false`；选择 pom 要求的实际 JDK，不修改签名/测试钩子绕过失败。
- [ ] 运行 Node 本任务与既有行为测试、`npm run build`；失败先查根因，再修代码。
- [ ] 启动本次实际前端/Python/必要 Java 服务，使用已授权用户会话和配置模型做真实普通推理、原生 reasoning、协同、工具、文件与联网；没有真实上游验证的项必须保留未完成。
- [ ] 浏览器 golden path 与异常：上传/发送/流式/展开/复制/产物四标签/应用/任务/重试/重载；模拟断网/超时/取消/切换；检查 console/network。
- [ ] 独立 code review（Python/Java/Vue/JS、安全边界）发现项逐一复核修复；不可把不可用代理声称为已审查。
- [ ] 对照需求矩阵逐项记录具体代码、命令输出和浏览器证据；检查没有静态示例数据、假百分比、失效按钮、思考丢失、模型调用职责倒置。
- [ ] 回迁仅本任务差异时先检查主工作区现状，冲突协调，不覆写用户新改动。
- [ ] 停掉自己启动的服务，清理自己已无用的构建/截图/临时依赖；有交付代码的工作树在验收前保留，不擅删分支。
- [ ] 只有全部明确要求完成且有证据，才标记目标 complete；不能仅以测试绿灯/界面截图代替完整验收。
