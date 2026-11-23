# 工作台 MCP 扩展 / 多 Agent / 像素风实施与验收

状态：用户已批准完整计划，随后于 2026-11-22 明确改为七会话分工。本会话仅实施 T1，并承担服务统一管理、按依赖接补丁和最终 T7；不自行实施 T2/T3/T4A/T4B/T5/T6，也不继续自动推进原整包后六项。下文完整范围保留为集成依赖及验收依据，不代表本会话独占实现。运行协议以 docs/contracts/workbench-runtime-v1.md 为冻结基准。

源码根：/Users/anglv/alma/worktrees/nexus/worthwhile-tuna；基线 8f28a5a；分支 workbench-multi-agent-mcp-pixel-ui。

## 1. 不可变边界

- 仅现有 AI 工作台。不得重做聊天/联系人/群组页面、设置、陪伴、酒馆、桌宠或转场。
- Python 管模型、MCP 客户端/管理注册/发现/连接/执行、Agent/子任务编排、上下文、预算、检查点、取消/重试。
- Java 只管登录身份、业务数据/授权、业务关联与记录/凭据持久化、签名调用和事件转发；现有 Java MCP 只是 Nexus 数据提供方。不得新增 Java LLM/MCP 客户端或 Agent 调度引擎。
- 浏览器只访问 Java API；不得直连 Python 或外部 MCP。
- 保留 Vue3 + Pinia + Element Plus，现有页面/路由/store 上改；必要组件是实际领域模块拆分，不另建应用。前端新增能力放进现有 agent/agentWorkspace 状态。
- 黑白灰主题，只有过程表现层动漫卡通像素化；保留顶部 3D 舞台。通用平实命名，不把工作台变成游戏。
- 运行节点/角色/状态/进度/来源/产物/耗时/usage 均来自真实后端/供应商事件。测试替身只用于测试，不出现在生产 UI 中。缺值显示未上报；无总量不显示百分比；无 reasoning 不编造。
- 运行授权更新（用户 2026-11-22 23:04 明确授权）：用户已停止本地前端、Java 和 Python，本次由助手在当前工作树接管 5173 / 8080 / 8100，允许启动并在改动后重启自己启动的三个服务，负责持续联调至完成。先核对端口并记录 PID/启动路径，只操作本次服务，不另起第二套，也不关闭未知或其他任务服务。MySQL/Redis 优先复用现有真实实例。单测局部 fixture、构建与浏览器仍须串行并清理。
- 所有 CPU 密集测试、构建与浏览器一次一个，跑完释放；不运行多个无头浏览器。优先侧栏浏览器。
- 不验证依赖 embedding 的上传/向量检索，不修改真实用户模型/凭据/配置；不使用私有历史作测试。必要联调用合成数据，不能因旧服务未重启而宣称新代码通过真实 E2E。
- 启动服务时仅在进程内加载原有本地配置，不复制或输出凭据，不修改原配置或把秘密写入命令参数、日志、事件、测试报告或计划；新增 MCP 配置秘密由后端加密保管并脱敏返回。
- 不 commit/push/PR。不得覆盖用户或其他线程改动。仅移除本轮生成的测试脚本、fixture、缓存、构建等中间产物，保留交付源码/测试/文档。
- 使用相关 skills：Nexus项目续作、todo、use-ui-libraries、ui-ux-pro-max、界面一致性巡检；浏览器/Preview 时用 browser 与 artifact-sidebar；批量工具链需要时用 programmatic-tools。不要为了展示 skills 安装无关工具。

## 2. 已核查底座和缺陷（静态事实，不是实时线上版本）

- 已有 LangGraph 单 Agent + SQLite 检查点，Python MCP 动态发现/调用 Java 七个只读工具及外部 HTTP 白名单。
- natural-language @username/群名定位链路已有；boundChatId 创建仅回显，实体没有保存；Composer 没有完整显式绑定；工具执行只有 actor membership，没有任务 scope。
- collaboration 固定 code/doc/arch 三并发+synth，tools=None；synth 非 stop 有文本仍强制 stop；实际四次调用记 model_calls+=1。
- Java 事件去重只有 sequence+eventName，不含 attempt；Python 每次恢复 sequence 从1开始。旧 AgentRunResumeTest.interrupt 事件未带 sequence，漏测此碰撞。
- Java 保存 role-local error 到 run.error；前端恢复将带 error 的 done 改 error，可能把成功汇总刷新成失败。
- context_max_tokens 声明未落实；内部 MCP 结果未限长；条数不是 token 预算；时间范围主要进入 prompt，没有参数化查询。
- 外部 MCP 工具全量注册，而 _tools 假设全是读工具并行。未提交批次内已成功调用会在恢复时重执行。
- 完成图 artifact 另造节点，ToolCallSummary 不含 callId，协同节点与实际执行不一致。
- Python forget checkpoint 先于保存结果，存在崩溃窗口；同会话不同 run 需要顺序/锁；scope 记忆及会话删除的召回清理不闭环。
- 本轮实施前仅跑 runView.test.mjs：25/25。旧文档测试数字不得当成本次验证。

## 3. 实施切片及硬验收（回归随项执行）

### T1 / P0 可靠性内核

- 每个公开事件统一 runId/attemptId/sequence/at，模型/工具节点有稳定 nodeId/agentId/parentIds/callId。
- Java 和前端按 run+attempt+sequence 身份去重；兼容旧帧但不让旧帧跨 attempt 污染。
- 协同和单 Agent 共用终态、usage、失败语义；length/content_filter/timeout 不假成功，不写成功记忆；按真实发起模型次数统计，包括失败/重试/汇总。未知 usage 不是0。
- 区分 run 错误、子 Agent 错误、工具错误与 transport 错误；role-local error 不污染最终 run.error。
- Python 先保存可重放终态，再清检查点；Java 幂等记录/断线对账。不能把跨进程写入承诺为一个数据库事务。
- 新增显式取消 RPC，Python 确认，前端先 stop_requested 后 cancelled；取消关闭所有子模型流、工具会话与排队工作。
- 同 session 防并发轮次交错，run/子节点完成与重放幂等；逐工具调用提交记录，恢复复用已成功只读调用。
- 验收：真实信封形状的两次 attempt 同号同名帧均保留；同 attempt 重放一次；role 失败+synth成功刷新仍成功；截断不入成功记忆；各保存断点可恢复；取消全子流关闭；旧单 Agent 不退化。

### T2 / P0 指定对象与授权范围

- 复用现有 contact/chat 资源选择或 Java 可读资源接口，在 Composer 明示联系人/群组、时间范围与当前授权，不靠模型猜授权。
- Java 持久绑定与版本、run 保存当轮 scope；明确本轮选择覆盖绑定须重新授权。Python 只能继承并收窄范围。
- 有效权限=用户选定范围 ∩ Java 数据访问权限；scope 缺失的聊天任务不得默认全聊天可读。自然语言可以发现本人候选，但同名歧义不可读错对象。
- 每次真实业务工具调用再验成员/撤权；resume/retry 重新授权，不能通过旧 checkpoint 绕过。
- scope 进入工具约束、上下文/记忆/cache key；跨 scope 不带旧群私有记忆；删除会话同步清理对应召回数据或令其不可召回。
- 验收：A/B群及多账号越范围读取为0，成员退出/撤权即时拒绝，歧义不误读，空数据明确空结果；scope 在恢复/重试/列表/记录一致。

### T3 / P1 数据效率与预算

- Java 参数化时间窗/稳定游标/消息水位查询，明确时区与闭开边界；返回 readCount/returnedCount/truncatedCount/hasMore 和 messageId、chatId、timestamp 证据。不得用全群消息数冒充读取数。
- Python 一次准备授权事实，子角色共享已读取内容；按任务需要压缩/裁剪/再取页，不把全历史全工具结果重复发给每个角色。
- 输入+历史+附件+工具结果整体 token 上限，预留输出；明确 tokenizer/保守估算方法及超限行为。实际 usage 只来自模型。
- run 总期限、模型调用上限、用户/供应商并发、工具结果上限与队列背压；默认多 Agent 并发2，可配置，不静默超过预算。
- 不依赖 embedding；simple request 单 Agent；cache 按 actor+scope+范围+授权版本+消息水位隔离且撤权失效。
- 验收：时间边界/数量/证据可核对，共享检索无重复访问，输入再长也有硬预算，慢流总期限生效，实际模型次数准确。

### T4A / P1 MCP 扩展接口

这些是拟新增接口，实现后再更新为真实接口表：

- GET/POST /api/agent/mcp/servers：列表、添加。
- PATCH/DELETE /api/agent/mcp/servers/{id}：更新、启停、删除。
- POST /api/agent/mcp/servers/{id}/test：仅握手/能力发现，不调用工具。
- GET /api/agent/mcp/servers/{id}/tools：工具与允许能力。
- POST /api/agent/mcp/servers/{id}/tools/refresh：刷新目录。
- GET /api/agent/mcp/servers/{id}/status：真实连接/探测状态；未探测不能成功。

Java 对前端 BFF 鉴权/业务关联/转发；Python 内部 /v1/agent/mcp 对应接口统一管理实际 MCP 注册、连接、发现、调用与关闭。

- Python MCPProvider/ToolRegistry/Executor 可插拔接口，复用现有 mcp_tools.py，不另造主编排。
- 持久服务配置及版本，后端加密凭据（脱敏返回）；工具稳定命名空间、JSON Schema、超时、结果限制、风险标签及授权。
- 新同类 MCP 服务只改配置、主 LangGraph 不改；新增传输只扩适配层。
- 现有 Nexus Java MCP 工具兼容；支持目录刷新/配置失效；活动 run 的权限快照不被配置扩大。
- 首期 Streamable HTTP 与只读白名单。不开放任意 stdio/shell/自动写能力。
- 前端复用工作台工具入口/Element Plus 抽屉，能真实添加、修改、测试、启停、发现服务，并查看当轮服务/工具授权；不得只做接口后把用户管理能力留空。

### T4B / P1 MCP 只读权限与外传治理

- 每用户/每 Agent/每 run 允许服务与工具；新发现工具默认不授权；远端 readOnlyHint 不等于权限。
- 实时权限与 run snapshot 取交集；撤权/服务停用即时拒绝，缓存不得绕过。
- 所有管理、握手、发现和调用 owner 校验。外部地址白名单限制协议、地址解析及重定向，防 SSRF；内部认证/actor header 不外传。
- 工具结果与文件/聊天/召回内容作为不可信数据，不提升为可改变工具权限的指令；外传必须显式允许。
- 每 call 分类与幂等记录，关闭/取消释放会话；断连、超时、非法 schema 明确错误。只读和少量允许外部只读一起过门槛。
- 写入/审批/preview+hash+期限/外部自动化是后续受控边界，不能为验收本任务新增未授权业务写入。
- 验收：跨账号CRUD/测试/发现/调用拒绝，未授权新工具拒绝，撤权立即拒绝，握手无副作用，秘密不进入日志/前端/trace，现有工具和动态新增服务均可用。

### T5 / P1 按需真正多 Agent

- 在现有 LangGraph 拆协调、事实提取、摘要/待办/回复分析、审阅、汇总为可恢复子节点；不是固定每次 code/doc/arch 四次调用。
- 子 Agent 独立 transcript/受权工具循环，共享事实但授权只能收窄；简单任务只有一次必要调用，复杂任务按用户协同开关/计划按需分派。
- 子节点状态/依赖/结果持久化，可取消和恢复；只重试失败及必要下游，已提交 call 不重做。
- 部分失败输出 partial，明确缺失，不冒充完整成功；只有定义允许的成功最终结果入主记忆，partial 默认不入成功记忆。
- 模型/用户全 run 并发与token/总期限一起限制；失败、重试、review/synth按真实发起统计。
- 验收：子角色真用MCP（有真实调用链），scope/role拒绝生效，取消全子流，恢复复用结果，简单请求不协同，计费/失败/记忆语义准确。

### T6 / P1 动漫卡通像素风过程表现

- 改现有 AgentTrace / AgentRunGraph / runView；按必要性补 AgentExecutionBoard / AgentComposer / agentWorkspace / agentApi，保留顶部3D和现有页面路由。
- UI库先查并复用 Element Plus ElCollapse/ElTag/ElButton/ElTooltip/ElTimeline/ElDrawer/ElDialog/ElSelect 等；不得新塞 React/shadcn。像素 sprite 是领域表现，附在真实任务节点/角色卡上。
- 默认摘要（对象/范围/任务/真实耗时与统计口径），展开看依赖图、工具参数、来源、产物与错误；仅看结论始终可用。像素字不铺正文。
- DAG 按真实依赖分层，同层并行；稳定 agentId 绑定角色，run+attempt+node绑定工作；交接必须 parent/产物证据，不用时间相邻猜。
- queued/running/waiting_tool/waiting_approval/stop_requested/succeeded/partial/failed/cancelled/unknown/interrupted/limited/skipped 保留真实语义；没有调度的角色不出现。功能不支持的等待审批/局部重试不得摆假入口。
- reasoning 明确模型原生推理、非执行审计；没有就显示未上报，不造台词/结果/token/进度。
- 局部重试只在Python支持且展示受影响下游/副作用时启用；全停止先请求再等确认，不把断线等同取消成功。
- 390单列、820自适应、1440图+详情，不能横溢；键盘/焦点恢复/44px触控/reduced-motion；隐藏/折叠/后台过程区无持续动画，优先轻量2D sprite，不新增WebGL。
- 验收：像素节点逐一与执行记录相符，刷新/重放一致，失败/取消/缺reasoning不假成功；真实MCP管理和scope选择可达；响应式/键盘/低动效通过。

### T7 / 贯穿与收尾

- 每项先复现具体旧缺陷并落 regression，再实现、逐项验证；scope不是只测分支，必须断言实际未执行越界调用/返回数据为0。
- 顺序：Python/Java/前端单测 → 签名/协议/事件落盘恢复 → 合成场景联调 → UI/响应式/可访问性 → 独立审查修复与复验。各项串行。
- 单测脚本模型只能证明契约，不证明真实模型质量；P50/P95/首token/总token/任务完成质量要另有测量，未测就注明。
- 新工作树没有 node_modules/.venv/target。合理使用现有解释器或在本工作树安装依赖；不得修改共享旧项目，不能用陈旧运行服务证明新代码。
- 最终 git diff/check、locale key一致性、界面一致性巡检（只作用于本次工作台范围，不替用户重涂全站）、独立review、清理自己的中间产物。
- 更新本文件和 .alma/todos-mwrs4rwy60qp7zm91md.md 为真实进度。最终汇报开头提醒用户重启 Python（Java代码改动也需重启Java）；明确测试数/失败/跳过/未验证范围与服务版本限制。

## 本次续作实际状态

前次交接记录：工作树为基线 8f28a5a，原进度停在 T1 开始；交接请求在 300 秒后超时，未返回可追踪任务 ID，没有发现实施源码，T1–T7 未验收。

2026-11-22 重启后接续记录：

- 原任务已按恢复方式接续，产出 T1 的 `tests/evals/test_reliability_kernel.py`、`eval_harness.py` 3 行改动和当前工作树 `.venv`，已保留。但未取得新的三端测试/构建通过回执，生产 Python/Java/前端源码尚未落地修复。
- 编码连接超时后，主协调者核对了运行状态及本次进程归属。长时间没有新增实施产出后，停止了本次卡住的执行；该进程及其子进程已退出，没有操作用户 Java/Python 服务或其他执行。
- 原任务仍报告运行占用、处于 T1 生成阶段，0/8 阶段通过；新的接续请求被“已在运行”拦截，没有实际启动新的实施。没有通过改写应用数据库、重启整个应用或绕过原流程手改生产代码来强行解锁。
- 当前状态为阻塞，不是完成，也不是正常的后台继续开发。需要先恢复应用中的原任务占用，再从 T1 原验收续接，保留已有回归/依赖与后续计划；不可将未取得回执的测试或占用状态视为成功。
- Java 8080 / Python 8100 PID 仍为 11501 / 61978；无凭据或真实配置修改，无提交推送。本轮没有生产代码需要重启生效；后续实际修改后端代码后仍按既有规则提醒用户自行重启。

完整任务范围和验收仍以上文 T1–T7、T4A/T4B 为准。
