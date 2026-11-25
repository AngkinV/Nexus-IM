# Qoder 前端 + Tailscale Funnel 受控后端入口

## 当前状态（2026-11-25 16:35，UTC+8）

- 原站点：`https://nexus-chat-2026-fwohlcnoevd.qoder.zone`。此前只确认登录页能加载；登录、真实聊天、上传和外部设备访问尚未验收。
- 实现基于远端主线 `8b2d97d`，已完成全部本地回归与发布产物检查。代码提交/PR 的最新状态以 git 和 PR 记录为准；**代码落地不等于公网激活**，仍需完成下列密钥、用户重启与外部验收步骤。
- Java/Python 当前由用户从主仓库运行；**助手没有启停它们**。运行中的旧 Java 的本地 readiness 请求返回 403；新版网关已实际拒绝为它启动，18080 没有新增监听。
- **Funnel 未开启，Qoder 未重新发布。** 修改源码和构建通过不代表公网已经接通。

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

## 公开前的密钥门禁

`funnel` profile 仅在以下配置都显式提供非开发默认值、至少 32 字节时启动：

- `jwt.secret`
- `agent.internal.token`
- `agent.internal.signing-secret`
- `companion.api-key.secret`

不会把密钥的值写进日志、异常或文档。默认模式不受此门禁影响。

本轮只读检查主仓库 `application-local.properties`：JWT 已配置、长度满足要求且不是当前被跟踪的默认值；后三项未在该文件找到。**进程环境变量未核对，不能据此断言运行中一定缺少密钥。**

Java 的 `agent.internal.token` 对应 Python 环境变量 `JAVA_INTERNAL_TOKEN`；Java 的 `agent.internal.signing-secret` 对应 Python 的 `INTERNAL_SIGNING_SECRET`。对应两端的值必须一致（按 Python `app/config.py` 的 `BaseSettings` 字段核对）。**不要盲目更换 Companion 加密密钥：现有加密的模型凭据需要先确认原密钥或迁移，否则会无法解密。** 私有 `application-local.properties`、Python `.env` 和运行数据由用户保管，本任务不改、不复制、不输出其值。

## 激活顺序（尚未执行）

1. 完成全量回归，按项目 PR 流程落地代码，保留用户已有前端主题、Qoder 站点元数据及私有文件。
2. 用户检查上述密钥来源，并同步 Java/Python 的互信配置。配置已通过环境变量提供时，无需重复写文件。
3. **用户从主仓库重启 Java**，明确启用本地与 Funnel profile：

   ```bash
   cd /Users/anglv/Nexus/nexus-chat-backend
   mvn spring-boot:run -Dspring-boot.run.profiles=local,funnel
   ```

   Python 未改代码；若调整它的互信配置，需由用户同步配置并重启 Python。助手不代启停。

4. 读取真实本地 readiness，核对 `ready=true`、`policyVersion=nexus-funnel-v1`、Origin 仅为当前 Qoder；用 `lsof` 确认 Java 只监听 `127.0.0.1:8080`。默认模式保留原客户端来源；**受限 Funnel 模式只接受当前 Qoder，localhost/Electron Origin 不在该模式的白名单中**。
5. 从回环地址串行验真实鉴权、越权、附件与 SockJS；通过后运行 `node nginx/funnel-gateway.mjs`，仅监听 18080 回环端口。
6. 核对 Tailscale 当前无其他需保留的入口与 Funnel 权限；本机 CLI 已只读确认支持以下命令，**只在上一步真实检查通过后执行**：

   ```bash
   tailscale funnel --bg --https=443 http://127.0.0.1:18080
   ```

   禁止直接以 8080 或 8100 为公网目标。现状核对仍为 `No serve config`，上面的公开命令尚未执行。
7. 在原 Qoder 站点重新发布，不更改站点网址；用未连接 Tailscale 的外部设备验收。

## 验收与回退

验收包括真实登录、聊天、上传/资源显示、SockJS/STOMP 连接及恢复、AI SSE 分段到达，以及匿名、跨账号和内部路由请求被拒绝。没有测试账号、对应后端未激活或缺少外部设备时明确标记未验证，不能用夹具冒充真实验收。

回退只关闭本轮新增的 Funnel HTTPS 入口、停止本轮新增网关并恢复 Qoder 的前端端点；不停止用户 Java/Python，也不重置其他 Serve 配置。Funnel 适合联调和演示，电脑休眠、断网和后端停止会导致不可用，不承诺生产级稳定性。

## 实际测试记录

- Node 网关：14/14，通过（含流式分段到达、双向 WebSocket 数据、原始编码绕过、请求大小、错误 Origin 和后端安全模式失效后的拒绝；上游为隔离夹具）。
- 前端全部单测：685/685，通过；最新主线基点上的 Web 发布构建通过（4.76 秒），产物中回环与旧 IP 后端端点均为 0。
- Java 全量回归：714/714，通过（按项目标准排除 `NexusChatApplicationTests`），单 JVM 串行；密钥门禁和真实 Spring profile readiness 均在其中。
- 过期文件回归已做撤回验证：撤掉修复后 `/api/files/access` 真实测试响应从 410 变成 200，新回归失败；已还原修复后跑全套通过。
- 跨域夹具增加了仅测试启用的 profile，避免污染其他真实 JPA 测试；MCP 审计回归捕获真实日志事件，不依赖跨上下文的全局 stdout，原有内容、权限与隐私断言均保留。
- Python 全量：864 passed、6 skipped（28.19 秒；测试配置有 1 条 pytest warning），无 Python 代码改动、未调用真实 embedding 功能。
- 当前旧 Java 的实际启动拒绝已验证；它不满足公开条件，没有开启 Funnel，也没有把这些隔离回归当作真实公网验收。
