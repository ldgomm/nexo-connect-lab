package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.nio.charset.StandardCharsets
import java.time.Instant

data class DurableReceiptCursor(
    val conversationRef: String,
    val subjectRef: String,
    val actorType: ConnectActorType,
    val highestDeliveredSequence: Long,
    val highestReadSequence: Long,
    val deliveredAt: Instant?,
    val readAt: Instant?,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        requireReceiptReference(conversationRef, "conversationRef")
        requireReceiptReference(subjectRef, "subjectRef")
        require(actorType == ConnectActorType.BUSINESS || actorType == ConnectActorType.CLIENT) {
            "A durable receipt cursor requires a business or client subject"
        }
        require(highestDeliveredSequence >= 0) { "highestDeliveredSequence must not be negative" }
        require(highestReadSequence in 0L..highestDeliveredSequence) {
            "highestReadSequence must be monotonic and not exceed delivery"
        }
        require((highestDeliveredSequence == 0L) == (deliveredAt == null)) {
            "deliveredAt must match the durable delivery boundary"
        }
        require((highestReadSequence == 0L) == (readAt == null)) {
            "readAt must match the durable read boundary"
        }
        require(version >= 1) { "A persisted durable receipt cursor requires a positive version" }
    }
}

data class DurableReceiptCursorEvent(
    val cursor: DurableReceiptCursor,
)

private fun requireReceiptReference(
    value: String,
    fieldName: String,
) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES) {
        "$fieldName exceeds the realtime reference limit"
    }
}
