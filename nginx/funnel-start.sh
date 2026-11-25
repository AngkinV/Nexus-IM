#!/usr/bin/env bash
# Retired. The public environment no longer runs on the Mac itself: it runs in containers
# inside an isolated VM, with its own database and secrets (docs/PUBLIC-ISOLATION-PLAN.md).
# Local development starts the backends the plain way, without this script.
printf '%s\n' '这个启动方式已停用：公网环境改为在隔离虚拟机里运行。' \
    '  公网环境: bash deploy/public/nexus-public.sh status' \
    '  本地开发: 照常用 mvn spring-boot:run 和 ENGINE=langgraph .venv311/bin/python main.py' >&2
exit 1
