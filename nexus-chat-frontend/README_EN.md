<div align="center">

# Nexus Chat

<img src="public/icons/icon.png" alt="Nexus Chat Logo" width="120" height="120">

**A Modern Real-time Chat Application**

[![Vue](https://img.shields.io/badge/Vue-3.4.0-4FC08D?style=flat-square&logo=vue.js&logoColor=white)](https://vuejs.org/)
[![Electron](https://img.shields.io/badge/Electron-28.0.0-47848F?style=flat-square&logo=electron&logoColor=white)](https://www.electronjs.org/)
[![Vite](https://img.shields.io/badge/Vite-5.0.0-646CFF?style=flat-square&logo=vite&logoColor=white)](https://vitejs.dev/)
[![Element Plus](https://img.shields.io/badge/Element%20Plus-2.5.0-409EFF?style=flat-square)](https://element-plus.org/)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

English | **[简体中文](./README.md)**

</div>

---

## Introduction

Nexus Chat is a cross-platform real-time chat application built with Vue 3 and Electron. It supports both web browsers and desktop applications (Windows, macOS, Linux), providing a smooth chatting experience with rich social features.

## Features

| Icon | Feature |
|:----:|---------|
| 💬 | **Real-time Communication** - Instant messaging based on WebSocket |
| 🖥️ | **Cross-platform Support** - Both web browser and desktop applications |
| 👥 | **Group Chat** - Create groups, manage members, set administrators |
| 📇 | **Contact Management** - Friend requests, online status display |
| 👤 | **User Profile** - Custom avatar, background, social links |
| 🌐 | **Internationalization** - Chinese and English interface support |
| 🔍 | **Message Search** - Quick search through chat history |
| 📁 | **File Transfer** - Support for images and file sharing |

## Tech Stack

| Category | Technology |
|:---------|:-----------|
| Frontend Framework | Vue 3 + Composition API |
| State Management | Pinia |
| Router | Vue Router 4 |
| UI Library | Element Plus |
| Build Tool | Vite 5 |
| Desktop Framework | Electron 28 |
| Real-time Communication | STOMP.js + SockJS |
| HTTP Client | Axios |
| i18n | Vue i18n |

## Project Structure

```
Nexus-Chat/
├── 📂 src/
│   ├── 📂 components/        # Reusable components
│   │   ├── 📂 chat/          # Chat components
│   │   ├── 📂 contact/       # Contact components
│   │   ├── 📂 layout/        # Layout components
│   │   └── 📂 common/        # Common components
│   ├── 📂 views/             # Page views
│   ├── 📂 stores/            # Pinia state management
│   ├── 📂 services/          # API and WebSocket services
│   ├── 📂 locales/           # i18n files
│   ├── 📂 styles/            # Global styles
│   └── 📂 router/            # Router configuration
├── 📂 electron/              # Electron main process
├── 📂 public/                # Static assets
└── 📂 dist/                  # Build output
```

## Quick Start

### Requirements

- Node.js >= 18.0.0
- npm >= 9.0.0

### Installation

```bash
# Clone the repository
git clone https://github.com/AngkinV/Nexus-Chat.git

# Navigate to project directory
cd Nexus-Chat

# Install dependencies
npm install

# Start web development server
npm run dev:web

# Or start Electron development mode
npm run dev
```

### Build

```bash
# Build for web
npm run build

# Build for macOS
npm run electron:build:mac

# Build for Windows
npm run electron:build:win
```

## Configuration

| File | Purpose |
|:-----|:--------|
| `.env.development` | Development environment |
| `.env.production` | Production environment |
| `.env.electron` | Electron environment |

```env
VITE_API_BASE_URL=http://localhost:8080/api    # API server address
VITE_WS_URL=http://localhost:8080/ws           # WebSocket server address
```

## Related Projects

| Project | Description |
|:--------|:------------|
| [Nexus Backend](https://github.com/AngkinV/nexus) | Backend Service |

## License

This project is licensed under the [MIT](LICENSE) License.

---

<div align="center">

**Made with ❤️ by Nexus Team**

</div>
