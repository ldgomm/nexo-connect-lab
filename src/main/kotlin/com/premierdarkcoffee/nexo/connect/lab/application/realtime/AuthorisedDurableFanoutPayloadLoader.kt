package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AuthorisedDurableFanoutPayloadLoader {
    suspend fun loadMessage(principal: ConnectPrincipal, envelope: RealtimeFanoutEnvelope): DurableMessageCreatedEvent?

    suspend fun loadReceipt(principal: ConnectPrincipal, envelope: RealtimeFanoutEnvelope): DurableReceiptCursorEvent?
}

class PostgresAuthorisedDurableFanoutPayloadLoader(
    private val catchUp: DurableConversationCatchUp,
    private val receiptCursorService: DurableReceiptCursorService,
) : AuthorisedDurableFanoutPayloadLoader {
    override suspend fun loadMessage(
        principal: ConnectPrincipal,
        envelope: RealtimeFanoutEnvelope,
    ): DurableMessageCreatedEvent? {
        val result =
            withContext(Dispatchers.IO) {
                catchUp.load(
                    LoadDurableConversationCatchUpRequest(
                        principal = principal,
                        conversationRef = envelope.conversationRef,
                        afterSequence = envelope.aggregateSequence - 1,
                        snapshotLastMessageSequence = envelope.aggregateSequence,
                    ),
                )
            }
        return (result as? DurableConversationCatchUpResult.Loaded)
            ?.events
            ?.singleOrNull { event ->
                event.serverMessageRef == envelope.payloadRef &&
                    event.sequence.value == envelope.aggregateSequence &&
                    event.acceptedAtServer.toString() == envelope.occurredAt
            }
    }

    override suspend fun loadReceipt(
        principal: ConnectPrincipal,
        envelope: RealtimeFanoutEnvelope,
    ): DurableReceiptCursorEvent? {
        val separator = envelope.payloadRef.indexOf(':')
        if (separator <= 0 || separator == envelope.payloadRef.lastIndex) return null
        val actorType =
            runCatching { ConnectActorType.valueOf(envelope.payloadRef.substring(0, separator)) }.getOrNull()
                ?: return null
        val subjectRef = envelope.payloadRef.substring(separator + 1)
        val result =
            receiptCursorService.load(
                LoadDurableReceiptCursorsRequest(
                    principal = principal,
                    conversationRef = envelope.conversationRef,
                ),
            )
        val cursor =
            (result as? LoadDurableReceiptCursorsResult.Loaded)
                ?.cursors
                ?.singleOrNull { candidate ->
                    candidate.subjectRef == subjectRef &&
                        candidate.actorType == actorType &&
                        candidate.version == envelope.aggregateSequence &&
                        candidate.updatedAt.toString() == envelope.occurredAt
                }
        return cursor?.let(::DurableReceiptCursorEvent)
    }
}
