package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryPage
import java.nio.charset.StandardCharsets

data class LoadDurableMessageHistoryRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val cursor: DurableMessageHistoryCursor? = null,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require('\u0000' !in conversationRef) { "conversationRef must not contain NUL" }
        require(conversationRef.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONVERSATION_REF_UTF8_BYTES) {
            "conversationRef must not exceed $MAX_CONVERSATION_REF_UTF8_BYTES UTF-8 bytes"
        }
        require(pageSize in 1..MAX_PAGE_SIZE) {
            "pageSize must be between 1 and $MAX_PAGE_SIZE"
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        private const val MAX_CONVERSATION_REF_UTF8_BYTES = 256
    }
}

sealed interface DurableMessageHistoryResult {
    data class Loaded(
        val page: DurableMessageHistoryPage,
    ) : DurableMessageHistoryResult

    /** Deliberately merges absent, out-of-scope, unsupported and non-participant outcomes. */
    data object NotFoundOrDenied : DurableMessageHistoryResult
}

fun interface DurableMessageHistoryRepository {
    /** Loads only committed messages visible to an explicit durable participant. */
    fun load(request: LoadDurableMessageHistoryRequest): DurableMessageHistoryResult
}
