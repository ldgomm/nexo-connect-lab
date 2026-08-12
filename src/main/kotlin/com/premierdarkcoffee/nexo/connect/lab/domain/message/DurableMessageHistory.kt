package com.premierdarkcoffee.nexo.connect.lab.domain.message

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.nio.charset.StandardCharsets
import java.time.Instant

data class DurableMessageHistoryEntry(
    val serverMessageRef: String,
    val sequence: ConversationSequence,
    val senderSubjectRef: String,
    val senderActorType: ConnectActorType,
    val body: TextMessageBody,
    val acceptedAtServer: Instant,
) {
    init {
        requireBoundedReference(serverMessageRef, "serverMessageRef")
        requireBoundedReference(senderSubjectRef, "senderSubjectRef")
        require(sequence.value > ConversationSequence.INITIAL.value) {
            "A durable history entry requires a positive conversation sequence"
        }
        require(senderActorType == ConnectActorType.BUSINESS || senderActorType == ConnectActorType.CLIENT) {
            "Durable business-client history supports only business and client senders"
        }
    }

    fun cursor(): DurableMessageHistoryCursor = DurableMessageHistoryCursor(sequence)
}

data class DurableMessageHistoryCursor(
    val beforeSequence: ConversationSequence,
) {
    init {
        require(beforeSequence.value > ConversationSequence.INITIAL.value) {
            "A durable history cursor requires a positive conversation sequence"
        }
    }
}

data class DurableMessageHistoryPage(
    val items: List<DurableMessageHistoryEntry>,
    val nextCursor: DurableMessageHistoryCursor?,
) {
    init {
        require(items.map(DurableMessageHistoryEntry::serverMessageRef).distinct().size == items.size) {
            "A durable message may appear only once per history page"
        }
        require(items.zipWithNext().all { (newer, older) -> newer.sequence.value > older.sequence.value }) {
            "Durable message history must use strict descending conversation sequence order"
        }
        require(nextCursor == null || (items.isNotEmpty() && nextCursor == items.last().cursor())) {
            "A continuation cursor must identify the final visible message"
        }
    }
}

private fun requireBoundedReference(
    value: String,
    fieldName: String,
) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES) {
        "$fieldName must not exceed $MAX_REFERENCE_UTF8_BYTES UTF-8 bytes"
    }
}

private const val MAX_REFERENCE_UTF8_BYTES = 256
