package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiInstanceTypingSignalFanoutTest {
    @Test
    fun `typing fans out once across instances and never requires durable payload loading`() = runBlocking {
        val bus = Bus()
        val nodeA = node("instance-a", bus)
        val nodeB = node("instance-b", bus)
        val originEvents = mutableListOf<EphemeralTypingSignal>()
        val remoteEvents = mutableListOf<EphemeralTypingSignal>()
        val origin = nodeA.hub.register(
            principal("origin"),
            MessageCreatedEventSink {
            },
            typingSink = TypingSignalSink(originEvents::add),
        )
        val remote =
            nodeB.hub.register(
                principal("remote"),
                MessageCreatedEventSink { },
                typingSink = TypingSignalSink(remoteEvents::add),
            )
        nodeA.hub.subscribe(origin, CONVERSATION)
        nodeB.hub.subscribe(remote, CONVERSATION)

        nodeA.fanout.publishTyping(signal(), excludedRegistration = origin)
        bus.replay()

        assertEquals(emptyList(), originEvents)
        assertEquals(listOf(signal()), remoteEvents)
    }

    private fun node(instanceRef: String, bus: Bus): Node {
        val hub =
            AuthorizedConversationEventHub(
                ConversationSubscriptionAuthorizer { request ->
                    ConversationSubscriptionAuthorizationResult.Authorized(request.conversationRef, 0)
                },
            )
        val fanout =
            MultiInstanceRealtimeFanout(
                localHub = hub,
                transport = bus.transport(instanceRef),
                payloadLoader = { null },
                codec = RealtimeFanoutEnvelopeCodec(Json),
                typingCodec = TypingSignalEnvelopeCodec(Json),
            )
        fanout.start()
        return Node(hub, fanout)
    }

    private class Bus {
        private val consumers = linkedMapOf<String, suspend (EphemeralRealtimeFanoutDelivery) -> Unit>()
        private var last: EphemeralRealtimeFanoutDelivery? = null

        fun transport(instanceRef: String) = object : EphemeralRealtimeFanoutTransport {
            override val localInstanceRef = instanceRef

            override fun start(consumer: suspend (EphemeralRealtimeFanoutDelivery) -> Unit) {
                consumers[instanceRef] = consumer
            }

            override suspend fun publish(
                channel: RealtimeFanoutChannel,
                payload: String,
            ): EphemeralRealtimeFanoutPublishResult {
                val delivery = EphemeralRealtimeFanoutDelivery(channel, payload)
                last = delivery
                consumers.values.forEach { it(delivery) }
                return EphemeralRealtimeFanoutPublishResult.Published(consumers.size.toLong())
            }

            override fun close() {
                consumers.remove(instanceRef)
            }
        }

        suspend fun replay() {
            val delivery = checkNotNull(last)
            consumers.values.forEach { it(delivery) }
        }
    }

    private data class Node(val hub: AuthorizedConversationEventHub, val fanout: MultiInstanceRealtimeFanout)

    private fun signal() = EphemeralTypingSignal(
        eventId = "typing-event-1",
        conversationRef = CONVERSATION,
        subjectRef = "origin",
        actorType = ConnectActorType.CLIENT,
        active = true,
        expiresInMillis = 6_000,
        occurredAt = Instant.parse("2026-08-14T05:00:00Z"),
        originInstanceRef = "instance-a",
    )

    private fun principal(subjectRef: String) = ConnectPrincipal(
        subjectRef = subjectRef,
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
    )

    private companion object {
        const val CONVERSATION = "conversation-1"
    }
}
