package com.premierdarkcoffee.nexo.connect.lab.application.persistence

enum class DurableAcknowledgementBoundary {
    AFTER_TRANSACTION_COMMIT,
}

enum class DurablePersistenceConstraint {
    CONVERSATION_REF_PRIMARY_KEY,
    PARTICIPANT_CONVERSATION_SUBJECT_UNIQUE,
    SERVER_MESSAGE_REF_PRIMARY_KEY,
    CONVERSATION_SEQUENCE_UNIQUE,
    IDEMPOTENCY_PLATFORM_SENDER_KEY_UNIQUE,
    CLIENT_MESSAGE_PLATFORM_SENDER_REF_UNIQUE,
    MESSAGE_IDENTITY_BINDING_ONE_TO_ONE,
    MESSAGE_SENDER_PARTICIPANT_FOREIGN_KEY,
    IDENTITY_MESSAGE_FOREIGN_KEY,
    NOTIFICATION_MESSAGE_TARGET_UNIQUE,
    NOTIFICATION_MESSAGE_FOREIGN_KEY,
    NOTIFICATION_REGISTRATION_FOREIGN_KEY,
}

object DurableTextWriteContract {
    val transactionStages: List<DurableTextWriteStage> =
        listOf(
            DurableTextWriteStage.LOCK_CONVERSATION,
            DurableTextWriteStage.LOCK_SENDER_PARTICIPANT,
            DurableTextWriteStage.RECHECK_AUTHORIZATION,
            DurableTextWriteStage.RESOLVE_IDEMPOTENCY,
            DurableTextWriteStage.ALLOCATE_NEXT_SEQUENCE,
            DurableTextWriteStage.INSERT_MESSAGE,
            DurableTextWriteStage.INSERT_IDEMPOTENCY_BINDING,
            DurableTextWriteStage.INSERT_NOTIFICATION_OUTBOX_INTENTS,
            DurableTextWriteStage.COMMIT,
        )

    val acknowledgementBoundary: DurableAcknowledgementBoundary =
        DurableAcknowledgementBoundary.AFTER_TRANSACTION_COMMIT

    val persistenceConstraints: Set<DurablePersistenceConstraint> =
        DurablePersistenceConstraint.entries.toSet()

    val claimsExactlyOnceDelivery: Boolean
        get() = false

    val claimsGlobalMessageOrder: Boolean
        get() = false
}
