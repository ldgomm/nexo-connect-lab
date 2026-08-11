CREATE SCHEMA connect;

CREATE TABLE connect.conversations (
    conversation_ref text NOT NULL,
    conversation_type text NOT NULL,
    platform_scope_ref text NOT NULL,
    organization_scope_ref text NOT NULL,
    business_scope_ref text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    last_message_sequence bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    schema_version integer NOT NULL DEFAULT 1,

    CONSTRAINT pk_connect_conversations PRIMARY KEY (conversation_ref),
    CONSTRAINT uq_connect_conversation_ref_platform UNIQUE (conversation_ref, platform_scope_ref),
    CONSTRAINT ck_connect_conversation_ref CHECK (
        octet_length(conversation_ref) BETWEEN 1 AND 256
        AND conversation_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_conversation_type CHECK (conversation_type = 'BUSINESS_CLIENT'),
    CONSTRAINT ck_connect_conversation_platform_scope CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_conversation_organization_scope CHECK (
        octet_length(organization_scope_ref) BETWEEN 1 AND 256
        AND organization_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_conversation_business_scope CHECK (
        octet_length(business_scope_ref) BETWEEN 1 AND 256
        AND business_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_conversation_status CHECK (
        status IN ('ACTIVE', 'MUTED', 'BLOCKED', 'CLOSED', 'ARCHIVED')
    ),
    CONSTRAINT ck_connect_conversation_sequence CHECK (last_message_sequence >= 0),
    CONSTRAINT ck_connect_conversation_version CHECK (version >= 0),
    CONSTRAINT ck_connect_conversation_schema_version CHECK (schema_version = 1)
);

CREATE TABLE connect.conversation_participants (
    conversation_ref text NOT NULL,
    subject_ref text NOT NULL,
    actor_type text NOT NULL,
    status text NOT NULL,
    capabilities text[] NOT NULL DEFAULT ARRAY[]::text[],
    joined_at timestamptz NOT NULL,
    left_at timestamptz,

    CONSTRAINT pk_connect_conversation_participants PRIMARY KEY (conversation_ref, subject_ref),
    CONSTRAINT uq_connect_participant_ref_subject_actor UNIQUE (conversation_ref, subject_ref, actor_type),
    CONSTRAINT fk_connect_participant_conversation FOREIGN KEY (conversation_ref)
        REFERENCES connect.conversations (conversation_ref)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_participant_subject_ref CHECK (
        octet_length(subject_ref) BETWEEN 1 AND 256
        AND subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_participant_actor_type CHECK (actor_type IN ('BUSINESS', 'CLIENT')),
    CONSTRAINT ck_connect_participant_status CHECK (status IN ('ACTIVE', 'LEFT', 'BLOCKED')),
    CONSTRAINT ck_connect_participant_capabilities CHECK (
        capabilities <@ ARRAY['SEND_TEXT']::text[]
        AND array_position(capabilities, NULL) IS NULL
    ),
    CONSTRAINT ck_connect_participant_left_at CHECK (
        (status = 'ACTIVE' AND left_at IS NULL)
        OR (status = 'LEFT' AND left_at IS NOT NULL)
        OR status = 'BLOCKED'
    ),
    CONSTRAINT ck_connect_participant_time_order CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE TABLE connect.messages (
    server_message_ref text NOT NULL,
    conversation_ref text NOT NULL,
    sequence bigint NOT NULL,
    sender_subject_ref text NOT NULL,
    sender_actor_type text NOT NULL,
    message_type text NOT NULL,
    status text NOT NULL,
    body text NOT NULL,
    payload_fingerprint text NOT NULL,
    accepted_at_server timestamptz NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,

    CONSTRAINT pk_connect_messages PRIMARY KEY (server_message_ref),
    CONSTRAINT uq_connect_message_conversation_sequence UNIQUE (conversation_ref, sequence),
    CONSTRAINT uq_connect_message_identity_target UNIQUE (
        server_message_ref,
        conversation_ref,
        sender_subject_ref,
        sequence,
        payload_fingerprint
    ),
    CONSTRAINT fk_connect_message_conversation FOREIGN KEY (conversation_ref)
        REFERENCES connect.conversations (conversation_ref)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_message_sender_participant FOREIGN KEY (
        conversation_ref,
        sender_subject_ref,
        sender_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_message_server_ref CHECK (
        octet_length(server_message_ref) BETWEEN 1 AND 256
        AND server_message_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_message_sequence CHECK (sequence > 0),
    CONSTRAINT ck_connect_message_type CHECK (message_type = 'TEXT'),
    CONSTRAINT ck_connect_message_status CHECK (status = 'PERSISTED'),
    CONSTRAINT ck_connect_message_body CHECK (
        octet_length(body) BETWEEN 1 AND 16384
        AND body !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_message_fingerprint CHECK (
        payload_fingerprint ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_connect_message_schema_version CHECK (schema_version = 1)
);

CREATE TABLE connect.message_identities (
    server_message_ref text NOT NULL,
    platform_scope_ref text NOT NULL,
    conversation_ref text NOT NULL,
    sender_subject_ref text NOT NULL,
    idempotency_key text NOT NULL,
    client_message_ref text NOT NULL,
    payload_fingerprint text NOT NULL,
    sequence bigint NOT NULL,

    CONSTRAINT pk_connect_message_identities PRIMARY KEY (server_message_ref),
    CONSTRAINT uq_connect_identity_idempotency UNIQUE (
        platform_scope_ref,
        sender_subject_ref,
        idempotency_key
    ),
    CONSTRAINT uq_connect_identity_client_message UNIQUE (
        platform_scope_ref,
        sender_subject_ref,
        client_message_ref
    ),
    CONSTRAINT fk_connect_identity_conversation_scope FOREIGN KEY (
        conversation_ref,
        platform_scope_ref
    ) REFERENCES connect.conversations (
        conversation_ref,
        platform_scope_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_identity_message FOREIGN KEY (
        server_message_ref,
        conversation_ref,
        sender_subject_ref,
        sequence,
        payload_fingerprint
    ) REFERENCES connect.messages (
        server_message_ref,
        conversation_ref,
        sender_subject_ref,
        sequence,
        payload_fingerprint
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_identity_platform_scope CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_identity_sender_subject CHECK (
        octet_length(sender_subject_ref) BETWEEN 1 AND 256
        AND sender_subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_identity_idempotency_key CHECK (
        octet_length(idempotency_key) BETWEEN 1 AND 256
        AND idempotency_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_identity_client_message_ref CHECK (
        octet_length(client_message_ref) BETWEEN 1 AND 256
        AND client_message_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_identity_fingerprint CHECK (
        payload_fingerprint ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_connect_identity_sequence CHECK (sequence > 0)
);

CREATE INDEX ix_connect_conversation_scope
    ON connect.conversations (platform_scope_ref, organization_scope_ref, business_scope_ref);

CREATE INDEX ix_connect_participant_subject
    ON connect.conversation_participants (subject_ref, conversation_ref);

CREATE INDEX ix_connect_message_conversation_accepted
    ON connect.messages (conversation_ref, accepted_at_server, server_message_ref);
