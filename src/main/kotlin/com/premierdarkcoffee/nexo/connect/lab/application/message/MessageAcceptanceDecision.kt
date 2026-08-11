package com.premierdarkcoffee.nexo.connect.lab.application.message

import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence

sealed interface MessageAcceptanceDecision {
    data object AcceptNew : MessageAcceptanceDecision

    data class ReplayExisting(
        val serverMessageRef: String,
        val sequence: ConversationSequence,
    ) : MessageAcceptanceDecision

    data class Conflict(
        val reason: MessageConflictReason,
    ) : MessageAcceptanceDecision
}

enum class MessageConflictReason {
    DEDUPLICATION_STATE_DIVERGED,
    SCOPE_MISMATCH,
    IDEMPOTENCY_KEY_REUSED,
    CLIENT_MESSAGE_REF_REUSED,
    PAYLOAD_MISMATCH,
}
