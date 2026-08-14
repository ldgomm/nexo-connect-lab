#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

if [[ ! -f "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    printf 'ERROR=POSTGRES_REPOSITORY_LOCAL_CONTRACT_MISSING\n' >&2
    exit 2
fi

read_env_value() {
    local key="$1"
    awk -F= -v key="$key" '
        $1 == key {
            value = substr($0, index($0, "=") + 1)
            print value
            found = 1
            exit
        }
        END {
            if (!found) exit 1
        }
    ' "$ENV_FILE"
}

POSTGRES_HOST_PORT="$(read_env_value CONNECT_LAB_POSTGRES_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
POSTGRES_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_PASSWORD)"
POSTGRES_APP_USER="$(read_env_value CONNECT_LAB_POSTGRES_APP_USER)"
POSTGRES_APP_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_APP_PASSWORD)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"

if [[ ! "$POSTGRES_HOST_PORT" =~ ^[0-9]+$ ]] ||
    (( POSTGRES_HOST_PORT < 1 || POSTGRES_HOST_PORT > 65535 )); then
    printf 'ERROR=POSTGRES_REPOSITORY_HOST_PORT_INVALID\n' >&2
    exit 3
fi

if [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$POSTGRES_APP_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] ||
    [[ -z "$POSTGRES_PASSWORD" ]] ||
    [[ -z "$POSTGRES_APP_PASSWORD" ]]; then
    printf 'ERROR=POSTGRES_REPOSITORY_ENV_VALUE_INVALID\n' >&2
    exit 4
fi

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

if [[ -z "$(compose ps -q postgres)" ]]; then
    printf 'ERROR=POSTGRES_REPOSITORY_RUNTIME_NOT_STARTED\n' >&2
    exit 5
fi

if ! compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$DATABASE_NAME" >/dev/null 2>&1; then
    printf 'ERROR=POSTGRES_REPOSITORY_RUNTIME_NOT_READY\n' >&2
    exit 6
fi

(
    cd "$PROJECT_DIR"
    CONNECT_LAB_POSTGRES_APP_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT}/${DATABASE_NAME}?sslmode=disable&ApplicationName=nexo-connect-lab-b2-test" \
    CONNECT_LAB_POSTGRES_APP_USER="$POSTGRES_USER" \
    CONNECT_LAB_POSTGRES_APP_PASSWORD="$POSTGRES_PASSWORD" \
    CONNECT_LAB_POSTGRES_APP_MAX_POOL_SIZE=16 \
    CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT}/${DATABASE_NAME}?sslmode=disable&ApplicationName=nexo-connect-lab-b4-test" \
    CONNECT_LAB_B4_POSTGRES_APP_USER="$POSTGRES_APP_USER" \
    CONNECT_LAB_B4_POSTGRES_APP_PASSWORD="$POSTGRES_APP_PASSWORD" \
        ./gradlew --no-daemon postgresIntegrationTest --console=plain
)

printf 'POSTGRES_REPOSITORY_INTEGRATION=PASS\n'
printf 'POSTGRES_CONVERSATION_REPOSITORY_INTEGRATION=PASS\n'
printf 'POSTGRES_CONVERSATION_LISTING_INTEGRATION=PASS\n'
printf 'POSTGRES_MESSAGE_HISTORY_INTEGRATION=PASS\n'
printf 'POSTGRES_DURABLE_RESTART_RECOVERY_INTEGRATION=PASS\n'
