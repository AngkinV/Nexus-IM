# Nexus 结构与提交流程清单

更新：2026-11-20，基准 `feature-branch` = `3d5248e`（PR #12 合并点）。
本清单与 `DEVELOPMENT.md` 冲突时以本清单为准。

> 分支结构、凭据布局、提交流程是**唯一事实来源**，其他文档引用本文，不复制。
> 每次合并 PR 后必须同步更新：本行基线与 §1 分支/worktree 状态。

## 1. 分支结构

| 分支 | 角色 | 状态 |
|---|---|---|
| `feature-branch` | **主线**（GitHub 默认分支） | 所有 PR 合并到这里 |
| `main` | **稳定兜底分支** | feature-branch 稳定后才同步过来；是兜底，不是开发目标，不直接往它提交/合并 |
| `m0/foundation` | 历史里程碑分支 | 已合并，可清理 |
| `feat/companion-roles` | 功能分支（PR #8） | 已合并，本地+远端仍在，可清理 |
| `feat/ui-reshape` | 功能分支（PR #6、#7） | 已删除（本地+远端） |
| `partial-giraffe` | worktree 分支 | 内有 8 处未提交修改，属并行会话，**不要动** |
| `bad-condor` | worktree 分支 | 干净，并行会话工作区 |
| `slimy-silkworm` | worktree 分支 | 内有 4 处未提交修改，属并行会话，**不要动** |
| `central-wombat` | worktree 分支 | 当前会话工作区，文档维护 |

**双分支策略**：日常开发全在 `feature-branch` 及其短分支上进行；只有当 `feature-branch` 上攒够一批功能、验证稳定后，才把它同步到 `main`。`main` 永远保持「可回退的稳定快照」，不直接从 `main` 开分支或提交。

worktree（同一仓库的多份检出）：

| 路径 | 分支 | 说明 |
|---|---|---|
| `/Users/anglv/Nexus` | `feature-branch` | 主工作区 |
| `/Users/anglv/alma/worktrees/nexus/bad-condor` | `bad-condor` | 并行会话工作区（当前干净） |
| `/Users/anglv/alma/worktrees/nexus/central-wombat` | `central-wombat` | 当前会话工作区 |
| `/Users/anglv/alma/worktrees/nexus/partial-giraffe` | `partial-giraffe` | 并行会话工作区（8 处未提交） |
| `/Users/anglv/alma/worktrees/nexus/slimy-silkworm` | `slimy-silkworm` | 并行会话工作区（4 处未提交） |

**并行会话警告**：多个会话会同时往各自 worktree 写代码。当前各工作区未提交修改：
`/Users/anglv/Nexus` 4 处（`AgentSessionList.vue`、`en.json`、`zh.json`、`agent.js`）、
`partial-giraffe` 8 处、`slimy-silkworm` 4 处。提交前先 `git status` + `ls -lT <文件>`
看时间戳，确认改动已收尾、且属于本轮要提交的内容；不要动别人的 worktree 及其未提交修改。

## 2. 目录结构

| 目录 | 技术栈 | 说明 |
|---|---|---|
| `nexus-chat-frontend/` | Vue 3 + Vite + Tailwind + Electron | `src/components/` 按 agent / chat / companion / contact / layout / standby 等模块分目录 |
| `nexus-chat-backend/` | Spring Boot 3.2 / Java 17 | 配置见第 3 节 |
| `nexus-agent-backend/` | Python FastAPI | `.venv` 为 py3.9；`.env` 存本地 LLM 密钥 |
| `nginx/` | nginx | 反向代理配置 |
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

- 改本地凭据：`chflags nouchg <文件>` → 编辑 → `chflags uchg <文件>` 锁回。
  直接编辑会报 Operation not permitted，这是保护不是故障。
- 提交前自查：`git grep -nE "授权码片段|真实密码片段" -- .` 应无匹配。
- **历史遗留**：旧 SMTP 授权码、旧 MySQL 密码、旧 JWT 密钥仍可在公开历史中翻到
  （`.env.app` 等旧提交）。仓库屏蔽已完成，但已公开的东西只能靠轮换失效：
  QQ 邮箱作废旧授权码、服务器轮换仍在用的旧密码。当前在用的 SMTP 码从未上传。

## 4. 标准提交流程（按顺序执行）

0. **预检**：`git status --porcelain`、`git fetch origin --prune`；
   核对改动归属与时间戳（见第 1 节警告）。
1. **开分支**：`git switch -c feat/简短名`（从 `feature-branch` 开出）。
2. **本地三检查**（与 CI 同口径）：
   ```bash
   cd nexus-chat-frontend && npm run build
   cd nexus-agent-backend && env -u NO_PROXY -u no_proxy .venv/bin/python -m pytest -q
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
9. **同步**：`git fetch origin feature-branch:feature-branch && git switch feature-branch`。
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

- 并行 worktree 未提交修改（截至 2026-11-20）：`/Users/anglv/Nexus` 4 处、
  `partial-giraffe` 8 处、`slimy-silkworm` 4 处；均属并行会话，去留待定，**不要动**。
- 可清理分支：`feat/companion-roles`、`m0/foundation`（本地+远端）。
- 旧凭据轮换：QQ 邮箱旧授权码、服务器旧 MySQL/JWT（见第 3 节）。
- 本清单文件尚未提交，随下一轮提交入库。