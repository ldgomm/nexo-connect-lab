ALTER TABLE connect.conversations
    ADD CONSTRAINT uq_connect_conversation_full_scope UNIQUE (
        conversation_ref,
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref
    );

CREATE TABLE connect.business_client_conversation_keys (
    platform_scope_ref text NOT NULL,
    organization_scope_ref text NOT NULL,
    business_scope_ref text NOT NULL,
    business_subject_ref text NOT NULL,
    business_actor_type text NOT NULL DEFAULT 'BUSINESS',
    client_subject_ref text NOT NULL,
    client_actor_type text NOT NULL DEFAULT 'CLIENT',
    conversation_ref text NOT NULL,

    CONSTRAINT pk_connect_business_client_conversation_keys PRIMARY KEY (
        platform_scope_ref,
        organization_scope_ref,
        business_scope_ref,
        business_subject_ref,
        client_subject_ref
    ),
    CONSTRAINT uq_connect_business_client_conversation_ref UNIQUE (conversation_ref),
    CONSTRAINT fk_connect_business_client_conversation_scope FOREIGN KEY (
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
    CONSTRAINT fk_connect_business_client_business_participant FOREIGN KEY (
        conversation_ref,
        business_subject_ref,
        business_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_connect_business_client_client_participant FOREIGN KEY (
        conversation_ref,
        client_subject_ref,
        client_actor_type
    ) REFERENCES connect.conversation_participants (
        conversation_ref,
        subject_ref,
        actor_type
    ) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_connect_business_client_platform_scope CHECK (
        octet_length(platform_scope_ref) BETWEEN 1 AND 256
        AND platform_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_organization_scope CHECK (
        octet_length(organization_scope_ref) BETWEEN 1 AND 256
        AND organization_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_business_scope CHECK (
        octet_length(business_scope_ref) BETWEEN 1 AND 256
        AND business_scope_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_business_subject CHECK (
        octet_length(business_subject_ref) BETWEEN 1 AND 256
        AND business_subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_client_subject CHECK (
        octet_length(client_subject_ref) BETWEEN 1 AND 256
        AND client_subject_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_conversation_ref CHECK (
        octet_length(conversation_ref) BETWEEN 1 AND 256
        AND conversation_ref !~ '^[[:space:]]*$'
    ),
    CONSTRAINT ck_connect_business_client_actor_types CHECK (
        business_actor_type = 'BUSINESS' AND client_actor_type = 'CLIENT'
    ),
    CONSTRAINT ck_connect_business_client_distinct_subjects CHECK (
        business_subject_ref <> client_subject_ref
    )
);

CREATE INDEX ix_connect_business_client_client_lookup
    ON connect.business_client_conversation_keys (
        platform_scope_ref,
        client_subject_ref,
        conversation_ref
    );
