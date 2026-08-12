package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AuthenticatedRealtimeCatchUpRuntimeTest {
    @Test
    fun `replays the exclusive durable gap then continues with ordered live delivery`() = runBlocking {
        val websocketEndpoint = System.getenv("CONNECT_LAB_C4_RUNTIME_URL") ?: return@runBlocking
        val httpEndpoint = requiredEnvironment("CONNECT_LAB_C4_RUNTIME_HTTP_URL").trimEnd('/')
        val businessToken = requiredEnvironment("CONNECT_LAB_C4_RUNTIME_BUSINESS_TOKEN")
        val conversationRef = requiredEnvironment("CONNECT_LAB_C4_CONVERSATION_REF")
        val protocolJson = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }

        try {
            val committed =
                (1..3).map { sequence ->
                    commitMessage(
                        client = client,
                        httpEndpoint = httpEndpoint,
                        token = businessToken,
                        conversationRef = conversationRef,
                        index = sequence,
                        protocolJson = protocolJson,
                    )
                }
            assertEquals(listOf("1", "2", "3"), committed.map { it.getValue("sequence").jsonPrimitive.content })

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
                sendSubscription(protocolJson, conversationRef, afterSequence = 1, correlationId = "catch-up-1")

                val subscribed = receiveServerFrame(protocolJson)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, subscribed.second.type)
                assertEquals(3L, subscribed.second.lastMessageSequence)
                val replayed = listOf(receiveServerFrame(protocolJson), receiveServerFrame(protocolJson))
                assertEquals(listOf(2L, 3L), replayed.map { it.second.message?.sequence })
                val synced = receiveServerFrame(protocolJson)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, synced.second.type)
                assertEquals(3L, synced.second.lastMessageSequence)
                assertEquals(2, synced.second.replayedMessageCount)

                val fourth =
                    commitMessage(
                        client = client,
                        httpEndpoint = httpEndpoint,
                        token = businessToken,
                        conversationRef = conversationRef,
                        index = 4,
                        protocolJson = protocolJson,
                    )
                assertEquals("4", fourth.getValue("sequence").jsonPrimitive.content)
                val live = receiveServerFrame(protocolJson)
                assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, live.second.type)
                assertEquals(4L, live.second.message?.sequence)

                (replayed + synced + live).forEach { observed ->
                    assertFalse(observed.first.contains(businessToken))
                }
            }

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                receiveServerFrame(protocolJson)
                sendSubscription(protocolJson, conversationRef, afterSequence = 4, correlationId = "current-4")
                val subscribed = receiveServerFrame(protocolJson).second
                val synced = receiveServerFrame(protocolJson).second
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, subscribed.type)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, synced.type)
                assertEquals(0, synced.replayedMessageCount)
                assertEquals(4L, synced.lastMessageSequence)
            }

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                receiveServerFrame(protocolJson)
                sendSubscription(protocolJson, conversationRef, afterSequence = 0, correlationId = "resync-0")
                assertEquals(
                    ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                    receiveServerFrame(protocolJson).second.type,
                )
                val resynced = (1..4).map { receiveServerFrame(protocolJson).second }
                assertEquals(listOf(1L, 2L, 3L, 4L), resynced.map { it.message?.sequence })
                val boundary = receiveServerFrame(protocolJson).second
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, boundary.type)
                assertEquals(4, boundary.replayedMessageCount)
            }

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                receiveServerFrame(protocolJson)
                sendSubscription(protocolJson, conversationRef, afterSequence = 99, correlationId = "future-99")
                val error = receiveServerFrame(protocolJson).second
                assertEquals(ServerRealtimeFrameType.ERROR, error.type)
                assertEquals("INVALID_RESUME_SEQUENCE", error.error?.code)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun commitMessage(
        client: HttpClient,
        httpEndpoint: String,
        token: String,
        conversationRef: String,
        index: Int,
        protocolJson: Json,
    ) =
        client.post("$httpEndpoint/v1/conversations/$conversationRef/messages") {
            bearerAuth(token)
            setBody(
                """{"clientMessageRef":"c4-client-$index","idempotencyKey":"c4-key-$index","body":"C4 durable $index"}""",
            )
        }.let { response ->
            assertEquals(HttpStatusCode.Created, response.status)
            protocolJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }

    private suspend fun DefaultClientWebSocketSession.sendSubscription(
        protocolJson: Json,
        conversationRef: String,
        afterSequence: Long,
        correlationId: String,
    ) {
        send(
            Frame.Text(
                protocolJson.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                        eventId = "event-$correlationId",
                        correlationId = correlationId,
                        conversationRef = conversationRef,
                        afterSequence = afterSequence,
                    ),
                ),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveServerFrame(
        protocolJson: Json,
    ): Pair<String, ServerRealtimeFrame> {
        val raw = assertIs<Frame.Text>(incoming.receive()).readText()
        return raw to protocolJson.decodeFromString(raw)
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")
}
