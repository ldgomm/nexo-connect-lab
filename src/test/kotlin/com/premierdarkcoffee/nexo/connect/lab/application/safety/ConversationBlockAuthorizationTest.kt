package com.premierdarkcoffee.nexo.connect.lab.application.safety

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScope
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScopeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationBlockAuthorizationTest {
    @Test
    fun `block authority is deny by default for active missing and unavailable truth`() {
        val expected = mapOf(
            ConversationBlockLookupResult.ActiveBlock to
                ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK,
            ConversationBlockLookupResult.NotFoundOrDenied to
                ConversationBlockAuthorizationDecision.DENY_NOT_FOUND_OR_SCOPE,
            ConversationBlockLookupResult.Unavailable to
                ConversationBlockAuthorizationDecision.DENY_AUTHORITY_UNAVAILABLE,
        )

        expected.forEach { (lookup, decision) ->
            val actual = DenyByDefaultConversationBlockAuthorizer { lookup }.authorize(REQUEST)
            assertEquals(decision, actual)
            assertFalse(actual.allowsCommunication)
        }
    }

    @Test
    fun `lookup failure is contained as unavailable denial`() {
        val authorizer = DenyByDefaultConversationBlockAuthorizer {
            error("simulated block authority outage")
        }

        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_AUTHORITY_UNAVAILABLE,
            authorizer.authorize(REQUEST),
        )
    }

    @Test
    fun `notification mute remains outside durable delivery authorization`() {
        val decision = DenyByDefaultConversationBlockAuthorizer {
            ConversationBlockLookupResult.Clear
        }.authorize(REQUEST)

        assertEquals(ConversationBlockAuthorizationDecision.ALLOW, decision)
        assertTrue(decision.allowsCommunication)
    }

    private companion object {
        val REQUEST = ConversationBlockAuthorizationRequest(
            scope = ConversationSafetyScope(
                type = ConversationSafetyScopeType.CONVERSATION,
                conversationRef = "conversation-1",
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            ),
            first = ConversationSafetyParticipant("business-subject-1", ConnectActorType.BUSINESS),
            second = ConversationSafetyParticipant("client-subject-1", ConnectActorType.CLIENT),
        )
    }
}
