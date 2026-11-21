# AI 会话工作台 — 新会话交接文档（2026-11-20）

> 用途：在新会话中继续本任务。先读本文件，再读详细 plan，然后从「未完成工作清单」接着做。
> 本文档同时存在于主工作区与隔离工作树（内容相同）：
> - `/Users/anglv/Nexus/docs/superpowers/plans/2026-11-20-ai-workbench-handoff.md`
> - `/Users/anglv/alma/worktrees/nexus/ai-workbench/docs/superpowers/plans/2026-11-20-ai-workbench-handoff.md`

---

## 0. 底座同步（2026-11-21，已完成）

主工作区 `/Users/anglv/Nexus` 在本次同步前完成了三件底座改造；工作树已按「三方合并」同步（基线 `fe5bc5ee`、工作树改动、主工作区改动）：

- **LangGraph 单引擎**：手写 ReAct 循环与 `orchestrator_langgraph.py` 已删除，`ENGINE` 只接受 `langgraph`；图为 `prepare_context → reason ⇄ tools → validate_output → settle`。
- **工具走 MCP**：`app/tools.py` 已删除。Java 在 `/internal/agent/mcp` 提供只读工具，Python 由 `app/mcp_tools.py` 发现并调用。
- **运行检查点**：`app/checkpoint.py` 新增「已结束运行的结果表」；`/v1/agent/runs/pending` 的返回多了 `finished`。

合并时解决冲突的文件（保留工作树的界面重写 + 主工作区的功能修复）：`stores/agent.js`、`AgentChatView.vue`、`AgentTrace.vue`、`stores/agent.test.mjs`、`views/Main.vue`、两个 locale。

## 同步后的实测状态（2026-11-21 下午，最新）

| 项 | 结果 |
|---|---|
| Python | **345 passed, 0 failed**。三份红测试全部转绿，另新增 `tests/test_workbench_documents.py`（4 条） |
| Java | **317 tests, 0 failures**（31 个测试类）。六份红测试全部转绿 |
| 前端 | **228 passed**，`vite build` 通过 |
| 真实端到端 | 真实 Java(8080) → 真实 Python(8196) → 真实模型：会话、附件上传与提取、流式对话、来源 A1、执行图产物、运行记录、补丁预演与应用、任务创建与勾选，逐项实测通过 |
| 真实浏览器 | 无头 Chrome 打开真实应用：AI 工作台、思考面板展开、产物抽屉、390/820/1440 三档无横向溢出、刷新后思考与答案还原 |

本轮新增的实现：

- Python `app/artifacts.py`（严格 unified diff + 产物提取）、`app/attachments.py`（UTF-8/PDF/DOCX，5MB 上限，32k 字符预算）、`app/web_search.py`（固定端点，挑战页/重定向/超时/超大明确失败）、`app/collaboration.py`（三角色并行 + synth 汇总）。
- 编排器：公开事件信封 `{runId, sequence, at}`、`sources`/`artifact` 事件、`usageReported`、附件注入、协同接入图。
- Python 端点 `POST /v1/agent/attachments/extract`、`POST /v1/agent/artifacts/patch-preview`。
- Java `AgentRunService`（运行记录、脱敏、SSE 逐帧记录、恢复）、`AgentWorkspaceService`（附件/任务/补丁版本）、`AgentWorkspaceController`、`SseRecordingOutputStream`、`AgentController` 的 runs 接口与清空/删除联动。

修掉的问题（本轮发现）：

- 已完成的运行点「继续」原本报 400，现在直接交回保存的结果，不再调模型、不重开运行。
- 待办产物的 `task` 字段（项目自己的 `TodoItem` 字段名）被当成缺标题而拒绝；现在 `title` 与 `task` 都认。
- Java 转发未带 `runId`/`retryOfRunId`/`input.attachments`/两个新开关；已补。
- 文档请求没带 actor 签名；已补，且上游 422 保留为 422 并只透出原因。
- 显式选择的渠道不可用时曾静默回落默认渠道；现在明确报错。
- SSE 逐帧解析按字节切分，多字节字符跨写不再被破坏。
- 会话栏摘要会把「渠道配置的下一个模型」和 `Mock demo` 当成本轮模型；只显示本轮真实报告的数据。
- 会话栏摘要把传输提前结束当运行失败；改为不覆盖终态。
- 会话栏挂载时抢输入焦点。
- `answerMarkdown` 转义引用仍可点击。
- `AgentChatView` 清空确认跨会话竞态、工作区不随会话切换。
- 手机点选会话要等历史读完才开画布。

## 同步后的实测状态（2026-11-21 上午）

| 项 | 结果 |
|---|---|
| Python | 257 passed / 84 failed。失败仍是三份红测试：`tests/test_workbench_files.py`（缺 `app/artifacts.py`、`app/attachments.py`）、`tests/test_workbench_search.py`（缺 `app/web_search.py`）、`tests/evals/test_workbench_runtime.py`（缺事件信封 / sources / artifact / 协同） |
| Java | main 编译通过；暂时移开 6 个依赖未实现类的红测试文件后，其余 25 个测试类共 269 个测试全绿 |
| 前端 | 228 个测试全绿（`sessionNavigation` 9/9），`vite build` 通过 |
| 仍缺 | `AgentRunService`、`AgentWorkspaceService`、`AgentRunController`、`AgentWorkspaceController` 及 DTO 接线 |

同步时一并修掉的问题：

- `AgentSessionList.onSelect` 在手机上「等历史读完才开画布」，读失败就停在列表；改为点选即开。
- 会话栏摘要把「渠道配置的下一个模型」和 `Mock demo` 当成本轮模型显示；改为只显示本轮真实报告的数据。
- 会话栏摘要把「传输提前结束」当成运行失败，覆盖了本轮真实终态；改为传输故障不覆盖终态。
- `answerMarkdown` 的转义引用（`\[A1\]`）仍被渲染成可点击来源：审查第 6 项此前没有真正修好。
- 会话栏挂载时抢走输入焦点（审查清单之外的新发现）。
- `AgentChatView` 的「清空」确认跨会话竞态、工作区不随会话切换（审查 Blockers 1、2）已修复并补测试。
- QA 的 `python-qa.py` 补了 `ENGINE=langgraph`（主工作区 `.env` 仍是 `handcrafted`，会拒绝启动）。

---

## 1. 任务目标（用户原话要点）

按设计稿重构 Nexus 的 AI 会话模块，布局与功能全部真实实现：

- 设计稿：`/Users/anglv/Downloads/stitch_ios_style_ai_messenger/`（`DESIGN.md`、`code.html`、`screen.png`）。单色极简 AI 工作台：会话侧栏、顶部控制条、Swarm Pipeline 执行看板 + Extracted Artifacts、可折叠 Thought Ribbon（思考过程：多角色 DAG 拓扑 + 细线步骤时间轴）、结构化答案卡（复制全文/重新推理/引用溯源）、产物抽屉（Diff/Raw/Log/Graph + 应用/下载）、composer（附件/联网/协同开关 + 字数）。
- **保留顶部鼠标跟随 3D 人物**（`abyssStage.js` 的 Three.js/WebGL 舞台），把设计稿顶部看板围绕它融合，不用静态图替代。
- **思考过程必须是真实后端数据**（原生 reasoning、真实工具/角色/来源/产物事件），严禁静态假数据、假百分比、假 tokens、假角色。
- 流程要求：先规划、后按 plan 执行。plan 已完成并经用户确认（见下）。
- 用户已确认的决策：
  - 产物操作作用于「文件与会话任务」（上传文件→新版本；阻塞项→持久会话任务），**不接外部 Git/Issue 平台**。
  - 实现在**隔离工作树**（已建，见下）。
  - 同意**少量真实模型联调**（只用合成测试 prompt，绝不使用用户历史/私有附件）。
  - 用户的 Python 后端**没有开**（不要动 8100 端口）；联调用自己起的隔离实例（8196）。

## 2. 详细计划（权威规格）

**`/Users/anglv/alma/worktrees/nexus/ai-workbench/docs/superpowers/plans/2026-11-20-ai-workbench.md`**
（主工作区 `/Users/anglv/Nexus/docs/superpowers/plans/` 下有同一份，已 diff 确认一致。）

包含：Global Constraints、需求→任务验收矩阵、固定接口与数据形状（请求/事件/持久化/工作区操作）、Task 1–11 的文件清单、接口签名、TDD 步骤与测试代码。11 个任务概览：

1. 真实事件与补丁/产物基础（Python `app/artifacts.py` + 事件信封）
2. Java 持久运行与所有权（`AgentRunService` + runs 接口）
3. 附件与会话任务工作区（Java `AgentWorkspaceService` + Python `app/attachments.py` + 前端 `agentWorkspace.js`）
4. 真实联网与来源上下文（Python `app/web_search.py` + sources 事件）
5. 真实模型协同（Python `app/collaboration.py`，code/doc/arch 三并行角色 + synth 汇总）
6. 产物/任务/来源前端投影（`runView.js`、`artifactView.js`）
7. SSE、会话 store 与历史恢复（`agentApi.js`、`stores/agent.js`）
8. 顶部融合、Thought Ribbon 与 composer（`AgentExecutionBoard/AgentTrace/AgentRunGraph/AgentMarkdown/AgentComposer`）
9. 产物抽屉完整交互（`AgentArtifactDrawer.vue`）
10. 会话栏、全局整合与响应式（`AgentSessionList.vue`、`Main.vue`、`LeftPanel.vue`）
11. 全链路验证、独立审查与完成审计（全套测试 + 真实浏览器 golden path + 逐项审计 + 清理）

关键事件契约（plan「固定接口」节为准）：每个公开 SSE 事件带 `{runId, sequence, at}`；kinds：meta/stage/reasoning/tool_call/tool_result/agent_output/sources/artifact/delta/usage/done/error；stage/tool 带 nodeId/agentId/parentIds/callId；sources ID 为 A1/K1/W1/T1（附件/知识库/联网/工具）；artifact 带真实 sizeBytes/sha256/runId/agentId/nodeId/sourceIds/callIds；供应商未报 usage 时 `usageReported:false`。协同=显式开启后 3 个真实并行角色 LLM 调用（同一所选模型、独立 transcript），join 后 synth；只有 synth 的 delta 进主答案与短期记忆。

## 3. 代码位置与分支

| 项 | 值 |
|---|---|
| 主工作区 | `/Users/anglv/Nexus`（分支 `feature-branch`，**有用户自己的未提交改动，不要碰**） |
| 隔离工作树 | `/Users/anglv/alma/worktrees/nexus/ai-workbench` |
| 工作分支 | `feat/ai-workbench`（基于 `3d5248e`，**尚未做任何 commit**，全部是工作区改动） |
| 基线快照 | 主工作区当时的未提交改动已复制进工作树；基线 tree `fe5bc5ee903152866fd1fa0838041328bca9b762` 记录在工作树 `.git/ai-workbench-update.index`（回迁前先 diff 主工作区现状，避免覆盖用户新改动） |
| 前端依赖 | 工作树 `node_modules` 由主工作区 `cp -cR` 克隆；新增 `markdown-it@^14`、`dompurify@^3`（只装在工作树） |
| Python 环境 | 工作树 `nexus-agent-backend/.venv311` 是指向主工作区 venv 的符号链接；解释器 `/Users/anglv/Nexus/nexus-agent-backend/.venv311/bin/python` |

架构职责（用户记忆中的硬约束）：**模型发现/测试/推理全部在 Python**；Java 只做鉴权、凭据、业务、持久化、SSE 透传（HMAC 签名内部头 X-Internal-*）。不新建 Java 供应商客户端。

## 4. 当前进度快照（2026-11-20 ~16:10 实测）

### 测试现状

- **Python**：`182 passed, 84 failed`。失败全部来自未实现模块的红测试：
  - `tests/test_workbench_files.py` → 缺 `app/artifacts.py`（apply_unified_patch / extract_artifacts / ArtifactError）与 `app/attachments.py`（extract_attachment）。
  - `tests/test_workbench_search.py` → 缺 `app/web_search.py`（search_web / WebSearchError）。
  - `tests/evals/test_workbench_runtime.py`（3 条，我写的集成契约红测试）→ orchestrator 尚未输出事件信封 sequence、attachments→A1 sources、fenced-json artifact 溯源、协同并行。
- **Java**：测试**编译失败**——缺 `AgentRunService.java`、`AgentWorkspaceService.java` 及控制器接线。已存在：`model/agent/AgentRunRecord.java`、`AgentWorkspace.java`、两个 Repository、红测试 `AgentRunServiceTest`、`AgentWorkspaceServiceTest`、`AgentWorkbenchGatewayTest`、`controller/agent/AgentRunControllerTest`、mock 助手 `AgentWorkbenchTestStore`。既有 `AgentRunGatewayTest`/`AgentRunResumeTest` 之前是绿的。
- **前端 Node**：`62 pass, 1 fail`。失败文件 `sessionNavigation.test.mjs`（导航整合代理中途失败留下的；9 个用例只跑完部分，文件级失败/挂起，需排查）。其余全绿：runView/artifactView 32 条、AgentArtifactDrawer 15 条、AgentChatView 7 条、answerMarkdown、workbenchUi 等。

### 已完成（有证据）

- **前端投影层**：`runView.js`（attempt 感知去重、resumed 分离、终态优先、usageReported 处理）、`artifactView.js`（Log/Graph 按生产者节点+parentIds+callIds+sourceIds 严格溯源隔离）+ 32 条测试。
- **前端组件**（均真实数据驱动，无假数据）：
  - `AgentTrace.vue` = Thought Ribbon（摘要开关 aria-expanded、复制思考流、仅看结论、DAG、步骤轴、reasoning、agent_output、实时时钟、reduced-motion、44px 触控）。
  - `AgentRunGraph.vue` = 真实事件 DAG（DOM 测量 SVG 连线、仅 running 节点流动动画）。
  - `AgentExecutionBoard.vue` = 顶部看板（**动态挂载 abyssStage 人物**、IntersectionObserver、WebGL 不可用降级文案、真实节点/产物、≤530px 容器查询重排）。
  - `abyssStage.js` 增强：无 WebGL 时抛错降级、鼠标归一化 clamp、prefers-reduced-motion 冻结。
  - `AgentMarkdown.vue` + `answerMarkdown.js`：markdown-it(html:false)+DOMPurify 白名单、代码块复制、`[A1]` 引用按钮（跳过代码/链接）、仅 https 链接。
  - `AgentChatView.vue`（我完整重写）：面包屑/模型下拉/产物计数⌘E/刷新/参数对话框/更多菜单（真全屏、md 导出、清空确认）；768px  feed；看板+欢迎 prompts+消息；助手=Trace+答案卡（复制/重试/来源/续跑）；抽屉绑定产物所属 run。
  - `AgentComposer.vue`（我实现）：自动增高、4000 码点计数、IME 守卫、附件≤4/≤5MB、联网/协同开关+披露、工作区对话框、真实 options 发送、epoch 守卫。
  - `AgentArtifactDrawer.vue` + 15 条测试：Diff/Raw/Log/Graph、hash 绑定 preview→确认→apply、任务清单/导出、graph 保存、stale 失效、⌘1-3、焦点恢复。
  - `stores/agentWorkspace.js`：select/generation/owner 守卫 + 全部工作区 API 封装。
  - locales `agent.workbench.*`（zh/en）已加。
- **真实浏览器组件级验证**（chrome-devtools，隔离 harness 5186）：人物鼠标跟随（gaze uniform 随鼠标变化）、390px 无溢出、reduced-motion、复制/折叠/焦点/引用均实测通过。
- **真实上游预检**：隔离 Java(8186)→Python(8196) 真实模型调用成功（deepseek-v4.1-flash，原生 reasoning×2，1542 tokens，33.9s）。
- **独立渲染安全审查已完成**，发现 7 项（见 §6）。

### 未完成 / 中断

多个后台实现代理因**模型配额耗尽（Wendy 429/403）中途失败**，留下红测试和部分文件：前端流/历史接线代理、Java 网关持久化代理、导航整合代理、证据/文件处理代理、协同模块代理（未写任何文件）。**新会话建议直接自己实现或小额重试代理，不要假设代理产出完整。**

## 5. 未完成工作清单（按依赖顺序）

### A. Python（工作树 `nexus-agent-backend/`）
1. `app/artifacts.py`：严格 unified diff（文件名匹配、禁绝对/父级路径、hunk 旧行逐项校验）+ `extract_artifacts`（完整 fence 才产出、真实 UTF-8 字节数/sha256、非法 JSON 归 code）。红测试：`tests/test_workbench_files.py`。
2. `app/attachments.py`：`extract_attachment(file_name, content: bytes)`（UTF-8/PDF/DOCX，复用现有解析能力，截断元数据）。同上红测试。
3. `app/web_search.py`：`async search_web(query, limit=5)` 固定公网端点、challenge/429/timeout 明确失败、绝不发送附件内容。红测试：`tests/test_workbench_search.py`。
4. `app/collaboration.py`：**完全缺失**。契约：`run_collaboration(messages, client, tools, emit, *, run_id, options)`，code/doc/arch 三独立并行调用（barrier 可证重叠）+ synth join；角色 reasoning/输出带 agentId，只有 synth delta 进答案/记忆；cancel 关闭全部 generators。plan Task 5 有测试代码。
5. `app/schemas.py`：Options 增 `collaborationEnabled`/`webSearchEnabled`；InputPayload 增 `attachments`；`retryOfRunId`。
6. `app/orchestrator.py`：公开事件信封 `{runId, sequence(从1递增), at}`；stage/reasoning 带 nodeId/parentIds（context→model-1→tool-n→validate→settle）；attachments→sources A1；完成阶段 `extract_artifacts` 发 artifact 事件；usageReported；协同分支接线；`_prepare_context` 注入附件/KB/联网来源并提示引用。红测试：`tests/evals/test_workbench_runtime.py`（注意 `eval_harness.check_invariants` 要求恰好一个 usage 事件、model-stage 对数==模型调用数；`build_messages` 有快照，改 prompt 需有意更新）。
7. `app/routes.py`：签名内部端点 `POST /v1/agent/attachments/extract`、`POST /v1/agent/artifacts/patch-preview`。

### B. Java（工作树 `nexus-chat-backend/`）
1. `service/agent/AgentRunService.java`：begin/record/finish/list/findArtifact/deleteSession；SSE 透传中完整解析帧记录（UTF-8/拆包）、节流文本保存、EOF 无 done→interrupted、allowlist 脱敏（不存 key/内部头）。红测试已存在（含 `AgentWorkbenchTestStore` mock）。
2. `service/agent/AgentWorkspaceService.java` + `controller/agent/AgentWorkspaceController.java`：附件 multipart≤5MB/≤4 选中、经签名通道调 Python extract/patch-preview、apply 双 hash 校验后新版本、tasks 幂等创建/状态持久、save 产物为附件。红测试已存在。
3. `AgentGatewayService`/`AgentController`/DTO 接线：runId/attachmentIds/options 转发、`GET /api/agent/sessions/{sid}/runs`、清空/删除联动运行与工作区。
4. 验证命令：`mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false`（用 pom 要求的 JDK）。

### C. 前端（工作树 `nexus-chat-frontend/`）
1. 核查/补完 `stores/agent.js` + `services/agentApi.js`（接线代理中途失败）：executeStream/resumeMessage/callId 匹配/interrupted 已有基础；需确认 refreshActiveSession、retryMessage（新 runId 不重复用户问题）、agentOwnerId 跨账号隔离、持久 runs 优先恢复历史。plan Task 7 的 `agentApi.test.mjs`/`agent.test.mjs` 可能未建。
2. 导航整合（`Main.vue`、`LeftPanel.vue`、`AgentSessionList.vue`）：修复 `sessionNavigation.test.mjs` 文件级失败；**已知集成 bug：5187 真实应用点「AI 会话」导航后新 AgentChatView 未挂载**（页面仍是消息列表空态，无 console 错误）——怀疑虚拟 AI 会话未经 `setActiveChat(AI_ASSISTANT_CHAT_ID)`/`openAiAssistant` 路径激活，或 5187 dev server 缓存旧 transform。这是恢复浏览器验收的第一件事。
3. 修复审查 7 项（§6）。
4. `npm run build` + 真实应用浏览器验收。

### D. 全链路验收（plan Task 11）
重启隔离 QA 栈（§7）→ 真实模型 golden path：上传/发送/流式/展开思考/复制/产物四标签/应用补丁/任务/重试/刷新恢复/协同/联网/断网/取消/切换会话；390/820/1440px；console/network 检查；三套测试全绿；对照 plan 需求矩阵逐项审计；清理自起服务与临时产物；**不 commit/push/PR，除非用户另行要求**。

## 6. 独立审查发现（渲染安全代理，已完成；逐项修复）

**Blockers：**
1. `AgentChatView.vue:101` 清空确认竞态：A 会话弹确认时切到 B，确认会清空 B。打开前捕获 sessionId，变更则中止。
2. `AgentChatView.vue:111` 工作区会话选择：A→B 切换后未重新 select/load workspace，B 的上传被拒。每次会话变更（含 null）都要 select/load。

**非阻塞正确性：**
3. `AgentTrace.vue:61` 仅 done 的 trace 复制按钮会把空文本写入剪贴板并报成功 → 格式化文本为空时禁用复制。
4. `AgentTrace.vue:67-72` 续跑 30s 的运行，时钟从 ~30s 起而非新 attempt 的 0 → attempt 变更时重置时钟。

**打磨/可达性：**
5. `AgentChatView.vue:100` 不支持 Fullscreen API 的浏览器应禁用该菜单项。
6. `answerMarkdown.js:19-23` `\[A1\]` 转义引用被误判为可点击 → 在 Markdown 丢弃转义信息前解析引用。
7. `AgentTrace.vue:60` 键盘触发「仅看结论」后焦点按钮被移除 → 折叠后把焦点还给 ribbon 开关。

## 7. 隔离 QA 环境（当前全部已停止，需要时重启）

目录：`/private/tmp/nexus-workbench-qa.3SMY7m/`（一次性 MySQL datadir、0600 密钥文件、脚本与日志）。

| 服务 | 端口 | 启动方式 |
|---|---|---|
| MySQL（一次性） | 3396 | 目录内 datadir + db-secret/db-admin |
| Redis | 6396 | 隔离实例 |
| 本地 SMTP（不外发） | 2526 | `mailbox.py` |
| Java 网关 | 8186 | `java-qa.py` |
| Python FastAPI | 8196 | `python-qa.py`（加载主工作区 `.env` 凭据；**已修复 NO_PROXY 中 `[::1]` 导致 httpx InvalidURL 的问题**） |
| Vite 组件 harness | 5186 | 工作树前端根 `.qa-workbench.html` |
| Vite 真实应用 | 5187 | 工作树前端 dev server |

- QA 用户：`workbenchqa` / `workbench-qa@example.invalid`，密码在目录内 `qa-user-pw`（`Workbench-UI-QA-Only-7391!`），浏览器登录脚本 `browser-login.js`，预检 `preflight.py`。
- 浏览器：chrome-devtools MCP（注意本会话中服务器重连后工具前缀从 `mcp__chrome_devtools__` 变为 `mcp__chrome-devtools__`，用 mcp_list 确认）。
- 真实模型预检曾成功（合成 prompt）；后续联调继续只用合成数据。
- **任务结束清理**：停掉自起服务、删无用临时产物；保留有交付代码的工作树与分支；不碰主工作区用户改动。

## 8. 常用命令

```bash
# Python 全套（工作树内）
cd /Users/anglv/alma/worktrees/nexus/ai-workbench/nexus-agent-backend
/Users/anglv/Nexus/nexus-agent-backend/.venv311/bin/python -m pytest -q

# 前端 agent 测试
cd /Users/anglv/alma/worktrees/nexus/ai-workbench/nexus-chat-frontend
node --test src/components/agent/*.test.mjs

# Java（pom 要求的 JDK）
cd /Users/anglv/alma/worktrees/nexus/ai-workbench/nexus-chat-backend
mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
```

## 9. 硬约束备忘（违反即返工）

- 不 commit/push/PR，除非用户明确要求；不删其他工作树/分支/用户未提交改动。
- 人物必须保留且真实鼠标跟随；思考/角色/进度/tokens/产物全部来自真实事件，无 reasoning 时明确说明。
- 模型调用只在 Python；Java 不建供应商客户端。
- 联网仅显式开启才发查询；附件内容不进搜索；补丁确认后新版本不覆盖原文件；不执行 shell/模型代码/HTML。
- 所有「成功」提示必须对应真实成功结果；所有权校验覆盖会话/运行/附件/产物；运行 JSON 不存凭据。
- TDD：先红后绿；独立审查不可用代理冒充。
- 上一会话多个代理因配额（429/403 token quota）失败——新会话若代理再失败，直接自己实现。

## 10. 新会话建议开场

> 继续 AI 会话工作台任务。先读交接文档 `/Users/anglv/Nexus/docs/superpowers/plans/2026-11-20-ai-workbench-handoff.md` 和详细 plan `/Users/anglv/alma/worktrees/nexus/ai-workbench/docs/superpowers/plans/2026-11-20-ai-workbench.md`，在隔离工作树 `/Users/anglv/alma/worktrees/nexus/ai-workbench`（分支 feat/ai-workbench）按「未完成工作清单」继续：先补 Python 缺失模块使红测试转绿，再补 Java 服务，然后修前端导航挂载 bug 与审查 7 项，最后重启隔离 QA 栈做真实浏览器全链路验收。遵守文档 §9 硬约束。