# Nexus 公网环境：运行、迭代与扩展边界

写于 2026-11-26 16:45（UTC+8）。基线：主线 `84b35c1`，公网镜像 `db81c7ecd5f2`。

这份文档回答三件事：公网环境现在是什么样、以后改代码怎么发布上去、哪些事不能做。任何人（包括以后的 AI 会话）动公网相关的东西之前先读第 2 节。

和其他文档的关系：

- `docs/BOUNDARIES.md` 第 11 节登记了本文的红线，冲突时以它为准。
- `docs/PUBLIC-ISOLATION-PLAN.md` 是这套环境怎么一步步建起来的记录，含每次核对的时间和结果。查“当时为什么这么做”看那里。
- `docs/QODER-SITE-REBUILD-CHECKLIST.md` 是交给 Qoder 重新构建发布前端时用的完整清单。

---

## 1. 现在是什么样

```
访客浏览器
  → Qoder 静态站点  https://nexus-im-web-2026-fwohlcnoevd.qoder.zone   （只有页面，没有数据）
  → Tailscale Funnel https://lvmacbook-air.tail007e54.ts.net  仅 443
  → Mac 上的 127.0.0.1:18080
  → 虚拟机 nexus-public 里的网关 → Java → Python Agent → 独立的 MySQL / Redis
```

两套环境互不相干：

| | 本地开发环境 | 公网环境 |
|---|---|---|
| 运行位置 | Mac 上直接运行 | Lima 虚拟机 `nexus-public` 里的 6 个容器 |
| 启动方式 | 原来的命令：`mvn spring-boot:run`、`ENGINE=langgraph .venv311/bin/python main.py`、`npm run dev` | `bash /Users/anglv/Nexus/deploy/public/nexus-public.sh …` |
| 端口 | 8080 / 8100 / 5173 / 3306 / 6379 | Mac 上只有 `127.0.0.1:18080` |
| 数据 | 本机 MySQL、Redis、`uploads/`、`data/` | 虚拟机里的独立数据卷，从空库开始 |
| 密钥 | `application-local.properties`、Python `.env` | 钥匙串服务 `nexus-public` 里的 8 项，首次启动时生成 |
| 配置 | 默认 profile，所有加固开关关闭 | `funnel,public` 两个 profile，所有加固开关打开 |
| 代码来源 | 工作区当前文件 | 某个 git 提交打出的镜像 |

公网环境在 `public` profile 下打开的限制（`application-public.properties`）：

- 注册必须有邀请码，不发邮箱验证码。
- 同一账号连续 8 次登录失败锁 15 分钟。
- 每账号每天上传 500MB、每天 200 次模型请求、同时最多 2 个；所有账号的文件合计不超过 5GB。
- 用户填写的模型地址和 MCP 地址只能是公网地址。
- 知识库写入关闭。
- 网关限流（每分钟）：登录注册类每访客 10 次、全局 120 次；连接升级 30 / 300；其余 1200 / 6000。

---

## 2. 红线

这些是用户定下的边界，或者一旦破坏就会让 Mac 暴露的东西。要改其中任何一条，先问用户，再改 `docs/BOUNDARIES.md` 第 11 节，然后才动手。

**入口**

1. 公网入口只有一条：Funnel 的 443 → `http://127.0.0.1:18080`。不把 Funnel 指向 8080、8100、3306、6379 或别的端口，不加第二条 Funnel 或 Serve 规则，不开 Tailscale SSH、子网路由、出口节点。
2. 打开和关闭 Funnel 由用户亲手做。助手不执行 `tailscale funnel …`，只核对结果。
3. 18080 只监听 `127.0.0.1`。虚拟机不向 Mac 转发其他端口。
4. Python Agent、MySQL、Redis、`/api/internal/**`、调试和管理接口永远不对公网开放。

**隔离**

5. 公网环境只在虚拟机里运行。不把 Mac 的目录挂进虚拟机或容器，不把容器改成 root、可写根文件系统或特权模式。
6. 不削弱虚拟机里的防火墙 `nexus-guard`。它拦住从容器到 Mac、家里局域网、Tailscale 内网的连接。
7. 两套环境不共用任何东西：公网环境不连本机的 MySQL 和 Redis，不导入本地数据，不放用户自己的模型 Key。后端不新开不经校验的出站连接（第 8 节）。
8. 公网用户用自己的模型 Key 和服务商。不给公网环境配一个大家共用的默认模型。

**本地环境不能被影响**

9. 所有加固功能做成开关，默认关闭，只在 `public` profile 打开，并有测试守住“默认配置下行为不变”。新加的限制照此办理。
10. 不执行 `FunnelSecretPrep prepare`。它会把本地的模型凭据换成另一把密钥加密，本地原命令启动后就解不开了。`nginx/funnel-start.sh` 已停用。
11. 不轮换 JWT 密钥。

**来源与凭据**

12. 后端只接受明确列出的网站地址，不用通配符，不写成“任何来源都行”。来源检查不是身份验证，脚本可以伪造来源，所以它不能替代登录。
13. 邀请码由用户在自己的终端里生成。助手不代为生成，不在对话、日志、文档里出现邀请码。
14. 任何密钥、令牌、口令的值不出现在对话、文档、提交信息和日志里。

**数据**

15. 有了真实账号以后，不执行 `reset`，除非用户明确要清空。`reset` 会删掉所有账号、聊天记录和上传的文件，不可恢复。同理，会注册测试账号的验收脚本 `deploy/public/acceptance.py` 不在有真实账号的环境里跑（第 6 节）。
16. 不提交用户工作区里这几个文件的未提交改动：`nexus-chat-frontend/.env.production`（本地改动指向旧服务器 `47.118.29.122`，提交会让发布的站点连回旧服务器）、`src/styles/nexus-theme.css`、`.Nexus Chat.qoder.site`。

---

## 3. 用户定下的决定

| 时间（2026-11-26） | 决定 |
|---|---|
| 01:34 | 选方案 B：公网 + 隔离加固 |
| 01:44 | 邀请码注册；新用户自己配 Key；可以用自己的服务商、可以加 MCP，但不能指向本机和内网；公网环境从空库开始；本地测试不能受影响，两套环境必须隔离 |
| 02:08 | 按计划执行到底，中途不必询问；卡住就换思路；注意内存，不要大量跑测试 |
| 12:2x | 用户自己打开了 Tailscale 和 Funnel |

---

## 4. 日常操作

都在 Mac 的“终端”里执行。脚本要用主仓库里的那一份，不要用工作树里的。

```
bash /Users/anglv/Nexus/deploy/public/nexus-public.sh <命令>
```

| 要做的事 | 命令 |
|---|---|
| 看状态 | `status` |
| 看最近谁来过 | `summary`，或 `summary 6` 看最近 6 小时 |
| 看某个服务的日志 | `logs gateway 200`（可选 `gateway` `backend` `agent` `mysql` `redis`） |
| 发邀请码 | `invite create --uses 1 --days 7 --note 给谁的` |
| 看已发的 / 作废 | `invite list`、`invite revoke 编号` |
| 备份数据库 | `backup`，存到 `~/NexusPublicBackups` |
| 每日自动备份和摘要 | `schedule on`、`schedule off`、`schedule status`（现在是开的，每天 09:00） |

关和开，分三级：

| 级别 | 关 | 开 |
|---|---|---|
| 只断公网，服务照常运行 | `tailscale funnel reset` | `tailscale funnel --bg --https=443 http://127.0.0.1:18080` |
| 停掉服务，虚拟机还开着 | `nexus-public.sh down` | `nexus-public.sh up` |
| 整台虚拟机关掉，释放约 2GB 内存 | `limactl stop nexus-public` | `nexus-public.sh vm` |

Mac 重启或注销之后要做三件事：打开 Tailscale；执行 `nexus-public.sh vm`；执行 `tailscale funnel status` 看那条规则还在不在。

Docker Desktop 和这套环境无关，退出它没有影响。退出 Alma 也没有影响（2026-11-26 16:01 验证过）。

---

## 5. 改了代码以后怎么发布

本地开发照旧，公网环境不会自己变。想让公网用上新代码：

| 改了什么 | 要做的 |
|---|---|
| 只改前端 | 让 Qoder 按清单重新构建发布。后端不用动 |
| 只改后端（Java / Python / 网关） | 合入主线后，`backup`，再 `build`，再 `up` |
| 都改了 | 两样都做。先上后端，再发前端 |

后端发布的三条命令：

```
bash /Users/anglv/Nexus/deploy/public/nexus-public.sh backup
bash /Users/anglv/Nexus/deploy/public/nexus-public.sh build
bash /Users/anglv/Nexus/deploy/public/nexus-public.sh up
```

要知道的：

- `build` 取的是主仓库当前所在的那个提交（也可以写 `build 提交号`）。工作区里没提交的改动和私有配置文件不会进镜像。发布前确认主仓库停在 `feature-branch` 并且已经拉到最新。
- 数据在独立的数据卷里（`mysql` `redis` `uploads` `storage` `logs` `backend-tmp` `agent-data`），`build` 和 `up` 不清它们。**带着真实账号做更新还没有实测过**，所以先 `backup`。
- `backup` 只导出数据库，不含用户上传的文件。
- 数据库结构用的是 `ddl-auto=update`：新表、新字段启动时自动建；改名、删字段、改类型不会自动处理，要手写迁移。
- `up` 期间公网会断一小会儿。
- 登录锁定、上传量、模型调用次数这些计数在内存里，后端重启后从零开始计。

现在线上的前端是主线 `14707af` 构建的（页面引用 `/assets/index-De0dGedW.js`）。到 `84b35c1` 为止前端目录没有再改过，所以前端和后端目前是对得上的。

前端发布要点（完整要求见 Qoder 清单）：

- 构建时必须带这两个值，它们是构建时写进产物的，发布后改不了：
  ```
  VITE_API_BASE_URL=https://lvmacbook-air.tail007e54.ts.net/api
  VITE_WS_URL=https://lvmacbook-air.tail007e54.ts.net/ws
  ```
- 只发布 Web 版的 `dist`，`ELECTRON` 不能是 `true`。
- 注册表单要填什么由后端决定（`GET /api/auth/registration-policy`）。请求不到后端时页面显示旧表单（邮箱验证码、没有邀请码框），这是设计如此，不是前端坏了。先查后端和 Funnel 通不通。
- 如果后端新增了前端必须配合的东西（像当初的邀请码输入框），前端不重新发布，线上页面就用不了那个功能。

---

## 6. 改动以后怎么验证

- 本地只跑和改动相关的测试类，一次一个，不开无头浏览器。全量测试交给 CI，三项全绿再合并。
- 改了 `nginx/funnel-gateway.mjs`：`node --test nginx/funnel-gateway.test.mjs`。
- 上线后做不产生数据的检查：`status` 全部 Up；`/health` 200；内部路径 404；别的来源 403；未登录访问私有数据 403。
- **完整的攻击清单 `deploy/public/acceptance.py`（47 项）现在不能直接在线上跑。** 它会注册一批测试账号，按设计要在“还没对公网开放、跑完清空”的环境里执行。现在库里有真实账号，跑它会留下测试数据，清空又会删掉真实数据。要重跑的话得先有一套空的验收环境，这个还没做。
- 动了下面这些地方，属于要重跑完整清单的改动，改之前先和用户说明上面这个限制：网关的放行规则和限流、登录注册、邀请码、配额、出站地址校验、虚拟机防火墙、容器的权限设置。

---

## 7. 加域名或换地址

有两个地址，别混：

**网站地址**（访客在浏览器里打开的那个，现在是 `https://nexus-im-web-2026-fwohlcnoevd.qoder.zone`）

可以换。现在后端只认这一个，而且写死在代码里，换的时候这些地方要一起改，然后重新 `build` 和 `up`：

- `nginx/funnel-gateway.mjs` 的 `PUBLIC_ORIGIN`
- `nexus-chat-backend/src/main/resources/application-funnel.properties` 的 `funnel.public-origin` 和 `cors.exclusive-origins`
- `nexus-chat-backend/src/main/java/com/nexus/chat/config/AllowedOrigins.java`
- `deploy/public/acceptance.py` 的 `ORIGIN`
- 三个测试类里的常量：`AllowedOriginsTest`、`OriginPolicyTest`、`FunnelReadinessControllerTest`
- `nginx/funnel-gateway.test.mjs` 里“旧地址被拒绝”的断言

漏改一处，新地址来的请求会被拒绝（403）或者网关认为后端没就绪（503）。

同时认两个地址现在不支持，网关按“等于这一个”判断。要支持得改网关和后端的就绪检查。

建议的改法（还没做，等用户说要）：把网站地址改成配置项，支持列出多个。之后加或换地址只改一行配置再 `up`，不用改代码。做的时候仍然遵守第 2 节第 12 条。

Qoder 能不能绑自己的域名没有核实过。

**后端入口地址**（现在是 `https://lvmacbook-air.tail007e54.ts.net`，访客看不到它）

Tailscale Funnel 应该只能用它自己的 `ts.net` 域名，绑不了自己的域名。这一条是凭已有了解写的：2026-11-26 16:50 想对着官方文档核实，页面没取到。真要换之前先查官方文档。要换成自己的域名就得换一种入口，比如 Cloudflare Tunnel 或云服务器。那样下面这些要重新做一遍，不能照搬现在的结论：

- 访客真实地址从哪个请求头来。现在网关取 `X-Forwarded-For` 的最后一项，这是对着 Funnel 实测过的；换了入口，这个头由谁写、能不能被访客伪造都要重新测。测不准的话每访客限流就失效。
- 前端的两个构建值要换，Qoder 要重新构建发布。
- 入口是不是只转发到 `127.0.0.1:18080`。

---

## 8. 以后加功能时容易漏的地方

| 要加的东西 | 公网这边要配合的 |
|---|---|
| 新的一组接口，比如 `/api/新模块/…` | 把模块名加进 `nginx/funnel-gateway.mjs` 的 `API_GROUPS`，否则公网上是 404。`internal` 永远不加进去 |
| 新的上传接口 | 加进同一个文件的 `UPLOAD_PATHS`，否则请求体超过 8MB 会被网关拒绝；同时确认它计入上传配额（`PublicQuotaInterceptor`） |
| 新的静态资源目录 | 网关现在只放行 `/uploads/`、`/models/`、`/motions/` |
| 新的会调用模型的接口 | 确认它计入每日模型请求配额 |
| 让后端去连用户填写的地址（新的服务商类型、Webhook、抓取网页等） | 连接必须走已有的地址校验（Java 的 `ModelEndpointGuard`；Python 的 `app/llm/base.py`，由 `MODEL_ENDPOINT_ALLOW_PRIVATE_NETWORK=false` 控制），公网模式下拒绝本机、内网、Tailscale 网段。不许新开一条不校验的出站路径 |
| 新的密钥或配置项 | 公网的值放钥匙串 `nexus-public`：在 `nexus-public.sh` 的 `SECRETS` 里登记名字，`up` 时从钥匙串读出、只经管道传进虚拟机，再由 `compose.yaml` 交给容器。值不写进仓库，也不落在 Mac 或虚拟机的文件里。已有的条目只增不换，数据库里有用它们加密的内容 |
| 新的容器或服务 | 不向 Mac 发布端口；非 root、只读根文件系统；能不能访问公网要想清楚 |
| 新的限制或安全开关 | 默认关闭，只在 `application-public.properties` 打开，加一条“默认配置下行为不变”的测试 |
| 新的 WebSocket 路径 | 网关只放行 `/ws`、`/ws/**`、`/ws-native` |

公网模式下现在关着的功能，要打开前先想清楚再问用户：

- **知识库写入**：关着是因为 `chromadb`、`unstructured` 有未修的公告，而且没有可用的向量模型。
- **邮箱验证码**：关着是因为不想把邮箱密码放进容器。

---

## 9. 已知限制

- Mac 关机、睡眠、合盖或断网时，公网站点打不开。2026-11-26 13:47–15:49 合盖期间就是这样。
- 访客配置的模型服务商必须能从这台 Mac 的网络直连。只有走代理才能访问的服务商在公网环境里用不了。
- 计数类限制重启后清零。
- 后端去连访客填写的地址时，对方能看到家里的公网地址。
- 只有一台机器、一份实例，没有冗余。
- 防不住的：虚拟机或容器自身的逃逸漏洞（靠保持更新）；流量攻击能让服务不可用，但碰不到电脑里的东西；容器被攻破时，容器里的公网用户数据会泄露。
- 域名是公开可查的，会一直有人来扫。2026-11-26 13:29 已经有一个陌生地址来过，得到 404。这是正常情况，看 `summary` 里的失败登录和限流次数有没有异常就行。

---

## 10. 还没验证的，和等用户决定的

没有验证过：

- 带着真实账号做一次后端更新，数据是否完整保留。
- Mac 重启后 Funnel 的规则是否还在。
- `tailscale funnel reset` 从来没有执行过。
- 一个邀请码多次使用（`--uses 2` 以上）。
- 每日摘要的系统通知是否真的显示在屏幕上。
- 最新主线上本地 AI 对话和本地 MCP 管理没有重新试过。
- 用户用两个真实账号做的登录、聊天、上传、AI 对话验收，结果还没有反馈。
- Tailscale 管理后台（访问规则、设备、账号的两步验证）和家里路由器有没有端口转发，助手查不到。

等用户决定：

- 给登录 Tailscale 的账号开两步验证；删掉不用的设备（有一台叫 `node` 的安卓设备 17 天没上线）。
- 是否打开 macOS 防火墙的隐身模式。
- 本机 MySQL 是否改成只监听本机、本机 Redis 是否加密码。会动本地开发配置。
- 虚拟机是否随登录自动启动（占约 2GB 内存）。
- `nexus-theme.css` 的未提交改动是否提交。
- 是否把网站地址改成可配置（第 7 节）。

遗留的小事：

- `AllowedOrigins` 的内置名单里还有一个更早的站点地址 `https://nexus-chat-fwohlcnoevd.qoder.zone`，站点已不存在，只影响本地默认模式。
- 本地 `.venv311` 里的 `pypdf`、`markdown` 还是旧版，要升级执行 `.venv311/bin/pip install pypdf==7.1.1 markdown==3.11.4`。
- 整套公网环境不要了的话：`limactl delete -f nexus-public && brew uninstall lima`，再删钥匙串里 `nexus-public` 的条目。

---

## 11. 出了问题先看哪里

| 现象 | 先查 |
|---|---|
| 注册页没有邀请码框 | 后端没通。`status`；`tailscale funnel status`；虚拟机是不是停了 |
| 页面提示无网络 | 同上；再看 `logs gateway 100` 里有没有 403（来源不对）或 503（后端没就绪） |
| `status` 说虚拟机没有运行 | 执行 `vm`。虚拟机自己的日志在 `~/.lima/nexus-public/ha.stderr.log` 和 `~/Library/Logs/nexus-public-vm.log` |
| 某个访客被拒绝 | `summary` 看是限流（429）、锁定还是配额 |
| 模型调用失败 | `logs agent 200`；多半是访客填的服务商从这台 Mac 连不上，或地址被判为非公网 |
| 本地启动后模型 Key 解不开 | 有人执行过 `FunnelSecretPrep prepare`。用同一个工具的 `restore` 换回来，用法见 `docs/FUNNEL-DEPLOYMENT.md` |
| 本地启动后看不到新功能 | 先查主仓库停在哪个分支：`git -C /Users/anglv/Nexus branch --show-current`，应该是 `feature-branch` |

---

## 12. 修订记录

| 时间 | 内容 |
|---|---|
| 2026-11-26 16:45 | 首版。汇总公网开放当天定下的边界、操作、发布流程和扩展注意事项 |
