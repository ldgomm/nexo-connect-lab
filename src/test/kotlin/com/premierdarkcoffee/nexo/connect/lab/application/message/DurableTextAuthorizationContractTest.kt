package com.premierdarkcoffee.nexo.connect.lab.application.message

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantCommandState
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableTextAuthorizationContext
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurableTextAuthorizationContractTest {
    private val authorizer = DurableTextMessageAuthorizer()

    @Test
    fun `only active and muted conversations accept durable text`() {
        assertEquals(
            setOf(ConversationStatus.ACTIVE, ConversationStatus.MUTED),
            ConversationStatus.entries.filter(ConversationStatus::acceptsDurableText).toSet(),
        )
    }

    @Test
    fun `only active participants may send durable text`() {
        assertEquals(
            setOf(ConversationParticipantStatus.ACTIVE),
            ConversationParticipantStatus.entries.filter(ConversationParticipantStatus::canSendDurableText).toSet(),
        )
    }

    @Test
    fun `allows an explicitly scoped active business participant with send capability`() {
        assertEquals(
            DurableTextAuthorizationDecision.ALLOW,
            authorizer.decide(businessPrincipal(), command(), context()),
        )
    }

    @Test
    fun `muting notifications does not prevent durable text`() {
        assertEquals(
            DurableTextAuthorizationDecision.ALLOW,
            authorizer.decide(
                businessPrincipal(),
                command(),
                context(conversationStatus = ConversationStatus.MUTED),
            ),
        )
    }

    @Test
    fun `allows an explicitly listed client participant`() {
        assertEquals(
            DurableTextAuthorizationDecision.ALLOW,
            authorizer.decide(
                clientPrincipal(),
                command(senderSubjectRef = "client-alpha"),
                context(),
            ),
        )
    }

    @Test
    fun `denies a command that targets another conversation`() {
        assertEquals(
            DurableTextAuthorizationDecision.DENY_COMMAND_SCOPE,
            authorizer.decide(
                businessPrincipal(),
                command(conversationRef = "conversation-guessed"),
                context(),
            ),
        )
    }

    @Test
    fun `denies sender impersonation even when the impersonated subject is a member`() {
        assertEquals(
            DurableTextAuthorizationDecision.DENY_COMMAND_SCOPE,
            authorizer.decide(
                businessPrincipal(),
                command(senderSubjectRef = "client-alpha"),
                context(),
            ),
        )
    }

    @Test
    fun `denies a guessed subject that is not a member`() {
        assertEquals(
            DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
            authorizer.decide(
                businessPrincipal(subjectRef = "business-guessed"),
                command(senderSubjectRef = "business-guessed"),
                context(),
            ),
        )
    }

    @Test
    fun `denies a business principal from another tenant scope`() {
        assertEquals(
            DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
            authorizer.decide(
                businessPrincipal(organizationScopeRef = "organization-other"),
                command(),
                context(),
            ),
        )
    }

    @Test
    fun `denies every non writable conversation state`() {
        setOf(ConversationStatus.BLOCKED, ConversationStatus.CLOSED, ConversationStatus.ARCHIVED).forEach { status ->
            assertEquals(
                DurableTextAuthorizationDecision.DENY_CONVERSATION_STATE,
                authorizer.decide(
                    businessPrincipal(),
                    command(),
                    context(conversationStatus = status),
                ),
            )
        }
    }

    @Test
    fun `denies a participant who left or was blocked`() {
        setOf(ConversationParticipantStatus.LEFT, ConversationParticipantStatus.BLOCKED).forEach { status ->
            assertEquals(
                DurableTextAuthorizationDecision.DENY_PARTICIPANT_STATE,
                authorizer.decide(
                    businessPrincipal(),
                    command(),
                    context(businessStatus = status),
                ),
            )
        }
    }

    @Test
    fun `denies a participant without explicit send text capability`() {
        assertEquals(
            DurableTextAuthorizationDecision.DENY_CAPABILITY,
            authorizer.decide(
                businessPrincipal(),
                command(),
                context(businessCapabilities = emptySet()),
            ),
        )
    }

    @Test
    fun `denies future conversation types even for an explicitly listed superadmin`() {
        val scope =
            ConversationAccessScope(
                conversationRef = "future-conversation",
                type = ConversationType.SUPERADMIN_BUSINESS,
                platformScopeRef = "platform-alpha",
                organizationScopeRef = "organization-alpha",
                businessScopeRef = "business-alpha",
                participants =
                    setOf(
                        ConversationParticipant("superadmin-alpha", ConnectActorType.SUPERADMIN),
                        ConversationParticipant("business-alpha-agent", ConnectActorType.BUSINESS),
                    ),
            )
        val context =
            DurableTextAuthorizationContext(
                scope = scope,
                conversationStatus = ConversationStatus.ACTIVE,
                participantStates =
                    setOf(
                        state("superadmin-alpha", ConnectActorType.SUPERADMIN),
                        state("business-alpha-agent", ConnectActorType.BUSINESS),
                    ),
            )
        val principal =
            ConnectPrincipal(
                subjectRef = "superadmin-alpha",
                actorType = ConnectActorType.SUPERADMIN,
                platformScopeRef = "platform-alpha",
            )

        assertEquals(
            DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
            authorizer.decide(
                principal,
                command(
                    conversationRef = "future-conversation",
                    senderSubjectRef = "superadmin-alpha",
                ),
                context,
            ),
        )
    }

    @Test
    fun `rejects incomplete or divergent participant command state`() {
        assertFailsWith<IllegalArgumentException> {
            DurableTextAuthorizationContext(
                scope = scope(),
                conversationStatus = ConversationStatus.ACTIVE,
                participantStates = setOf(state("business-alpha-agent", ConnectActorType.BUSINESS)),
            )
        }
    }

    private fun context(
        conversationStatus: ConversationStatus = ConversationStatus.ACTIVE,
        businessStatus: ConversationParticipantStatus = ConversationParticipantStatus.ACTIVE,
        businessCapabilities: Set<ConversationCapability> = setOf(ConversationCapability.SEND_TEXT),
    ): DurableTextAuthorizationContext =
        DurableTextAuthorizationContext(
            scope = scope(),
            conversationStatus = conversationStatus,
            participantStates =
                setOf(
                    state(
                        subjectRef = "business-alpha-agent",
                        actorType = ConnectActorType.BUSINESS,
                        status = businessStatus,
                        capabilities = businessCapabilities,
                    ),
                    state(
                        subjectRef = "client-alpha",
                        actorType = ConnectActorType.CLIENT,
                    ),
                ),
        )

    private fun scope(): ConversationAccessScope =
        ConversationAccessScope(
            conversationRef = "conversation-alpha",
            type = ConversationType.BUSINESS_CLIENT,
            platformScopeRef = "platform-alpha",
            organizationScopeRef = "organization-alpha",
            businessScopeRef = "business-alpha",
            participants =
                setOf(
                    ConversationParticipant("business-alpha-agent", ConnectActorType.BUSINESS),
                    ConversationParticipant("client-alpha", ConnectActorType.CLIENT),
                ),
        )

    private fun state(
        subjectRef: String,
        actorType: ConnectActorType,
        status: ConversationParticipantStatus = ConversationParticipantStatus.ACTIVE,
        capabilities: Set<ConversationCapability> = setOf(ConversationCapability.SEND_TEXT),
    ): ConversationParticipantCommandState =
        ConversationParticipantCommandState(
            subjectRef = subjectRef,
            actorType = actorType,
            status = status,
            capabilities = capabilities,
        )

    private fun businessPrincipal(
        subjectRef: String = "business-alpha-agent",
        organizationScopeRef: String = "organization-alpha",
    ): ConnectPrincipal =
        ConnectPrincipal(
            subjectRef = subjectRef,
            actorType = ConnectActorType.BUSINESS,
            platformScopeRef = "platform-alpha",
            organizationScopeRef = organizationScopeRef,
            businessScopeRef = "business-alpha",
        )

    private fun clientPrincipal(): ConnectPrincipal =
        ConnectPrincipal(
            subjectRef = "client-alpha",
            actorType = ConnectActorType.CLIENT,
            platformScopeRef = "platform-alpha",
        )

    private fun command(
        conversationRef: String = "conversation-alpha",
        senderSubjectRef: String = "business-alpha-agent",
    ): SendTextMessageCommand =
        SendTextMessageCommand(
            conversationRef = conversationRef,
            senderSubjectRef = senderSubjectRef,
            identity = ClientMessageIdentity("client-message-alpha", "idempotency-alpha"),
            body = TextMessageBody("Hello"),
        )
}
