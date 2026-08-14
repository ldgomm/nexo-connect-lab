package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorizedConversationEventHub
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MessageCreatedEventSink
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MultiInstanceRealtimeFanout
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.TypingSignalEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.TypingSignalSink
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisTypingSignalFanoutIntegrationTest {
    @Test
    fun `real Redis fans typing across instances with destination authorization and zero leak`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_TYPING_INTEGRATION") != "true") return@runBlocking
        val redisConfig = RedisEphemeralConfig.fromEnvironment()
        val transportA = transport(redisConfig, "typing-fanout-a")
        val transportB = transport(redisConfig, "typing-fanout-b")
        val received = CopyOnWriteArrayList<EphemeralTypingSignal>()
        val leaked = CopyOnWriteArrayList<EphemeralTypingSignal>()
        val hubA = hub()
        val hubB = hub()
        val fanoutA = fanout(hubA, transportA)
        val fanoutB = fanout(hubB, transportB)
        val origin = hubA.register(principal("origin"), MessageCreatedEventSink { })
        val destination =
            hubB.register(
                principal("destination"),
                MessageCreatedEventSink { },
                typingSink = TypingSignalSink { received += it },
            )
        val outsider =
            hubB.register(
                principal("outsider"),
                MessageCreatedEventSink { },
                typingSink = TypingSignalSink { leaked += it },
            )
        hubA.subscribe(origin, CONVERSATION)
        hubB.subscribe(destination, CONVERSATION)
        hubB.subscribe(outsider, "other-conversation")
        try {
            fanoutA.start()
            fanoutB.start()
            delay(500)
            fanoutA.publishTyping(signal(), excludedRegistration = origin)
            withTimeout(3_000) {
                while (received.isEmpty()) delay(20)
            }
            assertEquals(listOf(signal()), received.toList())
            assertEquals(emptyList(), leaked.toList())
        } finally {
            transportA.close()
            transportB.close()
        }
    }

    private fun transport(redisConfig: RedisEphemeralConfig, instanceRef: String) = LettuceRedisRealtimeFanoutTransport(
        redisConfig,
        RedisRealtimeFanoutConfig.fromEnvironment(
            redisConfig,
            mapOf("CONNECT_LAB_INSTANCE_REF" to instanceRef),
        ),
    )

    private fun fanout(hub: AuthorizedConversationEventHub, transport: LettuceRedisRealtimeFanoutTransport) =
        MultiInstanceRealtimeFanout(
            localHub = hub,
            transport = transport,
            payloadLoader = { null },
            codec = RealtimeFanoutEnvelopeCodec(Json),
            typingCodec = TypingSignalEnvelopeCodec(Json),
        )

    private fun hub() = AuthorizedConversationEventHub(
        ConversationSubscriptionAuthorizer { request ->
            if (request.conversationRef == CONVERSATION) {
                ConversationSubscriptionAuthorizationResult.Authorized(CONVERSATION, 0)
            } else {
                ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
            }
        },
    )

    private fun signal() = EphemeralTypingSignal(
        eventId = "typing-integration-event-1",
        conversationRef = CONVERSATION,
        subjectRef = "origin",
        actorType = ConnectActorType.CLIENT,
        active = true,
        expiresInMillis = 6_000,
        occurredAt = Instant.parse("2026-08-14T06:00:00Z"),
        originInstanceRef = "typing-fanout-a",
    )

    private fun principal(subjectRef: String) = ConnectPrincipal(
        subjectRef = subjectRef,
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
    )

    private companion object {
        const val CONVERSATION = "typing-integration-conversation"
    }
}
