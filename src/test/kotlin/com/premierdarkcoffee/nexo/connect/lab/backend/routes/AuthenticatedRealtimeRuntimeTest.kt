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
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")
}
