package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationListCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationListPage
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

data class CreateBusinessClientConversationRequest(
    val principal: ConnectPrincipal,
    val command: CreateBusinessClientConversationCommand,
) {
    init {
        require(principal.subjectRef != command.clientSubjectRef) {
            "Business and client participants must have distinct subjects"
        }
    }
}

enum class ConversationCreationDenialReason {
    CREATOR_NOT_SCOPED_BUSINESS,
}

enum class ConversationCreationConflictReason {
    CONVERSATION_REF_ALREADY_BOUND,
}

sealed interface ConversationCreationResult {
    data class Created(
        val conversation: DurableConversationSnapshot,
    ) : ConversationCreationResult

    data class Existing(
        val conversation: DurableConversationSnapshot,
    ) : ConversationCreationResult

    data class Conflict(
        val reason: ConversationCreationConflictReason,
    ) : ConversationCreationResult

    data class Denied(
        val reason: ConversationCreationDenialReason,
    ) : ConversationCreationResult
}

data class OpenConversationRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require('\u0000' !in conversationRef) { "conversationRef must not contain NUL" }
    }
}

sealed interface OpenConversationResult {
    data class Opened(
        val conversation: DurableConversationSnapshot,
    ) : OpenConversationResult

    data object NotFoundOrDenied : OpenConversationResult
}

data class ListConversationsRequest(
    val principal: ConnectPrincipal,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val cursor: ConversationListCursor? = null,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) {
            "pageSize must be between 1 and $MAX_PAGE_SIZE"
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
    }
}

enum class ConversationListingDenialReason {
    PRINCIPAL_TYPE_NOT_SUPPORTED,
}

sealed interface ConversationListingResult {
    data class Listed(
        val page: DurableConversationListPage,
    ) : ConversationListingResult

    data class Denied(
        val reason: ConversationListingDenialReason,
    ) : ConversationListingResult
}

interface ConversationRepository {
    /** Creates one durable business-client conversation per scoped participant pair. */
    fun create(request: CreateBusinessClientConversationRequest): ConversationCreationResult

    /** Does not reveal whether a conversation is absent or merely outside the principal's scope. */
    fun open(request: OpenConversationRequest): OpenConversationResult

    /** Lists only explicit participant conversations using an exclusive durable keyset cursor. */
    fun listForParticipant(request: ListConversationsRequest): ConversationListingResult
}
