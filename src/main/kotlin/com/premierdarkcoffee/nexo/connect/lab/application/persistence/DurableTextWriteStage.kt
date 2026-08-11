package com.premierdarkcoffee.nexo.connect.lab.application.persistence

enum class DurableTextWriteStage {
    LOCK_CONVERSATION,
    LOCK_SENDER_PARTICIPANT,
    RECHECK_AUTHORIZATION,
    RESOLVE_IDEMPOTENCY,
    ALLOCATE_NEXT_SEQUENCE,
    INSERT_MESSAGE,
    INSERT_IDEMPOTENCY_BINDING,
    COMMIT,
}
