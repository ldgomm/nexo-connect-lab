ALTER TABLE connect.conversations
    ADD COLUMN last_activity_at timestamptz;

UPDATE connect.conversations AS conversation
SET last_activity_at = GREATEST(
    conversation.created_at,
    COALESCE(
        (
            SELECT MAX(message.accepted_at_server)
            FROM connect.messages AS message
            WHERE message.conversation_ref = conversation.conversation_ref
        ),
        conversation.created_at
    )
);

ALTER TABLE connect.conversations
    ALTER COLUMN last_activity_at SET NOT NULL,
    ADD CONSTRAINT ck_connect_conversation_activity_time CHECK (
        last_activity_at >= created_at
    );

CREATE INDEX ix_connect_conversation_activity_listing
    ON connect.conversations (
        platform_scope_ref,
        last_activity_at DESC,
        conversation_ref COLLATE "C" DESC
    );

CREATE INDEX ix_connect_participant_listing
    ON connect.conversation_participants (
        subject_ref,
        actor_type,
        conversation_ref
    );
