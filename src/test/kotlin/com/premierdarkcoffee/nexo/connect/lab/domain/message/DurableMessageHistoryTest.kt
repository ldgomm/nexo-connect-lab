package com.premierdarkcoffee.nexo.connect.lab.domain.message

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableMessageHistoryRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurableMessageHistoryTest {
    @Test
    fun `request enforces bounded conversation and page contracts`() {
        assertFailsWith<IllegalArgumentException> { request(conversationRef = " ") }
        assertFailsWith<IllegalArgumentException> { request(conversationRef = "bad\u0000ref") }
        assertFailsWith<IllegalArgumentException> { request(conversationRef = "é".repeat(129)) }
        assertEquals("é".repeat(128), request(conversationRef = "é".repeat(128)).conversationRef)
        assertFailsWith<IllegalArgumentException> { request(pageSize = 0) }
        assertFailsWith<IllegalArgumentException> { request(pageSize = 101) }
    }

    @Test
    fun `cursor requires a positive durable sequence`() {
        assertFailsWith<IllegalArgumentException> {
            DurableMessageHistoryCursor(ConversationSequence.INITIAL)
        }
        assertEquals(
            1,
            DurableMessageHistoryCursor(ConversationSequence(1)).beforeSequence.value,
        )
    }

    @Test
    fun `page enforces descending sequence uniqueness and final cursor`() {
        val newest = entry(sequence = 3, serverMessageRef = "server-3")
        val middle = entry(sequence = 2, serverMessageRef = "server-2")
        val oldest = entry(sequence = 1, serverMessageRef = "server-1")

        val page = DurableMessageHistoryPage(listOf(newest, middle, oldest), oldest.cursor())
        assertEquals(listOf(3L, 2L, 1L), page.items.map { it.sequence.value })
        assertEquals(1, page.nextCursor?.beforeSequence?.value)

        assertFailsWith<IllegalArgumentException> {
            DurableMessageHistoryPage(listOf(oldest, newest), null)
        }
        assertFailsWith<IllegalArgumentException> {
            DurableMessageHistoryPage(
                listOf(newest, newest.copy(sequence = ConversationSequence(2))),
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DurableMessageHistoryPage(listOf(newest, middle), newest.cursor())
        }
    }

    @Test
    fun `history entries enforce durable sender and reference boundaries`() {
        assertFailsWith<IllegalArgumentException> { entry(serverMessageRef = " ") }
        assertFailsWith<IllegalArgumentException> { entry(senderSubjectRef = "bad\u0000subject") }
        assertFailsWith<IllegalArgumentException> { entry(serverMessageRef = "é".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { entry(sequence = 0) }
        assertFailsWith<IllegalArgumentException> {
            entry(senderActorType = ConnectActorType.ADMIN)
        }
    }

    private fun request(
        conversationRef: String = "conversation-1",
        pageSize: Int = LoadDurableMessageHistoryRequest.DEFAULT_PAGE_SIZE,
    ): LoadDurableMessageHistoryRequest =
        LoadDurableMessageHistoryRequest(
            principal = businessPrincipal,
            conversationRef = conversationRef,
            pageSize = pageSize,
        )

    private fun entry(
        sequence: Long = 1,
        serverMessageRef: String = "server-1",
        senderSubjectRef: String = "business-subject-1",
        senderActorType: ConnectActorType = ConnectActorType.BUSINESS,
    ): DurableMessageHistoryEntry =
        DurableMessageHistoryEntry(
            serverMessageRef = serverMessageRef,
            sequence = ConversationSequence(sequence),
            senderSubjectRef = senderSubjectRef,
            senderActorType = senderActorType,
            body = TextMessageBody("Durable body"),
            acceptedAtServer = Instant.parse("2026-08-12T00:00:00Z"),
        )

    companion object {
        private val businessPrincipal =
            ConnectPrincipal(
                subjectRef = "business-subject-1",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )
    }
}
