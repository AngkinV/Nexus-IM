# 开发流程

一个人、一台电脑的版本。等有了服务器和协作者，再往上加。

## 几个词

| 词 | 意思 |
|---|---|
| 分支 | 代码的一份草稿。在草稿上改，不影响正本 |
| `main` | 正本。只有检查通过的东西才能进 |
| 合并请求 | “把这份草稿并进正本”的申请单，GitHub 上叫 Pull request |
| 流水线 | 每次提交申请单，GitHub 自动开一台干净的临时机器把测试跑一遍 |
| 环境 | 同一套程序跑在不同地方：自己电脑上是开发环境，给真实用户用的是生产环境 |
| 版本标签 | 给某个时刻的正本贴个号，比如 `v1.0.0`，出问题能退回去 |

## 每做一个功能

1. 从 `main` 开一个短分支，名字写清楚做什么，例如 `m1/companion-lazy-load`。
2. 在分支上改代码，本机跑下面的三条检查命令。
3. 把分支推到 GitHub。
4. 在 GitHub 仓库页的 **Pull requests** 标签里创建合并请求。
5. 等页面底部的自动检查出结果：绿勾可以合并，红叉点进去看哪一项失败。
6. 合并后删掉这个分支。
7. 攒够一批功能，在 `main` 上打版本标签。

## 操作在哪

| 事情 | 位置 |
|---|---|
| 创建和合并申请单 | GitHub 仓库页 → Pull requests |
| 看自动检查结果 | 申请单页面底部，或仓库页 → Actions |
| 禁止直接改 `main` | 仓库页 → Settings → Branches → Add branch ruleset |
| 流水线的配置 | 仓库里的 `.github/workflows/ci.yml` |
| 任务规划 | `docs/ROADMAP.md` |

## 环境

| 环境 | 在哪 | 用途 |
|---|---|---|
| 开发 | 本机直接运行 | 写代码 |
| 测试 | GitHub 的临时机器 | 每次推代码自动跑检查 |
| 预演 | 本机 Docker（`docker-compose.yml`） | 发版前把整套服务起一遍 |
| 生产 | 暂无 | 有了服务器后，把预演那套搬过去 |

## 本机检查命令

和流水线跑的是同一套。

```bash
# 后端，在 nexus-chat-backend/ 下
mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false

# Agent，在 nexus-agent-backend/ 下
.venv/bin/python -m pytest -q

# 前端，在 nexus-chat-frontend/ 下
npm run build
```

- `NexusChatApplicationTests` 被排除，因为它要连本机真实的 MySQL 和 Redis。
- Agent 的测试会自动切到一个临时目录里运行，不读本机的 `.env`，也不碰 `data/chroma` 里的真实向量库。不要绕开 `tests/conftest.py` 里的这段隔离。

## 规则

- **密钥不进仓库。** 仓库里只放 `.env.example`（占位符）。真实的值放在本机的 `.env`，它已被 `.gitignore` 忽略。
- **运行数据不进仓库。** 用户上传的文件（`uploads/`）和向量库（`nexus-agent-backend/data/`）只留在本机。
- **`main` 只通过合并请求进。** 不直接往 `main` 推代码。
- **改了行为就补测试。** 尤其是鉴权、发消息、同步这几条主链路。
