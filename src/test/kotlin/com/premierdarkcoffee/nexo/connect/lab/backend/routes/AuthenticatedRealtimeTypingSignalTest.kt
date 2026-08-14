package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.typing.EphemeralTypingLeaseStore
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseHandle
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefreshResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseReleaseResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.installAuthenticatedRealtimeTransport
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity.SyntheticTokenVerifier
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticatedRealtimeTypingSignalTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `authorised subscribed devices receive start refresh stop while origin and history remain untouched`() =
        testApplication {
            val store = RecordingTypingStore()
            application {
                installAuthenticatedRealtimeTransport(
                    identityVerifier =
                    SyntheticTokenVerifier(
                        mapOf(
                            TOKEN_A to principal("client-a"),
                            TOKEN_B to principal("client-b"),
                        ),
                    ),
                    conversationSubscriptionAuthorizer =
                    ConversationSubscriptionAuthorizer { request ->
                        if (request.conversationRef == CONVERSATION) {
                            ConversationSubscriptionAuthorizationResult.Authorized(CONVERSATION, 0)
                        } else {
                            ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
                        }
                    },
                    typingLeaseStore = store,
                )
                configureRouting()
            }
            val client = createClient { install(WebSockets) }
            val origin = client.session(TOKEN_A)
            val recipient = client.session(TOKEN_B)
            assertEquals(ServerRealtimeFrameType.AUTH_OK, origin.receiveFrame().type)
            assertEquals(ServerRealtimeFrameType.AUTH_OK, recipient.receiveFrame().type)
            origin.subscribe("subscribe-a")
            recipient.subscribe("subscribe-b")
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, origin.receiveFrame().type)
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, recipient.receiveFrame().type)

            origin.typing(ClientRealtimeFrameType.TYPING_START, "typing-start")
            val started = recipient.receiveFrame()
            assertEquals(ServerRealtimeFrameType.TYPING_STATE_CHANGED, started.type)
            assertEquals("client-a", started.typing?.subjectRef)
            assertEquals(true, started.typing?.active)
            assertEquals(6_000, started.typing?.expiresInMillis)

            origin.typing(ClientRealtimeFrameType.TYPING_START, "typing-refresh")
            assertEquals(true, recipient.receiveFrame().typing?.active)
            origin.typing(ClientRealtimeFrameType.TYPING_STOP, "typing-stop")
            assertEquals(false, recipient.receiveFrame().typing?.active)

            origin.close()
            recipient.close()
            withTimeout(2_000) {
                while (store.stopCount.get() == 0) delay(10)
            }
            assertEquals(1, store.startCount.get())
            assertEquals(1, store.refreshCount.get())
            assertTrue(store.stopCount.get() >= 1)
        }

    private suspend fun io.ktor.client.HttpClient.session(token: String): DefaultClientWebSocketSession =
        webSocketSession {
            url("/v1/realtime")
            bearerAuth(token)
        }

    private suspend fun DefaultClientWebSocketSession.receiveFrame(): ServerRealtimeFrame {
        val text = incoming.receive() as Frame.Text
        return json.decodeFromString(text.readText())
    }

    private suspend fun DefaultClientWebSocketSession.subscribe(eventId: String) {
        send(
            Frame.Text(
                json.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                        eventId = eventId,
                        conversationRef = CONVERSATION,
                    ),
                ),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.typing(type: String, eventId: String) {
        send(
            Frame.Text(
                json.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = type,
                        eventId = eventId,
                        conversationRef = CONVERSATION,
                        typingSchemaVersion = 1,
                    ),
                ),
            ),
        )
    }

    private class RecordingTypingStore : EphemeralTypingLeaseStore {
        override val leaseTtl: Duration = Duration.ofSeconds(6)
        val startCount = AtomicInteger()
        val refreshCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override suspend fun start(target: TypingLeaseTarget): TypingLeaseAcquireResult {
            startCount.incrementAndGet()
            return TypingLeaseAcquireResult.Acquired(
                TypingLeaseHandle(
                    target = target,
                    ownerInstanceRef = "test-instance",
                    leaseRef = "typing_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                ),
                leaseTtl.toMillis(),
            )
        }

        override suspend fun refresh(handle: TypingLeaseHandle): TypingLeaseRefreshResult {
            refreshCount.incrementAndGet()
            return TypingLeaseRefreshResult.Refreshed(leaseTtl.toMillis())
        }

        override suspend fun stop(handle: TypingLeaseHandle): TypingLeaseReleaseResult {
            stopCount.incrementAndGet()
            return TypingLeaseReleaseResult.APPLIED
        }

        override fun close() = Unit
    }

    private fun principal(subjectRef: String) = ConnectPrincipal(
        subjectRef = subjectRef,
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
    )

    private companion object {
        const val TOKEN_A = "typing-token-a-0123456789abcdef"
        const val TOKEN_B = "typing-token-b-0123456789abcdef"
        const val CONVERSATION = "conversation-1"
    }
}
