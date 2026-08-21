package com.premierdarkcoffee.nexo.connect.lab.domain.safety

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSafetyModelTest {
    @Test
    fun `block direction is explicit while authorization may match either participant order`() {
        val direction = ConversationBlockDirection(BUSINESS, CLIENT)

        assertTrue(direction.connects(BUSINESS, CLIENT))
        assertTrue(direction.connects(CLIENT, BUSINESS))
        assertFalse(direction.connects(BUSINESS, OTHER_CLIENT))
    }

    @Test
    fun `self block is rejected before persistence`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationBlockDirection(CLIENT, CLIENT)
        }
    }

    @Test
    fun `block lifecycle requires versioned status and matching revocation time`() {
        ConversationBlock(
            blockRef = "block-1",
            scope = SCOPE,
            direction = ConversationBlockDirection(BUSINESS, CLIENT),
            status = ConversationBlockStatus.ACTIVE,
            createdAt = NOW,
            revokedAt = null,
            updatedAt = NOW,
            version = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            ConversationBlock(
                blockRef = "block-1",
                scope = SCOPE,
                direction = ConversationBlockDirection(BUSINESS, CLIENT),
                status = ConversationBlockStatus.REVOKED,
                createdAt = NOW,
                revokedAt = null,
                updatedAt = NOW,
                version = 2,
            )
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T18:00:00Z")
        val SCOPE = ConversationSafetyScope(
            type = ConversationSafetyScopeType.CONVERSATION,
            conversationRef = "conversation-1",
            platformScopeRef = "platform-1",
            organizationScopeRef = "organization-1",
            businessScopeRef = "business-1",
        )
        val BUSINESS = ConversationSafetyParticipant("business-subject-1", ConnectActorType.BUSINESS)
        val CLIENT = ConversationSafetyParticipant("client-subject-1", ConnectActorType.CLIENT)
        val OTHER_CLIENT = ConversationSafetyParticipant("client-subject-2", ConnectActorType.CLIENT)
    }
}
