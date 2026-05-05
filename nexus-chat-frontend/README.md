<div align="center">

# Nexus Chat

<img src="public/icons/icon.png" alt="Nexus Chat Logo" width="120" height="120">

**一款现代化的实时聊天应用**

[![Vue](https://img.shields.io/badge/Vue-3.4.0-4FC08D?style=flat-square&logo=vue.js&logoColor=white)](https://vuejs.org/)
[![Electron](https://img.shields.io/badge/Electron-28.0.0-47848F?style=flat-square&logo=electron&logoColor=white)](https://www.electronjs.org/)
[![Vite](https://img.shields.io/badge/Vite-5.0.0-646CFF?style=flat-square&logo=vite&logoColor=white)](https://vitejs.dev/)
[![Element Plus](https://img.shields.io/badge/Element%20Plus-2.5.0-409EFF?style=flat-square)](https://element-plus.org/)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

**[English](./README_EN.md)** | 简体中文

</div>

---

## 简介

Nexus Chat 是一款基于 Vue 3 和 Electron 构建的跨平台实时聊天应用。支持 Web 端和桌面端（Windows、macOS、Linux），提供流畅的聊天体验和丰富的社交功能。

## 特性

| 功能 | 描述 |
|:----:|------|
| 💬 | **实时通讯** - 基于 WebSocket 的即时消息传递 |
| 🖥️ | **跨平台支持** - 同时支持 Web 浏览器和桌面应用 |
| 👥 | **群组聊天** - 创建群组、管理成员、设置管理员 |
| 📇 | **联系人管理** - 好友申请、在线状态显示 |
| 👤 | **个人资料** - 自定义头像、背景、社交链接 |
| 🌐 | **国际化** - 支持中文和英文界面 |
| 🔍 | **消息搜索** - 快速检索历史消息 |
| 📁 | **文件传输** - 支持图片和文件的发送 |

## 技术栈

| 类别 | 技术 |
|:-----|:-----|
| 前端框架 | Vue 3 + Composition API |
| 状态管理 | Pinia |
| 路由管理 | Vue Router 4 |
| UI 组件库 | Element Plus |
| 构建工具 | Vite 5 |
| 桌面框架 | Electron 28 |
| 实时通信 | STOMP.js + SockJS |
| HTTP 客户端 | Axios |
| 国际化 | Vue i18n |

## 项目结构

```
Nexus-Chat/
├── 📂 src/
│   ├── 📂 components/        # 可复用组件
│   │   ├── 📂 chat/          # 聊天相关组件
│   │   ├── 📂 contact/       # 联系人组件
│   │   ├── 📂 layout/        # 布局组件
│   │   └── 📂 common/        # 通用组件
│   ├── 📂 views/             # 页面视图
│   ├── 📂 stores/            # Pinia 状态管理
│   ├── 📂 services/          # API 和 WebSocket 服务
│   ├── 📂 locales/           # 国际化文件
│   ├── 📂 styles/            # 全局样式
│   └── 📂 router/            # 路由配置
├── 📂 electron/              # Electron 主进程
├── 📂 public/                # 静态资源
└── 📂 dist/                  # 构建输出
```

## 快速开始

### 环境要求

- Node.js >= 18.0.0
- npm >= 9.0.0

### 安装与运行

```bash
# 克隆项目
git clone https://github.com/AngkinV/Nexus-Chat.git

# 进入项目目录
cd Nexus-Chat

# 安装依赖
npm install

# 启动 Web 开发服务器
npm run dev:web

# 或启动 Electron 开发模式
npm run dev
```

### 构建项目

```bash
# 构建 Web 版本
npm run build

# 构建 macOS 桌面应用
npm run electron:build:mac

# 构建 Windows 桌面应用
npm run electron:build:win
```

## 配置说明

| 文件 | 用途 |
|:-----|:-----|
| `.env.development` | 开发环境配置 |
| `.env.production` | 生产环境配置 |
| `.env.electron` | Electron 环境配置 |

```env
VITE_API_BASE_URL=http://localhost:8080/api    # API 服务地址
VITE_WS_URL=http://localhost:8080/ws           # WebSocket 服务地址
```

## 相关项目

| 项目 | 描述 |
|:-----|:-----|
| [Nexus Backend](https://github.com/AngkinV/nexus) | 后端服务 |

## 许可证

本项目基于 [MIT](LICENSE) 许可证开源。

---

<div align="center">

**Made with ❤️ by Nexus Team**

</div>
