CREATE TABLE connect.conversation_receipt_cursors (
    conversation_ref TEXT NOT NULL,
    subject_ref TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    highest_delivered_sequence BIGINT NOT NULL DEFAULT 0,
    highest_read_sequence BIGINT NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (conversation_ref, subject_ref, actor_type),
    CONSTRAINT conversation_receipt_cursors_conversation_fk
        FOREIGN KEY (conversation_ref)
        REFERENCES connect.conversations (conversation_ref)
        ON DELETE CASCADE,
    CONSTRAINT conversation_receipt_cursors_actor_type_check
        CHECK (actor_type IN ('BUSINESS', 'CLIENT')),
    CONSTRAINT conversation_receipt_cursors_delivery_check
        CHECK (highest_delivered_sequence >= 0),
    CONSTRAINT conversation_receipt_cursors_read_check
        CHECK (
            highest_read_sequence >= 0
            AND highest_read_sequence <= highest_delivered_sequence
        ),
    CONSTRAINT conversation_receipt_cursors_delivery_timestamp_check
        CHECK ((highest_delivered_sequence = 0) = (delivered_at IS NULL)),
    CONSTRAINT conversation_receipt_cursors_read_timestamp_check
        CHECK ((highest_read_sequence = 0) = (read_at IS NULL)),
    CONSTRAINT conversation_receipt_cursors_version_check
        CHECK (version > 0)
);

CREATE INDEX conversation_receipt_cursors_conversation_idx
    ON connect.conversation_receipt_cursors (conversation_ref);

GRANT SELECT, INSERT, UPDATE ON connect.conversation_receipt_cursors TO nexo_connect_lab_app;
