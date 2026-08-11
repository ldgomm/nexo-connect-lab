package com.premierdarkcoffee.nexo.connect.lab.application.message

import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessageIdempotencyRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand

class MessageIdempotencyEvaluator {
    fun decide(
        command: SendTextMessageCommand,
        existingByIdempotencyKey: MessageIdempotencyRecord?,
        existingByClientMessageRef: MessageIdempotencyRecord?,
    ): MessageAcceptanceDecision {
        val existingRecords =
            listOfNotNull(existingByIdempotencyKey, existingByClientMessageRef).distinct()

        if (existingRecords.isEmpty()) {
            return MessageAcceptanceDecision.AcceptNew
        }

        if (existingRecords.size != 1) {
            return MessageAcceptanceDecision.Conflict(
                MessageConflictReason.DEDUPLICATION_STATE_DIVERGED,
            )
        }

        val existing = existingRecords.single()
        val conflictReason = conflictReason(existing, command)
        if (conflictReason != null) {
            return MessageAcceptanceDecision.Conflict(conflictReason)
        }

        return MessageAcceptanceDecision.ReplayExisting(
            serverMessageRef = existing.serverMessageRef,
            sequence = existing.sequence,
        )
    }

    private fun conflictReason(
        existing: MessageIdempotencyRecord,
        command: SendTextMessageCommand,
    ): MessageConflictReason? =
        when {
            existing.conversationRef != command.conversationRef ||
                existing.senderSubjectRef != command.senderSubjectRef ->
                MessageConflictReason.SCOPE_MISMATCH

            existing.identity.idempotencyKey != command.identity.idempotencyKey ->
                MessageConflictReason.CLIENT_MESSAGE_REF_REUSED

            existing.identity.clientMessageRef != command.identity.clientMessageRef ->
                MessageConflictReason.IDEMPOTENCY_KEY_REUSED

            existing.payloadFingerprint != command.payloadFingerprint ->
                MessageConflictReason.PAYLOAD_MISMATCH

            else -> null
        }
}
