package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryEntry
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryPage
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DurableConversationCatchUpTest {
    @Test
    fun `loads the exclusive durable gap in ascending delivery order`() {
        val catchUp = DurableConversationCatchUp(repository(entries(1L, 2L, 3L, 4L)))

        val loaded =
            assertIs<DurableConversationCatchUpResult.Loaded>(
                catchUp.load(request(afterSequence = 1, snapshotLastMessageSequence = 4)),
            )

        assertEquals(listOf(2L, 3L, 4L), loaded.events.map { it.sequence.value })
        assertEquals(listOf("message-2", "message-3", "message-4"), loaded.events.map { it.serverMessageRef })
        assertEquals(4L, loaded.snapshotLastMessageSequence)
    }

    @Test
    fun `returns an empty synchronized result when the client is current`() {
        val catchUp =
            DurableConversationCatchUp(
                DurableMessageHistoryRepository { error("history must not be loaded") },
            )

        val loaded =
            assertIs<DurableConversationCatchUpResult.Loaded>(
                catchUp.load(request(afterSequence = 7, snapshotLastMessageSequence = 7)),
            )

        assertEquals(emptyList(), loaded.events)
        assertEquals(7L, loaded.snapshotLastMessageSequence)
    }

    @Test
    fun `preserves the absence and denial oracle and bounds replay memory`() {
        val denied =
            DurableConversationCatchUp(
                DurableMessageHistoryRepository { DurableMessageHistoryResult.NotFoundOrDenied },
            ).load(request(afterSequence = 0, snapshotLastMessageSequence = 1))
        val exceeded =
            DurableConversationCatchUp(
                historyRepository = repository(entries(1L, 2L, 3L)),
                maxCatchUpMessages = 2,
            ).load(request(afterSequence = 0, snapshotLastMessageSequence = 3))

        assertEquals(DurableConversationCatchUpResult.NotFoundOrDenied, denied)
        assertEquals(DurableConversationCatchUpResult.WindowExceeded, exceeded)
    }

    private fun repository(entries: List<DurableMessageHistoryEntry>) =
        DurableMessageHistoryRepository { request ->
            val before = request.cursor?.beforeSequence?.value ?: Long.MAX_VALUE
            val records =
                entries
                    .filter { it.sequence.value < before }
                    .sortedByDescending { it.sequence.value }
                    .take(request.pageSize + 1)
            val hasMore = records.size > request.pageSize
            val items = records.take(request.pageSize)
            DurableMessageHistoryResult.Loaded(
                DurableMessageHistoryPage(
                    items = items,
                    nextCursor = if (hasMore) items.last().cursor() else null,
                ),
            )
        }

    private fun entries(vararg sequences: Long): List<DurableMessageHistoryEntry> =
        sequences.map { sequence ->
            DurableMessageHistoryEntry(
                serverMessageRef = "message-$sequence",
                sequence = ConversationSequence(sequence),
                senderSubjectRef = "business-subject",
                senderActorType = ConnectActorType.BUSINESS,
                body = TextMessageBody("body-$sequence"),
                acceptedAtServer = Instant.parse("2026-08-12T10:00:00Z").plusSeconds(sequence),
            )
        }

    private fun request(
        afterSequence: Long,
        snapshotLastMessageSequence: Long,
    ) =
        LoadDurableConversationCatchUpRequest(
            principal =
                ConnectPrincipal(
                    subjectRef = "business-subject",
                    actorType = ConnectActorType.BUSINESS,
                    platformScopeRef = "platform",
                    organizationScopeRef = "organization",
                    businessScopeRef = "business",
                ),
            conversationRef = "conversation-1",
            afterSequence = afterSequence,
            snapshotLastMessageSequence = snapshotLastMessageSequence,
        )
}
