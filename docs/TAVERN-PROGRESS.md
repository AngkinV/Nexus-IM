# 酒馆模块 · 进度与边界

> 本文是**酒馆（Tavern / Companion）模块**的施工记录，后续修改与扩展以此为准。
> 最后更新：2026-11-21 15:05（UTC+8）
> 代码基线：`/Users/anglv/Nexus`，分支 `feat/settings-redesign`，HEAD `3ac2c9e`，含本文所述**未提交改动**
> 核对方式：改动前后均启动服务做过真实接口与界面验证；测试与构建结果见第 8 节。
> 凭据约定：本文不记录任何密钥、口令、令牌的值。

---

## 1. 这份文档的用法

### 1.1 它回答什么

- 酒馆模块**现在是什么样**（数据从哪来、经过谁、落到哪）。
- 这一轮**改了什么、为什么改**。
- **哪些边界不能碰**（第 2 节，最重要）。
- **还欠什么、哪里有已知缺陷**（第 7 节）。

### 1.2 更新规则

1. 改完一条，把第 7 节对应条目状态改为「已解决」并写上提交号；**不要删编号**。
2. 新增问题按前缀续号：`BE-`（Java 后端）、`FE-`（前端）、`X-`（跨端契约）。
3. 每次更新先跑 `git rev-parse --short HEAD`，把新基线写进文首。
4. 复查时以**符号名**（类名、函数名、接口路径）为准，行号会漂移。

### 1.3 边界优先于本文

本文任何描述与**第 2 节的边界**冲突时，以第 2 节为准。本文不授权任何超出边界的改动。

---

## 2. 边界（不可越过的红线）

这一节是用户明确要求过的，不是推测。

### 2.1 服务启停

| 服务 | 端口 | 谁启动 |
|---|---|---|
| Java 聊天后端 | 8080 | **用户**。助手不得启停 |
| Python Agent 后端 | 8100 | **用户**。助手不得启停 |
| 前端 dev server | 5173 | 助手可以自己拉起 |

- 改了 Python 代码后，**汇报第一段**就要写明「需要重启 Python 服务才生效」。
- 例外：2026-11-21 15:03 用户明确说「我把 java 后端关闭，前端也关闭，你自己启动即可」，这属于**一次性授权**，不构成长期规矩。

### 2.2 视觉依据

- 唯一依据是用户给的 Stitch 导出（`code.html` + `DESIGN.md`）：**纯黑白灰单色极简**。
- 设计稿里**没有后端字段支撑的元素不做假的**（好感度、送礼、语音、点赞、沉浸度曲线之类）。
- 设计稿没覆盖的部分由助手自行设计，但必须贴合单色主题，且**界面上显示的过程和数据必须来自模型与后端的真实返回**，不能用预设脚本冒充。

### 2.3 改动范围

- **在现有界面和组件上改**，不新建页面、路由或 store 来「重做」。
- 改界面时必须同步核对后端：界面用到的接口、字段名、事件要和 Java / Python 后端的真实返回对上。光把界面改好看不算完成。
- 转场组件（`src/components/warp/`、`src/composables/useTavernEntrance.js`、`src/views/dev/`）属于其他线程，**只能调用，不能改**。
- `src/components/standby/ComputerEye.vue` 不是本模块的，别动。

### 2.4 命名与文案

- 界面文案和命名要**通用平实**，不要中二或科幻设定式的命名。
- 文案在 `src/locales/zh.json` / `en.json`，默认语言是 en，两边 key 必须完全对齐。
- 局部文案用 `useI18n({ useScope: 'local' })` 写在组件内（如 `RoleList.vue`），同样要中英对齐。

### 2.5 依赖限制

- **用户没有可用的向量（embedding）模型**。不要尝试知识库上传、向量检索这类依赖 embedding 的功能验证。
- 重点放在功能逻辑正确和页面设计上。

### 2.6 验证纪律

- 测试、无头浏览器这类吃 CPU 的东西**必须串行**：一次只开一个，跑完立即关掉。
- 验证用的测试数据**用完要删**，并还原被改动的真实数据。
- **不要用用户的真实数据做破坏性验证。**（2026-11-21 违反过一次，见 7.4）

### 2.7 提交

- 用户没要求提交时**不要擅自 `git commit`**。改动留在工作区，由用户决定。
- 不要 `git add -A`：同一个工作区里可能还有别的线程的未提交改动。

---

## 3. 模块地图

### 3.1 三端职责

| 端 | 目录 | 端口 | 职责 |
|---|---|---|---|
| 前端 | `nexus-chat-frontend` | 5173 | Vue 3 + Pinia + Element Plus；酒馆界面、3D 舞台、消息流 |
| Java | `nexus-chat-backend` | 8080 | 角色、会话、消息、场次、模型绑定、凭据加密 |
| Python | `nexus-agent-backend` | 8100 | 模型网关（酒馆的模型调用经 Java 走这里） |

### 3.2 前端文件

| 文件 | 作用 |
|---|---|
| `src/views/companion/RoleChatView.vue` | 酒馆主视图：头部、3D 舞台、消息流、输入坞、场次分隔线 |
| `src/components/companion/RoleList.vue` | 左栏角色列表：席位、访客、邀请、恢复对话、删除会话 |
| `src/components/companion/ClearConversationButton.vue` | 删除会话按钮 + 二次确认 |
| `src/components/companion/RoleDossier.vue` | 角色档案（记忆 + 成长），默认收起 |
| `src/components/companion/ModelSettingsPanel.vue` | 单个角色的模型绑定与凭据 |
| `src/components/companion/RoleSettingsPanel.vue` | 设置页「角色与模型」面板：3D 建模、动作、凭据 |
| `src/components/companion/CompanionAvatar3D.vue` | 桌面宠物（默认关闭，`v-if` 挂载） |
| `src/components/companion/tavernStage.js` | 舞台绘制 |
| `src/components/companion/tavernActor.js` | VRM 加载、口型、动作 |
| `src/stores/companion.js` | 酒馆全部前端状态 |
| `src/services/api.js` | `companionAPI` 定义 |

### 3.3 后端文件

| 文件 | 作用 |
|---|---|
| `controller/CompanionController.java` | `/api/companion/**`：角色 CRUD、消息、记忆、成长、状态、模型绑定 |
| `controller/CompanionConversationController.java` | `/api/companion/conversations/**`：删除会话、恢复会话 |
| `controller/TavernSessionController.java` | `/api/tavern/**`：场次开关、历史、邀请访客、收留访客、**可用建模列表** |
| `controller/CompanionAssetController.java` | `/api/companion/assets/**`：建模与动作文件的上传、列表、改名、删除 |
| `service/CompanionService.java` | 角色、消息、`getRoles` 聚合、`exchange` 收发主流程 |
| `service/TavernSessionService.java` | 场次生命周期、邀请访客、收留访客 |
| `service/CompanionConversationHistoryService.java` | 删除/恢复会话的事务性实现 |
| `service/CompanionModelAssetCatalog.java` | **本轮新增**：建模目录的唯一读取点 |
| `service/CompanionConversationGuard.java` | 按 (userId, roleId) 的并发锁 |
| `model/CompanionRole.java` | 角色实体，含 `modelUrl` / `modelType` / `kind` / `conversationHidden` |
| `repository/CompanionMessageRepository.java` | 消息查询，含本轮新增的 `findLatestPerRole` |

### 3.4 数据表

| 表 | 说明 |
|---|---|
| `companion_roles` | 角色。`kind` = `regular`（常驻）/ `guest`（过客）；`conversation_hidden` = 会话是否被删 |
| `companion_conversations` | 会话关系，`last_message_at` 驱动列表排序 |
| `companion_messages` | 消息，`session_id` 关联场次 |
| `tavern_sessions` | 场次（一场「夜晚」） |
| `companion_status` | 角色当前状态（presence） |
| `companion_growths` | 成长数值（注意表名是复数） |
| `companion_memories` | 独立记忆 |
| `companion_model_bindings` | 角色与模型的绑定 |

---

## 4. 数据流

### 4.1 左栏列表（本轮改动）

```
GET /api/companion/roles?userId=
  → CompanionService.getRoles(userId)
      ├─ roleRepository.findByUserIdOrderByIdAsc        角色本体
      ├─ conversationRepository.findByUserId            lastMessageAt（排序用）
      ├─ statusRepository.findByUserId                  presence（在做什么）
      └─ messageRepository.findLatestPerRole(userId)    lastMessage（预览用，一条 SQL）
  → CompanionRoleDTO（含 lastMessage）
  → RoleList.vue 渲染：名字 / 时间 / 预览句
```

**关键约束**：`findLatestPerRole` 必须是**一条批量查询**。如果退化成每个角色查一次，左栏会变成 N 次往返，打开酒馆就慢——这是历史上踩过的坑。

### 4.2 邀请访客（本轮改动）

```
POST /api/tavern/roles/invite  { sceneKey, modelUrl? }
  → TavernSessionService.inviteRole(userId, sceneKey, modelUrl)
      ├─ 模型调用生成角色人设（名字/性格/背景/开场白）
      ├─ resolveGuestModelUrl(userId, modelUrl)        ← 本轮新增
      │     ├─ 指定了 modelUrl 且文件真实存在 → 用它
      │     └─ 否则 → modelAssetCatalog.pickForGuest(已占用集合)
      │           优先挑没人用的；全被占用则复用
      └─ 事务内落库（含 modelUrl / modelType）
  → CompanionRoleDTO
```

**为什么要有这一步**：改动前 `inviteRole` 从不设置 `modelUrl`，邀请进来的角色建模永远是 null，舞台空白。同时设置页的建模列表与邀请流程是两条不相干的代码路径。

### 4.3 建模目录（本轮新增的唯一数据源）

```
CompanionModelAssetCatalog.list()
  ├─ GET /api/companion/assets/models   （设置页角色面板）
  └─ GET /api/tavern/roles/models       （邀请弹窗）
```

两处读同一个类，**保证不会漂移**。在设置里上传一个建模，邀请时立刻能选到。

安全边界：只接受 `models` 目录下的**纯文件名**。带路径分隔符、`..`、或不在该目录的文件一律拒绝。这是防路径穿越的唯一关口。

### 4.4 删除会话

```
DELETE /api/companion/conversations/{roleId}
  → CompanionConversationHistoryService.clear()
      物理删除：messages、tavern_sessions
      逻辑标记：roles.conversation_hidden = true
      清空：conversations.last_message_at、status
```

**注意**：消息和场次是**物理删除，不可恢复**。`POST .../conversations/{roleId}` 只能把角色放回列表，旧消息不会回来。

---

## 5. 本轮改动清单

### 5.1 后端

| 文件 | 改动 |
|---|---|
| `service/CompanionModelAssetCatalog.java` | **新增**。建模目录唯一读取点：`list()` / `exists()` / `typeOfUrl()` / `pickForGuest()` |
| `service/TavernSessionService.java` | `inviteRole` 增加 `modelUrl` 参数；新增 `resolveGuestModelUrl`；构造器注入 catalog |
| `controller/TavernSessionController.java` | `invite` 接受可选 `modelUrl`；新增 `GET /roles/models` |
| `controller/CompanionAssetController.java` | `listModels` 改为读 catalog，不再自己列目录 |
| `repository/CompanionMessageRepository.java` | 新增 `findLatestPerRole(userId)` |
| `service/CompanionService.java` | `getRoles` 填充 `lastMessage`；`toRoleDTO` 增加四参重载 |
| `dto/CompanionRoleDTO.java` | 新增字段 `lastMessage` |
| `test/.../CompanionModelAssetCatalogTest.java` | **新增**，10 个用例 |
| `test/.../TavernSessionServiceTest.java` | 构造器补 catalog mock |

### 5.2 前端

| 文件 | 改动 |
|---|---|
| `components/companion/RoleList.vue` | 行内显示时间 + 预览句；邀请改为先弹建模选择器；修掉邀请失败误选旧角色、建模列表缓存不失效 |
| `stores/companion.js` | `inviteGuest` 支持 `modelUrl`；新增 `fetchInviteModels`；`settleExchange` / `settleModelFailure` 同步 `lastMessage`；`clearConversation` 清 `lastMessage` |
| `services/api.js` | `inviteGuest` 增加 `modelUrl`；新增 `inviteModels` |

### 5.3 设计决策记录

**为什么选「B+」而不是其他方案**

| 方案 | 结论 |
|---|---|
| A · 邀请前强制选建模 | 否决。邀请本该是「推门进来一个陌生人」的随机感，强制弹窗破坏它 |
| B · 静默复用默认建模 | 采纳为基础。改动最小，复用已有的 `PUT /roles/{id}` |
| B+ · 默认自动挑，可展开指定 | **最终方案**。不卡流程，也留了控制权 |
| C · 建模池 + 占用表 | 暂缓。要管「谁在用哪个」，改动量是 B 的三四倍 |

**自动挑选的算法**：优先挑没被占用的建模；全被占用时复用；一个都没有则返回 null（舞台空白，与改动前一致）。

---

## 6. 接口契约

### 6.1 新增 / 变更

| 方法 | 路径 | 变更 |
|---|---|---|
| `GET` | `/api/companion/roles` | 响应新增 `lastMessage`（String，可为 null） |
| `POST` | `/api/tavern/roles/invite` | 请求体新增可选 `modelUrl` |
| `GET` | `/api/tavern/roles/models` | **新增**。返回 `[{ fileName, fileUrl, size, type }]` |
| `GET` | `/api/companion/assets/models` | 响应新增 `type`；数据源改为 catalog |

### 6.2 `CompanionRoleDTO` 字段顺序

`@AllArgsConstructor` 的**位置参数顺序即构造器签名**。新增字段插在 `presence` 之后：

```
id, name, traits, tone, baselineMood, avatarUrl, modelUrl, modelType, active,
kind, lastMessageAt, presence, lastMessage, background, greeting, conversationHidden
```

**改这个顺序会静默破坏 `toRoleDTO` 的构造调用**，编译能过但字段会错位。改动时必须同步 `CompanionService.toRoleDTO`。

---

## 7. 未完成与已知缺陷

### 7.1 已解决（本轮）

| 编号 | 问题 | 处理 |
|---|---|---|
| `FE-01` | 发消息后左栏时间变、预览句不变 | `settleExchange` 同步 `lastMessage`。已实测：发完立刻显示新句子 |
| `FE-02` | 删除会话后预览句残留 | `clearConversation` 清 `lastMessage`。已实测 |
| `FE-03` | 邀请失败时误跳到旧访客并提示成功 | 改为只认「操作前不存在的角色」 |
| `FE-04` | 建模列表缓存永不失效，新上传的看不到 | 每次打开重读，保留旧列表避免闪烁 |
| `BE-01` | 邀请的角色 `modelUrl` 永远为 null | `resolveGuestModelUrl` 兜底 |
| `BE-02` | 设置页建模列表与邀请流程两套代码 | 抽出 `CompanionModelAssetCatalog` 作为唯一数据源 |

### 7.2 未解决

| 编号 | 问题 | 影响 | 建议 |
|---|---|---|---|
| `BE-03` | 并发邀请可能撞建模 | 两人同时邀请会挑到同一个建模。不崩溃，只是撞脸 | 需占用表或按用户加锁。改动不小，待定 |
| `BE-04` | 存量角色的 `modelUrl` 为 null | 早期创建的角色（如 4 个「无名的旅人」）进房仍是空舞台 | 用户 2026-11-21 表示「我把他们删除就可以了」，故不做迁移 |
| `BE-05` | 场次无删除接口 | `tavern_sessions` 只能靠删除整个会话清掉。挂着的 `active` 场次不会自动结束 | 需要 `DELETE /api/tavern/sessions/{id}` + 前端场次管理 |
| `FE-05` | 场次列表无界面 | 后端有 `GET /api/tavern/sessions`，前端只用来画分隔线，没有管理页 | 与 `BE-05` 一起做 |
| `X-01` | 对话记忆的 Redis 库不一致 | Python 写 2 号库，Java 读删用 0 号库；历史接口永远返回空 | 待用户拍板，属于配置层面 |

### 7.3 用户已拍板、不要重提

- 存量角色不补 `modelUrl`：用户自己删除。
- 不做建模占用表（`BE-03`）：暂缓，用户未要求。

### 7.4 事故记录

**2026-11-21 14:5x — 误删用户真实数据**

- **做了什么**：为验证「删除会话后预览是否清空」，对用户真实角色「理性伙伴」（roleId 29）执行了 `DELETE /api/companion/conversations/29`。
- **后果**：该角色的 2 条消息和 1 场 `tavern_sessions`（11-19 开始）被**物理删除，不可恢复**。`restore` 只能把角色放回列表。
- **本该如何**：用测试角色验证，或先备份表。
- **已写入边界**：见 2.6「不要用用户的真实数据做破坏性验证」。

---

## 8. 验证方法

### 8.1 构建与测试（串行，不要并行）

```bash
# 前端（在 nexus-chat-frontend/ 下）
./node_modules/.bin/vite build 2>&1 | grep -E "error|✓ built"

# 后端（在 nexus-chat-backend/ 下）
mvn -o test 2>&1 | grep -E "Tests run:.*Failures|BUILD"
```

**本轮结果**：前端 `✓ built`；后端 `Tests run: 333, Failures: 0, Errors: 0`。

注意：`mvn compile` 的增量编译**不会发现新增的源文件**，加新类后要 `mvn -o clean compile`。

### 8.2 文案对齐（改完 locale 必跑）

```bash
cd nexus-chat-frontend && python3 - <<'PY'
import json
def flat(o, pre=''):
    out=set()
    for k,v in o.items():
        key=f'{pre}.{k}' if pre else k
        out |= flat(v,key) if isinstance(v,dict) else {key}
    return out
en=flat(json.load(open('src/locales/en.json'))); zh=flat(json.load(open('src/locales/zh.json')))
print('en',len(en),'zh',len(zh),'diff',sorted(en^zh))
PY
```

**本轮结果**：`en 1235 zh 1235 diff []`。

### 8.3 接口验证

需要登录态。签发 token 时读 `application-local.properties` 的 `jwt.secret`（**不要把值写进任何文件**），用 HS512 签 `{userId, username, sub, iat, exp}`。

关键验证点：

1. `GET /api/companion/roles` → 每个角色都有 `lastMessage` 字段
2. `GET /api/tavern/roles/models` 与 `GET /api/companion/assets/models` 返回**同一组** `fileUrl`
3. `POST /api/tavern/roles/invite` 不带 `modelUrl` → 自动挑到未被占用的建模
4. 带一个存在的 `modelUrl` → 精确使用
5. 带不存在的 `modelUrl` → 回退到合法建模，**不能存空值**
6. 带 `../../../etc/passwd` → 拒绝，回退到合法建模
7. 删除会话后 `lastMessage` 与 `lastMessageAt` 都为空

### 8.4 界面验证

用侧栏浏览器，桌面视口：

```bash
alma iab cdp Emulation.setDeviceMetricsOverride '{"width":1440,"height":900,"deviceScaleFactor":1,"mobile":false}'
```

注意：输入框在 900 高视口下可能被截在视口外（y≈932），点击会落空。用 `focus()` + 回车发送。

---

## 9. 踩过的坑

- **`mvn compile` 不认新文件**：新增 Java 类后必须 `clean compile`，否则报「程序包不存在」。
- **短类名要 import**：在 controller 里写 `CompanionModelAssetCatalog` 必须加 `import com.nexus.chat.service.CompanionModelAssetCatalog;`，否则编译报「程序包 X 不存在」（这个报错信息很有误导性）。
- **`@AllArgsConstructor` 的位置参数**：DTO 加字段会改变构造器签名，`toRoleDTO` 不会报错但字段错位。见 6.2。
- **`companion_growths` 是复数**：写 SQL 清理测试数据时容易写错表名。
- **Element Plus 的 `el-dialog` 关闭后 DOM 还在**：`v-model=false` 后节点保留、`display:none`。判断「弹窗是否关闭」要看 `display`，不能看节点是否存在。
- **Pinia 流式更新要改响应式副本**：`messages.value[messages.value.length-1]`，改 push 之前的普通对象不会重绘。
- **`stores/companion.js` 的 `sendMessage` 失败返回 `null`**，不抛异常，别用 try/catch 接。
- **WebGL 舞台隐藏时容器宽度为 0**，鼠标换算会除以零，把场景变成 NaN 后永远画不出来。宽度为 0 直接 return。
- **Tailwind preflight 是关的**：原生 `button`/`h2`/`p` 的默认样式靠 `prism.css` 里的重置。

---

## 10. 下一步建议顺序

1. **`BE-05` + `FE-05` 场次管理**：后端加删除场次接口，前端加场次列表。当前 `active` 场次永不过期，会一直累积。
2. **`BE-03` 并发邀请**：如果要多人同时用，需要处理。单人使用可以先放着。
3. **`X-01` Redis 库不一致**：属于配置问题，需要用户拍板，不是代码能单方面改的。
