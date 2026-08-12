package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListConversationsRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurableConversationListingTest {
    @Test
    fun `list request accepts only bounded page sizes`() {
        assertFailsWith<IllegalArgumentException> { ListConversationsRequest(principal, pageSize = 0) }
        assertFailsWith<IllegalArgumentException> { ListConversationsRequest(principal, pageSize = 101) }
        assertEquals(50, ListConversationsRequest(principal).pageSize)
        assertEquals(100, ListConversationsRequest(principal, pageSize = 100).pageSize)
    }

    @Test
    fun `cursor enforces durable reference limits`() {
        assertFailsWith<IllegalArgumentException> { ConversationListCursor(ACTIVITY, " ") }
        assertFailsWith<IllegalArgumentException> { ConversationListCursor(ACTIVITY, "bad\u0000ref") }
        assertFailsWith<IllegalArgumentException> {
            ConversationListCursor(ACTIVITY, "é".repeat(129))
        }
        assertEquals(
            "é".repeat(128),
            ConversationListCursor(ACTIVITY, "é".repeat(128)).conversationRef,
        )
    }

    @Test
    fun `page requires strict descending activity and reference order`() {
        val newest = entry("conversation-a", ACTIVITY.plusSeconds(2), createdAt = ACTIVITY)
        val tiedHigherRef = entry("conversation-c", ACTIVITY.plusSeconds(1), createdAt = ACTIVITY)
        val tiedLowerRef = entry("conversation-b", ACTIVITY.plusSeconds(1), createdAt = ACTIVITY)
        val ordered = listOf(newest, tiedHigherRef, tiedLowerRef)

        val page = DurableConversationListPage(ordered, tiedLowerRef.cursor())
        assertEquals(ordered, page.items)
        assertEquals(tiedLowerRef.cursor(), page.nextCursor)

        assertFailsWith<IllegalArgumentException> {
            DurableConversationListPage(ordered.reversed(), null)
        }
        assertFailsWith<IllegalArgumentException> {
            DurableConversationListPage(listOf(newest, newest), null)
        }
        assertFailsWith<IllegalArgumentException> {
            DurableConversationListPage(ordered, newest.cursor())
        }

        val utf8HigherRef = entry("é", ACTIVITY, createdAt = ACTIVITY)
        val utf8LowerRef = entry("z", ACTIVITY, createdAt = ACTIVITY)
        assertEquals(
            listOf(utf8HigherRef, utf8LowerRef),
            DurableConversationListPage(listOf(utf8HigherRef, utf8LowerRef), null).items,
        )
    }

    @Test
    fun `activity cannot precede durable conversation creation`() {
        assertFailsWith<IllegalArgumentException> {
            entry(
                conversationRef = "conversation-1",
                activityAt = ACTIVITY.minusSeconds(1),
                createdAt = ACTIVITY,
            )
        }
    }

    private fun entry(
        conversationRef: String,
        activityAt: Instant,
        createdAt: Instant,
    ): DurableConversationListEntry =
        DurableConversationListEntry(
            conversation =
                DurableConversationSnapshot(
                    scope =
                        ConversationAccessScope(
                            conversationRef = conversationRef,
                            type = ConversationType.BUSINESS_CLIENT,
                            platformScopeRef = "platform-1",
                            organizationScopeRef = "organization-1",
                            businessScopeRef = "business-1",
                            participants =
                                setOf(
                                    ConversationParticipant("business-1", ConnectActorType.BUSINESS),
                                    ConversationParticipant("client-1", ConnectActorType.CLIENT),
                                ),
                        ),
                    status = ConversationStatus.ACTIVE,
                    participantStates =
                        setOf(
                            ConversationParticipantCommandState(
                                subjectRef = "business-1",
                                actorType = ConnectActorType.BUSINESS,
                                status = ConversationParticipantStatus.ACTIVE,
                                capabilities = setOf(ConversationCapability.SEND_TEXT),
                            ),
                            ConversationParticipantCommandState(
                                subjectRef = "client-1",
                                actorType = ConnectActorType.CLIENT,
                                status = ConversationParticipantStatus.ACTIVE,
                                capabilities = setOf(ConversationCapability.SEND_TEXT),
                            ),
                        ),
                    createdAt = createdAt,
                    lastMessageSequence = ConversationSequence.INITIAL,
                ),
            lastActivityAt = activityAt,
        )

    companion object {
        private val ACTIVITY: Instant = Instant.parse("2026-08-11T23:00:00Z")

        private val principal =
            ConnectPrincipal(
                subjectRef = "business-1",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )
    }
}
