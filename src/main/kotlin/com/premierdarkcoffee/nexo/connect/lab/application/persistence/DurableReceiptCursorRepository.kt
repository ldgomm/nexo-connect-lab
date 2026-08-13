package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import java.nio.charset.StandardCharsets

enum class DurableReceiptAdvance {
    DELIVERY,
    READ,
}

data class AdvanceDurableReceiptCursorRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val sequence: Long,
    val advance: DurableReceiptAdvance,
) {
    init {
        requireReceiptRequestReference(conversationRef)
        require(sequence > 0) { "A durable receipt sequence must be positive" }
    }
}

data class LoadDurableReceiptCursorsRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
) {
    init {
        requireReceiptRequestReference(conversationRef)
    }
}

sealed interface AdvanceDurableReceiptCursorResult {
    data class Recorded(
        val cursor: DurableReceiptCursor,
        val advanced: Boolean,
    ) : AdvanceDurableReceiptCursorResult

    data object InvalidSequence : AdvanceDurableReceiptCursorResult

    data object NotFoundOrDenied : AdvanceDurableReceiptCursorResult
}

sealed interface LoadDurableReceiptCursorsResult {
    data class Loaded(
        val cursors: List<DurableReceiptCursor>,
    ) : LoadDurableReceiptCursorsResult

    data object NotFoundOrDenied : LoadDurableReceiptCursorsResult
}

interface DurableReceiptCursorRepository {
    fun advance(request: AdvanceDurableReceiptCursorRequest): AdvanceDurableReceiptCursorResult

    fun load(request: LoadDurableReceiptCursorsRequest): LoadDurableReceiptCursorsResult
}

private fun requireReceiptRequestReference(conversationRef: String) {
    require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
    require('\u0000' !in conversationRef) { "conversationRef must not contain NUL" }
    require(
        conversationRef.toByteArray(StandardCharsets.UTF_8).size <=
            com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES,
    ) { "conversationRef exceeds the realtime reference limit" }
}
