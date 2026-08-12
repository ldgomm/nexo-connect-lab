package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
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

interface ConversationRepository {
    /** Creates one durable business-client conversation per scoped participant pair. */
    fun create(request: CreateBusinessClientConversationRequest): ConversationCreationResult

    /** Does not reveal whether a conversation is absent or merely outside the principal's scope. */
    fun open(request: OpenConversationRequest): OpenConversationResult
}
