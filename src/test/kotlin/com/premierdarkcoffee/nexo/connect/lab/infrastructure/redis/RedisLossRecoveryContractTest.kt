package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptCursorRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorisedDurableFanoutPayloadLoader
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorizedConversationEventHub
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableConversationCatchUp
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableConversationCatchUpResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableRealtimeFanoutEnvelopeFactory
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableReceiptCursorService
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutDelivery
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutPublishResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutTransport
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.LoadDurableConversationCatchUpRequest
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MessageCreatedEventSink
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MultiInstanceRealtimeFanout
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutChannel
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ReceiptCursorEventSink
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryEntry
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryPage
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RedisLossRecoveryContractTest {
    @Test
    fun `durable catch-up repairs flush loss partition and rejoin without acknowledged loss`() = runBlocking {
        val bus = FailureInjectableFanoutBus()
        val store = DurableRecoveryStore()
        val codec = RealtimeFanoutEnvelopeCodec(Json { ignoreUnknownKeys = false })
        val hubA = authorizedHub()
        val hubB = authorizedHub()
        val transportA = bus.transport(INSTANCE_A)
        val transportB = bus.transport(INSTANCE_B)
        val fanoutA = MultiInstanceRealtimeFanout(hubA, transportA, { store }, codec)
        val fanoutB = MultiInstanceRealtimeFanout(hubB, transportB, { store }, codec)
        val messagesA = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val messagesB = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val receiptsA = CopyOnWriteArrayList<DurableReceiptCursorEvent>()
        val receiptsB = CopyOnWriteArrayList<DurableReceiptCursorEvent>()
        val outsiderMessages = CopyOnWriteArrayList<DurableMessageCreatedEvent>()

        try {
            fanoutA.start()
            fanoutB.start()
            val registrationA =
                hubA.register(
                    businessPrincipal(),
                    MessageCreatedEventSink(messagesA::add),
                    ReceiptCursorEventSink(receiptsA::add),
                )
            val registrationB =
                hubB.register(
                    clientPrincipal(),
                    MessageCreatedEventSink(messagesB::add),
                    ReceiptCursorEventSink(receiptsB::add),
                )
            val outsider = hubB.register(outsiderPrincipal(), MessageCreatedEventSink(outsiderMessages::add))
            hubA.subscribe(registrationA, CONVERSATION_REF)
            hubB.subscribe(registrationB, CONVERSATION_REF)
            hubB.subscribe(outsider, OTHER_CONVERSATION_REF)

            persistAndPublish(store, fanoutA, sequence = 1)

            bus.mode = FailureMode.FLUSHED
            persistAndPublish(store, fanoutA, sequence = 2)
            bus.mode = FailureMode.ACTIVE

            bus.mode = FailureMode.LOST
            persistAndPublish(store, fanoutA, sequence = 3)
            repairFromDurableTruth(store, messagesB, receiptsB, afterSequence = 2, snapshotSequence = 3)

            bus.mode = FailureMode.PARTITIONED
            persistAndPublish(store, fanoutA, sequence = 4)
            repairFromDurableTruth(store, messagesB, receiptsB, afterSequence = 3, snapshotSequence = 4)

            bus.mode = FailureMode.ACTIVE
            persistAndPublish(store, fanoutA, sequence = 5)

            val duplicate =
                codec.encode(
                    DurableRealtimeFanoutEnvelopeFactory(INSTANCE_A).messageCreated(messageEvent(5)),
                )
            transportA.publish(RealtimeFanoutChannel.MESSAGE_CREATED, duplicate)

            val expectedSequences = (1L..5L).toList()
            assertEquals(expectedSequences, store.messageSequences())
            assertEquals(expectedSequences, messagesA.map { it.sequence.value })
            assertEquals(expectedSequences, messagesB.map { it.sequence.value })
            assertEquals(expectedSequences, receiptsA.map { it.cursor.version })
            assertEquals(expectedSequences, receiptsB.map { it.cursor.version })
            assertEquals(expectedSequences.size, store.distinctMessageCount())
            assertEquals(0, outsiderMessages.size)
        } finally {
            transportA.close()
            transportB.close()
        }
    }

    private suspend fun persistAndPublish(
        store: DurableRecoveryStore,
        fanout: MultiInstanceRealtimeFanout,
        sequence: Long,
    ) {
        val message = messageEvent(sequence)
        val receipt = receiptEvent(sequence)
        store.persist(message, receipt)
        fanout.publish(message)
        fanout.publishReceipt(receipt)
    }

    private suspend fun repairFromDurableTruth(
        store: DurableRecoveryStore,
        messages: MutableList<DurableMessageCreatedEvent>,
        receipts: MutableList<DurableReceiptCursorEvent>,
        afterSequence: Long,
        snapshotSequence: Long,
    ) {
        val catchUp = DurableConversationCatchUp(store.historyRepository())
        val loadedMessages =
            assertIs<DurableConversationCatchUpResult.Loaded>(
                catchUp.load(
                    LoadDurableConversationCatchUpRequest(
                        principal = clientPrincipal(),
                        conversationRef = CONVERSATION_REF,
                        afterSequence = afterSequence,
                        snapshotLastMessageSequence = snapshotSequence,
                    ),
                ),
            )
        messages += loadedMessages.events

        val receiptService = DurableReceiptCursorService(store.receiptRepository(), null)
        val loadedReceipts =
            assertIs<LoadDurableReceiptCursorsResult.Loaded>(
                receiptService.load(
                    LoadDurableReceiptCursorsRequest(
                        principal = clientPrincipal(),
                        conversationRef = CONVERSATION_REF,
                    ),
                ),
            )
        receipts += loadedReceipts.cursors.map(::DurableReceiptCursorEvent)
    }

    private class DurableRecoveryStore : AuthorisedDurableFanoutPayloadLoader {
        private val messages = linkedMapOf<String, DurableMessageCreatedEvent>()
        private var latestReceipt: DurableReceiptCursorEvent? = null

        fun persist(message: DurableMessageCreatedEvent, receipt: DurableReceiptCursorEvent) {
            check(messages.putIfAbsent(message.serverMessageRef, message) == null) {
                "A durable message may be persisted only once"
            }
            latestReceipt = receipt
        }

        fun messageSequences(): List<Long> = messages.values.map { it.sequence.value }

        fun distinctMessageCount(): Int = messages.values.map { it.serverMessageRef }.distinct().size

        fun historyRepository(): DurableMessageHistoryRepository = DurableMessageHistoryRepository { request ->
            val beforeSequence = request.cursor?.beforeSequence?.value ?: Long.MAX_VALUE
            val candidates =
                messages.values
                    .asSequence()
                    .filter { it.sequence.value < beforeSequence }
                    .sortedByDescending { it.sequence.value }
                    .map(::historyEntry)
                    .take(request.pageSize + 1)
                    .toList()
            val hasMore = candidates.size > request.pageSize
            val items = candidates.take(request.pageSize)
            DurableMessageHistoryResult.Loaded(
                DurableMessageHistoryPage(
                    items = items,
                    nextCursor = if (hasMore) items.last().cursor() else null,
                ),
            )
        }

        fun receiptRepository(): DurableReceiptCursorRepository = object : DurableReceiptCursorRepository {
            override fun advance(request: AdvanceDurableReceiptCursorRequest): AdvanceDurableReceiptCursorResult =
                AdvanceDurableReceiptCursorResult.NotFoundOrDenied

            override fun load(request: LoadDurableReceiptCursorsRequest): LoadDurableReceiptCursorsResult =
                LoadDurableReceiptCursorsResult.Loaded(
                    latestReceipt?.let { listOf(it.cursor) }.orEmpty(),
                )
        }

        override suspend fun loadMessage(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableMessageCreatedEvent? = messages[envelope.payloadRef]

        override suspend fun loadReceipt(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableReceiptCursorEvent? = latestReceipt?.takeIf { it.cursor.version == envelope.aggregateSequence }

        private fun historyEntry(event: DurableMessageCreatedEvent): DurableMessageHistoryEntry =
            DurableMessageHistoryEntry(
                serverMessageRef = event.serverMessageRef,
                sequence = event.sequence,
                senderSubjectRef = event.senderSubjectRef,
                senderActorType = event.senderActorType,
                body = event.body,
                acceptedAtServer = event.acceptedAtServer,
            )
    }

    private enum class FailureMode {
        ACTIVE,
        FLUSHED,
        LOST,
        PARTITIONED,
    }

    private class FailureInjectableFanoutBus {
        var mode: FailureMode = FailureMode.ACTIVE
        private val consumers = linkedMapOf<String, suspend (EphemeralRealtimeFanoutDelivery) -> Unit>()

        fun transport(instanceRef: String): EphemeralRealtimeFanoutTransport =
            object : EphemeralRealtimeFanoutTransport {
                override val localInstanceRef: String = instanceRef
                private var stopped = false

                override fun start(consumer: suspend (EphemeralRealtimeFanoutDelivery) -> Unit) {
                    check(!stopped && consumers.putIfAbsent(instanceRef, consumer) == null)
                }

                override suspend fun publish(
                    channel: RealtimeFanoutChannel,
                    payload: String,
                ): EphemeralRealtimeFanoutPublishResult {
                    if (stopped) return EphemeralRealtimeFanoutPublishResult.Stopped
                    if (mode == FailureMode.LOST || mode == FailureMode.PARTITIONED) {
                        return EphemeralRealtimeFanoutPublishResult.Unavailable
                    }
                    consumers.values.forEach { consumer ->
                        consumer(EphemeralRealtimeFanoutDelivery(channel, payload))
                    }
                    return EphemeralRealtimeFanoutPublishResult.Published(consumers.size.toLong())
                }

                override fun close() {
                    if (!stopped) consumers.remove(instanceRef)
                    stopped = true
                }
            }
    }

    private fun authorizedHub(): AuthorizedConversationEventHub = AuthorizedConversationEventHub(
        ConversationSubscriptionAuthorizer { request ->
            ConversationSubscriptionAuthorizationResult.Authorized(request.conversationRef, 1)
        },
    )

    private fun messageEvent(sequence: Long): DurableMessageCreatedEvent = DurableMessageCreatedEvent(
        conversationRef = CONVERSATION_REF,
        serverMessageRef = "recovery-message-$sequence",
        sequence = ConversationSequence(sequence),
        senderSubjectRef = "business-1",
        senderActorType = ConnectActorType.BUSINESS,
        body = TextMessageBody("durable recovery body $sequence"),
        acceptedAtServer = BASE_TIME.plusSeconds(sequence),
    )

    private fun receiptEvent(sequence: Long): DurableReceiptCursorEvent = DurableReceiptCursorEvent(
        DurableReceiptCursor(
            conversationRef = CONVERSATION_REF,
            subjectRef = "client-1",
            actorType = ConnectActorType.CLIENT,
            highestDeliveredSequence = sequence,
            highestReadSequence = sequence,
            deliveredAt = BASE_TIME.plusSeconds(sequence),
            readAt = BASE_TIME.plusSeconds(sequence),
            updatedAt = BASE_TIME.plusSeconds(sequence),
            version = sequence,
        ),
    )

    private fun businessPrincipal(): ConnectPrincipal = ConnectPrincipal(
        subjectRef = "business-1",
        actorType = ConnectActorType.BUSINESS,
        platformScopeRef = "platform",
        organizationScopeRef = "organization",
        businessScopeRef = "business",
    )

    private fun clientPrincipal(): ConnectPrincipal = ConnectPrincipal("client-1", ConnectActorType.CLIENT, "platform")

    private fun outsiderPrincipal(): ConnectPrincipal =
        ConnectPrincipal("outsider-1", ConnectActorType.CLIENT, "platform")

    private companion object {
        const val INSTANCE_A = "recovery-instance-a"
        const val INSTANCE_B = "recovery-instance-b"
        const val CONVERSATION_REF = "conversation-recovery-1"
        const val OTHER_CONVERSATION_REF = "conversation-recovery-2"
        val BASE_TIME: Instant = Instant.parse("2026-08-14T05:00:00Z")
    }
}
