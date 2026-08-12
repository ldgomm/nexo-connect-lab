#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${PROJECT_DIR}/.env"

read_env_value() {
    local key="$1"
    awk -F= -v key="$key" '
        $1 == key {
            print substr($0, index($0, "=") + 1)
            found = 1
            exit
        }
        END { if (!found) exit 1 }
    ' "$ENV_FILE"
}

if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" ]]; then
    printf 'ERROR=AUTHENTICATED_WEBSOCKET_ENV_MISSING\n' >&2
    exit 2
fi

HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
BUSINESS_TOKEN="$(read_env_value CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN)"

if [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] || [[ ${#BUSINESS_TOKEN} -lt 32 ]]; then
    printf 'ERROR=AUTHENTICATED_WEBSOCKET_ENV_INVALID\n' >&2
    exit 3
fi

HANDSHAKE_URL="http://127.0.0.1:${HTTP_HOST_PORT}/v1/realtime"
handshake_status() {
    local authorization_header="${1:-}"
    local curl_args=(
        --silent
        --show-error
        --output /dev/null
        --write-out '%{http_code}'
        --max-time 10
        --http1.1
        --header 'Connection: Upgrade'
        --header 'Upgrade: websocket'
        --header 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='
        --header 'Sec-WebSocket-Version: 13'
    )
    if [[ -n "$authorization_header" ]]; then
        curl_args+=(--header "$authorization_header")
    fi
    curl "${curl_args[@]}" "$HANDSHAKE_URL"
}

MISSING_BEARER_STATUS="$(handshake_status)"
INVALID_BEARER_STATUS="$(handshake_status 'Authorization: Bearer invalid-synthetic-token')"
if [[ "$MISSING_BEARER_STATUS" != "401" || "$INVALID_BEARER_STATUS" != "401" ]]; then
    printf 'ERROR=AUTHENTICATED_WEBSOCKET_PRE_UPGRADE_REJECTION_FAILED\n' >&2
    printf 'MISSING_BEARER_STATUS=%s\n' "$MISSING_BEARER_STATUS" >&2
    printf 'INVALID_BEARER_STATUS=%s\n' "$INVALID_BEARER_STATUS" >&2
    exit 4
fi

(
    cd "$PROJECT_DIR"
    CONNECT_LAB_C1_RUNTIME_URL="ws://127.0.0.1:${HTTP_HOST_PORT}/v1/realtime" \
    CONNECT_LAB_C1_RUNTIME_TOKEN="$BUSINESS_TOKEN" \
        ./gradlew --no-daemon test \
            --tests 'com.premierdarkcoffee.nexo.connect.lab.backend.routes.AuthenticatedRealtimeRuntimeTest' \
            --rerun-tasks --console=plain
)

printf 'REALTIME_MISSING_BEARER_HANDSHAKE=PASS\n'
printf 'REALTIME_INVALID_BEARER_HANDSHAKE=PASS\n'
printf 'REALTIME_UNAUTHENTICATED_HANDSHAKE=PASS\n'
printf 'REALTIME_AUTHENTICATED_HANDSHAKE=PASS\n'
printf 'REALTIME_AUTH_OK=PASS\n'
printf 'REALTIME_PING_PONG=PASS\n'
printf 'REALTIME_TOKEN_DISCLOSURE=0\n'
printf 'AUTHENTICATED_WEBSOCKET_RUNTIME=PASS\n'
