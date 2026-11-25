# Qoder 前端 + Tailscale Funnel 受控后端入口

> **2026-11-26 起，本文描述的“后端直接在 Mac 上对公网开放”的做法已停用。** 公网环境改为在隔离虚拟机的容器里运行，有自己的数据库和密钥，见 `docs/PUBLIC-ISOLATION-PLAN.md`（操作方法在第 12 节）。`nginx/funnel-start.sh` 已不再启动任何服务。下面的内容保留作历史记录；其中“把本地数据库恢复到原密钥”一节仍然有效。

## 当前状态（UTC+8，按核对时间分列，越靠上越新）

- **11-26 00:07 只读核对**：链路三段在本机都已在位，**按已对公网开放对待**。Funnel on，`/` 代理到 `http://127.0.0.1:18080`；网关在监听 `127.0.0.1:18080`（node 进程，00:03 启动，工作目录是 funnel-gateway 工作树），本机 `/health` 返回 200，本机经网关请求 `/internal/funnel/readiness` 返回 404；Java（23:43 启动，工作目录是主仓库）只监听 `127.0.0.1:8080`，本机 readiness 返回 200，`ready=true`、`policyVersion=nexus-funnel-v2`；Python 监听 `*:8100`（所有网卡，Funnel 不代理它）。写这条记录的助手没有启停任何服务，也没有改 Funnel 配置。**没有从外部设备访问过**，登录、聊天、上传均未验收，Qoder 是否重新发布未核对。
- **11-25 23:59 只读核对**：Funnel 配置和后端状态同上，但网关 18080 当时没有监听（00:03 才启动）。
- **11-25 21:06 只读核对（沿用当时记录）**：已存在指向 18080 的 Funnel 配置（来源未核对，助手未执行开启）；当时后端仍是 local，网关未运行。

以下五条是 **11-25 17:15 的历史记录**，其中“Funnel 未开启”只对当时成立：

- 原站点：`https://nexus-chat-2026-fwohlcnoevd.qoder.zone`。此前只确认登录页能加载；登录、真实聊天、上传和外部设备访问尚未验收。
- 主实现通过 PR #27 合入远端与本地主线（`8663647`，三项 CI 全绿）。当前兼容修正保留本机/Electron 直连，同时公网网关仍仅接受 Qoder 来源；以最新 git/PR 为准。**代码落地不等于公网激活**。
- 主仓库的原 `.env.production`、`nexus-theme.css` 和 Qoder 站点元数据已逐字节保留。仓库提交的发布目标是 Funnel，本机原发布端点仍等后端 ready 后再切换，不提前改变已发布站点。
- Java/Python 当前由用户从主仓库运行；**助手没有启停它们**。运行中的旧 Java 的本地 readiness 请求返回 403；新版网关已实际拒绝为它启动，18080 没有新增监听。
- **17:15 时 Funnel 未开启，Qoder 未重新发布。** 修改源码和构建通过不代表公网已经接通。

## 架构与公开范围

Qoder 托管原前端 → Funnel HTTPS → 本机 `127.0.0.1:18080` Node 网关 → Java `127.0.0.1:8080` → Python Agent（仅内部）。不公开 Python、MySQL、Redis，不通过网关重新托管开发前端或本机目录。

网关只放行业务 API、SockJS `/ws`、原生 `/ws-native`，以及只读 `/uploads`、`/models`、`/motions` 资源。内部路径（包括 readiness）、APK、版本管理、调试路径、隐藏文件和编码绕过在代理前拒绝。CORS/Origin 不是鉴权；实际权限仍由 JWT 主体、聊天成员、附件归属与短期签名访问控制。

网关每次业务 HTTP 请求和 WebSocket 升级前都读取本机的 `/internal/funnel/readiness`。旧后端、错误 Origin 配置、缺失受限 profile 或不合格密钥不会被代理到公网；用户随后以旧配置重启后端时，也不会继续放行业务请求。`/health` **只表示网关活着，不代表后端已验收**。

## 发布前端配置

设备域名已通过 `tailscale status --json` 核对为 `lvmacbook-air.tail007e54.ts.net`，最终仍按启用 Funnel 的实际输出核对：

```env
VITE_API_BASE_URL=https://lvmacbook-air.tail007e54.ts.net/api
VITE_WS_URL=https://lvmacbook-air.tail007e54.ts.net/ws
```

SockJS 使用 HTTPS `/ws`，不是直接填 WSS。构建和运行时共用校验：必须是同源公网 HTTPS，拒绝回环/私网地址、URL 内凭据、查询串和错误路径；开发和 Electron 本地后端行为保留。

已构建的发布 JS 中，Funnel `/api` 和 `/ws` 各有 1 处，localhost/127.0.0.1:8080 后端端点和旧 IP 的 `/api`、`/ws` 都是 **0 处**。旧 localhost/旧后端上传头像会归一到当前后端；私有附件通过经真实后端授权的短期 URL 获取，不把登录 JWT 放进 URL。

**这些值必须进入原 Qoder 站点的发布构建。更新本地文件不会自动证明已发布网页用了新地址。**

## 本机启动报错补充（2026-11-25）

用户 18:56 以 `local,funnel` 启动时只有 banner 和 Maven exit 1，真正异常不可见：原 `logback-spring.xml` 只匹配 `default/dev/prod`，没有匹配 `local,funnel` 的 logger/root。现已为其它显式 profile 补 Console/File/Error 输出，保留 dev/prod 行为，关闭不支持 Spring profile 扩展的 scan 重载；密钥诊断一次报告全部无效项，绝不输出值。

原配置回退后，新日志回归真实失败（`local,funnel` 没有 Console appender）；恢复修复后 Console 和错误文件都能看到异常与缺项，全部 Java 719 项通过。测试仅初始化日志/配置，没有启动 Java/Python 服务。

只读的 Spring 原生配置预检查（主仓库文件 + 工具进程环境）拒绝以下三项：`agent.internal.token`、`agent.internal.signing-secret`、`companion.api-key.secret`。这不冒充已终止 Maven 进程的环境；MySQL 配置端口 TCP 可达、Redis 有监听，不等于已验证数据库/Redis认证。

若先恢复本机使用，可由用户启动**只绑定回环的 local 模式**：

```bash
cd /Users/anglv/Nexus/nexus-chat-backend
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

这不开放公网，也不会通过 Funnel readiness。公开模式仍需按下节配齐强密钥：互信值应与 Python 一致，Companion 现有加密密钥需要确认或迁移，不能盲目换值。助手不更改私有配置、不启动服务、不放宽安全门禁。

## 公开前的密钥门禁

`funnel` profile 仅在以下配置都显式提供非开发默认值、至少 32 字节时启动：

- `jwt.secret`
- `agent.internal.token`
- `agent.internal.signing-secret`
- `companion.api-key.secret`

不会把密钥的值写进日志、异常或文档。默认模式不受此门禁影响。

本轮只读检查主仓库 `application-local.properties`：JWT 已配置、长度满足要求且不是当前被跟踪的默认值；后三项未在该文件找到。**进程环境变量未核对，不能据此断言运行中一定缺少密钥。**

Java 的 `agent.internal.token` 对应 Python 环境变量 `JAVA_INTERNAL_TOKEN`；Java 的 `agent.internal.signing-secret` 对应 Python 的 `INTERNAL_SIGNING_SECRET`。对应两端的值必须一致（按 Python `app/config.py` 的 `BaseSettings` 字段核对）。**不要盲目更换 Companion 加密密钥：现有加密的模型凭据需要先确认原密钥或迁移，否则会无法解密。** 私有 `application-local.properties`、Python `.env` 和运行数据由用户保管，本任务不改、不复制、不输出其值。

## 用户执行的安全准备工具

工具 `com.nexus.chat.tools.FunnelSecretPrep` 不启动 Spring 服务，默认 `check` 只读，`prepare` 需两个后端已停止并输入 `MIGRATE`。助手不替用户执行迁移或启停后端。

**Maven 会先编译主仓库，建议先由用户停止 Java/Python 再执行。** 用绝对 `-f` 路径避免 `cd` 粘贴失败或从 `~` 运行而报缺插件。项目继承的 Boot 插件读取 `${start-class}`，必须用 `-Dstart-class` 选择工具，不能用被继承配置覆盖的 `spring-boot.run.main-class`：

```bash
mvn -f /Users/anglv/Nexus/nexus-chat-backend/pom.xml -q spring-boot:run -Dstart-class=com.nexus.chat.tools.FunnelSecretPrep -Dspring-boot.run.arguments=check
```

后端在运行、只想 `check` 时，加三个跳过参数就直接用 `target/classes` 里已编译好的类，不走编译和资源复制，不改写运行中后端正在用的文件：

```bash
mvn -f /Users/anglv/Nexus/nexus-chat-backend/pom.xml -q spring-boot:run -Dmaven.main.skip=true -Dmaven.test.skip=true -Dmaven.resources.skip=true -Dstart-class=com.nexus.chat.tools.FunnelSecretPrep -Dspring-boot.run.arguments=check
```

前提是主仓库已编译过含本工具的版本；它读到的配置是上次编译时复制进 `target/classes` 的那份，之后改过 `application-local.properties` 而没重新编译的话不会体现。`prepare` 本来就要求两个后端已停，用不带跳过参数的命令。

检查未就绪是正常结果；确认显示现有记录全部可解密后，用户执行：

```bash
mvn -f /Users/anglv/Nexus/nexus-chat-backend/pom.xml -q spring-boot:run -Dstart-class=com.nexus.chat.tools.FunnelSecretPrep -Dspring-boot.run.arguments=prepare
```

- JWT 只用现有文件/环境，不从 Keychain 查询、覆盖或轮换；JWT不合格就拒绝。
- 已有 Keychain 项不合格时在任何写入前拒绝，不覆盖它们。只为缺失的其余强密钥生成随机值，写入后回读；秘密不进参数、文件、Git或输出。
- 每条现有加密记录必须可解密。旧密钥需可归档，原配置为空且有待迁移数据时 `check` 和 `prepare` 都报拒绝，不猜默认值。
- 出错时只输出异常类型和所在步骤（配置项名、Keychain 项名或表列名），不输出异常内容；旧密钥归档项的名称带指纹，也不输出。
- 只在一个数据库事务里更新 `api_key_encrypted`，逐条回读校验；失败整体回滚，新旧 Keychain 密钥留存可恢复。中断后重试沿用已保存的目标密钥，不再次轮换。
- 迁移成功后，不带新 Companion Keychain 值启动 Java（包括local模式）会无法解密旧模型凭据，不再用原来的裸启动命令。

用户在两个独立终端中分别启动：

```bash
bash /Users/anglv/Nexus/nginx/funnel-start.sh java
```

```bash
bash /Users/anglv/Nexus/nginx/funnel-start.sh python
```

`java` 最终在 `nexus-chat-backend` 里执行 `mvn spring-boot:run -Dspring-boot.run.profiles=local,funnel`，`python` 最终在 `nexus-agent-backend` 里执行 `ENGINE=langgraph .venv311/bin/python main.py`。

脚本仅供用户启动，自动进入各自原主仓库目录，用子进程环境注入对应 Keychain 值，不解除私有文件 `uchg`。Keychain读取失败/缺项立即退出；端口已占用时不重复启动，也不代用户停止。脚本针对本次三项都需准备的配置；如果用户此前已在外部提供有效强密钥但Keychain不含全部三项，需要保留并合并其既有安全启动环境，不盲目生成替换。

测试仅使用合成密文、假 Keychain 和内存数据库。真实 Keychain读取及无写入的interactive help已核对；真实prepare写入/MySQL迁移必须用户确认后验证，不能用合成测试冒充。启动脚本只做过语法解析，并在临时目录里跑过一份把 Keychain 读取、`lsof`、`mvn`、Python 都换成假程序的副本（缺项、读取失败、空值、端口占用、没有私有配置都在启动前退出，密钥只出现在子进程环境里）；真实脚本助手没有执行过。现有MCP凭据密钥不在本工具迁移范围。

## 把本地数据库恢复到原密钥（restore）

公网环境改为在容器里从空库起步（见 `docs/PUBLIC-ISOLATION-PLAN.md`），本地数据库不再需要钥匙串里的那把加密密钥。`restore` 把 `prepare` 迁移过的模型凭据换回配置文件里的原密钥，之后用原来的命令（`mvn spring-boot:run`）启动即可解密。

```bash
mvn -f /Users/anglv/Nexus/nexus-chat-backend/pom.xml -q spring-boot:run -Dstart-class=com.nexus.chat.tools.FunnelSecretPrep -Dspring-boot.run.arguments=restore
```

- 按普通启动（不带 profile）解析配置。要求两个后端都已停止，并输入 `RESTORE`；全部记录已在原密钥下时不询问、不写入。
- 单事务，只更新 `api_key_encrypted`，逐条回读校验，失败整体回滚。钥匙串不改动。
- 用原密钥和钥匙串密钥都解不开的记录存在时，什么都不写。
- 恢复之后，本地用原来的命令启动（`funnel-start.sh` 已停用）。

**2026-11-26 01:53 这条命令已对真实本地数据库执行过一次：4 条记录换回原密钥并提交，钥匙串三项仍在。** 这次执行是助手验证时的失误，不是用户操作：助手以为后端还在运行、工具会拒绝，把确认词通过管道传了进去，而后端当时已停止。01:54 只读核对：4 条都能用原配置密钥解密，无法解密 0。如需回到迁移后的状态，由用户再执行一次 `prepare`。

## 激活顺序（进行中，各步现状见“当前状态”）

1. 完成全量回归，按项目 PR 流程落地代码，保留用户已有前端主题、Qoder 站点元数据及私有文件。
2. 用户检查上述密钥来源，并同步 Java/Python 的互信配置。配置已通过环境变量提供时，无需重复写文件。
3. **用户从主仓库重启 Java**，明确启用本地与 Funnel profile：

   ```bash
   cd /Users/anglv/Nexus/nexus-chat-backend
   mvn spring-boot:run -Dspring-boot.run.profiles=local,funnel
   ```

   密钥若是用上节工具放进 Keychain 的，这一步改用上节的 `funnel-start.sh java` 和 `funnel-start.sh python`（两端都重启，互信值才一致），上面的裸命令拿不到这些值，会被密钥门禁拒绝。

   Python 未改代码；若调整它的互信配置，需由用户同步配置并重启 Python。助手不代启停。

4. 读取真实本地 readiness，核对 `ready=true`、`policyVersion=nexus-funnel-v2`、`publicOrigin` 为当前 Qoder，Origin 列表只包含 Qoder 与本机/Electron 来源；用 `lsof` 确认 Java 只监听 `127.0.0.1:8080`。**后端保留本机/Electron 直连，公网网关独立拒绝这些来源，不能把 CORS 当作鉴权。**
5. 从回环地址串行验真实鉴权、越权、附件与 SockJS；通过后运行 `node nginx/funnel-gateway.mjs`，仅监听 18080 回环端口。
6. 核对 Tailscale 当前无其他需保留的入口与 Funnel 权限；本机 CLI 已只读确认支持以下命令，**只在上一步真实检查通过后执行**：

   ```bash
   tailscale funnel --bg --https=443 http://127.0.0.1:18080
   ```

   禁止直接以 8080 或 8100 为公网目标。21:06只读核对已存在指向18080的Funnel配置（来源未核对、助手未执行开启），但后端仍是local且网关未运行。后续复核现有配置，不重复开启或重置其它Serve配置。
7. 在原 Qoder 站点重新发布，不更改站点网址；用未连接 Tailscale 的外部设备验收。

## 验收与回退

验收包括真实登录、聊天、上传/资源显示、SockJS/STOMP 连接及恢复、AI SSE 分段到达，以及匿名、跨账号和内部路由请求被拒绝。没有测试账号、对应后端未激活或缺少外部设备时明确标记未验证，不能用夹具冒充真实验收。

回退只关闭本轮新增的 Funnel HTTPS 入口、停止本轮新增网关并恢复 Qoder 的前端端点；不停止用户 Java/Python，也不重置其他 Serve 配置。Funnel 适合联调和演示，电脑休眠、断网和后端停止会导致不可用，不承诺生产级稳定性。

## 实际测试记录

- Node 网关：14/14，通过（含流式分段到达、双向 WebSocket 数据、原始编码绕过、请求大小、错误 Origin 和后端安全模式失效后的拒绝；上游为隔离夹具）。
- 前端全部单测：685/685，通过；最新主线基点上的 Web 发布构建通过（4.76 秒），产物中回环与旧 IP 后端端点均为 0。
- Java 全量回归：730/730，通过（按项目标准排除 `NexusChatApplicationTests`），单 JVM 串行；密钥门禁、真实 Spring profile readiness、后端本机/Electron 兼容与公网网关仍拒绝本机 Origin 均已覆盖。
- 过期文件回归已做撤回验证：撤掉修复后 `/api/files/access` 真实测试响应从 410 变成 200，新回归失败；已还原修复后跑全套通过。
- 跨域夹具增加了仅测试启用的 profile，避免污染其他真实 JPA 测试；MCP 审计回归捕获真实日志事件，不依赖跨上下文的全局 stdout，原有内容、权限与隐私断言均保留。
- Python 全量：864 passed、6 skipped（28.19 秒；测试配置有 1 条 pytest warning），无 Python 代码改动、未调用真实 embedding 功能。
- 17:15 时旧 Java 的实际启动拒绝已验证；它当时不满足公开条件，助手没有开启 Funnel（之后出现的 Funnel 配置见“当前状态”），也没有把这些隔离回归当作真实公网验收。
- 11-26 00:05 这一轮加固：`FunnelSecretPrepTest` 11/11、`FunnelSecretGuardTest` 5/5 通过，新增断言先在旧实现上失败 3 项再修复；改动只在该工具及其测试、没有新增测试方法，全量 730 项未重跑。
