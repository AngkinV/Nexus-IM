# 酒馆模块：实现、验证证据与修改边界

> 关联线程：`mwq0dyxio5rcxrllod5`。
> 本轮开始于 2026-11-21；后续跨日修补仍属于同轮任务。
> 工作区：`/Users/anglv/alma/worktrees/nexus/illegal-marlin`，分支 `illegal-marlin`，基线 `76dc7bf`，修改尚未提交。
> 原项目 `/Users/anglv/Nexus` 是另一份工作目录，基线 `3ac2c9e`，还有其他线程的设置硬化暂存修改。**不能混同代码版本，不能整文件覆盖。**
> 状态：本工作区原范围修补和返回导航修补已落盘，最终隔离回归及构建通过；原项目未应用，真实服务/浏览器仍待用户验收。不承诺零缺陷。

## 1. 授权与范围

### 1.1 用户明确要求

以下时间是用户消息发送时间，不是工作完成时间。

| 时间（UTC+8） | 要求 | 范围解释 |
|---|---|---|
| 2026-11-21 14:12 | 排查酒馆选择、会话删除、最近消息 | 排查既有功能，不重建模块 |
| 14:35 | 左栏时间与最后一句；邀请选择已有建模并接通设置 | 会话删除不是角色删除；形象绑定不是 LLM 配置 |
| 14:42 | 选 B，做健壮性测试；临时允许启动已关闭的 Java 和前端 | 一次任务授权，不是长期启停权限；没有 Python 启停授权 |
| 14:53 | 存量角色由用户自行删除，再核查 | 不做存量绑定迁移，不代替用户批量删除 |
| 15:03 | 停服务并写详细进度文件 | 当时只做收尾，不继续启动服务 |
| 19:08 | 解决现有缺陷，确保健壮性、完整性和不冲突 | 重新授权本模块原范围修补与隔离回归 |
| 19:56 | 后端已由用户启动，不要重复启动 | 不启动、重启或关闭用户后端 |
| 23:43 | 先核对前端使用并停止 | 当时 5173 无监听且无 Electron；未停止其他工作区单测 |
| 23:51 | 仔细排查并解决全站返回路由 | 导航另记 `NAVIGATION-PROGRESS.md`；不新增页面/路由/store |

用户明确选择 B。助手实现了自动选择加可选指定弹窗，不能把“B+”这个助手命名或方案评价写成用户逐项批准的产品决定。

### 1.2 不可越过的边界

- 修改只在当前 worktree。原项目和其他线程的设置、渲染改动仅作只读参考；不提交、不合并、不用整目录同步。
- 既有界面、路由和 store 保留。允许小型业务 helper/composable，不另建角色/会话系统。
- 视觉保持原有黑白灰主题，命名平实，过程和数值来自真实返回。没有完整设计材料时不声称像素级还原。
- 不新增角色删除、场次管理、自动过期、存量迁移、全局建模独占、多人房间、账号设置项。
- 3D 的 `modelUrl/modelType` 与 LLM provider、模型名、凭据、采样参数分开，不混用配置。
- 默认 Java/Python 由用户启停。当前修补未启停用户服务，也未运行真实业务数据库、Redis 或模型请求。
- 没有 embedding 模型，不做知识库上传/向量检索验证，不修改相关配置。
- 高负载校验串行。测试仅用 mock、内存、工作区内临时目录；不使用真实会话做删除探测，不签用户令牌，不把凭据落盘。
- 未明确授权的登记项不是下一批开发任务。

## 2. 术语与数据生命周期

| 对象 | 含义 | 删除会话后的状态 |
|---|---|---|
| `CompanionRole` | 人设、归属、常驻/访客分类、形象绑定 | 保留 |
| `CompanionConversation` | 当前用户与角色的对话关系 | 关系保留，最近时间清空 |
| `CompanionMessage` | 用户/角色的真实消息 | 全部物理删除 |
| `TavernSession` | 开场到散场的记录及摘要 | 全部物理删除 |
| 成长、独立记忆、LLM 绑定 | 对话之外的独立数据 | 保留 |
| 3D 资产 | 现有共享服务端文件目录 | 保留，不因删除会话删文件 |

`POST /api/companion/conversations/{roleId}` 只取消隐藏、重新显示会话；不是撤销删除，不能恢复旧消息/场次。单独删除独立记忆需要另一项明确操作。

数据表：`companion_roles`、`companion_conversations`、`companion_messages`、`tavern_sessions`、`companion_status`、`companion_growths`、`companion_memories`、`companion_model_bindings`。酒馆消息和左栏预览来自 Java/MySQL，不来自 Python 的 Redis 短期上下文。

## 3. 当前文件地图

### 3.1 前端（相对 `nexus-chat-frontend/`）

| 文件/符号 | 职责 |
|---|---|
| `src/views/Main.vue` | 五个原有主模块入口；返回导航见独立文档 |
| `src/components/companion/RoleList.vue` | 列表、时间、预览、邀请弹窗、恢复会话 |
| `src/components/companion/ClearConversationButton.vue` | 删除会话确认，不是删除角色 |
| `src/stores/companion.js` | 账号/选择代次、数据读取、发送、预览、删除/恢复、邀请、共享改名缓存同步 |
| `src/composables/useTavernInvite.js` | 弹窗请求版本、错误/空目录区分、邀请结果归属、卸载保护 |
| `src/composables/useRoleModelTarget.js` | 设置操作的账号归属和选择代次；共享资产回执用 owner-only 判断 |
| `src/components/companion/modelAssetType.js` | 形象类型映射及酒馆 VRM 能力过滤 |
| `src/components/companion/RoleSettingsPanel.vue` | 原设置中的形象选择、导入、改名；不重复替换 store 已更新的角色对象 |
| `src/components/companion/ModelSettingsPanel.vue` | LLM 渠道，不是 3D 建模库 |
| `src/components/companion/tavernStage.js` / `tavernActor.js` | 既有 VRM 舞台与动作；本轮未新增非 VRM 加载器 |
| `src/views/companion/RoleChatView.vue` | 对话、场次、舞台、输入；不新增场次管理 |

### 3.2 Java（相对 `nexus-chat-backend/src/main/java/com/nexus/chat/`）

| 文件/符号 | 职责 |
|---|---|
| `controller/CompanionController.java` | 角色读取/更新、发送、状态、成长、记忆、模型绑定；校验错误保留状态码 |
| `controller/CompanionConversationController.java` | 删除/重新显示会话 |
| `controller/TavernSessionController.java` | 场次、邀请、收留及邀请模型列表 |
| `controller/CompanionAssetController.java` | 资产上传/改名/列表、模型文件互斥和失败回退 |
| `service/CompanionService.java` | 列表聚合、单角色预览、形象绑定验证、收发 |
| `service/CompanionModelAssetCatalog.java` | 目录唯一读取点、规范 URL、能力/容器检查、自动分配 |
| `service/TavernSessionService.java` | 邀请验证顺序、同账号互斥、形象分配与场次 |
| `service/CompanionConversationHistoryService.java` | 会话清理事务 |
| `repository/CompanionMessageRepository.java` | 批量最大消息 ID 和单角色最新消息查询 |
| `repository/CompanionRoleRepository.java` | 角色查询、隐藏/显示、共享文件改名后的 URL 引用更新 |

## 4. 左栏与异步状态契约

### 4.1 服务端预览

`getRoles()` 每张表一次批量读取：角色、会话、状态、每角色最大消息 ID。没有按角色逐个查消息的 N+1 回退。

- `lastMessage` 是最后一条消息内容，不是生成摘要或虚构句子。
- 有真实消息时间时，`lastMessageAt` 使用该消息的 `createdAt`，而不是模型完成时间。
- 没有任何消息时不显示会话关系的创建时间，避免“未聊过却有最近时间”。
- 单角色映射/`updateRole()` 也返回真实预览；客户端不能通过角色更新改写预览、最近时间、状态、分类或隐藏标记。
- 最大 ID 口径避免秒级时间的并列；非法/缺失字段不能被浏览器时钟补成已持久化历史。

### 4.2 客户端状态

- `ownerEpoch` 在账号改变时递增，清空旧账号状态、取消旧流；A→B→A 也不能接受最初 A 的返回。
- 每类读取有请求序号与选择代次。旧角色响应、旧 finally、旧列表不能覆盖新选择/新操作的状态。
- 完整回复立即更新对应角色预览；离开该角色后完成的回复只更新它的列表行，不写当前角色消息。
- 旧消息幂等回放按 ID 保持顺序，不移到末尾、不倒退预览。
- 未落库的用户行保持在其已落库部分回答前，不能简单把所有非数字 ID 排到最后。
- 停止、普通网络失败及部分模型失败只读对账历史，不自动重发 POST；对账失败保留最后确认过的预览。
- 删除成功后预览和时间都清空；重新显示不带回旧句子。
- 设置保存期间完成的回复，不会被较慢的角色设置响应抹掉。
- 旧本地形象覆盖仅允许形象字段，不能伪造身份、预览或隐藏状态；明确保存新形象时清除该角色旧覆盖。

日期显示仍使用现有 hourCycle 设置：当天时间、同年 `MM-DD`、跨年完整日期。所有跨时区真实浏览器场景尚未验收，不能称与全站每个时间组件完全一致。

## 5. 邀请与设置建模契约

### 5.1 来源与类型

配置项 `companion.assets.models-dir` 是唯一目录来源，默认相对后端工作目录的 `../nexus-chat-frontend/public/models`。换 worktree/容器时需重新确认资产路径。

- 设置资产列表保留实际 VRM/GLB/GLTF/FBX 文件元数据。
- `type` 为 `vrm`、`gltf`（GLB 和 GLTF）或 `fbx`，设置选择不再一律写成 VRM。
- 邀请列表只提供当前舞台可用的 VRM 候选，不假称已支持非 VRM。
- 弹窗每次打开重读，读取失败有错误/重试；刷新期间保留旧行但禁止确认旧选项。
- 空目录可以文字邀请；读取错误不能解释成“还没上传”。

### 5.2 创建与归属

邀请顺序：验证指定资产 → 事务外生成既有人设 → 选择/规范形象 → 短事务保存角色。

- 指定无效、缺失、错误格式或不支持的资产，在模型调用之前明确拒绝，不静默换另一个。
- 自动选择优先未被当前账号可见角色使用的形象；隐藏会话不代表全局独占。
- 有空闲候选随机选；全部占用随机复用；没有可用候选允许文字角色。
- 同一账号、同一 JVM 同时一个邀请；finally 释放锁；不同账号共享文件不默认视为错误。
- 前端只用本次 POST 返回的 guest ID 确认结果，移除“最新角色”或 ID 差集猜测。
- 超时/未知结果提示核实，不自动选择另一个角色、不自动重发。
- 账号改变、组件卸载和旧请求返回不能切换新页面的角色或弹旧提示。

### 5.3 本地绑定、上传与改名

- 引用统一为 `/models/<文件名>`；裸名与 URL 形式同一身份。
- 拒绝穿越、嵌套路径、百分号编码、反斜线、控制字符、隐藏文件及子文件符号链接。
- 设置绑定本地文件也走 catalog，不能绕过邀请路径的验证；类型需匹配实际后缀。
- 原有 HTTP/HTTPS 形象引用保留，验证 URL、后缀和类型，不发网络请求验证；不接受其他协议或 URL user-info。
- 空字符串清除形象绑定，不删除角色、历史或资产。
- 上传使用 CREATE_NEW，不覆盖已有文件；容器不合格的新文件移除。文件名相同不代表内容相同。
- 改名验证源名和目标名，拒绝符号链接及改变格式后缀；冲突使用新文件名，不覆盖目标。
- 现有共享文件改名时，在数据库事务内更新精确引用（含旧裸名）。数据库更新失败尝试把文件移回；并非重构资产权限。
- 前端同步全部已缓存引用且保留预览。共享回执只看账号，切角色/卸载不能漏同步；旧上传后续绑定则必须匹配选择代次。

文件校验仅二进制 glTF 容器层（魔数、版本、长度、JSON 首块），没有完整证明 VRM 扩展、骨骼、材质或渲染有效性。

## 6. 接口摘要

| 接口 | 语义 |
|---|---|
| `GET /api/companion/roles` | 含可空预览/时间，批量聚合 |
| `PUT /api/companion/roles/{roleId}` | 只写角色可写字段，返回真实预览；形象输入校验 |
| `GET /api/companion/conversations/{roleId}` | 真实历史与只读对账 |
| `DELETE /api/companion/conversations/{roleId}` | 清消息/场次/状态并隐藏会话 |
| `POST /api/companion/conversations/{roleId}` | 204；仅重新显示 |
| `POST /api/tavern/roles/invite` | `{sceneKey, modelUrl?}`，成功返回本次 guest |
| `GET /api/tavern/roles/models` | 只含可邀请 VRM；同目录的能力过滤子集 |
| `GET /api/companion/assets/models` | 设置的完整模型元数据列表 |
| `POST /api/companion/assets/model` | multipart VRM 上传，唯一命名 |
| `PUT /api/companion/assets/model/rename` | 共享文件改名并保持引用 |

邀请/绑定校验理由：`model_url_invalid`、`model_type_unsupported`、`model_not_found`、`model_file_invalid`（400）；`invite_in_progress`（409）；目录错误 `model_catalog_unavailable`（500）。全局异常响应的理由在 `message`。资产上传/改名原有错误体使用 `error`，前端不可混读；网络错误不可据此自证业务失败或成功。

认证以 `CurrentUser` 的 principal 为准，客户端 userId 只是被核对的声明。不能用“预期会是 403”的 DELETE 探测真实对象。

## 7. 缺陷登记与状态

| 编号 | 本轮处理 | 验收边界 |
|---|---|---|
| FE-01/FE-02 | 预览更新、旧回放、删除/重新显示一致 | 隔离 store 用例已通过；真实浏览器待用户验证 |
| FE-03 | 不猜测邀请结果、不自动重发、请求归属 | 隔离 store/helper 已覆盖；没有跨重启幂等 ID |
| FE-04 | 真实空库和 I/O 失败区分，版本与确认锁 | helper 已覆盖；真实导入后浏览器刷新待验证 |
| FE-06 | 设置更新保预览、形象类型准确 | Java/store 回归已覆盖 |
| FE-07 | 账号、角色 A→B→A、卸载保护 | 选择代次补齐，新增三条设置代次回归已通过 |
| FE-08 | 停止/网络中断只读对账 | 已覆盖，不把 delivered 字符等同落库 |
| BE-01/BE-02 | 新邀请绑定及 catalog 共用 | 已覆盖，存量角色不迁移 |
| BE-03 | 同账号单 JVM 邀请互斥 | 已覆盖；多实例部署不提供独占保证 |
| BE-06 | 全占用随机复用，不再固定选一张脸 | 已覆盖；不是全局占用表 |
| BE-07 | 邀请能力只 VRM，设置类型映射 | 已覆盖；不新增加载器 |
| BE-08 | 路径/子符号链接/绑定/改名保护 | 已覆盖；完整 VRM 语义、硬链接与可信目录配置未全面验证 |
| BE-09 | 目录错误、坏候选与上传部分文件处理 | 已覆盖；校验后被其他进程改名的竞态仍可能发生 |
| X-02 | 预览时间取对应真实消息，未聊过不显示创建时间 | Java 已覆盖；真实不同客户端时区待验证 |

仅登记，不自动扩展：BE-04 存量访客形象、BE-05 逐场删除/过期、FE-05 场次管理界面、X-01 早期 Agent Redis 库记录。本轮未据此改配置或新增产品功能。

## 8. 验证证据

### 8.1 已实跑结果

- 2026-11-21 23:08 的工作区 Java 回归：10 类、230 测试，失败/错误/跳过均 0，Maven 退出码 0。
- 最后前端相关串行回归：179 测试，失败/取消/跳过均 0，退出码 0；含 42 个导航用例、酒馆/设置/3D 相关回归及原转场用例。
- 最后前端生产构建成功，4.17 秒，退出码 0；保留既有大 chunk 警告。原项目导航合并快照另通过 42 用例和 3.54 秒构建，不代表酒馆全部改动已合并。
- 初次独立只读复核发现两条设置选择遗漏，已写入修补；不是一次“未发现问题”的审查。
- 原项目旧 333 测试报告仅历史证据，不用于当前树验收。

Java类：catalog 63、场次/邀请 service 29、预览 13、邀请 MVC 36、收发 27、会话 MVC 12、模型绑定 MVC 10、删除 14、资产安全 15、形象绑定 11。

### 8.2 安全执行规则与命令

先确认其他线程没有高负载校验，再逐条执行。fixture 是短容器/模拟消息，不是真用户数据。

```bash
# 当前工作区内，测试临时目录同样在工作区
cd nexus-chat-backend
mkdir -p target/ws-tmp
export MAVEN_OPTS="-Djava.io.tmpdir=$PWD/target/ws-tmp"
mvn -o -B -ntp test \
  -Dtest=CompanionModelAssetCatalogTest,CompanionRolePreviewTest,CompanionShapeBindingTest,CompanionAssetSafetyTest,CompanionExchangeTest,TavernSessionServiceTest,TavernInviteMvcTest,CompanionConversationMvcTest,CompanionModelBindingMvcTest,CompanionConversationDeletionTest \
  -DargLine="-Djava.io.tmpdir=$PWD/target/ws-tmp"
```

```bash
cd nexus-chat-frontend
node --test --test-concurrency=1 \
  src/router/navigation.test.mjs src/composables/useTavernEntrance.test.mjs \
  src/stores/companion.test.mjs src/composables/useTavernInvite.test.mjs \
  src/stores/settings.test.mjs src/components/companion/tavernRig.test.mjs \
  src/components/companion/tavernSpeech.test.mjs src/components/companion/vrmExpressions.test.mjs
npm run build
```

每条命令以真实退出码与实跑数量验收，不用 grep/tail 的退出码冒充成功。没有运行真实应用上下文、实际 MySQL/Redis/模型。未执行全量 `mvn test`；不靠排除一个类来假定所有剩余测试都安全。

### 8.3 还未证明的范围

- 真实 JPA 解析与数据库事务、部署环境文件行为；mock 回滚不代表物理崩溃原子性。
- 完整 VRM 内容、GPU 加载、骨骼与材质、完整 Electron/浏览器 UI。
- 多 JVM 并发、服务重启后重复请求、文件校验后被外部改动。
- 原项目另一线程的最新设置硬化树与本补丁合并后的整体验证。
- 无完整设计源时不做像素级一致宣称。

## 9. 数据事故与禁止重复的操作

2026-11-21 再次核查曾对真实用户 3 的角色 29“理性伙伴”执行 DELETE。已知原有 2 条消息与场次 5 被物理删除；此前还写入过测试消息，不能把原有两条说成删除时全部数量。

恢复接口只恢复列表显示，未恢复历史。未检查所有备份，不能宣称所有恢复途径已排除。早前对真实无消息角色的删除/重新显示也不是隔离测试。

本轮新回归仅使用明确由测试创建的对象与临时文件。此事故记录永久保留：不再用真实对象执行删除或“预期失败”的授权探测，不把重新显示当撤销，不把测试通过当数据已原样还原。

## 10. 后续接手

1. 先检查当前目录、HEAD、工作区 diff、服务与其他任务，不把本 worktree当原项目。
2. 按具体 hunk 与原项目设置硬化合并，禁止整文件拷贝，尤其 Main/Settings/RoleSettingsPanel/companion/api 和渲染相关文件。
3. 保持上述状态/类型/生命周期契约，增加行为回归后再改变实现。
4. 返回路由的后续修补看 `NAVIGATION-PROGRESS.md`，不能据此扩展新页面。
5. 更新实跑命令、数量、退出码、源代码状态和未覆盖项。用户未最终验收之前保留“待用户验证”。
