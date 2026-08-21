#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
TEMP_BODY="$(mktemp "${TMPDIR:-/tmp}/connect-ready.XXXXXX")"

cleanup() {
    /bin/rm -f "$TEMP_BODY"
}
trap cleanup EXIT

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

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

APP_USER="$(read_env_value CONNECT_LAB_POSTGRES_APP_USER)"
APP_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_APP_PASSWORD)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
MIGRATION_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
EXPECTED_FLYWAY_MIGRATION_COUNT="9"
EXPECTED_APP_READ_WRITE_TABLE_COUNT="10"

if [[ "$APP_USER" != "nexo_connect_lab_app" ]] ||
    [[ -z "$APP_PASSWORD" ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]]; then
    printf 'ERROR=DATABASE_LIFECYCLE_ENV_INVALID\n' >&2
    exit 2
fi

query_scalar() {
    compose exec -T postgres psql -X -v ON_ERROR_STOP=1 \
        -U "$MIGRATION_USER" -d "$DATABASE_NAME" -tAc "$1" | tr -d '[:space:]'
}

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9') AND success")" != "$EXPECTED_FLYWAY_MIGRATION_COUNT" ]] ||
    ! compose logs --no-color app 2>&1 | grep -F 'CONNECT_DATABASE_POOL=READY' >/dev/null; then
    printf 'ERROR=APPLICATION_FLYWAY_VALIDATION_EVIDENCE_MISSING\n' >&2
    exit 3
fi
printf 'APPLICATION_FLYWAY_VALIDATE=PASS\n'

if [[ "$(query_scalar "SELECT count(*) FROM pg_roles WHERE rolname = '${APP_USER}' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls")" != "1" ]]; then
    printf 'ERROR=POSTGRES_APP_ROLE_PRIVILEGE_MISMATCH\n' >&2
    exit 4
fi

if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'SELECT') AND has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'INSERT') AND has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'UPDATE')")" != "$EXPECTED_APP_READ_WRITE_TABLE_COUNT" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND (has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'DELETE') OR has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'TRUNCATE') OR has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'REFERENCES') OR has_table_privilege('${APP_USER}', quote_ident(table_schema) || '.' || quote_ident(table_name), 'TRIGGER'))")" != "0" ]] ||
    [[ "$(query_scalar "SELECT has_schema_privilege('${APP_USER}', 'connect', 'CREATE')")" != "f" ]] ||
    [[ "$(query_scalar "SELECT has_table_privilege('${APP_USER}', 'public.flyway_schema_history', 'SELECT')")" != "t" ]]; then
    printf 'ERROR=POSTGRES_APP_ROLE_GRANT_MISMATCH\n' >&2
    exit 5
fi

if [[ "$(compose exec -T -e PGPASSWORD="$APP_PASSWORD" postgres psql -X -h 127.0.0.1 -U "$APP_USER" -d "$DATABASE_NAME" -tAc 'SELECT 1' | tr -d '[:space:]')" != "1" ]]; then
    printf 'ERROR=POSTGRES_APP_ROLE_LOGIN_FAILED\n' >&2
    exit 6
fi
printf 'POSTGRES_APP_ROLE=PASS\n'

http_probe() {
    local path="$1"
    curl --silent --show-error --max-time 8 \
        --output "$TEMP_BODY" --write-out '%{http_code}' \
        "http://127.0.0.1:${HTTP_HOST_PORT}${path}" 2>/dev/null || true
}

if [[ "$(http_probe /health/ready)" != "200" ]] || [[ "$(tr -d '\r\n' <"$TEMP_BODY")" != "READY" ]]; then
    printf 'ERROR=INITIAL_DATABASE_READINESS_FAILED\n' >&2
    exit 7
fi
printf 'DATABASE_READY_INITIAL=PASS\n'

compose stop --timeout 20 postgres >/dev/null

OUTAGE_READY=""
for _attempt in $(seq 1 20); do
    OUTAGE_READY="$(http_probe /health/ready)"
    if [[ "$OUTAGE_READY" == "503" ]] && [[ "$(tr -d '\r\n' <"$TEMP_BODY")" == "NOT_READY" ]]; then
        break
    fi
    sleep 1
done

if [[ "$OUTAGE_READY" != "503" ]] || [[ "$(http_probe /health/live)" != "200" ]] || [[ "$(tr -d '\r\n' <"$TEMP_BODY")" != "LIVE" ]]; then
    printf 'ERROR=DATABASE_OUTAGE_HEALTH_SEMANTICS_FAILED\n' >&2
    exit 8
fi
printf 'DATABASE_OUTAGE_READINESS=PASS\n'
printf 'LIVENESS_DURING_DATABASE_OUTAGE=PASS\n'

compose start postgres >/dev/null
for _attempt in $(seq 1 40); do
    if compose exec -T postgres pg_isready -U "$MIGRATION_USER" -d "$DATABASE_NAME" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

RECOVERED_READY=""
for _attempt in $(seq 1 30); do
    RECOVERED_READY="$(http_probe /health/ready)"
    if [[ "$RECOVERED_READY" == "200" ]] && [[ "$(tr -d '\r\n' <"$TEMP_BODY")" == "READY" ]]; then
        break
    fi
    sleep 1
done

if [[ "$RECOVERED_READY" != "200" ]]; then
    printf 'ERROR=DATABASE_READINESS_RECOVERY_FAILED\n' >&2
    exit 9
fi
printf 'DATABASE_READINESS_RECOVERY=PASS\n'

compose stop --timeout 20 app >/dev/null
if ! compose logs --no-color app 2>&1 | grep -F 'CONNECT_DATABASE_POOL=CLOSED' >/dev/null; then
    printf 'ERROR=DATABASE_POOL_CLOSE_EVIDENCE_MISSING\n' >&2
    exit 10
fi
printf 'DATABASE_POOL_CLOSE=PASS\n'
printf 'DATABASE_LIFECYCLE_RUNTIME=PASS\n'
