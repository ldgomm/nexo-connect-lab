#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

if [[ ! -f "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_LOCAL_CONTRACT_MISSING\n' >&2
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

POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
POSTGRES_APP_USER="$(read_env_value CONNECT_LAB_POSTGRES_APP_USER)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"

if [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$POSTGRES_APP_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_ENV_VALUE_INVALID\n' >&2
    exit 3
fi

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

if [[ -z "$(compose ps -q postgres)" ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_RUNTIME_NOT_STARTED\n' >&2
    exit 4
fi

compose --profile migration run --rm --no-deps flyway migrate
printf 'FLYWAY_MIGRATE=PASS\n'

compose --profile migration run --rm --no-deps flyway validate
printf 'FLYWAY_VALIDATE=PASS\n'

query_scalar() {
    compose exec -T postgres psql -X -v ON_ERROR_STOP=1 \
        -U "$POSTGRES_USER" -d "$DATABASE_NAME" -tAc "$1" | tr -d '[:space:]'
}

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '1' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_ONE_MISSING\n' >&2
    exit 5
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '3' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_THREE_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '4' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_FOUR_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '6' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_SIX_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '7' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_SEVEN_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '8' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_EIGHT_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM public.flyway_schema_history WHERE version = '9' AND success")" != "1" ]]; then
    printf 'ERROR=FLYWAY_HISTORY_VERSION_NINE_MISSING\n' >&2
    exit 6
fi

if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND table_name IN ('conversations','conversation_participants','messages','message_identities','business_client_conversation_keys')")" != "5" ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_TABLE_SET_MISMATCH\n' >&2
    exit 7
fi

if [[ "$(query_scalar "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'connect' AND c.conname IN ('pk_connect_conversations','pk_connect_conversation_participants','pk_connect_messages','uq_connect_message_conversation_sequence','uq_connect_identity_idempotency','uq_connect_identity_client_message','pk_connect_message_identities','fk_connect_message_sender_participant','fk_connect_identity_message','uq_connect_conversation_full_scope','pk_connect_business_client_conversation_keys','uq_connect_business_client_conversation_ref','fk_connect_business_client_conversation_scope','fk_connect_business_client_business_participant','fk_connect_business_client_client_participant','ck_connect_conversation_activity_time')")" != "16" ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_B5_CONSTRAINT_SET_MISMATCH\n' >&2
    exit 8
fi

if [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'conversations' AND column_name = 'last_activity_at' AND is_nullable = 'NO' AND data_type = 'timestamp with time zone'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname IN ('ix_connect_conversation_activity_listing','ix_connect_participant_listing')")" != "2" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname = 'ix_connect_conversation_activity_listing' AND indexdef LIKE '%COLLATE \"C\" DESC%'")" != "1" ]]; then
    printf 'ERROR=POSTGRES_SCHEMA_B5_LISTING_INDEX_SET_MISMATCH\n' >&2
    exit 8
fi

if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND table_name = 'push_device_registrations'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'push_device_registrations'")" != "21" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'connect' AND c.conname IN ('uq_connect_push_registration_owner','ck_connect_push_registration_ref','ck_connect_push_platform_scope_ref','ck_connect_push_organization_scope_ref','ck_connect_push_business_scope_ref','ck_connect_push_subject_ref','ck_connect_push_actor_type','ck_connect_push_scope_shape','ck_connect_push_application','ck_connect_push_application_owner','ck_connect_push_provider','ck_connect_push_environment','ck_connect_push_device_fingerprint','ck_connect_push_token_fingerprint','ck_connect_push_token_material','ck_connect_push_token_version','ck_connect_push_version','ck_connect_push_timestamps')")" != "18" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname IN ('uq_connect_push_active_device_binding','uq_connect_push_active_token_fingerprint','ix_connect_push_owner_active')")" != "3" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name = 'push_device_registrations' AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('SELECT','INSERT','UPDATE')")" != "3" ]]; then
    printf 'ERROR=POSTGRES_PUSH_DEVICE_REGISTRY_SCHEMA_MISMATCH\n' >&2
    exit 8
fi
if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND table_name = 'notification_outbox'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'notification_outbox'")" != "27" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'connect' AND c.conname IN ('uq_connect_notification_message_target','fk_connect_notification_conversation_scope','fk_connect_notification_message','fk_connect_notification_recipient','fk_connect_notification_registration','ck_connect_notification_intent_ref','ck_connect_notification_platform_scope_ref','ck_connect_notification_organization_scope_ref','ck_connect_notification_business_scope_ref','ck_connect_notification_conversation_ref','ck_connect_notification_message_ref','ck_connect_notification_recipient_subject_ref','ck_connect_notification_recipient_actor_type','ck_connect_notification_scope_shape','ck_connect_notification_registration_ref','ck_connect_notification_application','ck_connect_notification_application_owner','ck_connect_notification_provider','ck_connect_notification_environment','ck_connect_notification_type','ck_connect_notification_status','ck_connect_notification_attempts','ck_connect_notification_lease_owner','ck_connect_notification_error_code','ck_connect_notification_state_shape','ck_connect_notification_timestamps','ck_connect_notification_version','ck_connect_notification_presentation_mode','ck_connect_notification_badge_mode')")" != "29" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname IN ('ix_connect_notification_claimable','ix_connect_notification_expired_lease','ix_connect_notification_message','ix_connect_notification_recipient')")" != "4" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name = 'notification_outbox' AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('SELECT','INSERT','UPDATE')")" != "3" ]]; then
    printf 'ERROR=POSTGRES_NOTIFICATION_OUTBOX_SCHEMA_MISMATCH\n' >&2
    exit 8
fi
if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND table_name = 'push_notification_preferences'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'push_notification_preferences'")" != "11" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'connect' AND c.conname IN ('pk_connect_push_notification_preferences','fk_connect_push_preference_conversation_scope','fk_connect_push_preference_participant','fk_connect_push_preference_registration_owner','ck_connect_push_preference_conversation_ref','ck_connect_push_preference_registration_ref','ck_connect_push_preference_platform_scope_ref','ck_connect_push_preference_subject_ref','ck_connect_push_preference_actor_type','ck_connect_push_preference_lock_screen_privacy','ck_connect_push_preference_badge_mode','ck_connect_push_preference_quiet_mode','ck_connect_push_preference_version')")" != "13" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname = 'ix_connect_push_notification_preference_owner'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name = 'push_notification_preferences' AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('SELECT','INSERT','UPDATE')")" != "3" ]]; then
    printf 'ERROR=POSTGRES_PUSH_NOTIFICATION_PREFERENCE_SCHEMA_MISMATCH\n' >&2
    exit 8
fi
if [[ "$(query_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'connect' AND table_name IN ('conversation_blocks','conversation_block_audit_events','notification_mute_audit_events')")" != "3" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'conversation_blocks'")" != "15" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'conversation_block_audit_events'")" != "14" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'connect' AND table_name = 'notification_mute_audit_events'")" != "9" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'connect' AND c.conname IN ('pk_connect_conversation_blocks','uq_connect_conversation_block_direction','uq_connect_conversation_block_scope','fk_connect_block_conversation_scope','fk_connect_block_blocker','fk_connect_block_blocked','ck_connect_block_ref','ck_connect_block_scope_type','ck_connect_block_conversation_ref','ck_connect_block_platform_scope','ck_connect_block_organization_scope','ck_connect_block_business_scope','ck_connect_block_subject_refs','ck_connect_block_actor_types','ck_connect_block_distinct_participants','ck_connect_block_status','ck_connect_block_version','ck_connect_block_timestamps','pk_connect_block_audit_events','uq_connect_block_audit_version','fk_connect_block_audit_scope','fk_connect_block_audit_blocker','fk_connect_block_audit_blocked','ck_connect_block_audit_ref','ck_connect_block_audit_scope_type','ck_connect_block_audit_action','ck_connect_block_audit_version','pk_connect_mute_audit_events','uq_connect_mute_audit_version','fk_connect_mute_audit_preference','fk_connect_mute_audit_owner','fk_connect_mute_audit_participant','ck_connect_mute_audit_ref','ck_connect_mute_audit_actor_type','ck_connect_mute_audit_action','ck_connect_mute_audit_version')")" != "36" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connect' AND indexname = 'ix_connect_conversation_blocks_authorization'")" != "1" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name = 'conversation_blocks' AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('SELECT','INSERT','UPDATE')")" != "3" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name IN ('conversation_block_audit_events','notification_mute_audit_events') AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('SELECT','INSERT')")" != "4" ]] ||
    [[ "$(query_scalar "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'connect' AND table_name IN ('conversation_block_audit_events','notification_mute_audit_events') AND grantee = '${POSTGRES_APP_USER}' AND privilege_type IN ('UPDATE','DELETE','TRUNCATE')")" != "0" ]]; then
    printf 'ERROR=POSTGRES_CONVERSATION_SAFETY_SCHEMA_MISMATCH\n' >&2
    exit 8
fi
printf 'POSTGRES_SCHEMA_OBJECTS=PASS\n'
printf 'POSTGRES_PUSH_DEVICE_REGISTRY_SCHEMA=PASS\n'
printf 'POSTGRES_NOTIFICATION_OUTBOX_SCHEMA=PASS\n'
printf 'POSTGRES_PUSH_NOTIFICATION_PREFERENCE_SCHEMA=PASS\n'
printf 'POSTGRES_CONVERSATION_SAFETY_SCHEMA=PASS\n'

compose exec -T postgres psql -X -v ON_ERROR_STOP=1 \
    -U "$POSTGRES_USER" -d "$DATABASE_NAME" <<'SQL'
BEGIN;

INSERT INTO connect.conversations (
    conversation_ref, conversation_type, platform_scope_ref,
    organization_scope_ref, business_scope_ref, status,
    created_at, last_activity_at, last_message_sequence, version, schema_version
) VALUES (
    'conversation-1', 'BUSINESS_CLIENT', 'platform-1',
    'organization-1', 'business-1', 'ACTIVE',
    '2026-08-11T20:00:00Z', '2026-08-11T20:00:02Z', 2, 2, 1
);

INSERT INTO connect.conversation_participants (
    conversation_ref, subject_ref, actor_type, status,
    capabilities, joined_at, left_at
) VALUES
    ('conversation-1', 'business-subject-1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT'], '2026-08-11T20:00:00Z', NULL),
    ('conversation-1', 'client-subject-1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], '2026-08-11T20:00:00Z', NULL),
    ('conversation-1', 'client-subject-2', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], '2026-08-11T20:00:00Z', NULL);

INSERT INTO connect.business_client_conversation_keys (
    platform_scope_ref, organization_scope_ref, business_scope_ref,
    business_subject_ref, business_actor_type,
    client_subject_ref, client_actor_type,
    conversation_ref
) VALUES (
    'platform-1', 'organization-1', 'business-1',
    'business-subject-1', 'BUSINESS',
    'client-subject-1', 'CLIENT',
    'conversation-1'
);

INSERT INTO connect.conversations (
    conversation_ref, conversation_type, platform_scope_ref,
    organization_scope_ref, business_scope_ref, status,
    created_at, last_activity_at, last_message_sequence, version, schema_version
) VALUES (
    'conversation-2', 'BUSINESS_CLIENT', 'platform-1',
    'organization-1', 'business-1', 'ACTIVE',
    '2026-08-11T20:00:00Z', '2026-08-11T20:00:00Z', 0, 0, 1
);

INSERT INTO connect.conversation_participants (
    conversation_ref, subject_ref, actor_type, status,
    capabilities, joined_at, left_at
) VALUES
    ('conversation-2', 'business-subject-1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT'], '2026-08-11T20:00:00Z', NULL),
    ('conversation-2', 'client-subject-1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], '2026-08-11T20:00:00Z', NULL);

INSERT INTO connect.messages (
    server_message_ref, conversation_ref, sequence,
    sender_subject_ref, sender_actor_type, message_type,
    status, body, payload_fingerprint, accepted_at_server, schema_version
) VALUES
    ('server-message-1', 'conversation-1', 1,
     'business-subject-1', 'BUSINESS', 'TEXT',
     'PERSISTED', 'Hello', 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     '2026-08-11T20:00:01Z', 1),
    ('server-message-2', 'conversation-1', 2,
     'business-subject-1', 'BUSINESS', 'TEXT',
     'PERSISTED', 'Again', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
     '2026-08-11T20:00:02Z', 1);

INSERT INTO connect.message_identities (
    server_message_ref, platform_scope_ref, conversation_ref,
    sender_subject_ref, idempotency_key, client_message_ref,
    payload_fingerprint, sequence
) VALUES (
    'server-message-1', 'platform-1', 'conversation-1',
    'business-subject-1', 'idempotency-key-1', 'client-message-1',
    'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1
);

DO $probe$
DECLARE actual_constraint text;
BEGIN
    BEGIN
        INSERT INTO connect.business_client_conversation_keys (
            platform_scope_ref, organization_scope_ref, business_scope_ref,
            business_subject_ref, business_actor_type,
            client_subject_ref, client_actor_type,
            conversation_ref
        ) VALUES (
            'platform-1', 'organization-1', 'business-1',
            'business-subject-1', 'BUSINESS',
            'client-subject-1', 'CLIENT',
            'conversation-2'
        );
        RAISE EXCEPTION 'duplicate direct participant pair was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'pk_connect_business_client_conversation_keys' THEN
            RAISE EXCEPTION 'unexpected direct participant pair constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.business_client_conversation_keys (
            platform_scope_ref, organization_scope_ref, business_scope_ref,
            business_subject_ref, business_actor_type,
            client_subject_ref, client_actor_type,
            conversation_ref
        ) VALUES (
            'platform-1', 'organization-1', 'business-1',
            'business-subject-1', 'BUSINESS',
            'client-subject-2', 'CLIENT',
            'conversation-1'
        );
        RAISE EXCEPTION 'duplicate direct conversation ref was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'uq_connect_business_client_conversation_ref' THEN
            RAISE EXCEPTION 'unexpected direct conversation ref constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.business_client_conversation_keys (
            platform_scope_ref, organization_scope_ref, business_scope_ref,
            business_subject_ref, business_actor_type,
            client_subject_ref, client_actor_type,
            conversation_ref
        ) VALUES (
            'platform-1', 'organization-1', 'wrong-business',
            'business-subject-1', 'BUSINESS',
            'client-subject-1', 'CLIENT',
            'conversation-2'
        );
        RAISE EXCEPTION 'wrong direct conversation scope was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'fk_connect_business_client_conversation_scope' THEN
            RAISE EXCEPTION 'unexpected direct conversation scope constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.messages (
            server_message_ref, conversation_ref, sequence,
            sender_subject_ref, sender_actor_type, message_type,
            status, body, payload_fingerprint, accepted_at_server, schema_version
        ) VALUES (
            'server-message-duplicate-sequence', 'conversation-1', 1,
            'business-subject-1', 'BUSINESS', 'TEXT',
            'PERSISTED', 'Duplicate', 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
            '2026-08-11T20:00:03Z', 1
        );
        RAISE EXCEPTION 'duplicate sequence was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'uq_connect_message_conversation_sequence' THEN
            RAISE EXCEPTION 'unexpected duplicate sequence constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.message_identities (
            server_message_ref, platform_scope_ref, conversation_ref,
            sender_subject_ref, idempotency_key, client_message_ref,
            payload_fingerprint, sequence
        ) VALUES (
            'server-message-2', 'platform-1', 'conversation-1',
            'business-subject-1', 'idempotency-key-1', 'client-message-2',
            'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 2
        );
        RAISE EXCEPTION 'duplicate idempotency key was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'uq_connect_identity_idempotency' THEN
            RAISE EXCEPTION 'unexpected idempotency constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.message_identities (
            server_message_ref, platform_scope_ref, conversation_ref,
            sender_subject_ref, idempotency_key, client_message_ref,
            payload_fingerprint, sequence
        ) VALUES (
            'server-message-2', 'platform-1', 'conversation-1',
            'business-subject-1', 'idempotency-key-2', 'client-message-1',
            'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 2
        );
        RAISE EXCEPTION 'duplicate clientMessageRef was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'uq_connect_identity_client_message' THEN
            RAISE EXCEPTION 'unexpected client message constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.message_identities (
            server_message_ref, platform_scope_ref, conversation_ref,
            sender_subject_ref, idempotency_key, client_message_ref,
            payload_fingerprint, sequence
        ) VALUES (
            'server-message-1', 'platform-1', 'conversation-1',
            'business-subject-1', 'idempotency-key-3', 'client-message-3',
            'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1
        );
        RAISE EXCEPTION 'second identity binding was accepted';
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'pk_connect_message_identities' THEN
            RAISE EXCEPTION 'unexpected one-to-one constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.messages (
            server_message_ref, conversation_ref, sequence,
            sender_subject_ref, sender_actor_type, message_type,
            status, body, payload_fingerprint, accepted_at_server, schema_version
        ) VALUES (
            'server-message-unknown-sender', 'conversation-1', 3,
            'unknown-subject', 'BUSINESS', 'TEXT',
            'PERSISTED', 'Unknown', 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
            '2026-08-11T20:00:03Z', 1
        );
        RAISE EXCEPTION 'unknown sender was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'fk_connect_message_sender_participant' THEN
            RAISE EXCEPTION 'unexpected sender foreign key: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.message_identities (
            server_message_ref, platform_scope_ref, conversation_ref,
            sender_subject_ref, idempotency_key, client_message_ref,
            payload_fingerprint, sequence
        ) VALUES (
            'server-message-2', 'wrong-platform', 'conversation-1',
            'business-subject-1', 'idempotency-key-scope', 'client-message-scope',
            'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 2
        );
        RAISE EXCEPTION 'wrong platform scope was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'fk_connect_identity_conversation_scope' THEN
            RAISE EXCEPTION 'unexpected scope foreign key: %', actual_constraint;
        END IF;
    END;

    BEGIN
        UPDATE connect.conversations
        SET last_activity_at = created_at - INTERVAL '1 second'
        WHERE conversation_ref = 'conversation-1';
        RAISE EXCEPTION 'activity before creation was accepted';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'ck_connect_conversation_activity_time' THEN
            RAISE EXCEPTION 'unexpected activity time constraint: %', actual_constraint;
        END IF;
    END;

    BEGIN
        INSERT INTO connect.conversations (
            conversation_ref, conversation_type, platform_scope_ref,
            organization_scope_ref, business_scope_ref, status,
            created_at, last_activity_at, last_message_sequence, version, schema_version
        ) VALUES (
            repeat('é', 129), 'BUSINESS_CLIENT', 'platform-limit',
            'organization-limit', 'business-limit', 'ACTIVE',
            '2026-08-11T20:00:00Z', '2026-08-11T20:00:00Z', 0, 0, 1
        );
        RAISE EXCEPTION 'oversized UTF-8 reference was accepted';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint <> 'ck_connect_conversation_ref' THEN
            RAISE EXCEPTION 'unexpected UTF-8 limit constraint: %', actual_constraint;
        END IF;
    END;
END
$probe$;

ROLLBACK;
SQL

printf 'POSTGRES_CONSTRAINT_PROBES=PASS\n'
