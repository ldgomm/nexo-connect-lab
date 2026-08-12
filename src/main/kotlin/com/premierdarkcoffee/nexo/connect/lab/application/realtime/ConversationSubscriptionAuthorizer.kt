package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

data class AuthorizeConversationSubscriptionRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
)

sealed interface ConversationSubscriptionAuthorizationResult {
    data class Authorized(
        val conversationRef: String,
        val lastMessageSequence: Long,
    ) : ConversationSubscriptionAuthorizationResult

    data object NotFoundOrDenied : ConversationSubscriptionAuthorizationResult

    data object Unavailable : ConversationSubscriptionAuthorizationResult
}

fun interface ConversationSubscriptionAuthorizer {
    fun authorize(request: AuthorizeConversationSubscriptionRequest): ConversationSubscriptionAuthorizationResult
}

class RepositoryConversationSubscriptionAuthorizer(
    private val conversationRepository: ConversationRepository,
) : ConversationSubscriptionAuthorizer {
    override fun authorize(
        request: AuthorizeConversationSubscriptionRequest,
    ): ConversationSubscriptionAuthorizationResult =
        when (
            val opened =
                conversationRepository.open(
                    OpenConversationRequest(
                        principal = request.principal,
                        conversationRef = request.conversationRef,
                    ),
                )
        ) {
            is OpenConversationResult.Opened -> {
                val participantState =
                    opened.conversation.participantStates.singleOrNull { participant ->
                        participant.subjectRef == request.principal.subjectRef &&
                            participant.actorType == request.principal.actorType
                    }
                if (
                    participantState?.status == ConversationParticipantStatus.ACTIVE &&
                    opened.conversation.status.acceptsDurableText
                ) {
                    ConversationSubscriptionAuthorizationResult.Authorized(
                        conversationRef = opened.conversation.scope.conversationRef,
                        lastMessageSequence = opened.conversation.lastMessageSequence.value,
                    )
                } else {
                    ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
                }
            }

            OpenConversationResult.NotFoundOrDenied ->
                ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
        }
}

object UnavailableConversationSubscriptionAuthorizer : ConversationSubscriptionAuthorizer {
    override fun authorize(
        request: AuthorizeConversationSubscriptionRequest,
    ): ConversationSubscriptionAuthorizationResult =
        ConversationSubscriptionAuthorizationResult.Unavailable
}
