CREATE TABLE connect.conversation_blocks (
    block_ref TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    conversation_ref TEXT NOT NULL,
    platform_scope_ref TEXT NOT NULL,
    organization_scope_ref TEXT NOT NULL,
    business_scope_ref TEXT NOT NULL,
    blocker_subject_ref TEXT NOT NULL,
    blocker_actor_type TEXT NOT NULL,
    blocked_subject_ref TEXT NOT NULL,
    blocked_actor_type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_connect_conversation_blocks PRIMARY KEY (block_ref),
    CONSTRAINT uq_connect_conversation_block_direction UNIQUE (
        conversation_ref,
        blocker_subject_ref,
        blocker_actor_type,
        blocked_subject_ref,
        blocked_actor_type
    ),
    CONSTRAINT uq_connect_conversation_block_scope UNIQUE (
        block_ref,
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    ),
    CONSTRAINT fk_connect_block_conversation_scope FOREIGN KEY (
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    ) REFERENCES connect.conversations (
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_block_blocker FOREIGN KEY (
        conversation_ref,
        blocker_subject_ref,
        blocker_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_block_blocked FOREIGN KEY (
        conversation_ref,
        blocked_subject_ref,
        blocked_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_block_ref CHECK (
        octet_length(block_ref) BETWEEN 1 AND 256
        AND block_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_scope_type CHECK (scope_type = 'CONVERSATION'),
    CONSTRAINT ck_connect_block_conversation_ref CHECK (
        octet_length(conversation_ref) BETWEEN 1 AND 256
        AND conversation_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_platform_scope CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_organization_scope CHECK (
        octet_length(organization_scope_ref) BETWEEN 1 AND 256
        AND organization_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_business_scope CHECK (
        octet_length(business_scope_ref) BETWEEN 1 AND 256
        AND business_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_subject_refs CHECK (
        octet_length(blocker_subject_ref) BETWEEN 1 AND 256
        AND blocker_subject_ref !~ '^[[:space:]]*$'
        AND octet_length(blocked_subject_ref) BETWEEN 1 AND 256
        AND blocked_subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_actor_types CHECK (
        blocker_actor_type IN ('BUSINESS', 'CLIENT')
        AND blocked_actor_type IN ('BUSINESS', 'CLIENT')
    ),
    CONSTRAINT ck_connect_block_distinct_participants CHECK (
        blocker_subject_ref <> blocked_subject_ref
        OR blocker_actor_type <> blocked_actor_type
    ),
    CONSTRAINT ck_connect_block_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_connect_block_version CHECK (version >= 1),
    CONSTRAINT ck_connect_block_timestamps CHECK (
        updated_at >= created_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
        AND ((status = 'REVOKED') = (revoked_at IS NOT NULL))
    )
);

CREATE INDEX ix_connect_conversation_blocks_authorization
    ON connect.conversation_blocks (
        platform_scope_ref,
        conversation_ref,
        blocker_subject_ref,
        blocker_actor_type,
        blocked_subject_ref,
        blocked_actor_type
    )
    WHERE status = 'ACTIVE';

CREATE TABLE connect.conversation_block_audit_events (
    audit_ref TEXT NOT NULL,
    block_ref TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    conversation_ref TEXT NOT NULL,
    platform_scope_ref TEXT NOT NULL,
    organization_scope_ref TEXT NOT NULL,
    business_scope_ref TEXT NOT NULL,
    blocker_subject_ref TEXT NOT NULL,
    blocker_actor_type TEXT NOT NULL,
    blocked_subject_ref TEXT NOT NULL,
    blocked_actor_type TEXT NOT NULL,
    action TEXT NOT NULL,
    resulting_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_connect_block_audit_events PRIMARY KEY (audit_ref),
    CONSTRAINT uq_connect_block_audit_version UNIQUE (block_ref, resulting_version),
    CONSTRAINT fk_connect_block_audit_scope FOREIGN KEY (
        block_ref,
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    ) REFERENCES connect.conversation_blocks (
        block_ref,
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_block_audit_blocker FOREIGN KEY (
        conversation_ref,
        blocker_subject_ref,
        blocker_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_block_audit_blocked FOREIGN KEY (
        conversation_ref,
        blocked_subject_ref,
        blocked_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_block_audit_ref CHECK (
        octet_length(audit_ref) BETWEEN 1 AND 256
        AND audit_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_block_audit_scope_type CHECK (scope_type = 'CONVERSATION'),
    CONSTRAINT ck_connect_block_audit_action CHECK (action IN ('APPLIED', 'REVOKED')),
    CONSTRAINT ck_connect_block_audit_version CHECK (resulting_version >= 1)
);

CREATE TABLE connect.notification_mute_audit_events (
    audit_ref TEXT NOT NULL,
    conversation_ref TEXT NOT NULL,
    registration_ref TEXT NOT NULL,
    platform_scope_ref TEXT NOT NULL,
    subject_ref TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    action TEXT NOT NULL,
    resulting_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_connect_mute_audit_events PRIMARY KEY (audit_ref),
    CONSTRAINT uq_connect_mute_audit_version UNIQUE (
        conversation_ref,
        registration_ref,
        resulting_version
    ),
    CONSTRAINT fk_connect_mute_audit_preference FOREIGN KEY (
        conversation_ref,
        registration_ref
    ) REFERENCES connect.push_notification_preferences (
        conversation_ref,
        registration_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_mute_audit_owner FOREIGN KEY (
        registration_ref,
        platform_scope_ref,
        subject_ref,
        actor_type
    ) REFERENCES connect.push_device_registrations (
        registration_ref,
        platform_scope_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_mute_audit_participant FOREIGN KEY (
        conversation_ref,
        subject_ref,
        actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_mute_audit_ref CHECK (
        octet_length(audit_ref) BETWEEN 1 AND 256
        AND audit_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_mute_audit_actor_type CHECK (actor_type IN ('BUSINESS', 'CLIENT')),
    CONSTRAINT ck_connect_mute_audit_action CHECK (action IN ('APPLIED', 'REVOKED')),
    CONSTRAINT ck_connect_mute_audit_version CHECK (resulting_version >= 1)
);

INSERT INTO connect.notification_mute_audit_events (
    audit_ref,
    conversation_ref,
    registration_ref,
    platform_scope_ref,
    subject_ref,
    actor_type,
    action,
    resulting_version,
    occurred_at
)
SELECT
    'mute-backfill-' || md5(
        preference.conversation_ref || ':' || preference.registration_ref || ':' || preference.version::TEXT
    ),
    preference.conversation_ref,
    preference.registration_ref,
    preference.platform_scope_ref,
    preference.subject_ref,
    preference.actor_type,
    'APPLIED',
    preference.version,
    preference.updated_at
FROM connect.push_notification_preferences AS preference
WHERE preference.muted;

GRANT SELECT, INSERT, UPDATE ON connect.conversation_blocks TO nexo_connect_lab_app;
GRANT SELECT, INSERT ON connect.conversation_block_audit_events TO nexo_connect_lab_app;
GRANT SELECT, INSERT ON connect.notification_mute_audit_events TO nexo_connect_lab_app;
REVOKE UPDATE, DELETE ON connect.conversation_block_audit_events FROM nexo_connect_lab_app;
REVOKE UPDATE, DELETE ON connect.notification_mute_audit_events FROM nexo_connect_lab_app;
