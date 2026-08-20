CREATE TABLE connect.notification_outbox (
    intent_ref TEXT PRIMARY KEY,
    platform_scope_ref TEXT NOT NULL,
    organization_scope_ref TEXT,
    business_scope_ref TEXT,
    conversation_ref TEXT NOT NULL,
    server_message_ref TEXT NOT NULL,
    recipient_subject_ref TEXT NOT NULL,
    recipient_actor_type TEXT NOT NULL,
    registration_ref TEXT NOT NULL,
    application TEXT NOT NULL,
    provider TEXT NOT NULL,
    environment TEXT NOT NULL,
    notification_type TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_error_code TEXT,
    delivered_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_connect_notification_message_target UNIQUE (
        server_message_ref,
        registration_ref,
        notification_type
    ),
    CONSTRAINT fk_connect_notification_conversation_scope FOREIGN KEY (
        conversation_ref,
        platform_scope_ref
    ) REFERENCES connect.conversations (
        conversation_ref,
        platform_scope_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_notification_message FOREIGN KEY (server_message_ref)
        REFERENCES connect.messages (server_message_ref)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_notification_recipient FOREIGN KEY (
        conversation_ref,
        recipient_subject_ref,
        recipient_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_notification_registration FOREIGN KEY (registration_ref)
        REFERENCES connect.push_device_registrations (registration_ref)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_notification_intent_ref CHECK (
        octet_length(intent_ref) BETWEEN 1 AND 256
        AND intent_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_platform_scope_ref CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_organization_scope_ref CHECK (
        organization_scope_ref IS NULL
        OR (
            octet_length(organization_scope_ref) BETWEEN 1 AND 256
            AND organization_scope_ref !~ '^[[:space:]]*$'
        )
    ),
    CONSTRAINT ck_connect_notification_business_scope_ref CHECK (
        business_scope_ref IS NULL
        OR (
            octet_length(business_scope_ref) BETWEEN 1 AND 256
            AND business_scope_ref !~ '^[[:space:]]*$'
        )
    ),
    CONSTRAINT ck_connect_notification_conversation_ref CHECK (
        octet_length(conversation_ref) BETWEEN 1 AND 256
        AND conversation_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_message_ref CHECK (
        octet_length(server_message_ref) BETWEEN 1 AND 256
        AND server_message_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_recipient_subject_ref CHECK (
        octet_length(recipient_subject_ref) BETWEEN 1 AND 256
        AND recipient_subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_recipient_actor_type CHECK (
        recipient_actor_type IN ('BUSINESS', 'CLIENT')
    ),
    CONSTRAINT ck_connect_notification_scope_shape CHECK (
        (recipient_actor_type = 'CLIENT'
            AND organization_scope_ref IS NULL
            AND business_scope_ref IS NULL)
        OR (recipient_actor_type = 'BUSINESS'
            AND organization_scope_ref IS NOT NULL
            AND business_scope_ref IS NOT NULL)
    ),
    CONSTRAINT ck_connect_notification_registration_ref CHECK (
        octet_length(registration_ref) BETWEEN 1 AND 256
        AND registration_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_notification_application CHECK (
        application IN ('NEXO_CLIENT_IOS', 'NEXO_BUSINESS_IOS')
    ),
    CONSTRAINT ck_connect_notification_application_owner CHECK (
        (application = 'NEXO_CLIENT_IOS' AND recipient_actor_type = 'CLIENT')
        OR (application = 'NEXO_BUSINESS_IOS' AND recipient_actor_type = 'BUSINESS')
    ),
    CONSTRAINT ck_connect_notification_provider CHECK (provider = 'APNS'),
    CONSTRAINT ck_connect_notification_environment CHECK (
        environment IN ('SANDBOX', 'PRODUCTION')
    ),
    CONSTRAINT ck_connect_notification_type CHECK (
        notification_type = 'MESSAGE_CREATED'
    ),
    CONSTRAINT ck_connect_notification_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'RETRY_PENDING', 'DELIVERED', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_connect_notification_attempts CHECK (
        attempt_count BETWEEN 0 AND max_attempts
        AND max_attempts BETWEEN 1 AND 32
    ),
    CONSTRAINT ck_connect_notification_lease_owner CHECK (
        lease_owner IS NULL
        OR (
            octet_length(lease_owner) BETWEEN 1 AND 128
            AND lease_owner !~ '^[[:space:]]*$'
        )
    ),
    CONSTRAINT ck_connect_notification_error_code CHECK (
        last_error_code IS NULL
        OR last_error_code IN (
            'REGISTRATION_REVOKED',
            'PROVIDER_TIMEOUT',
            'PROVIDER_RATE_LIMITED',
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_REJECTED',
            'LEASE_EXPIRED_MAX_ATTEMPTS',
            'OPERATOR_DEAD_LETTER'
        )
    ),
    CONSTRAINT ck_connect_notification_state_shape CHECK (
        (status = 'PENDING'
            AND attempt_count = 0
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND last_error_code IS NULL
            AND delivered_at IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'CLAIMED'
            AND attempt_count >= 1
            AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND delivered_at IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'RETRY_PENDING'
            AND attempt_count >= 1
            AND attempt_count < max_attempts
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND last_error_code IS NOT NULL
            AND delivered_at IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'DELIVERED'
            AND attempt_count >= 1
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND delivered_at IS NOT NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'DEAD_LETTER'
            AND attempt_count >= 1
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND last_error_code IS NOT NULL
            AND delivered_at IS NULL
            AND dead_lettered_at IS NOT NULL)
    ),
    CONSTRAINT ck_connect_notification_timestamps CHECK (
        next_attempt_at >= created_at
        AND updated_at >= created_at
        AND (lease_expires_at IS NULL OR lease_expires_at > updated_at)
        AND (delivered_at IS NULL OR delivered_at >= created_at)
        AND (dead_lettered_at IS NULL OR dead_lettered_at >= created_at)
    ),
    CONSTRAINT ck_connect_notification_version CHECK (version >= 0)
);

CREATE INDEX ix_connect_notification_claimable
    ON connect.notification_outbox (
        status,
        next_attempt_at,
        created_at,
        intent_ref
    )
    WHERE status IN ('PENDING', 'RETRY_PENDING');

CREATE INDEX ix_connect_notification_expired_lease
    ON connect.notification_outbox (
        lease_expires_at,
        created_at,
        intent_ref
    )
    WHERE status = 'CLAIMED';

CREATE INDEX ix_connect_notification_message
    ON connect.notification_outbox (
        conversation_ref,
        server_message_ref,
        created_at,
        intent_ref
    );

CREATE INDEX ix_connect_notification_recipient
    ON connect.notification_outbox (
        platform_scope_ref,
        recipient_subject_ref,
        recipient_actor_type,
        status,
        created_at,
        intent_ref
    );

GRANT SELECT, INSERT, UPDATE ON connect.notification_outbox TO nexo_connect_lab_app;
