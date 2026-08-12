package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthenticatedRealtimeRuntimeTest {
    @Test
    fun `authenticates and exchanges a correlated ping against the live container`() = runBlocking {
        val endpoint = System.getenv("CONNECT_LAB_C1_RUNTIME_URL") ?: return@runBlocking
        val token = System.getenv("CONNECT_LAB_C1_RUNTIME_TOKEN") ?: return@runBlocking
        val protocolJson = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }

        try {
            client.webSocket(
                request = {
                    url(endpoint)
                    bearerAuth(token)
                },
            ) {
                val authFrame = assertIs<Frame.Text>(incoming.receive()).readText()
                val auth = protocolJson.decodeFromString<ServerRealtimeFrame>(authFrame)
                assertEquals(ServerRealtimeFrameType.AUTH_OK, auth.type)
                assertEquals(1, auth.protocolMajor)
                assertFalse(authFrame.contains(token))

                send(
                    Frame.Text(
                        protocolJson.encodeToString(
                            ClientRealtimeFrame(
                                protocolMajor = 1,
                                type = ClientRealtimeFrameType.PING,
                                eventId = "runtime-client-event",
                                correlationId = "runtime-correlation",
                            ),
                        ),
                    ),
                )

                val pong =
                    protocolJson.decodeFromString<ServerRealtimeFrame>(
                        assertIs<Frame.Text>(incoming.receive()).readText(),
                    )
                assertEquals(ServerRealtimeFrameType.PONG, pong.type)
                assertEquals("runtime-correlation", pong.correlationId)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `authorizes durable participants and hides denied versus absent subscriptions live`() = runBlocking {
        val endpoint = System.getenv("CONNECT_LAB_C2_RUNTIME_URL") ?: return@runBlocking
        val businessToken = requiredEnvironment("CONNECT_LAB_C2_RUNTIME_BUSINESS_TOKEN")
        val clientToken = requiredEnvironment("CONNECT_LAB_C2_RUNTIME_CLIENT_TOKEN")
        val allowedConversationRef = requiredEnvironment("CONNECT_LAB_C2_ALLOWED_CONVERSATION_REF")
        val deniedConversationRef = requiredEnvironment("CONNECT_LAB_C2_DENIED_CONVERSATION_REF")
        val absentConversationRef = requiredEnvironment("CONNECT_LAB_C2_ABSENT_CONVERSATION_REF")
        val protocolJson = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }

        try {
            client.webSocket(
                request = {
                    url(endpoint)
                    bearerAuth(businessToken)
                },
            ) {
                assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)

                sendSubscription(protocolJson, allowedConversationRef, "business-allowed")
                val allowed = receiveServerFrame(protocolJson)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, allowed.second.type)
                assertEquals(allowedConversationRef, allowed.second.conversationRef)
                assertEquals(0L, allowed.second.lastMessageSequence)

                sendSubscription(protocolJson, deniedConversationRef, "business-denied")
                val denied = receiveServerFrame(protocolJson)
                sendSubscription(protocolJson, absentConversationRef, "business-absent")
                val absent = receiveServerFrame(protocolJson)

                assertEquals("CONVERSATION_NOT_FOUND_OR_DENIED", denied.second.error?.code)
                assertEquals(denied.second.error, absent.second.error)
                assertNull(denied.second.conversationRef)
                assertNull(absent.second.conversationRef)
                assertFalse(denied.first.contains(deniedConversationRef))
                assertFalse(absent.first.contains(absentConversationRef))
                assertFalse(allowed.first.contains(businessToken))

                send(
                    Frame.Text(
                        protocolJson.encodeToString(
                            ClientRealtimeFrame(
                                protocolMajor = 1,
                                type = ClientRealtimeFrameType.PING,
                                eventId = "runtime-c2-after-denial",
                            ),
                        ),
                    ),
                )
                assertEquals(ServerRealtimeFrameType.PONG, receiveServerFrame(protocolJson).second.type)
            }

            client.webSocket(
                request = {
                    url(endpoint)
                    bearerAuth(clientToken)
                },
            ) {
                assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
                sendSubscription(protocolJson, allowedConversationRef, "client-allowed")
                val allowed = receiveServerFrame(protocolJson)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, allowed.second.type)
                assertEquals(allowedConversationRef, allowed.second.conversationRef)
                assertFalse(allowed.first.contains(clientToken))
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `commits once and fans out one durable message event to authorized live subscribers`() = runBlocking {
        val websocketEndpoint = System.getenv("CONNECT_LAB_C3_RUNTIME_URL") ?: return@runBlocking
        val httpEndpoint = requiredEnvironment("CONNECT_LAB_C3_RUNTIME_HTTP_URL").trimEnd('/')
        val businessToken = requiredEnvironment("CONNECT_LAB_C3_RUNTIME_BUSINESS_TOKEN")
        val clientToken = requiredEnvironment("CONNECT_LAB_C3_RUNTIME_CLIENT_TOKEN")
        val conversationRef = requiredEnvironment("CONNECT_LAB_C3_ALLOWED_CONVERSATION_REF")
        val otherConversationRef = requiredEnvironment("CONNECT_LAB_C3_OTHER_CONVERSATION_REF")
        val protocolJson = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }

        try {
            coroutineScope {
                val ready = Channel<Unit>(capacity = 3)
                val firstObservationComplete = Channel<Unit>(capacity = 3)
                val commitComplete = CompletableDeferred<Unit>()
                val replayComplete = CompletableDeferred<Unit>()
                val businessObservation =
                    async {
                        observeMessageCreated(
                            client = client,
                            endpoint = websocketEndpoint,
                            token = businessToken,
                            conversationRef = conversationRef,
                            protocolJson = protocolJson,
                            ready = ready,
                            commitComplete = commitComplete,
                            firstObservationComplete = firstObservationComplete,
                            replayComplete = replayComplete,
                        )
                    }
                val clientObservation =
                    async {
                        observeMessageCreated(
                            client = client,
                            endpoint = websocketEndpoint,
                            token = clientToken,
                            conversationRef = conversationRef,
                            protocolJson = protocolJson,
                            ready = ready,
                            commitComplete = commitComplete,
                            firstObservationComplete = firstObservationComplete,
                            replayComplete = replayComplete,
                        )
                    }
                val crossConversationLeak =
                    async {
                        observeNoMessageCreated(
                            client = client,
                            endpoint = websocketEndpoint,
                            token = businessToken,
                            conversationRef = otherConversationRef,
                            protocolJson = protocolJson,
                            ready = ready,
                            commitComplete = commitComplete,
                            firstObservationComplete = firstObservationComplete,
                        )
                    }

                repeat(3) { ready.receive() }
                val committed =
                    client.post("$httpEndpoint/v1/conversations/$conversationRef/messages") {
                        bearerAuth(businessToken)
                        setBody(runtimeMessageCommand())
                    }
                assertEquals(HttpStatusCode.Created, committed.status)
                val committedRaw = committed.bodyAsText()
                assertFalse(committedRaw.contains(businessToken))
                assertFalse(committedRaw.contains(clientToken))
                val committedBody = protocolJson.parseToJsonElement(committedRaw).jsonObject
                assertEquals("COMMITTED", committedBody.getValue("status").jsonPrimitive.content)
                commitComplete.complete(Unit)
                repeat(3) { firstObservationComplete.receive() }

                val replay =
                    client.post("$httpEndpoint/v1/conversations/$conversationRef/messages") {
                        bearerAuth(businessToken)
                        setBody(runtimeMessageCommand())
                    }
                assertEquals(HttpStatusCode.OK, replay.status)
                val replayBody = protocolJson.parseToJsonElement(replay.bodyAsText()).jsonObject
                assertEquals("REPLAY_EXISTING", replayBody.getValue("status").jsonPrimitive.content)
                replayComplete.complete(Unit)

                val business = businessObservation.await()
                val participantClient = clientObservation.await()
                assertNull(crossConversationLeak.await())
                assertNull(business.second)
                assertNull(participantClient.second)
                assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, business.first.type)
                assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, participantClient.first.type)
                assertEquals(conversationRef, business.first.conversationRef)
                assertEquals(conversationRef, participantClient.first.conversationRef)
                assertEquals(business.first.message, participantClient.first.message)
                assertEquals(
                    committedBody.getValue("serverMessageRef").jsonPrimitive.content,
                    business.first.message?.serverMessageRef,
                )
                assertEquals(
                    committedBody.getValue("sequence").jsonPrimitive.content,
                    business.first.message?.sequence.toString(),
                )
                assertEquals("BUSINESS", business.first.message?.senderActorType)
                assertEquals("TEXT", business.first.message?.messageType)
                assertEquals("C3 durable event", business.first.message?.body)
                assertEquals(1L, business.first.message?.sequence)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendSubscription(
        protocolJson: Json,
        conversationRef: String,
        correlationId: String,
    ) {
        send(
            Frame.Text(
                protocolJson.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                        eventId = "runtime-event-$correlationId",
                        correlationId = correlationId,
                        conversationRef = conversationRef,
                    ),
                ),
            ),
        )
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveServerFrame(
        protocolJson: Json,
    ): Pair<String, ServerRealtimeFrame> {
        val raw = assertIs<Frame.Text>(incoming.receive()).readText()
        return raw to protocolJson.decodeFromString(raw)
    }

    private suspend fun observeMessageCreated(
        client: HttpClient,
        endpoint: String,
        token: String,
        conversationRef: String,
        protocolJson: Json,
        ready: Channel<Unit>,
        commitComplete: CompletableDeferred<Unit>,
        firstObservationComplete: Channel<Unit>,
        replayComplete: CompletableDeferred<Unit>,
    ): Pair<ServerRealtimeFrame, ServerRealtimeFrame?> {
        var created: ServerRealtimeFrame? = null
        var duplicate: ServerRealtimeFrame? = null
        client.webSocket(
            request = {
                url(endpoint)
                bearerAuth(token)
            },
        ) {
            assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
            sendSubscription(protocolJson, conversationRef, "c3-$conversationRef")
            assertEquals(
                ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                receiveServerFrame(protocolJson).second.type,
            )
            ready.send(Unit)
            commitComplete.await()
            created = receiveServerFrame(protocolJson).second
            firstObservationComplete.send(Unit)
            replayComplete.await()
            duplicate = withTimeoutOrNull(600) { receiveServerFrame(protocolJson).second }
        }
        return checkNotNull(created) to duplicate
    }

    private suspend fun observeNoMessageCreated(
        client: HttpClient,
        endpoint: String,
        token: String,
        conversationRef: String,
        protocolJson: Json,
        ready: Channel<Unit>,
        commitComplete: CompletableDeferred<Unit>,
        firstObservationComplete: Channel<Unit>,
    ): ServerRealtimeFrame? {
        var leaked: ServerRealtimeFrame? = null
        client.webSocket(
            request = {
                url(endpoint)
                bearerAuth(token)
            },
        ) {
            assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
            sendSubscription(protocolJson, conversationRef, "c3-other")
            assertEquals(
                ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                receiveServerFrame(protocolJson).second.type,
            )
            ready.send(Unit)
            commitComplete.await()
            leaked = withTimeoutOrNull(600) { receiveServerFrame(protocolJson).second }
            firstObservationComplete.send(Unit)
        }
        return leaked
    }

    private fun runtimeMessageCommand(): String =
        """{"clientMessageRef":"c3-client-message","idempotencyKey":"c3-idempotency-key","body":"C3 durable event"}"""

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")
}
