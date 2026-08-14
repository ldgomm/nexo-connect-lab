package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiInstanceRealtimeFanoutTest {
    @Test
    fun `two instances deliver once exclude origin reauthorise and never leak conversations`() = runBlocking {
        val revokedSubjects = ConcurrentHashMap.newKeySet<String>()
        val bus = InMemoryFanoutBus()
        val message = messageEvent(sequence = 1)
        val receipt = receiptEvent()
        val loader = FixedPayloadLoader(message, receipt)
        val nodeA = node("instance-a", bus, loader, revokedSubjects)
        val nodeB = node("instance-b", bus, loader, revokedSubjects)
        val aMessages = mutableListOf<DurableMessageCreatedEvent>()
        val bMessages = mutableListOf<DurableMessageCreatedEvent>()
        val bSecondDeviceMessages = mutableListOf<DurableMessageCreatedEvent>()
        val leakedMessages = mutableListOf<DurableMessageCreatedEvent>()
        val aReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val aSecondDeviceReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val bReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val bSecondDeviceReceipts = mutableListOf<DurableReceiptCursorEvent>()

        val aOrigin =
            nodeA.hub.register(
                businessPrincipal(),
                MessageCreatedEventSink { aMessages += it },
                ReceiptCursorEventSink { aReceipts += it },
            )
        val aSecondDevice =
            nodeA.hub.register(
                businessPrincipal(),
                MessageCreatedEventSink { },
                ReceiptCursorEventSink { aSecondDeviceReceipts += it },
            )
        val bRegistration =
            nodeB.hub.register(
                clientPrincipal(),
                MessageCreatedEventSink { bMessages += it },
                ReceiptCursorEventSink { bReceipts += it },
            )
        val bSecondDevice =
            nodeB.hub.register(
                clientPrincipal(),
                MessageCreatedEventSink { bSecondDeviceMessages += it },
                ReceiptCursorEventSink { bSecondDeviceReceipts += it },
            )
        val outsider =
            nodeB.hub.register(
                outsiderPrincipal(),
                MessageCreatedEventSink { leakedMessages += it },
            )
        listOf(aOrigin, aSecondDevice).forEach { nodeA.hub.subscribe(it, CONVERSATION_REF) }
        nodeB.hub.subscribe(bRegistration, CONVERSATION_REF)
        nodeB.hub.subscribe(bSecondDevice, CONVERSATION_REF)
        nodeB.hub.subscribe(outsider, OTHER_CONVERSATION_REF)

        nodeA.fanout.publish(message)
        assertEquals(listOf(message), aMessages)
        assertEquals(listOf(message), bMessages)
        assertEquals(listOf(message), bSecondDeviceMessages)
        assertEquals(emptyList(), leakedMessages)

        bus.replayLast()
        assertEquals(1, aMessages.size)
        assertEquals(1, bMessages.size)
        assertEquals(1, bSecondDeviceMessages.size)

        nodeA.fanout.publishReceipt(receipt, excludedRegistration = aOrigin)
        assertEquals(emptyList(), aReceipts)
        assertEquals(listOf(receipt), aSecondDeviceReceipts)
        assertEquals(listOf(receipt), bReceipts)
        assertEquals(listOf(receipt), bSecondDeviceReceipts)
        bus.replayLast()
        assertEquals(1, aSecondDeviceReceipts.size)
        assertEquals(1, bReceipts.size)
        assertEquals(1, bSecondDeviceReceipts.size)

        revokedSubjects += "client-1"
        val secondMessage = messageEvent(sequence = 2)
        loader.message = secondMessage
        nodeA.fanout.publish(secondMessage)
        assertEquals(1, bMessages.size)
        assertEquals(1, bSecondDeviceMessages.size)
        assertTrue(nodeB.authorizationAttempts.get() >= 3)
        assertEquals(0, leakedMessages.size)
        assertFalse(bus.closed)
    }

    @Test
    fun `bounded dedupe expires entries and evicts oldest at capacity`() {
        var now = 0L
        val dedupe = BoundedRealtimeFanoutDedupe(capacity = 2, ttlNanos = 100) { now }

        assertTrue(dedupe.markIfNew("one"))
        assertFalse(dedupe.markIfNew("one"))
        assertTrue(dedupe.markIfNew("two"))
        assertTrue(dedupe.markIfNew("three"))
        assertTrue(dedupe.markIfNew("one"))
        now = 101
        assertTrue(dedupe.markIfNew("three"))
    }

    private fun node(
        instanceRef: String,
        bus: InMemoryFanoutBus,
        loader: FixedPayloadLoader,
        revokedSubjects: Set<String>,
    ): TestNode {
        val authorizationAttempts = AtomicInteger()
        val hub =
            AuthorizedConversationEventHub(
                authorizer =
                ConversationSubscriptionAuthorizer { request ->
                    authorizationAttempts.incrementAndGet()
                    if (request.principal.subjectRef in revokedSubjects) {
                        ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
                    } else {
                        ConversationSubscriptionAuthorizationResult.Authorized(
                            request.conversationRef,
                            loader.message.sequence.value,
                        )
                    }
                },
            )
        val transport = bus.transport(instanceRef)
        val fanout =
            MultiInstanceRealtimeFanout(
                localHub = hub,
                transport = transport,
                payloadLoader = { loader },
                codec = RealtimeFanoutEnvelopeCodec(Json { ignoreUnknownKeys = false }),
            )
        fanout.start()
        return TestNode(hub, fanout, authorizationAttempts)
    }

    private class InMemoryFanoutBus {
        private val consumers = linkedMapOf<String, suspend (EphemeralRealtimeFanoutDelivery) -> Unit>()
        private var lastDelivery: EphemeralRealtimeFanoutDelivery? = null
        var closed = false

        fun transport(instanceRef: String): EphemeralRealtimeFanoutTransport =
            object : EphemeralRealtimeFanoutTransport {
                override val localInstanceRef: String = instanceRef

                override fun start(consumer: suspend (EphemeralRealtimeFanoutDelivery) -> Unit) {
                    check(consumers.putIfAbsent(instanceRef, consumer) == null)
                }

                override suspend fun publish(
                    channel: RealtimeFanoutChannel,
                    payload: String,
                ): EphemeralRealtimeFanoutPublishResult {
                    val delivery = EphemeralRealtimeFanoutDelivery(channel, payload)
                    lastDelivery = delivery
                    consumers.values.forEach { consumer -> consumer(delivery) }
                    return EphemeralRealtimeFanoutPublishResult.Published(consumers.size.toLong())
                }

                override fun close() {
                    consumers.remove(instanceRef)
                    closed = true
                }
            }

        suspend fun replayLast() {
            val delivery = checkNotNull(lastDelivery)
            consumers.values.forEach { consumer -> consumer(delivery) }
        }
    }

    private class FixedPayloadLoader(
        var message: DurableMessageCreatedEvent,
        private val receipt: DurableReceiptCursorEvent,
    ) : AuthorisedDurableFanoutPayloadLoader {
        override suspend fun loadMessage(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableMessageCreatedEvent? = message.takeIf {
            it.conversationRef == envelope.conversationRef &&
                it.serverMessageRef == envelope.payloadRef &&
                it.sequence.value == envelope.aggregateSequence
        }

        override suspend fun loadReceipt(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableReceiptCursorEvent? = receipt.takeIf {
            it.cursor.conversationRef == envelope.conversationRef &&
                it.cursor.version == envelope.aggregateSequence
        }
    }

    private data class TestNode(
        val hub: AuthorizedConversationEventHub,
        val fanout: MultiInstanceRealtimeFanout,
        val authorizationAttempts: AtomicInteger,
    )

    private fun messageEvent(sequence: Long): DurableMessageCreatedEvent = DurableMessageCreatedEvent(
        conversationRef = CONVERSATION_REF,
        serverMessageRef = "message-$sequence",
        sequence = ConversationSequence(sequence),
        senderSubjectRef = "business-1",
        senderActorType = ConnectActorType.BUSINESS,
        body = TextMessageBody("message $sequence"),
        acceptedAtServer = Instant.parse("2026-08-14T04:00:0${sequence}Z"),
    )

    private fun receiptEvent(): DurableReceiptCursorEvent = DurableReceiptCursorEvent(
        DurableReceiptCursor(
            conversationRef = CONVERSATION_REF,
            subjectRef = "client-1",
            actorType = ConnectActorType.CLIENT,
            highestDeliveredSequence = 1,
            highestReadSequence = 1,
            deliveredAt = Instant.parse("2026-08-14T04:01:00Z"),
            readAt = Instant.parse("2026-08-14T04:01:00Z"),
            updatedAt = Instant.parse("2026-08-14T04:01:00Z"),
            version = 1,
        ),
    )

    private fun businessPrincipal() = ConnectPrincipal(
        subjectRef = "business-1",
        actorType = ConnectActorType.BUSINESS,
        platformScopeRef = "platform",
        organizationScopeRef = "organization",
        businessScopeRef = "business",
    )

    private fun clientPrincipal() = ConnectPrincipal(
        subjectRef = "client-1",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform",
    )

    private fun outsiderPrincipal() = ConnectPrincipal(
        subjectRef = "outsider-1",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform",
    )

    private companion object {
        const val CONVERSATION_REF = "conversation-1"
        const val OTHER_CONVERSATION_REF = "conversation-2"
    }
}
