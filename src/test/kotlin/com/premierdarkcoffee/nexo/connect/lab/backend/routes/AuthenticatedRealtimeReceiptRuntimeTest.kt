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

class AuthenticatedRealtimeReceiptRuntimeTest {
    @Test
    fun `delivery and read cursors advance durably without regression or duplicate publication`() = runBlocking {
        val websocketEndpoint = System.getenv("CONNECT_LAB_C5_RUNTIME_URL") ?: return@runBlocking
        val httpEndpoint = requiredEnvironment("CONNECT_LAB_C5_RUNTIME_HTTP_URL").trimEnd('/')
        val businessToken = requiredEnvironment("CONNECT_LAB_C5_RUNTIME_BUSINESS_TOKEN")
        val clientToken = requiredEnvironment("CONNECT_LAB_C5_RUNTIME_CLIENT_TOKEN")
        val conversationRef = requiredEnvironment("CONNECT_LAB_C5_CONVERSATION_REF")
        val protocolJson = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }

        try {
            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                val businessSession = this
                assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
                subscribeFromZero(protocolJson, conversationRef, "business-subscribe")
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, receiveServerFrame(protocolJson).second.type)
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, receiveServerFrame(protocolJson).second.type)

                client.webSocket(
                    request = {
                        url(websocketEndpoint)
                        bearerAuth(clientToken)
                    },
                ) {
                    val clientSession = this
                    assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(protocolJson).second.type)
                    subscribeFromZero(protocolJson, conversationRef, "client-subscribe")
                    assertEquals(
                        ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                        receiveServerFrame(protocolJson).second.type,
                    )
                    assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, receiveServerFrame(protocolJson).second.type)

                    (1..2).forEach { index ->
                        val committed =
                            commitMessage(
                                httpClient = client,
                                httpEndpoint = httpEndpoint,
                                token = businessToken,
                                conversationRef = conversationRef,
                                index = index,
                                protocolJson = protocolJson,
                            )
                        assertEquals(index.toString(), committed.getValue("sequence").jsonPrimitive.content)
                        assertEquals(
                            index.toLong(),
                            businessSession.receiveServerFrame(protocolJson).second.message?.sequence,
                        )
                        assertEquals(index.toLong(), clientSession.receiveServerFrame(protocolJson).second.message?.sequence)
                    }

                    sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.ACK_DELIVERY, 2, "delivery-2")
                    val clientDelivery = receiveServerFrame(protocolJson)
                    val businessDelivery = businessSession.receiveServerFrame(protocolJson)
                    assertReceipt(clientDelivery.second, delivered = 2, read = 0, version = 1)
                    assertReceipt(businessDelivery.second, delivered = 2, read = 0, version = 1)

                    sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.ACK_DELIVERY, 1, "late-delivery-1")
                    assertReceipt(receiveServerFrame(protocolJson).second, delivered = 2, read = 0, version = 1)

                    sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.UPDATE_READ_CURSOR, 2, "read-2")
                    val clientRead = receiveServerFrame(protocolJson)
                    val businessRead = businessSession.receiveServerFrame(protocolJson)
                    assertReceipt(clientRead.second, delivered = 2, read = 2, version = 2)
                    assertReceipt(businessRead.second, delivered = 2, read = 2, version = 2)

                    sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.UPDATE_READ_CURSOR, 1, "late-read-1")
                    assertReceipt(receiveServerFrame(protocolJson).second, delivered = 2, read = 2, version = 2)

                    sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.ACK_DELIVERY, 99, "future-99")
                    val future = receiveServerFrame(protocolJson).second
                    assertEquals(ServerRealtimeFrameType.ERROR, future.type)
                    assertEquals("INVALID_RECEIPT_SEQUENCE", future.error?.code)

                    listOf(clientDelivery.first, businessDelivery.first, clientRead.first, businessRead.first).forEach { raw ->
                        assertFalse(raw.contains(businessToken))
                        assertFalse(raw.contains(clientToken))
                    }
                }
            }

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                receiveServerFrame(protocolJson)
                sendReceipt(protocolJson, conversationRef, ClientRealtimeFrameType.ACK_DELIVERY, 1, "not-subscribed")
                val denied = receiveServerFrame(protocolJson).second
                assertEquals(ServerRealtimeFrameType.ERROR, denied.type)
                assertEquals("CONVERSATION_NOT_SUBSCRIBED", denied.error?.code)
            }

            client.webSocket(
                request = {
                    url(websocketEndpoint)
                    bearerAuth(businessToken)
                },
            ) {
                receiveServerFrame(protocolJson)
                sendSubscription(protocolJson, conversationRef, afterSequence = 2, correlationId = "receipt-reconnect")
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, receiveServerFrame(protocolJson).second.type)
                val durableSnapshot = receiveServerFrame(protocolJson)
                assertReceipt(durableSnapshot.second, delivered = 2, read = 2, version = 2)
                assertFalse(durableSnapshot.first.contains(businessToken))
                assertFalse(durableSnapshot.first.contains(clientToken))
                assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, receiveServerFrame(protocolJson).second.type)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun commitMessage(
        httpClient: HttpClient,
        httpEndpoint: String,
        token: String,
        conversationRef: String,
        index: Int,
        protocolJson: Json,
    ) =
        httpClient.post("$httpEndpoint/v1/conversations/$conversationRef/messages") {
            bearerAuth(token)
            setBody(
                """{"clientMessageRef":"c5-client-$index","idempotencyKey":"c5-key-$index","body":"C5 durable $index"}""",
            )
        }.let { response ->
            assertEquals(HttpStatusCode.Created, response.status)
            protocolJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }

    private suspend fun DefaultClientWebSocketSession.subscribeFromZero(
        protocolJson: Json,
        conversationRef: String,
        correlationId: String,
    ) = sendSubscription(protocolJson, conversationRef, afterSequence = 0, correlationId = correlationId)

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

    private suspend fun DefaultClientWebSocketSession.sendReceipt(
        protocolJson: Json,
        conversationRef: String,
        type: String,
        sequence: Long,
        correlationId: String,
    ) {
        send(
            Frame.Text(
                protocolJson.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = type,
                        eventId = "event-$correlationId",
                        correlationId = correlationId,
                        conversationRef = conversationRef,
                        receiptSequence = sequence,
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

    private fun assertReceipt(
        frame: ServerRealtimeFrame,
        delivered: Long,
        read: Long,
        version: Long,
    ) {
        assertEquals(ServerRealtimeFrameType.RECEIPT_CURSOR_UPDATED, frame.type)
        assertEquals("synthetic-client-c1", frame.receipt?.subjectRef)
        assertEquals("CLIENT", frame.receipt?.actorType)
        assertEquals(delivered, frame.receipt?.highestDeliveredSequence)
        assertEquals(read, frame.receipt?.highestReadSequence)
        assertEquals(version, frame.receipt?.version)
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")
}
