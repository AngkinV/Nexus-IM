#!/usr/bin/env bash
# Runs the public Nexus stack inside the nexus-public VM. See docs/PUBLIC-ISOLATION-PLAN.md.
#
#   bash deploy/public/nexus-public.sh vm                 create/start the VM
#   bash deploy/public/nexus-public.sh build [commit]     build images from a git commit (default HEAD)
#   bash deploy/public/nexus-public.sh up | down | status
#   bash deploy/public/nexus-public.sh invite create [--uses N] [--days D] [--note text] | list | revoke <id>
#   bash deploy/public/nexus-public.sh logs <gateway|backend|agent|mysql|redis> [lines]
#   bash deploy/public/nexus-public.sh backup             database dump into ~/NexusPublicBackups
#
# It never touches the local development setup: different ports, its own database, its own
# secrets (macOS Keychain service "nexus-public"), and images built from a commit, not from
# the working tree. Secrets are never written to disk, printed, or passed as arguments.
set +x
set -euo pipefail

VM=nexus-public
KEYCHAIN=nexus-public
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
fail() { printf '%s\n' "$1" >&2; exit 1; }
vm() { limactl shell --workdir / "$VM" -- sudo "$@"; }
container() { printf 'nexus-public-%s-1' "$1"; }

require_vm() {
    command -v limactl >/dev/null 2>&1 || fail '需要先安装 Lima：brew install lima'
    [[ $(limactl list --format '{{.Status}}' "$VM" 2>/dev/null) == Running ]] || fail "虚拟机没有运行，先执行: bash $0 vm"
    vm systemctl is-active --quiet nexus-guard.service || fail '虚拟机防火墙没有运行，拒绝继续。'
}

# name:kind pairs; hex is 32 random bytes in hex, b64 is 32 random bytes in base64.
SECRETS='NEXUS_JWT_SECRET:hex NEXUS_INTERNAL_TOKEN:hex NEXUS_SIGNING_SECRET:hex NEXUS_COMPANION_SECRET:hex
NEXUS_MCP_CREDENTIAL_KEY:b64 NEXUS_DB_PASSWORD:hex NEXUS_DB_ROOT_PASSWORD:hex NEXUS_REDIS_PASSWORD:hex'

read_secret() { /usr/bin/security find-generic-password -s "$KEYCHAIN" -a "$1" -w 2>/dev/null; }

# Prints KEY='value' lines for the VM on stdout. Missing secrets are generated once and only
# ever added to the Keychain, never replaced: the database is encrypted with some of them.
secret_env() {
    local item name kind value
    for item in $SECRETS; do
        name=${item%%:*}; kind=${item##*:}
        if ! value=$(read_secret "$name") || [[ -z "$value" ]]; then
            if [[ $kind == b64 ]]; then value=$(/usr/bin/openssl rand -base64 32); else value=$(/usr/bin/openssl rand -hex 32); fi
            printf 'add-generic-password -s %s -a %s -w %s\n' "$KEYCHAIN" "$name" "$value" | /usr/bin/security -i >/dev/null 2>&1 \
                || fail "无法把 $name 写入钥匙串，未启动。"
            [[ $(read_secret "$name") == "$value" ]] || fail "$name 写入钥匙串后读回不一致，未启动。"
            printf '钥匙串新增: %s\n' "$name" >&2
        fi
        [[ $value =~ ^[A-Za-z0-9+/=]{40,}$ ]] || fail "钥匙串里的 $name 格式不对，未启动。"
        printf "%s='%s'\n" "$name" "$value"
    done
}

cmd=${1:-}; shift || true
case "$cmd" in
vm)
    command -v limactl >/dev/null 2>&1 || fail '需要先安装 Lima：brew install lima'
    limactl list -q 2>/dev/null | grep -qx "$VM" || limactl create --name "$VM" --tty=false "$here/lima.yaml"
    [[ $(limactl list --format '{{.Status}}' "$VM") == Running ]] || limactl start "$VM" --tty=false
    require_vm
    printf '虚拟机已运行，防火墙已启用。\n'
    ;;
build)
    require_vm
    commit=$(git -C "$repo" rev-parse --verify "${1:-HEAD}^{commit}") || fail '找不到这个提交。'
    work=$(mktemp -d /tmp/nexus-public-build.XXXXXX); trap 'rm -rf "$work"' EXIT
    mkdir -p "$work/src" "$work/ctx/backend/assets" "$work/ctx/agent" "$work/ctx/gateway"
    # The commit, not the working tree: uncommitted edits and private config files stay out.
    git -C "$repo" archive "$commit" nexus-chat-backend nexus-agent-backend nginx/funnel-gateway.mjs \
        nexus-chat-frontend/public/models nexus-chat-frontend/public/motions deploy/public | tar -x -C "$work/src"
    printf '构建后端 jar（提交 %s）…\n' "${commit:0:12}"
    (cd "$work/src/nexus-chat-backend" && mvn -q -B -ntp -DskipTests package) || fail '后端构建失败。'
    jar=$(find "$work/src/nexus-chat-backend/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -1)
    [[ -f "$jar" ]] || fail '没有找到构建出的 jar。'
    cp "$jar" "$work/ctx/backend/app.jar"
    cp -R "$work/src/nexus-chat-frontend/public/models" "$work/src/nexus-chat-frontend/public/motions" "$work/ctx/backend/assets/"
    cp -R "$work/src/nexus-agent-backend/app" "$work/src/nexus-agent-backend/main.py" "$work/src/nexus-agent-backend/requirements.txt" "$work/ctx/agent/"
    cp "$work/src/nginx/funnel-gateway.mjs" "$work/ctx/gateway/"
    cp "$work/src/deploy/public/compose.yaml" "$work/src/deploy/public/"*.Dockerfile "$work/ctx/"
    printf '%s\n' "$commit" > "$work/ctx/RELEASE"
    tar -C "$work/ctx" -cf "$work/ctx.tar" .
    limactl copy "$work/ctx.tar" "$VM:/tmp/nexus-public-ctx.tar"
    vm bash -euo pipefail -c '
        rm -rf /opt/nexus-public.new && mkdir -p /opt/nexus-public.new
        tar -xf /tmp/nexus-public-ctx.tar -C /opt/nexus-public.new && rm -f /tmp/nexus-public-ctx.tar
        cd /opt/nexus-public.new && release=$(cut -c1-12 RELEASE)
        docker build -q -t nexus-public/gateway:$release -f gateway.Dockerfile gateway
        docker build -q -t nexus-public/backend:$release -f backend.Dockerfile backend
        docker build -t nexus-public/agent:$release -f agent.Dockerfile agent
        rm -rf /opt/nexus-public && mv /opt/nexus-public.new /opt/nexus-public
        echo "镜像已构建: $release"'
    ;;
up)
    require_vm
    vm test -f /opt/nexus-public/RELEASE || fail "还没有构建镜像，先执行: bash $0 build"
    secret_env | vm bash -euo pipefail -c '
        set -a; . /dev/stdin; set +a
        cd /opt/nexus-public && export NEXUS_RELEASE=$(cut -c1-12 RELEASE)
        exec docker compose up -d --remove-orphans --quiet-pull'
    printf '已启动。等后端就绪后网关才会开始监听，用 status 查看。\n'
    ;;
down)
    require_vm
    vm docker compose -p nexus-public down
    ;;
status)
    require_vm
    printf '版本: %s\n' "$(vm cat /opt/nexus-public/RELEASE 2>/dev/null | cut -c1-12)"
    vm docker ps -a --filter label=com.docker.compose.project=nexus-public --format '{{.Label "com.docker.compose.service"}}\t{{.Status}}'
    printf '网关（本机 127.0.0.1:18080）: %s\n' "$(curl -sS -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/health 2>/dev/null || true)"
    ;;
invite)
    require_vm
    [[ $# -ge 1 ]] || fail "用法: bash $0 invite create [--uses N] [--days D] [--note 备注] | list | revoke <id>"
    vm docker exec "$(container backend)" java -Xmx128m -XX:TieredStopAtLevel=1 -cp /app/app.jar \
        -Dloader.main=com.nexus.chat.tools.InviteCodeTool org.springframework.boot.loader.launch.PropertiesLauncher "$@" 2>/dev/null
    ;;
logs)
    require_vm
    [[ ${1:-} =~ ^(gateway|backend|agent|mysql|redis)$ ]] || fail "用法: bash $0 logs <gateway|backend|agent|mysql|redis> [行数]"
    [[ ${2:-200} =~ ^[0-9]+$ ]] || fail '行数必须是数字。'
    vm docker logs --tail "${2:-200}" "$(container "$1")"
    ;;
backup)
    require_vm
    umask 077
    mkdir -p "$HOME/NexusPublicBackups"
    target="$HOME/NexusPublicBackups/nexus_chat-$(date +%Y%m%d-%H%M%S).sql.gz"
    vm docker exec "$(container mysql)" sh -c 'exec mysqldump --no-tablespaces --single-transaction -unexus -p"$MYSQL_PASSWORD" nexus_chat 2>/dev/null' | gzip > "$target.part"
    gzip -t "$target.part" && [[ $(gzip -dc "$target.part" | tail -c 200 | grep -c 'Dump completed') == 1 ]] || { rm -f "$target.part"; fail '备份不完整，已删除。'; }
    mv "$target.part" "$target"
    printf '数据库已备份到 %s（不含上传的文件）。\n' "$target"
    ;;
*)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
