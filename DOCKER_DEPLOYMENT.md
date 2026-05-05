# Nexus Chat Docker 部署文档

## 一、架构概览

```
                         互联网用户
                             │
                    https://chat.angkin.cn
                             │
                    ┌────────┴────────┐
                    │   Cloudflare    │  ← 自动 HTTPS + CDN
                    │     Tunnel      │
                    └────────┬────────┘
                             │
               ┌─────────────┼─────────────┐
               │      Docker Network       │
               │                           │
          ┌────┴────┐              ┌───────┴───────┐
          │  Nginx  │              │  Spring Boot  │
          │ (前端)  │──── /api ───►│    (后端)     │
          │  :80    │──── /ws ────►│    :8080      │
          └─────────┘              └───────┬───────┘
                                           │
                                    ┌──────┴──────┐
                                    │    MySQL    │
                                    │    :3306    │
                                    └─────────────┘
```

## 二、服务清单

| 服务 | 容器名 | 端口 | 说明 |
|------|--------|------|------|
| MySQL | nexus-mysql | 3306 | 数据库 |
| Backend | nexus-backend | 8080 | Spring Boot API |
| Frontend | nexus-frontend | 80 | Vue + Nginx |
| Tunnel | nexus-tunnel | - | Cloudflare 内网穿透 |

## 三、文件结构

```
Nexus Chat/
├── docker-compose.yml          # 服务编排
├── .env                        # 环境变量（敏感信息，不提交 git）
├── .env.example                # 环境变量模板
├── nginx/
│   └── nginx.conf              # Nginx 配置
├── nexus-chat-backend/
│   ├── Dockerfile              # 后端镜像
│   └── src/main/resources/
│       └── application-docker.properties
└── nexus-chat-frontend/
    └── Dockerfile              # 前端镜像
```

## 四、环境变量说明 (.env)

```bash
# MySQL
MYSQL_ROOT_PASSWORD=<数据库 root 密码>
MYSQL_USER=nexus_chat
MYSQL_PASSWORD=<数据库用户密码>

# JWT
JWT_SECRET=<64位随机字符串，用 openssl rand -base64 64 生成>

# CORS
CORS_ORIGINS=https://chat.angkin.cn

# 邮件
MAIL_USERNAME=<QQ邮箱>
MAIL_PASSWORD=<QQ邮箱授权码>

# Companion
COMPANION_API_KEY_SECRET=<高强度随机密钥，用 openssl rand -hex 32 生成>

# Cloudflare Tunnel
CLOUDFLARE_TUNNEL_TOKEN=<Tunnel Token>
```

## 五、常用命令

### 启动服务
```bash
cd "/Users/anglv/Nexus Chat"
docker-compose up -d
```

### 停止服务
```bash
docker-compose down
```

### 重新构建并启动
```bash
docker-compose up -d --build
```

### 查看状态
```bash
docker-compose ps
```

### 查看日志
```bash
# 所有服务
docker-compose logs -f

# 单个服务
docker-compose logs -f backend
docker-compose logs -f frontend
docker-compose logs -f mysql
docker-compose logs -f cloudflared
```

### 重启单个服务
```bash
docker-compose restart backend
```

### 进入容器调试
```bash
docker exec -it nexus-backend sh
docker exec -it nexus-mysql mysql -u root -p
```

## 六、访问地址

| 用途 | 地址 |
|------|------|
| 网站首页 | https://chat.angkin.cn |
| API 接口 | https://chat.angkin.cn/api |
| WebSocket | wss://chat.angkin.cn/ws |

## 七、Cloudflare 配置

### DNS 记录
| Type | Name | Content | Proxy |
|------|------|---------|-------|
| CNAME | chat | `<tunnel-id>.cfargotunnel.com` | Proxied ☁️ |

### Tunnel 配置
- Tunnel ID: `eb2dc6a9-14d3-4a6c-a3f9-f44f8959b155`
- Public Hostname: `chat.angkin.cn` → `http://frontend:80`

### NS 服务器
```
irma.ns.cloudflare.com
norm.ns.cloudflare.com
```

## 八、注意事项

### 代理要求
由于网络限制，cloudflared 需要通过代理连接 Cloudflare：
- 确保本地代理软件（如 Clash）运行在端口 7897
- docker-compose.yml 中已配置 `HTTP_PROXY` 和 `HTTPS_PROXY`

### 数据持久化
数据存储在 Docker 卷中：
- `nexus-mysql-data`: 数据库数据
- `nexus-uploads-data`: 上传文件（图片、视频、音频、文档等）

### 文件存储
上传文件按日期存储在 `uploads/` 目录下：
```
uploads/
├── 2026/
│   └── 02/
│       └── 06/
│           ├── <uuid>.jpg
│           ├── <uuid>.pdf
│           └── ...
└── chunks/              # 分片上传临时目录（自动清理）
```
- 文件通过 Nginx 静态服务提供访问 (`/uploads/`)
- 文件下载/预览通过后端API (`/api/files/download/`, `/api/files/preview/`)
- 文件30天后自动过期，定时清理任务删除过期文件

### 备份数据库
```bash
docker exec nexus-mysql mysqldump -u root -p nexus_chat > backup.sql
```

### 恢复数据库
```bash
docker exec -i nexus-mysql mysql -u root -p nexus_chat < backup.sql
```

## 九、故障排查

### 问题：网站无法访问
1. 检查容器状态：`docker-compose ps`
2. 检查 Tunnel 连接：`docker logs nexus-tunnel`
3. 检查 DNS 解析：`nslookup chat.angkin.cn`

### 问题：Tunnel 连接失败
1. 确保代理软件已开启
2. 重启 Tunnel：`docker-compose restart cloudflared`

### 问题：后端报错
1. 查看日志：`docker logs nexus-backend`
2. 检查数据库连接：`docker exec nexus-mysql mysqladmin ping -h localhost`

---

*文档更新时间：2026-02-06*
