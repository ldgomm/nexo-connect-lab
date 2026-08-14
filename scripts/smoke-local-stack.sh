#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

if [[ ! -f "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    printf 'ERROR=LOCAL_STACK_CONTRACT_MISSING\n' >&2
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

HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
MEDIA_BUCKET="$(read_env_value CONNECT_LAB_MEDIA_BUCKET)"

if [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] || \
    [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] || \
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] || \
    [[ ! "$MEDIA_BUCKET" =~ ^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]]; then
    printf 'ERROR=LOCAL_STACK_ENV_VALUE_INVALID\n' >&2
    exit 3
fi

for flag in \
    CONNECT_LAB_NEXO_INTEGRATION_ENABLED \
    CONNECT_LAB_NEXO_DB_DIRECT_ACCESS \
    CONNECT_LAB_CALLS_ENABLED \
    CONNECT_LAB_E2EE_CLAIM; do
    if [[ "$(read_env_value "$flag")" != "false" ]]; then
        printf 'ERROR=FORBIDDEN_CONNECT_ZERO_FLAG\n' >&2
        exit 4
    fi
done

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

if [[ "$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$DATABASE_NAME" -tAc 'SELECT current_database();' | tr -d '[:space:]')" != "$DATABASE_NAME" ]]; then
    printf 'POSTGRESQL_SMOKE=FAIL\n' >&2
    printf 'ERROR=POSTGRES_SMOKE_FAILED\n' >&2
    exit 5
fi
printf 'POSTGRESQL_SMOKE=PASS\n'

if [[ "$(compose exec -T redis sh -ec 'REDISCLI_AUTH="$CONNECT_LAB_REDIS_APP_PASSWORD" redis-cli --no-auth-warning --user "$CONNECT_LAB_REDIS_APP_USER" ping' | tr -d '[:space:]')" != "PONG" ]]; then
    printf 'REDIS_SMOKE=FAIL\n' >&2
    printf 'ERROR=REDIS_SMOKE_FAILED\n' >&2
    exit 6
fi
printf 'REDIS_SMOKE=PASS\n'

REDIS_BOUNDARY_READY=""
for _attempt in $(seq 1 30); do
    REDIS_BOUNDARY_READY="$(curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${HTTP_HOST_PORT}/health/ready/ephemeral-redis" 2>&1 || true)"
    REDIS_BOUNDARY_READY="$(printf '%s' "$REDIS_BOUNDARY_READY" | tr -d '\r\n')"
    if [[ "$REDIS_BOUNDARY_READY" == "REDIS_READY" ]]; then
        break
    fi
    sleep 1
done
if [[ "$REDIS_BOUNDARY_READY" != "REDIS_READY" ]]; then
    printf 'REDIS_BOUNDARY_READINESS=FAIL\n' >&2
    printf 'ERROR=REDIS_BOUNDARY_READINESS_FAILED\n' >&2
    exit 6
fi
printf 'REDIS_BOUNDARY_READINESS=PASS\n'

if ! compose --profile setup run --rm --no-deps minio-init ready local >/dev/null; then
    printf 'MINIO_SMOKE=FAIL\n' >&2
    printf 'ERROR=MINIO_CLIENT_READY_FAILED\n' >&2
    exit 7
fi

if ! compose --profile setup run --rm --no-deps minio-init mb --ignore-existing "local/${MEDIA_BUCKET}" >/dev/null; then
    printf 'MINIO_SMOKE=FAIL\n' >&2
    printf 'ERROR=MINIO_BUCKET_CREATE_FAILED\n' >&2
    exit 8
fi

if ! compose --profile setup run --rm --no-deps minio-init stat "local/${MEDIA_BUCKET}" >/dev/null; then
    printf 'MINIO_SMOKE=FAIL\n' >&2
    printf 'ERROR=MINIO_BUCKET_STAT_FAILED\n' >&2
    exit 9
fi
printf 'MINIO_SMOKE=PASS\n'

if ! compose --profile setup run --rm --no-deps minio-init anonymous set none "local/${MEDIA_BUCKET}" >/dev/null; then
    printf 'MINIO_BUCKET_PRIVATE=FAIL\n' >&2
    printf 'ERROR=MINIO_PRIVATE_POLICY_SET_FAILED\n' >&2
    exit 10
fi

MINIO_POLICY="$(compose --profile setup run --rm --no-deps minio-init anonymous get "local/${MEDIA_BUCKET}" 2>/dev/null || true)"
case "$MINIO_POLICY" in
    *private*|*none*)
        printf 'MINIO_BUCKET_PRIVATE=PASS\n'
        ;;
    *)
        printf 'MINIO_BUCKET_PRIVATE=FAIL\n' >&2
        printf 'ERROR=MINIO_PRIVATE_POLICY_VERIFY_FAILED\n' >&2
        exit 11
        ;;
esac

INTERNAL_LIVE=""
INTERNAL_READY=""
for _attempt in $(seq 1 30); do
    INTERNAL_LIVE="$(compose exec -T minio curl --fail --silent --show-error --max-time 2 http://app:8282/health/live 2>&1 || true)"
    INTERNAL_READY="$(compose exec -T minio curl --fail --silent --show-error --max-time 2 http://app:8282/health/ready 2>&1 || true)"
    INTERNAL_LIVE="$(printf '%s' "$INTERNAL_LIVE" | tr -d '\r\n')"
    INTERNAL_READY="$(printf '%s' "$INTERNAL_READY" | tr -d '\r\n')"
    if [[ "$INTERNAL_LIVE" == "LIVE" && "$INTERNAL_READY" == "READY" ]]; then
        break
    fi
    sleep 1
done

if [[ "$INTERNAL_LIVE" != "LIVE" ]]; then
    printf 'APP_INTERNAL_HTTP=FAIL\n' >&2
    printf 'LIVENESS=FAIL\n' >&2
    printf 'APP_INTERNAL_LIVE_RESPONSE=%q\n' "$INTERNAL_LIVE" >&2
    printf 'ERROR=APP_INTERNAL_LIVENESS_FAILED\n' >&2
    exit 12
fi

if [[ "$INTERNAL_READY" != "READY" ]]; then
    printf 'APP_INTERNAL_HTTP=FAIL\n' >&2
    printf 'LIVENESS=PASS\n'
    printf 'READINESS=FAIL\n' >&2
    printf 'APP_INTERNAL_READY_RESPONSE=%q\n' "$INTERNAL_READY" >&2
    printf 'ERROR=APP_INTERNAL_READINESS_FAILED\n' >&2
    exit 13
fi
printf 'APP_INTERNAL_HTTP=PASS\n'

HOST_LIVE=""
HOST_READY=""
for _attempt in $(seq 1 30); do
    HOST_LIVE="$(curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${HTTP_HOST_PORT}/health/live" 2>&1 || true)"
    HOST_READY="$(curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${HTTP_HOST_PORT}/health/ready" 2>&1 || true)"
    HOST_LIVE="$(printf '%s' "$HOST_LIVE" | tr -d '\r\n')"
    HOST_READY="$(printf '%s' "$HOST_READY" | tr -d '\r\n')"
    if [[ "$HOST_LIVE" == "LIVE" && "$HOST_READY" == "READY" ]]; then
        break
    fi
    sleep 1
done

if [[ "$HOST_LIVE" != "LIVE" ]]; then
    printf 'APP_HOST_HTTP=FAIL\n' >&2
    printf 'LIVENESS=FAIL\n' >&2
    printf 'APP_HOST_LIVE_RESPONSE=%q\n' "$HOST_LIVE" >&2
    printf 'ERROR=APP_HOST_LIVENESS_FAILED\n' >&2
    exit 14
fi

if [[ "$HOST_READY" != "READY" ]]; then
    printf 'APP_HOST_HTTP=FAIL\n' >&2
    printf 'LIVENESS=PASS\n'
    printf 'READINESS=FAIL\n' >&2
    printf 'APP_HOST_READY_RESPONSE=%q\n' "$HOST_READY" >&2
    printf 'ERROR=APP_HOST_READINESS_FAILED\n' >&2
    exit 15
fi
printf 'APP_HOST_HTTP=PASS\n'
printf 'LIVENESS=PASS\n'
printf 'READINESS=PASS\n'

printf 'STACK_SMOKE=PASS\n'
