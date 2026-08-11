package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationAccessDecision
import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationParticipantAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationTopologyContractTest {
    private val authorizer = ConversationParticipantAuthorizer()

    @Test
    fun `reserves all four canonical conversation types but implements only business client`() {
        assertEquals(
            setOf(
                ConversationType.BUSINESS_CLIENT,
                ConversationType.SUPERADMIN_ADMIN,
                ConversationType.SUPERADMIN_BUSINESS,
                ConversationType.ADMIN_BUSINESS,
            ),
            ConversationType.entries.toSet(),
        )
        assertTrue(ConversationType.BUSINESS_CLIENT.isImplemented)
        assertFalse(ConversationType.SUPERADMIN_ADMIN.isImplemented)
        assertFalse(ConversationType.SUPERADMIN_BUSINESS.isImplemented)
        assertFalse(ConversationType.ADMIN_BUSINESS.isImplemented)
    }

    @Test
    fun `rejects participants that do not match the declared topology`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationAccessScope(
                conversationRef = "conversation-invalid",
                type = ConversationType.BUSINESS_CLIENT,
                platformScopeRef = "platform-alpha",
                organizationScopeRef = "organization-alpha",
                businessScopeRef = "business-alpha",
                participants =
                    setOf(
                        ConversationParticipant("admin-alpha", ConnectActorType.ADMIN),
                        ConversationParticipant("client-alpha", ConnectActorType.CLIENT),
                    ),
            )
        }
    }

    @Test
    fun `denies implicit superadmin access to a business client conversation`() {
        val conversation = businessClientConversation()
        val superadmin =
            ConnectPrincipal(
                subjectRef = "superadmin-alpha",
                actorType = ConnectActorType.SUPERADMIN,
                platformScopeRef = "platform-alpha",
            )

        assertEquals(
            ConversationAccessDecision.DENY,
            authorizer.decide(superadmin, conversation),
        )
    }

    @Test
    fun `keeps every future channel denied even for an explicit matching participant`() {
        val cases =
            listOf(
                futureConversationCase(
                    type = ConversationType.SUPERADMIN_ADMIN,
                    firstActorType = ConnectActorType.SUPERADMIN,
                    secondActorType = ConnectActorType.ADMIN,
                    businessScopeRef = null,
                ),
                futureConversationCase(
                    type = ConversationType.SUPERADMIN_BUSINESS,
                    firstActorType = ConnectActorType.SUPERADMIN,
                    secondActorType = ConnectActorType.BUSINESS,
                    businessScopeRef = "business-alpha",
                ),
                futureConversationCase(
                    type = ConversationType.ADMIN_BUSINESS,
                    firstActorType = ConnectActorType.ADMIN,
                    secondActorType = ConnectActorType.BUSINESS,
                    businessScopeRef = "business-alpha",
                ),
            )

        cases.forEach { (principal, conversation) ->
            assertEquals(
                ConversationAccessDecision.DENY,
                authorizer.decide(principal, conversation),
                "Future type ${conversation.type} must remain denied",
            )
        }
    }

    private fun futureConversationCase(
        type: ConversationType,
        firstActorType: ConnectActorType,
        secondActorType: ConnectActorType,
        businessScopeRef: String?,
    ): Pair<ConnectPrincipal, ConversationAccessScope> {
        val principal =
            when (firstActorType) {
                ConnectActorType.SUPERADMIN ->
                    ConnectPrincipal(
                        subjectRef = "first-actor",
                        actorType = firstActorType,
                        platformScopeRef = "platform-alpha",
                    )

                ConnectActorType.ADMIN ->
                    ConnectPrincipal(
                        subjectRef = "first-actor",
                        actorType = firstActorType,
                        platformScopeRef = "platform-alpha",
                        organizationScopeRef = "organization-alpha",
                    )

                ConnectActorType.BUSINESS,
                ConnectActorType.CLIENT,
                -> error("Unsupported first actor in a future channel test")
            }

        return principal to
            ConversationAccessScope(
                conversationRef = "conversation-${type.name.lowercase()}",
                type = type,
                platformScopeRef = "platform-alpha",
                organizationScopeRef = "organization-alpha",
                businessScopeRef = businessScopeRef,
                participants =
                    setOf(
                        ConversationParticipant("first-actor", firstActorType),
                        ConversationParticipant("second-actor", secondActorType),
                    ),
            )
    }

    @Test
    fun `denies a listed business participant from another organization`() {
        val principal =
            ConnectPrincipal(
                subjectRef = "business-alpha",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-alpha",
                organizationScopeRef = "organization-beta",
                businessScopeRef = "business-alpha",
            )

        assertEquals(
            ConversationAccessDecision.DENY,
            authorizer.decide(principal, businessClientConversation()),
        )
    }

    private fun businessClientConversation() =
        ConversationAccessScope(
            conversationRef = "conversation-business-client",
            type = ConversationType.BUSINESS_CLIENT,
            platformScopeRef = "platform-alpha",
            organizationScopeRef = "organization-alpha",
            businessScopeRef = "business-alpha",
            participants =
                setOf(
                    ConversationParticipant("business-alpha", ConnectActorType.BUSINESS),
                    ConversationParticipant("client-alpha", ConnectActorType.CLIENT),
                ),
        )
}
