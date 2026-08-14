package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorisedDurableFanoutPayloadLoader
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorizedConversationEventHub
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableRealtimeFanoutEnvelopeFactory
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutPublishResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MessageCreatedEventSink
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MultiInstanceRealtimeFanout
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutChannel
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ReceiptCursorEventSink
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedisMultiInstanceRealtimeFanoutIntegrationTest {
    @Test
    fun `two application nodes exchange message and receipt notifications through Redis`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_FANOUT_INTEGRATION") != "true") return@runBlocking

        val redisConfig = RedisEphemeralConfig.fromEnvironment()
        val transportA = transport(redisConfig, "integration-instance-a")
        val transportB = transport(redisConfig, "integration-instance-b")
        val message = messageEvent()
        val receipt = receiptEvent()
        val loader = FixedPayloadLoader(message, receipt)
        val json = Json { ignoreUnknownKeys = false }
        val codec = RealtimeFanoutEnvelopeCodec(json)
        val authorizationCount = AtomicInteger()
        val hubA = authorizedHub(authorizationCount)
        val hubB = authorizedHub(authorizationCount)
        val fanoutA = MultiInstanceRealtimeFanout(hubA, transportA, { loader }, codec)
        val fanoutB = MultiInstanceRealtimeFanout(hubB, transportB, { loader }, codec)
        val messagesA = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val messagesB = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val messagesBSecondDevice = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val leaked = CopyOnWriteArrayList<DurableMessageCreatedEvent>()
        val receiptsA = CopyOnWriteArrayList<DurableReceiptCursorEvent>()
        val receiptsB = CopyOnWriteArrayList<DurableReceiptCursorEvent>()
        val receiptsBSecondDevice = CopyOnWriteArrayList<DurableReceiptCursorEvent>()

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
            val registrationBSecondDevice =
                hubB.register(
                    clientPrincipal(),
                    MessageCreatedEventSink(messagesBSecondDevice::add),
                    ReceiptCursorEventSink(receiptsBSecondDevice::add),
                )
            val outsider = hubB.register(outsiderPrincipal(), MessageCreatedEventSink(leaked::add))
            hubA.subscribe(registrationA, CONVERSATION_REF)
            hubB.subscribe(registrationB, CONVERSATION_REF)
            hubB.subscribe(registrationBSecondDevice, CONVERSATION_REF)
            hubB.subscribe(outsider, OTHER_CONVERSATION_REF)

            awaitSubscribers(transportA)
            fanoutA.publish(message)
            awaitCondition { messagesB.size == 1 && messagesBSecondDevice.size == 1 }
            assertEquals(listOf(message), messagesA.toList())
            assertEquals(listOf(message), messagesB.toList())
            assertEquals(listOf(message), messagesBSecondDevice.toList())
            assertEquals(0, leaked.size)

            val duplicate = codec.encode(
                DurableRealtimeFanoutEnvelopeFactory(transportA.localInstanceRef).messageCreated(message),
            )
            transportA.publish(RealtimeFanoutChannel.MESSAGE_CREATED, duplicate)
            delay(300)
            assertEquals(1, messagesA.size)
            assertEquals(1, messagesB.size)
            assertEquals(1, messagesBSecondDevice.size)

            fanoutA.publishReceipt(receipt)
            awaitCondition { receiptsB.size == 1 && receiptsBSecondDevice.size == 1 }
            assertEquals(listOf(receipt), receiptsA.toList())
            assertEquals(listOf(receipt), receiptsB.toList())
            assertEquals(listOf(receipt), receiptsBSecondDevice.toList())
            assertTrue(authorizationCount.get() >= 4)
            assertEquals(0, leaked.size)
        } finally {
            transportA.close()
            transportB.close()
        }

        assertEquals(
            EphemeralRealtimeFanoutPublishResult.Stopped,
            transportA.publish(RealtimeFanoutChannel.MESSAGE_CREATED, "{}"),
        )
    }

    private fun transport(
        redisConfig: RedisEphemeralConfig,
        instanceRef: String,
    ): LettuceRedisRealtimeFanoutTransport = LettuceRedisRealtimeFanoutTransport(
        redisConfig = redisConfig,
        fanoutConfig =
        RedisRealtimeFanoutConfig(
            instanceRef = instanceRef,
            messageCreatedChannel = RedisRealtimeFanoutConfig.EXPECTED_MESSAGE_CHANNEL,
            receiptAdvancedChannel = RedisRealtimeFanoutConfig.EXPECTED_RECEIPT_CHANNEL,
        ),
    )

    private suspend fun awaitSubscribers(transport: LettuceRedisRealtimeFanoutTransport) {
        repeat(50) {
            val result = transport.publish(RealtimeFanoutChannel.MESSAGE_CREATED, "{}")
            if (result is EphemeralRealtimeFanoutPublishResult.Published && result.subscriberCount >= 2) return
            delay(100)
        }
        error("Redis fan-out subscribers did not become ready")
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            delay(100)
        }
        error("Expected fan-out delivery was not observed")
    }

    private fun authorizedHub(authorizationCount: AtomicInteger): AuthorizedConversationEventHub =
        AuthorizedConversationEventHub(
            authorizer =
            ConversationSubscriptionAuthorizer { request ->
                authorizationCount.incrementAndGet()
                ConversationSubscriptionAuthorizationResult.Authorized(request.conversationRef, 1)
            },
        )

    private class FixedPayloadLoader(
        private val message: DurableMessageCreatedEvent,
        private val receipt: DurableReceiptCursorEvent,
    ) : AuthorisedDurableFanoutPayloadLoader {
        override suspend fun loadMessage(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableMessageCreatedEvent? = message.takeIf { it.serverMessageRef == envelope.payloadRef }

        override suspend fun loadReceipt(
            principal: ConnectPrincipal,
            envelope: RealtimeFanoutEnvelope,
        ): DurableReceiptCursorEvent? = receipt.takeIf { it.cursor.version == envelope.aggregateSequence }
    }

    private fun messageEvent() = DurableMessageCreatedEvent(
        conversationRef = CONVERSATION_REF,
        serverMessageRef = "integration-message-1",
        sequence = ConversationSequence(1),
        senderSubjectRef = "business-1",
        senderActorType = ConnectActorType.BUSINESS,
        body = TextMessageBody("loaded from durable storage"),
        acceptedAtServer = Instant.parse("2026-08-14T04:10:00Z"),
    )

    private fun receiptEvent() = DurableReceiptCursorEvent(
        DurableReceiptCursor(
            conversationRef = CONVERSATION_REF,
            subjectRef = "client-1",
            actorType = ConnectActorType.CLIENT,
            highestDeliveredSequence = 1,
            highestReadSequence = 1,
            deliveredAt = Instant.parse("2026-08-14T04:11:00Z"),
            readAt = Instant.parse("2026-08-14T04:11:00Z"),
            updatedAt = Instant.parse("2026-08-14T04:11:00Z"),
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

    private fun clientPrincipal() = ConnectPrincipal("client-1", ConnectActorType.CLIENT, "platform")

    private fun outsiderPrincipal() = ConnectPrincipal("outsider-1", ConnectActorType.CLIENT, "platform")

    private companion object {
        const val CONVERSATION_REF = "conversation-integration-1"
        const val OTHER_CONVERSATION_REF = "conversation-integration-2"
    }
}
