package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableMessageHistoryRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent

data class LoadDurableConversationCatchUpRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val afterSequence: Long,
    val snapshotLastMessageSequence: Long,
) {
    init {
        require(afterSequence >= 0) { "afterSequence must not be negative" }
        require(snapshotLastMessageSequence >= afterSequence) {
            "snapshotLastMessageSequence must not precede afterSequence"
        }
    }
}

sealed interface DurableConversationCatchUpResult {
    data class Loaded(
        val events: List<DurableMessageCreatedEvent>,
        val snapshotLastMessageSequence: Long,
    ) : DurableConversationCatchUpResult

    data object NotFoundOrDenied : DurableConversationCatchUpResult

    data object WindowExceeded : DurableConversationCatchUpResult
}

class DurableConversationCatchUp(
    private val historyRepository: DurableMessageHistoryRepository,
    private val maxCatchUpMessages: Int = DEFAULT_MAX_CATCH_UP_MESSAGES,
) {
    init {
        require(maxCatchUpMessages in 1..MAX_CATCH_UP_MESSAGES) {
            "maxCatchUpMessages must be between 1 and $MAX_CATCH_UP_MESSAGES"
        }
    }

    fun load(request: LoadDurableConversationCatchUpRequest): DurableConversationCatchUpResult {
        if (request.afterSequence == request.snapshotLastMessageSequence) {
            return DurableConversationCatchUpResult.Loaded(
                events = emptyList(),
                snapshotLastMessageSequence = request.snapshotLastMessageSequence,
            )
        }

        val events = mutableListOf<DurableMessageCreatedEvent>()
        var cursor: DurableMessageHistoryCursor? = null
        var previousCursorSequence: Long? = null

        while (true) {
            val page =
                when (
                    val result =
                        historyRepository.load(
                            LoadDurableMessageHistoryRequest(
                                principal = request.principal,
                                conversationRef = request.conversationRef,
                                pageSize = HISTORY_PAGE_SIZE,
                                cursor = cursor,
                            ),
                        )
                ) {
                    is DurableMessageHistoryResult.Loaded -> result.page
                    DurableMessageHistoryResult.NotFoundOrDenied ->
                        return DurableConversationCatchUpResult.NotFoundOrDenied
                }

            page.items
                .asSequence()
                .filter { entry ->
                    entry.sequence.value > request.afterSequence &&
                        entry.sequence.value <= request.snapshotLastMessageSequence
                }
                .forEach { entry ->
                    events +=
                        DurableMessageCreatedEvent(
                            conversationRef = request.conversationRef,
                            serverMessageRef = entry.serverMessageRef,
                            sequence = entry.sequence,
                            senderSubjectRef = entry.senderSubjectRef,
                            senderActorType = entry.senderActorType,
                            body = entry.body,
                            acceptedAtServer = entry.acceptedAtServer,
                        )
                    if (events.size > maxCatchUpMessages) {
                        return DurableConversationCatchUpResult.WindowExceeded
                    }
                }

            val reachedResumeBoundary =
                page.items.any { entry -> entry.sequence.value <= request.afterSequence }
            val nextCursor = page.nextCursor
            if (reachedResumeBoundary || nextCursor == null) break

            check(nextCursor.beforeSequence.value != previousCursorSequence) {
                "Durable history cursor did not advance"
            }
            previousCursorSequence = nextCursor.beforeSequence.value
            cursor = nextCursor
        }

        val orderedEvents =
            events
                .distinctBy { event -> event.sequence.value }
                .sortedBy { event -> event.sequence.value }
        check(orderedEvents.size == events.size) { "Durable catch-up contained duplicate sequences" }

        return DurableConversationCatchUpResult.Loaded(
            events = orderedEvents,
            snapshotLastMessageSequence = request.snapshotLastMessageSequence,
        )
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 100
        const val DEFAULT_MAX_CATCH_UP_MESSAGES = 1_000
        const val MAX_CATCH_UP_MESSAGES = 10_000
    }
}
