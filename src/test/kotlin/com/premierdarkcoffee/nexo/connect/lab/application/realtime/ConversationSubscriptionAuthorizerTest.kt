package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListConversationsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantCommandState
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConversationSubscriptionAuthorizerTest {
    @Test
    fun `authorizes only an active durable participant in an active conversation`() {
        val repository = FakeConversationRepository(OpenConversationResult.Opened(snapshot()))
        val authorizer = RepositoryConversationSubscriptionAuthorizer(repository)

        val result =
            assertIs<ConversationSubscriptionAuthorizationResult.Authorized>(
                authorizer.authorize(
                    AuthorizeConversationSubscriptionRequest(
                        principal = businessPrincipal,
                        conversationRef = CONVERSATION_REF,
                    ),
                ),
            )

        assertEquals(CONVERSATION_REF, result.conversationRef)
        assertEquals(7L, result.lastMessageSequence)
        assertEquals(businessPrincipal, repository.lastOpenRequest?.principal)
    }

    @Test
    fun `collapses absent blocked participant and closed conversation into one denial`() {
        val results =
            listOf(
                OpenConversationResult.NotFoundOrDenied,
                OpenConversationResult.Opened(snapshot(businessStatus = ConversationParticipantStatus.BLOCKED)),
                OpenConversationResult.Opened(snapshot(conversationStatus = ConversationStatus.CLOSED)),
            ).map { opened ->
                RepositoryConversationSubscriptionAuthorizer(FakeConversationRepository(opened)).authorize(
                    AuthorizeConversationSubscriptionRequest(businessPrincipal, CONVERSATION_REF),
                )
            }

        results.forEach { result ->
            assertEquals(ConversationSubscriptionAuthorizationResult.NotFoundOrDenied, result)
        }
    }

    private fun snapshot(
        businessStatus: ConversationParticipantStatus = ConversationParticipantStatus.ACTIVE,
        conversationStatus: ConversationStatus = ConversationStatus.ACTIVE,
    ): DurableConversationSnapshot =
        DurableConversationSnapshot(
            scope =
                ConversationAccessScope(
                    conversationRef = CONVERSATION_REF,
                    type = ConversationType.BUSINESS_CLIENT,
                    platformScopeRef = businessPrincipal.platformScopeRef,
                    organizationScopeRef = checkNotNull(businessPrincipal.organizationScopeRef),
                    businessScopeRef = checkNotNull(businessPrincipal.businessScopeRef),
                    participants =
                        setOf(
                            ConversationParticipant(businessPrincipal.subjectRef, ConnectActorType.BUSINESS),
                            ConversationParticipant(CLIENT_SUBJECT, ConnectActorType.CLIENT),
                        ),
                ),
            status = conversationStatus,
            participantStates =
                setOf(
                    ConversationParticipantCommandState(
                        subjectRef = businessPrincipal.subjectRef,
                        actorType = ConnectActorType.BUSINESS,
                        status = businessStatus,
                        capabilities = setOf(ConversationCapability.SEND_TEXT),
                    ),
                    ConversationParticipantCommandState(
                        subjectRef = CLIENT_SUBJECT,
                        actorType = ConnectActorType.CLIENT,
                        status = ConversationParticipantStatus.ACTIVE,
                        capabilities = setOf(ConversationCapability.SEND_TEXT),
                    ),
                ),
            createdAt = Instant.parse("2026-08-12T04:00:00Z"),
            lastMessageSequence = ConversationSequence(7),
        )

    private class FakeConversationRepository(
        private val result: OpenConversationResult,
    ) : ConversationRepository {
        var lastOpenRequest: OpenConversationRequest? = null

        override fun create(request: CreateBusinessClientConversationRequest): ConversationCreationResult =
            error("Not used")

        override fun open(request: OpenConversationRequest): OpenConversationResult {
            lastOpenRequest = request
            return result
        }

        override fun listForParticipant(request: ListConversationsRequest): ConversationListingResult =
            error("Not used")
    }

    private companion object {
        const val CONVERSATION_REF = "conversation-c2"
        const val CLIENT_SUBJECT = "client-c2"

        val businessPrincipal =
            ConnectPrincipal(
                subjectRef = "business-c2",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-c2",
                organizationScopeRef = "organization-c2",
                businessScopeRef = "business-scope-c2",
            )
    }
}
