#!/usr/bin/env bash
# User-only backend launcher: no secret arguments, files, output, or lifecycle takeover.
set +x
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }
mode=${1:-}
case "$mode" in java|python) ;; *) printf 'Usage: bash nginx/funnel-start.sh java|python\n' >&2; exit 2 ;; esac
root=$(cd "$(dirname "$0")/.." && pwd)
[[ -f "$root/nexus-chat-backend/src/main/resources/application-local.properties" ]] || fail 'Run the main-repository launcher; do not copy private configuration into a worktree.'
[[ -f "$root/nexus-agent-backend/.env" ]] || fail 'Main Python configuration is missing; no service started.'

read_key() {
    /usr/bin/security find-generic-password -s nexus-funnel -a "$1" -w 2>/dev/null
}
internal_token=$(read_key agent.internal.token) || fail 'Internal token missing/unreadable in Keychain; no service started.'
signing_secret=$(read_key agent.internal.signing-secret) || fail 'Internal signing secret missing/unreadable in Keychain; no service started.'
[[ -n "$internal_token" && -n "$signing_secret" ]] || fail 'Empty mutual-trust settings; no service started.'

if [[ "$mode" == java ]]; then
    companion_secret=$(read_key companion.api-key.secret) || fail 'Companion key missing/unreadable in Keychain; no service started.'
    [[ -n "$companion_secret" ]] || fail 'Empty Companion key; no service started.'
    if /usr/sbin/lsof -nP -iTCP:8080 -sTCP:LISTEN -t >/dev/null 2>&1; then fail 'Port 8080 is already listening; stop your own Java service before restarting.'; fi
    cd "$root/nexus-chat-backend"
    export AGENT_INTERNAL_TOKEN="$internal_token" AGENT_INTERNAL_SIGNING_SECRET="$signing_secret" COMPANION_API_KEY_SECRET="$companion_secret"
    exec mvn -f "$root/nexus-chat-backend/pom.xml" spring-boot:run -Dspring-boot.run.profiles=local,funnel -Dstart-class=com.nexus.chat.NexusChatApplication
else
    if /usr/sbin/lsof -nP -iTCP:8100 -sTCP:LISTEN -t >/dev/null 2>&1; then fail 'Port 8100 is already listening; stop your own Python service before restarting.'; fi
    [[ -x "$root/nexus-agent-backend/.venv311/bin/python" ]] || fail 'Expected .venv311 Python is missing; no service started.'
    cd "$root/nexus-agent-backend"
    export JAVA_INTERNAL_TOKEN="$internal_token" INTERNAL_SIGNING_SECRET="$signing_secret" ENGINE=langgraph
    exec .venv311/bin/python main.py
fi
