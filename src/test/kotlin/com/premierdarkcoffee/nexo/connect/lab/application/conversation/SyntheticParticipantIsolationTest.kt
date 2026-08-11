package com.premierdarkcoffee.nexo.connect.lab.application.conversation

import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerificationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity.SyntheticTokenVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyntheticParticipantIsolationTest {
    private val authorizer = ConversationParticipantAuthorizer()

    private val verifier =
        SyntheticTokenVerifier(
            mapOf(
                "synthetic-client-alpha-token" to
                    ConnectPrincipal(
                        subjectRef = "synthetic-client-alpha",
                        actorType = ConnectActorType.CLIENT,
                        platformScopeRef = "synthetic-platform",
                    ),
                "synthetic-client-outsider-token" to
                    ConnectPrincipal(
                        subjectRef = "synthetic-client-outsider",
                        actorType = ConnectActorType.CLIENT,
                        platformScopeRef = "synthetic-platform",
                    ),
                "synthetic-business-alpha-token" to
                    ConnectPrincipal(
                        subjectRef = "synthetic-agent-alpha",
                        actorType = ConnectActorType.BUSINESS,
                        platformScopeRef = "synthetic-platform",
                        organizationScopeRef = "synthetic-organization-alpha",
                        businessScopeRef = "synthetic-business-alpha",
                    ),
                "synthetic-business-cross-scope-token" to
                    ConnectPrincipal(
                        subjectRef = "synthetic-agent-cross-scope",
                        actorType = ConnectActorType.BUSINESS,
                        platformScopeRef = "synthetic-platform",
                        organizationScopeRef = "synthetic-organization-beta",
                        businessScopeRef = "synthetic-business-beta",
                    ),
            ),
        )

    private val conversation =
        ConversationAccessScope(
            conversationRef = "synthetic-conversation-alpha",
            type = ConversationType.BUSINESS_CLIENT,
            platformScopeRef = "synthetic-platform",
            organizationScopeRef = "synthetic-organization-alpha",
            businessScopeRef = "synthetic-business-alpha",
            participants =
                setOf(
                    ConversationParticipant("synthetic-client-alpha", ConnectActorType.CLIENT),
                    ConversationParticipant("synthetic-agent-alpha", ConnectActorType.BUSINESS),
                    ConversationParticipant("synthetic-agent-cross-scope", ConnectActorType.BUSINESS),
                ),
        )

    @Test
    fun `allows a known synthetic participant in the matching business scope`() {
        val authentication =
            assertIs<IdentityVerificationResult.Authenticated>(
                verifier.verify("synthetic-business-alpha-token"),
            )

        assertEquals(
            ConversationAccessDecision.ALLOW,
            authorizer.decide(authentication.principal, conversation),
        )
    }

    @Test
    fun `allows a known client only through explicit membership`() {
        val authentication =
            assertIs<IdentityVerificationResult.Authenticated>(
                verifier.verify("synthetic-client-alpha-token"),
            )

        assertEquals(
            ConversationAccessDecision.ALLOW,
            authorizer.decide(authentication.principal, conversation),
        )
    }

    @Test
    fun `denies an authenticated subject who is not a participant`() {
        val authentication =
            assertIs<IdentityVerificationResult.Authenticated>(
                verifier.verify("synthetic-client-outsider-token"),
            )

        assertEquals(
            ConversationAccessDecision.DENY,
            authorizer.decide(authentication.principal, conversation),
        )
    }

    @Test
    fun `denies a listed business agent from another business scope`() {
        val authentication =
            assertIs<IdentityVerificationResult.Authenticated>(
                verifier.verify("synthetic-business-cross-scope-token"),
            )

        assertEquals(
            ConversationAccessDecision.DENY,
            authorizer.decide(authentication.principal, conversation),
        )
    }

    @Test
    fun `denies an unknown synthetic token`() {
        assertEquals(
            IdentityVerificationResult.Denied,
            verifier.verify("synthetic-unknown-token"),
        )
    }
}
