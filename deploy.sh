#!/bin/bash

# ============================================
# Nexus Chat 部署脚本
# ============================================
# 用法:
#   ./deploy.sh backend   - 仅部署后端
#   ./deploy.sh app       - 仅构建 APK
#   ./deploy.sh all       - 部署后端 + 构建 APK
# ============================================

set -e

# ==================== 配置 ====================
SERVER_IP="8.130.161.255"
SERVER_USER="root"
REMOTE_PATH="/www/wwwroot/nexus-chat"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_CONFIG_FILE="$PROJECT_DIR/nexus-chat-app/lib/core/config/api_config.dart"

# ==================== 颜色 ====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ==================== 日志函数 ====================
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# ==================== 部署后端 ====================
deploy_backend() {
    echo ""
    echo "============================================"
    echo "        部署后端到服务器"
    echo "============================================"
    echo ""

    cd "$PROJECT_DIR"

    # 1. 压缩后端代码
    log_step "1/4 压缩后端代码..."
    rm -f nexus-chat-backend.zip
    zip -rq nexus-chat-backend.zip nexus-chat-backend \
        -x "*.git*" \
        -x "*target/*" \
        -x "*.iml" \
        -x "*.log"
    log_info "压缩完成: nexus-chat-backend.zip"

    # 2. 上传到服务器
    log_step "2/4 上传到服务器 ($SERVER_IP)..."
    scp -o ConnectTimeout=10 nexus-chat-backend.zip ${SERVER_USER}@${SERVER_IP}:${REMOTE_PATH}/
    log_info "上传完成"

    # 3. 远程部署
    log_step "3/4 远程部署中..."
    ssh -o ConnectTimeout=10 ${SERVER_USER}@${SERVER_IP} << 'ENDSSH'
        cd /www/wwwroot/nexus-chat

        echo ">>> 解压代码..."
        rm -rf nexus-chat-backend
        unzip -oq nexus-chat-backend.zip
        rm -f nexus-chat-backend.zip

        echo ">>> 停止旧容器..."
        docker compose -f docker-compose-app.yml down 2>/dev/null || true

        echo ">>> 构建并启动新容器..."
        docker compose -f docker-compose-app.yml up -d --build

        echo ">>> 等待服务启动 (15秒)..."
        sleep 15

        echo ">>> 检查服务状态..."
        docker compose -f docker-compose-app.yml ps
ENDSSH

    # 4. 清理本地临时文件
    log_step "4/4 清理临时文件..."
    rm -f nexus-chat-backend.zip

    echo ""
    log_info "✅ 后端部署完成!"
    echo ""
}

# ==================== 构建 App ====================
build_app() {
    echo ""
    echo "============================================"
    echo "        构建生产环境 APK"
    echo "============================================"
    echo ""

    cd "$PROJECT_DIR/nexus-chat-app"

    # 1. 切换到生产环境
    log_step "1/5 切换到生产环境配置..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        sed -i '' 's/static const bool isProduction = false/static const bool isProduction = true/' "$API_CONFIG_FILE"
    else
        # Linux
        sed -i 's/static const bool isProduction = false/static const bool isProduction = true/' "$API_CONFIG_FILE"
    fi
    log_info "已切换到生产环境"

    # 2. 清理旧构建
    log_step "2/5 清理旧构建..."
    flutter clean > /dev/null 2>&1
    log_info "清理完成"

    # 3. 获取依赖
    log_step "3/5 获取依赖..."
    flutter pub get > /dev/null 2>&1
    log_info "依赖获取完成"

    # 4. 构建 APK
    log_step "4/5 构建 Release APK (这可能需要几分钟)..."
    flutter build apk --release

    # 5. 恢复开发环境
    log_step "5/5 恢复开发环境配置..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' 's/static const bool isProduction = true/static const bool isProduction = false/' "$API_CONFIG_FILE"
    else
        sed -i 's/static const bool isProduction = true/static const bool isProduction = false/' "$API_CONFIG_FILE"
    fi
    log_info "已恢复开发环境"

    APK_PATH="$PROJECT_DIR/nexus-chat-app/build/app/outputs/flutter-apk/app-release.apk"

    echo ""
    log_info "✅ APK 构建完成!"
    echo ""
    echo "APK 路径:"
    echo "  $APK_PATH"
    echo ""

    # 在 macOS 上打开 Finder
    if [[ "$OSTYPE" == "darwin"* ]]; then
        log_info "正在打开 Finder..."
        open "$(dirname "$APK_PATH")"
    fi
}

# ==================== 查看服务器日志 ====================
view_logs() {
    echo ""
    log_info "连接服务器查看日志 (按 Ctrl+C 退出)..."
    echo ""
    ssh ${SERVER_USER}@${SERVER_IP} "cd /www/wwwroot/nexus-chat && docker compose -f docker-compose-app.yml logs -f --tail=100 backend"
}

# ==================== 查看服务器状态 ====================
check_status() {
    echo ""
    log_info "检查服务器状态..."
    echo ""
    ssh ${SERVER_USER}@${SERVER_IP} << 'ENDSSH'
        echo "=== 容器状态 ==="
        cd /www/wwwroot/nexus-chat
        docker compose -f docker-compose-app.yml ps

        echo ""
        echo "=== API 健康检查 ==="
        curl -s http://localhost:8080/api/auth/health || echo "健康检查端点不可用"

        echo ""
        echo "=== 磁盘使用 ==="
        df -h / | tail -1
ENDSSH
}

# ==================== 帮助信息 ====================
show_help() {
    echo ""
    echo "Nexus Chat 部署脚本"
    echo ""
    echo "用法: $0 <命令>"
    echo ""
    echo "命令:"
    echo "  backend    部署后端到服务器"
    echo "  app        构建生产环境 APK"
    echo "  all        部署后端 + 构建 APK"
    echo "  logs       查看服务器日志"
    echo "  status     查看服务器状态"
    echo "  help       显示帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 backend   # 只部署后端"
    echo "  $0 app       # 只构建 APK"
    echo "  $0 all       # 全部部署"
    echo ""
}

# ==================== 主函数 ====================
main() {
    case "$1" in
        backend)
            deploy_backend
            ;;
        app)
            build_app
            ;;
        all)
            deploy_backend
            build_app
            ;;
        logs)
            view_logs
            ;;
        status)
            check_status
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

# 如果没有参数，显示帮助
if [ $# -eq 0 ]; then
    show_help
    exit 0
fi

main "$@"
