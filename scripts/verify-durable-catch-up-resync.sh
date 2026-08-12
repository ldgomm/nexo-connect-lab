#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${PROJECT_DIR}/.env"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

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

if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    printf 'ERROR=DURABLE_CATCH_UP_LOCAL_CONTRACT_MISSING\n' >&2
    exit 2
fi

HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
POSTGRES_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_PASSWORD)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
BUSINESS_TOKEN="$(read_env_value CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN)"

if [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] ||
    [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] ||
    [[ -z "$POSTGRES_PASSWORD" ]] ||
    [[ ${#BUSINESS_TOKEN} -lt 32 ]]; then
    printf 'ERROR=DURABLE_CATCH_UP_ENV_INVALID\n' >&2
    exit 3
fi

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

admin_psql() {
    compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
        psql --set ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$DATABASE_NAME" "$@"
}

cleanup_seed() {
    admin_psql >/dev/null <<'SQL'
DELETE FROM connect.message_identities WHERE conversation_ref = 'c4-catch-up-conversation';
DELETE FROM connect.messages WHERE conversation_ref = 'c4-catch-up-conversation';
DELETE FROM connect.business_client_conversation_keys WHERE conversation_ref = 'c4-catch-up-conversation';
DELETE FROM connect.conversation_participants WHERE conversation_ref = 'c4-catch-up-conversation';
DELETE FROM connect.conversations WHERE conversation_ref = 'c4-catch-up-conversation';
SQL
}

finish() {
    local status=$?
    trap - EXIT
    set +e
    if [[ -n "$(compose ps -q postgres 2>/dev/null)" ]]; then
        cleanup_seed
        if [[ $? -ne 0 ]]; then
            printf 'DURABLE_CATCH_UP_SEED_CLEANUP=FAIL\n' >&2
            status=1
        fi
    fi
    unset POSTGRES_PASSWORD BUSINESS_TOKEN
    exit "$status"
}

trap finish EXIT

if [[ -z "$(compose ps -q app)" || -z "$(compose ps -q postgres)" ]]; then
    printf 'ERROR=DURABLE_CATCH_UP_RUNTIME_NOT_STARTED\n' >&2
    exit 4
fi

cleanup_seed
admin_psql >/dev/null <<'SQL'
INSERT INTO connect.conversations (
    conversation_ref, conversation_type, platform_scope_ref,
    organization_scope_ref, business_scope_ref, status,
    created_at, last_activity_at, last_message_sequence, version, schema_version
) VALUES (
    'c4-catch-up-conversation', 'BUSINESS_CLIENT', 'synthetic-platform-c1',
    'synthetic-organization-c1', 'synthetic-business-scope-c1', 'ACTIVE',
    '2026-08-12T10:15:00Z', '2026-08-12T10:15:00Z', 0, 0, 1
);

INSERT INTO connect.conversation_participants (
    conversation_ref, subject_ref, actor_type, status, capabilities, joined_at, left_at
) VALUES
    ('c4-catch-up-conversation', 'synthetic-business-c1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT']::text[], '2026-08-12T10:15:00Z', NULL),
    ('c4-catch-up-conversation', 'synthetic-client-c1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT']::text[], '2026-08-12T10:15:00Z', NULL);

INSERT INTO connect.business_client_conversation_keys (
    platform_scope_ref, organization_scope_ref, business_scope_ref,
    business_subject_ref, client_subject_ref, conversation_ref
) VALUES (
    'synthetic-platform-c1', 'synthetic-organization-c1', 'synthetic-business-scope-c1',
    'synthetic-business-c1', 'synthetic-client-c1', 'c4-catch-up-conversation'
);
SQL
printf 'DURABLE_CATCH_UP_SEED=PASS\n'

(
    cd "$PROJECT_DIR"
    CONNECT_LAB_C4_RUNTIME_URL="ws://127.0.0.1:${HTTP_HOST_PORT}/v1/realtime" \
    CONNECT_LAB_C4_RUNTIME_HTTP_URL="http://127.0.0.1:${HTTP_HOST_PORT}" \
    CONNECT_LAB_C4_RUNTIME_BUSINESS_TOKEN="$BUSINESS_TOKEN" \
    CONNECT_LAB_C4_CONVERSATION_REF="c4-catch-up-conversation" \
        ./gradlew --no-daemon test \
            --tests 'com.premierdarkcoffee.nexo.connect.lab.backend.routes.AuthenticatedRealtimeCatchUpRuntimeTest' \
            --rerun-tasks --console=plain
)

message_count="$(admin_psql --tuples-only --no-align --command \
    "SELECT count(*) FROM connect.messages WHERE conversation_ref = 'c4-catch-up-conversation';")"
identity_count="$(admin_psql --tuples-only --no-align --command \
    "SELECT count(*) FROM connect.message_identities WHERE conversation_ref = 'c4-catch-up-conversation';")"
last_sequence="$(admin_psql --tuples-only --no-align --command \
    "SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'c4-catch-up-conversation';")"

if [[ "$message_count" != "4" || "$identity_count" != "4" || "$last_sequence" != "4" ]]; then
    printf 'ERROR=DURABLE_CATCH_UP_POSTGRES_ORACLE_MISMATCH\n' >&2
    exit 5
fi

printf 'DURABLE_CATCH_UP_EXCLUSIVE_CURSOR=PASS\n'
printf 'DURABLE_CATCH_UP_ASCENDING_ORDER=PASS\n'
printf 'DURABLE_RESYNC_FROM_ZERO=PASS\n'
printf 'DURABLE_CURRENT_CURSOR_DUPLICATE=0\n'
printf 'DURABLE_LIVE_CONTINUATION=PASS\n'
printf 'DURABLE_FUTURE_CURSOR_REJECTION=PASS\n'
printf 'DURABLE_CATCH_UP_TOKEN_DISCLOSURE=0\n'
printf 'DURABLE_CATCH_UP_RESYNC_RUNTIME=PASS\n'
