#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${PROJECT_DIR}/.env"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
RECOVERY_TEST_CLASS="com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.PostgresDurableRestartRecoveryIntegrationTest"
TEMP_BODY="$(mktemp "${TMPDIR:-/tmp}/connect-b7-ready.XXXXXX")"

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

if [[ ! -f "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    printf 'ERROR=DURABLE_RESTART_LOCAL_CONTRACT_MISSING\n' >&2
    exit 2
fi

POSTGRES_HOST_PORT="$(read_env_value CONNECT_LAB_POSTGRES_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
POSTGRES_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_PASSWORD)"
POSTGRES_APP_USER="$(read_env_value CONNECT_LAB_POSTGRES_APP_USER)"
POSTGRES_APP_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_APP_PASSWORD)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"

if [[ ! "$POSTGRES_HOST_PORT" =~ ^[0-9]+$ ]] ||
    [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] ||
    [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ "$POSTGRES_APP_USER" != "nexo_connect_lab_app" ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] ||
    [[ -z "$POSTGRES_PASSWORD" ]] ||
    [[ -z "$POSTGRES_APP_PASSWORD" ]]; then
    printf 'ERROR=DURABLE_RESTART_ENV_INVALID\n' >&2
    exit 3
fi

run_recovery_phase() {
    local phase="$1"
    (
        cd "$PROJECT_DIR"
        CONNECT_LAB_B7_RECOVERY_PHASE="$phase" \
        CONNECT_LAB_POSTGRES_APP_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT}/${DATABASE_NAME}?sslmode=disable&ApplicationName=nexo-connect-lab-b7-admin" \
        CONNECT_LAB_POSTGRES_APP_USER="$POSTGRES_USER" \
        CONNECT_LAB_POSTGRES_APP_PASSWORD="$POSTGRES_PASSWORD" \
        CONNECT_LAB_POSTGRES_APP_MAX_POOL_SIZE=8 \
        CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT}/${DATABASE_NAME}?sslmode=disable&ApplicationName=nexo-connect-lab-b7-app" \
        CONNECT_LAB_B4_POSTGRES_APP_USER="$POSTGRES_APP_USER" \
        CONNECT_LAB_B4_POSTGRES_APP_PASSWORD="$POSTGRES_APP_PASSWORD" \
            ./gradlew --no-daemon postgresIntegrationTest \
                --tests "$RECOVERY_TEST_CLASS" --rerun-tasks --console=plain
    )
}

state_snapshot() {
    compose exec -T postgres psql -X -qAt -F '|' -v ON_ERROR_STOP=1 \
        -U "$POSTGRES_USER" -d "$DATABASE_NAME" <<'SQL'
SELECT 'conversation', conversation_ref, conversation_type, platform_scope_ref,
       organization_scope_ref, business_scope_ref, status,
       extract(epoch FROM created_at), extract(epoch FROM last_activity_at),
       last_message_sequence, version, schema_version
FROM connect.conversations
WHERE conversation_ref = 'b7-conversation';
SELECT 'participant', conversation_ref, subject_ref, actor_type, status,
       array_to_string(capabilities, ','), extract(epoch FROM joined_at),
       COALESCE(extract(epoch FROM left_at)::text, '')
FROM connect.conversation_participants
WHERE conversation_ref = 'b7-conversation'
ORDER BY subject_ref COLLATE "C";
SELECT 'direct-key', platform_scope_ref, organization_scope_ref, business_scope_ref,
       business_subject_ref, client_subject_ref, conversation_ref
FROM connect.business_client_conversation_keys
WHERE conversation_ref = 'b7-conversation';
SELECT 'message', server_message_ref, conversation_ref, sequence,
       sender_subject_ref, sender_actor_type, message_type, status,
       encode(convert_to(body, 'UTF8'), 'hex'), payload_fingerprint,
       extract(epoch FROM accepted_at_server), schema_version
FROM connect.messages
WHERE conversation_ref = 'b7-conversation'
ORDER BY sequence;
SELECT 'identity', platform_scope_ref, conversation_ref, sender_subject_ref,
       idempotency_key, client_message_ref, payload_fingerprint,
       server_message_ref, sequence
FROM connect.message_identities
WHERE conversation_ref = 'b7-conversation'
ORDER BY sequence;
SQL
}

state_hash() {
    state_snapshot | shasum -a 256 | awk '{print $1}'
}

http_probe() {
    local path="$1"
    curl --silent --show-error --max-time 8 \
        --output "$TEMP_BODY" --write-out '%{http_code}' \
        "http://127.0.0.1:${HTTP_HOST_PORT}${path}" 2>/dev/null || true
}

old_app_id="$(compose ps -q app)"
old_postgres_id="$(compose ps -q postgres)"
if [[ -z "$old_app_id" || -z "$old_postgres_id" ]]; then
    printf 'ERROR=DURABLE_RESTART_RUNTIME_NOT_STARTED\n' >&2
    exit 4
fi

postgres_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$old_postgres_id")"
if [[ -z "$postgres_volume" ]]; then
    printf 'ERROR=DURABLE_RESTART_POSTGRES_VOLUME_NOT_FOUND\n' >&2
    exit 5
fi

run_recovery_phase SEED
printf 'DURABLE_RECOVERY_SEED=PASS\n'
before_hash="$(state_hash)"

compose stop --timeout 30 app postgres >/dev/null
compose rm --force app postgres >/dev/null

if docker inspect "$old_app_id" >/dev/null 2>&1 || docker inspect "$old_postgres_id" >/dev/null 2>&1; then
    printf 'ERROR=DURABLE_RESTART_OLD_CONTAINER_SURVIVED\n' >&2
    exit 6
fi

compose up -d postgres >/dev/null
for _attempt in $(seq 1 60); do
    if compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$DATABASE_NAME" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$DATABASE_NAME" >/dev/null 2>&1; then
    printf 'ERROR=DURABLE_RESTART_POSTGRES_NOT_READY\n' >&2
    exit 7
fi

new_postgres_id="$(compose ps -q postgres)"
if [[ -z "$new_postgres_id" ]]; then
    printf 'ERROR=POSTGRES_CONTAINER_NOT_RECREATED\n' >&2
    exit 8
fi
new_postgres_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$new_postgres_id")"
if [[ "$new_postgres_id" == "$old_postgres_id" ]]; then
    printf 'ERROR=POSTGRES_CONTAINER_NOT_RECREATED\n' >&2
    exit 8
fi
if [[ "$new_postgres_volume" != "$postgres_volume" ]] || ! docker volume inspect "$postgres_volume" >/dev/null 2>&1; then
    printf 'ERROR=POSTGRES_VOLUME_IDENTITY_CHANGED\n' >&2
    exit 9
fi
printf 'POSTGRES_CONTAINER_RECREATION=PASS\n'
printf 'POSTGRES_VOLUME_PRESERVED=PASS\n'

compose run --rm flyway validate >/dev/null
printf 'FLYWAY_AFTER_RESTART=PASS\n'

compose up -d --no-deps app >/dev/null
ready_code=""
for _attempt in $(seq 1 60); do
    ready_code="$(http_probe /health/ready)"
    if [[ "$ready_code" == "200" ]] && [[ "$(tr -d '\r\n' <"$TEMP_BODY")" == "READY" ]]; then
        break
    fi
    sleep 1
done

new_app_id="$(compose ps -q app)"
if [[ -z "$new_app_id" || "$new_app_id" == "$old_app_id" ]]; then
    printf 'ERROR=APPLICATION_CONTAINER_NOT_RECREATED\n' >&2
    exit 10
fi
if [[ "$ready_code" != "200" ]] || [[ "$(tr -d '\r\n' <"$TEMP_BODY")" != "READY" ]]; then
    printf 'ERROR=READINESS_AFTER_DURABLE_RESTART_FAILED\n' >&2
    exit 11
fi
if ! compose logs --no-color app 2>&1 | grep -F 'CONNECT_DATABASE_POOL=READY' >/dev/null; then
    printf 'ERROR=APPLICATION_POOL_AFTER_DURABLE_RESTART_NOT_READY\n' >&2
    exit 11
fi
printf 'APPLICATION_CONTAINER_RECREATION=PASS\n'
printf 'READINESS_AFTER_RESTART=PASS\n'

after_hash="$(state_hash)"
if [[ "$after_hash" != "$before_hash" ]]; then
    printf 'ERROR=DURABLE_STATE_CHANGED_ACROSS_RESTART\n' >&2
    exit 12
fi
printf 'DURABLE_STATE_HASH_PRESERVED=PASS\n'

run_recovery_phase VERIFY
printf 'DURABLE_RECOVERY_VERIFY=PASS\n'
printf 'IDEMPOTENT_REPLAY_AFTER_RESTART=PASS\n'
printf 'SEQUENCE_CONTINUITY_AFTER_RESTART=PASS\n'
printf 'LISTING_AND_HISTORY_CURSOR_RECOVERY=PASS\n'
printf 'DURABLE_RESTART_RECOVERY=PASS\n'
