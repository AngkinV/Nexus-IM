# Nexus 结构与提交流程清单

更新：2026-11-25。工作基线统一为 **先 fetch 后的 `origin/feature-branch`**，不把旧的固定 SHA 当作当前状态。已核对里程碑：PR #27 合并提交 `8663647`（受控 Funnel 代码），公开激活尚未完成。
本清单与 `DEVELOPMENT.md` 冲突时以本清单为准。

> 分支结构、凭据布局、提交流程是**唯一事实来源**，其他文档引用本文，不复制。
> 每次合并 PR 后必须同步更新：本行基线与 §1 分支/worktree 状态。

## 1. 分支结构

| 分支 | 角色 | 状态 |
|---|---|---|
| `feature-branch` | **主线**（GitHub 默认分支） | 所有 PR 合并到这里，当前 HEAD 用下方命令实时核对 |
| `main` | **稳定兜底分支** | 仅在 feature-branch 验证稳定后同步，不直接开发；本轮没有推进 |
| `feat/funnel-gateway` | 本轮连接、安全与网关改动 | PR #27 已合入；本机兼容修正继续通过独立 PR 落地，公网仍待用户重启与验收 |
| 其他分支 | 独立工作或历史分支 | 未提交内容与线程状态不在本轮检查范围，不得因提交号已在主线就删除工作树 |

**双分支策略**：短分支从 `origin/feature-branch` 开出，不从本地 feature-branch 或 main 开出。PR 合并、CI 与源码同步不表示运行中的 Java/Python 自动更新；服务由用户启停。

2026-11-25 16:59 通过 `git worktree list` 核对的工作树（用途表，不承诺随时间仍保持同一 HEAD 或干净状态）：

| 路径 | 分支 | 说明 |
|---|---|---|
| `/Users/anglv/Nexus` | `feature-branch` | 主仓库；用户原有发布端点、主题、Qoder 元数据保留 |
| `/Users/anglv/Nexus/.alma/worktrees/funnel-gateway` | `feat/funnel-gateway` | 本轮工作树；已有主题改动不属于本轮部署提交 |
| `/Users/anglv/alma/worktrees/nexus/fast-wolf` | `feat/chat-scope-popover` | 独立工作树，未核对未提交改动与运行线程 |
| `/Users/anglv/alma/worktrees/nexus/alleged-eel` | `fix-supplier-protocol-and-test-feedback` | 保留 |
| `/Users/anglv/alma/worktrees/nexus/gastric-whippet` | `redesign-input-ui-components` | 保留 |
| `/Users/anglv/alma/worktrees/nexus/glorious-amphibian` | detached | 保留 |
| `/Users/anglv/alma/worktrees/nexus/molecular-pigeon` | `feat/pixel-workroom-motion` | 保留 |
| `/Users/anglv/alma/worktrees/nexus/reliable-pony` | `deploy-backend-bt-panel-public-ip` | 保留 |
| `/Users/anglv/alma/worktrees/nexus/urban-halibut` | `feat/tavern-invite-port` | 保留 |

每次开工、提交与合并后运行：

```bash
git fetch origin --prune
git branch --show-current
git log --oneline -3
git status --short
git worktree list
git rev-list --left-right --count HEAD...origin/feature-branch
```

**并行会话警告**：只暂存本轮明确归属的路径。2026-11-25 已核对主仓库的 `.env.production`、`nexus-theme.css` 与 `.Nexus Chat.qoder.site` 为用户原有改动；PR #27 源码同步时三者逐字节保留，旧发布端点待后端真实 ready 后再切换。其他工作树不能凭旧文档断言干净或已经收尾。

## 2. 目录结构

| 目录 | 技术栈 | 说明 |
|---|---|---|
| `nexus-chat-frontend/` | Vue 3 + Vite + Tailwind + Electron | `src/components/` 按 agent / chat / companion / contact / layout / standby 等模块分目录 |
| `nexus-chat-backend/` | Spring Boot 3.2 / Java 17 | 配置见第 3 节 |
| `nexus-agent-backend/` | Python FastAPI | 使用 Python 3.11 的 `.venv311`；`.env` 存本地配置与密钥 |
| `nginx/` | nginx / Node | 原反向代理配置与 `funnel-gateway.mjs` 受控回环网关 |
| `docs/` | 文档 | DEVELOPMENT.md（部分过时）、ROADMAP.md、本清单 |
| `nexus-chat-app/` | Flutter（已暂停） | 整目录 gitignore，只留本机 |
| `docker-compose.yml` | 容器编排 | 凭据全部走环境变量，无硬编码 |

运行时数据（`uploads/`、`mysql_data/` 等）均 gitignore，不进仓库。

## 3. 凭据布局（红线）

| 文件 | 内容 | git | 防删除 |
|---|---|---|---|
| `nexus-chat-backend/src/main/resources/application-local.properties` | SMTP 授权码、DB 密码、JWT 密钥 | gitignore | `uchg` |
| `nexus-agent-backend/.env` | LLM API keys 等 | gitignore | `uchg` |
| 被跟踪的 `application.properties` / `.env.example` | 只有占位符或 `${ENV:默认值}` | 进仓库 | — |

- **本地凭据仅由用户修改。** 改本地凭据：`chflags nouchg <文件>` → 编辑 → `chflags uchg <文件>` 锁回。
  直接编辑会报 Operation not permitted，这是保护不是故障。
- 提交前自查：`git grep -nE "授权码片段|真实密码片段" -- .` 应无匹配。
- **历史遗留**：旧 SMTP 授权码、旧 MySQL 密码、旧 JWT 密钥仍可在公开历史中翻到
  （`.env.app` 等旧提交）。仓库屏蔽已完成，但已公开的东西只能靠轮换失效：
  QQ 邮箱作废旧授权码、服务器轮换仍在用的旧密码。当前在用的 SMTP 码从未上传。

## 4. 标准提交流程（按顺序执行）

0. **预检**：`git status --porcelain`、`git fetch origin --prune`；
   核对改动归属与时间戳（见第 1 节警告）。
1. **开分支**：`git switch -c feat/简短名 origin/feature-branch`（必须先 fetch，不从可能含其他线程提交的本地主线开出）。
2. **本地三检查**（与 CI 同口径，串行；Java 服务在跑时只在独立工作树编译测试，不改主仓库 target/classes）：
   ```bash
   cd nexus-chat-frontend && npm run build
   cd nexus-agent-backend && PYTHONDONTWRITEBYTECODE=1 ENGINE=langgraph env -u NO_PROXY -u no_proxy .venv311/bin/python -m pytest -q -p no:cacheprovider
   cd nexus-chat-backend && mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
   ```
3. **暂存**：`git add <具体目录或文件>`，**禁止 `git add -A` / `git add .`**；
   然后 `git status` + `git diff --cached --stat` 核对清单、扫凭据。
4. **提交**：conventional 风格，如 `feat: ...` / `fix(security): ...` / `chore: ...`。
5. **推送**：`git push -u origin feat/简短名`。
6. **建 PR**：base 固定 `feature-branch`。GitHub 页面或下方 API 模板。
7. **等 CI**：前端 (Vue) / 后端 (Java) / Agent (Python) 三项全绿才合并；
   红叉就在同分支修复追加提交，再推送会自动更新 PR。
8. **合并**：确认 `mergeable=true`；用 merge commit 方式（仓库历史风格）。
9. **同步**：先 fetch，在目标工作树用 `git merge --ff-only origin/feature-branch`；用户未提交改动必须先核对、保护，不能 reset/覆盖。私有配置与运行数据不改，Java/Python 不代启停。
10. **清理（可选）**：`git branch -d feat/简短名 && git push origin --delete feat/简短名`。
    只用 `-d`（git 会校验已合并），**禁止 `-D` 强删**。
11. **同步 `main`（稳定后才做）**：`feature-branch` 上攒够一批功能且验证稳定后，
    再把 `feature-branch` 快进同步到 `main`（`main` 是稳定兜底，不直接从它开发）。
    并同步更新本文基线与 §1 状态。

### API 模板（本机无 gh CLI，用 git 已存凭据）

```bash
TOKEN=$(printf "protocol=https\nhost=github.com\n" | git credential fill | sed -n 's/^password=//p')
# 建 PR
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/AngkinV/Nexus-IM/pulls \
  -d '{"title":"feat: ...","head":"feat/简短名","base":"feature-branch"}'
# 查 CI（head sha 见 PR 页面或建 PR 响应）
curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/AngkinV/Nexus-IM/commits/<head-sha>/check-runs
# 合并
curl -s -X PUT -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/AngkinV/Nexus-IM/pulls/<编号>/merge \
  -d '{"merge_method":"merge"}'
```

不要把 `$TOKEN` 打印到终端或写进任何文件。

## 5. 常见坑

- 本地 pytest 7 个失败：shell 的 `NO_PROXY` 含 `::1` + py3.9 venv 旧版 httpx 的解析
  bug，`env -u NO_PROXY -u no_proxy` 即全绿；CI 无此变量，不受影响。
- GitHub 分支页的 ahead/behind 有缓存延迟，以 `git rev-list --left-right --count` 为准。
- 不直接从 `main` 开发/合并（`main` 是稳定兜底，见 §1）；不强推（force push）；不动别的 worktree 及其未提交修改。
- `.env.example` 只是占位模板，别把真实值写回去。
- 合并后主工作区仍可能出现并行会话的新改动，属正常活跃工作，留下一轮处理。

## 6. 当前待办

- 受控 Funnel：代码与回归见 `docs/FUNNEL-DEPLOYMENT.md`；尚未开启公网入口，用户需核对密钥并重启 Java 后继续真实验收。
- 原 Qoder 站点、发布端点和元数据保留；只有后端真实安全边界通过后才切换发布端点并重新发布。
- 其他工作树是否已收尾、本机旧备份是否可清理：本轮未核对，不沿用 11-20 的数量和路径作决定。
- 历史凭据是否完成轮换：本轮未核对。不能因文件已忽略就认为公开历史中的旧值已失效。
