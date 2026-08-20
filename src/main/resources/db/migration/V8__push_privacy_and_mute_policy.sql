ALTER TABLE connect.push_device_registrations
    ADD CONSTRAINT uq_connect_push_registration_owner
        UNIQUE (registration_ref, platform_scope_ref, subject_ref, actor_type);

ALTER TABLE connect.notification_outbox
    ADD COLUMN presentation_mode TEXT NOT NULL DEFAULT 'BACKGROUND_ONLY',
    ADD COLUMN badge_mode TEXT NOT NULL DEFAULT 'UNCHANGED',
    ADD CONSTRAINT ck_connect_notification_presentation_mode CHECK (
        presentation_mode IN ('BACKGROUND_ONLY', 'GENERIC_ALERT')
    ),
    ADD CONSTRAINT ck_connect_notification_badge_mode CHECK (
        badge_mode IN ('UNCHANGED', 'SET_ONE')
    );

CREATE TABLE connect.push_notification_preferences (
    conversation_ref TEXT NOT NULL,
    registration_ref TEXT NOT NULL,
    platform_scope_ref TEXT NOT NULL,
    subject_ref TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    muted BOOLEAN NOT NULL,
    lock_screen_privacy TEXT NOT NULL,
    badge_mode TEXT NOT NULL,
    quiet_mode TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_connect_push_notification_preferences PRIMARY KEY (
        conversation_ref,
        registration_ref
    ),
    CONSTRAINT fk_connect_push_preference_conversation_scope FOREIGN KEY (
        conversation_ref,
        platform_scope_ref
    ) REFERENCES connect.conversations (
        conversation_ref,
        platform_scope_ref
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_push_preference_participant FOREIGN KEY (
        conversation_ref,
        subject_ref,
        actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_push_preference_registration_owner FOREIGN KEY (
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
    CONSTRAINT ck_connect_push_preference_conversation_ref CHECK (
        octet_length(conversation_ref) BETWEEN 1 AND 256
        AND conversation_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_push_preference_registration_ref CHECK (
        octet_length(registration_ref) BETWEEN 1 AND 256
        AND registration_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_push_preference_platform_scope_ref CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_push_preference_subject_ref CHECK (
        octet_length(subject_ref) BETWEEN 1 AND 256
        AND subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_push_preference_actor_type CHECK (
        actor_type IN ('BUSINESS', 'CLIENT')
    ),
    CONSTRAINT ck_connect_push_preference_lock_screen_privacy CHECK (
        lock_screen_privacy IN ('HIDDEN', 'GENERIC')
    ),
    CONSTRAINT ck_connect_push_preference_badge_mode CHECK (
        badge_mode IN ('UNCHANGED', 'SET_ONE')
    ),
    CONSTRAINT ck_connect_push_preference_quiet_mode CHECK (
        quiet_mode IN ('OFF', 'ON')
    ),
    CONSTRAINT ck_connect_push_preference_version CHECK (version >= 1)
);

CREATE INDEX ix_connect_push_notification_preference_owner
    ON connect.push_notification_preferences (
        platform_scope_ref,
        subject_ref,
        actor_type,
        conversation_ref,
        registration_ref
    );

GRANT SELECT, INSERT, UPDATE ON connect.push_notification_preferences TO nexo_connect_lab_app;
