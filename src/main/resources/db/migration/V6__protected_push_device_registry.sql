CREATE TABLE connect.push_device_registrations (
    registration_ref TEXT PRIMARY KEY,
    platform_scope_ref TEXT NOT NULL,
    organization_scope_ref TEXT,
    business_scope_ref TEXT,
    subject_ref TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    application TEXT NOT NULL,
    provider TEXT NOT NULL,
    environment TEXT NOT NULL,
    device_fingerprint TEXT NOT NULL,
    token_fingerprint TEXT,
    token_ciphertext BYTEA,
    token_nonce BYTEA,
    token_key_version INTEGER,
    token_version BIGINT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT ck_connect_push_registration_ref
        CHECK (octet_length(registration_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_connect_push_platform_scope_ref
        CHECK (octet_length(platform_scope_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_connect_push_organization_scope_ref
        CHECK (organization_scope_ref IS NULL OR octet_length(organization_scope_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_connect_push_business_scope_ref
        CHECK (business_scope_ref IS NULL OR octet_length(business_scope_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_connect_push_subject_ref
        CHECK (octet_length(subject_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_connect_push_actor_type
        CHECK (actor_type IN ('SUPERADMIN', 'ADMIN', 'BUSINESS', 'CLIENT')),
    CONSTRAINT ck_connect_push_scope_shape
        CHECK (
            (actor_type IN ('SUPERADMIN', 'CLIENT') AND organization_scope_ref IS NULL AND business_scope_ref IS NULL)
            OR (actor_type = 'ADMIN' AND organization_scope_ref IS NOT NULL AND business_scope_ref IS NULL)
            OR (actor_type = 'BUSINESS' AND organization_scope_ref IS NOT NULL AND business_scope_ref IS NOT NULL)
        ),
    CONSTRAINT ck_connect_push_application
        CHECK (application IN ('NEXO_CLIENT_IOS', 'NEXO_BUSINESS_IOS', 'NEXO_ADMIN_IOS')),
    CONSTRAINT ck_connect_push_application_owner
        CHECK (
            (application = 'NEXO_CLIENT_IOS' AND actor_type = 'CLIENT')
            OR (application = 'NEXO_BUSINESS_IOS' AND actor_type = 'BUSINESS')
            OR (application = 'NEXO_ADMIN_IOS' AND actor_type IN ('ADMIN', 'SUPERADMIN'))
        ),
    CONSTRAINT ck_connect_push_provider
        CHECK (provider = 'APNS'),
    CONSTRAINT ck_connect_push_environment
        CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_connect_push_device_fingerprint
        CHECK (device_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_connect_push_token_fingerprint
        CHECK (token_fingerprint IS NULL OR token_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_connect_push_token_material
        CHECK (
            (status = 'ACTIVE'
                AND token_fingerprint IS NOT NULL
                AND token_ciphertext IS NOT NULL
                AND octet_length(token_ciphertext) >= 32
                AND token_nonce IS NOT NULL
                AND octet_length(token_nonce) = 12
                AND token_key_version > 0
                AND revoked_at IS NULL)
            OR (status = 'REVOKED'
                AND token_fingerprint IS NULL
                AND token_ciphertext IS NULL
                AND token_nonce IS NULL
                AND token_key_version IS NULL
                AND revoked_at IS NOT NULL)
        ),
    CONSTRAINT ck_connect_push_token_version
        CHECK (token_version > 0),
    CONSTRAINT ck_connect_push_version
        CHECK (version >= token_version),
    CONSTRAINT ck_connect_push_timestamps
        CHECK (
            updated_at >= created_at
            AND (rotated_at IS NULL OR rotated_at >= created_at)
            AND (revoked_at IS NULL OR revoked_at >= created_at)
            AND ((token_version = 1) = (rotated_at IS NULL))
        )
);

CREATE UNIQUE INDEX uq_connect_push_active_device_binding
    ON connect.push_device_registrations (
        platform_scope_ref,
        COALESCE(organization_scope_ref, ''),
        COALESCE(business_scope_ref, ''),
        subject_ref,
        actor_type,
        application,
        provider,
        environment,
        device_fingerprint
    )
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_connect_push_active_token_fingerprint
    ON connect.push_device_registrations (token_fingerprint)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_connect_push_owner_active
    ON connect.push_device_registrations (
        platform_scope_ref,
        subject_ref,
        actor_type,
        application,
        provider,
        environment,
        updated_at DESC,
        registration_ref
    )
    WHERE status = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE ON connect.push_device_registrations TO nexo_connect_lab_app;
