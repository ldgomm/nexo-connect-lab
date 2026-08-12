package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import java.nio.charset.StandardCharsets
import java.time.Instant

data class DurableConversationListEntry(
    val conversation: DurableConversationSnapshot,
    val lastActivityAt: Instant,
) {
    init {
        require(!lastActivityAt.isBefore(conversation.createdAt)) {
            "Conversation activity must not precede creation"
        }
    }

    val conversationRef: String
        get() = conversation.scope.conversationRef

    fun cursor(): ConversationListCursor =
        ConversationListCursor(
            lastActivityAt = lastActivityAt,
            conversationRef = conversationRef,
        )
}

data class ConversationListCursor(
    val lastActivityAt: Instant,
    val conversationRef: String,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require('\u0000' !in conversationRef) { "conversationRef must not contain NUL" }
        require(conversationRef.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_BYTES) {
            "conversationRef must not exceed $MAX_REFERENCE_BYTES UTF-8 bytes"
        }
    }

    private companion object {
        const val MAX_REFERENCE_BYTES = 256
    }
}

data class DurableConversationListPage(
    val items: List<DurableConversationListEntry>,
    val nextCursor: ConversationListCursor?,
) {
    init {
        require(items.map(DurableConversationListEntry::conversationRef).distinct().size == items.size) {
            "A conversation may appear only once per page"
        }
        require(items.zipWithNext().all { (newer, older) -> newer.isStrictlyBefore(older) }) {
            "Conversation page must use descending activity and reference order"
        }
        require(nextCursor == null || (items.isNotEmpty() && nextCursor == items.last().cursor())) {
            "A continuation cursor must identify the final visible item"
        }
    }

    private fun DurableConversationListEntry.isStrictlyBefore(other: DurableConversationListEntry): Boolean =
        lastActivityAt.isAfter(other.lastActivityAt) ||
            (lastActivityAt == other.lastActivityAt && conversationRef.isUtf8After(other.conversationRef))

    private fun String.isUtf8After(other: String): Boolean {
        val left = toByteArray(StandardCharsets.UTF_8)
        val right = other.toByteArray(StandardCharsets.UTF_8)
        val sharedLength = minOf(left.size, right.size)
        for (index in 0 until sharedLength) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison > 0
        }
        return left.size > right.size
    }
}
