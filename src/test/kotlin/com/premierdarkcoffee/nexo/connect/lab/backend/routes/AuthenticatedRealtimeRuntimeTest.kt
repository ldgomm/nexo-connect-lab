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
}
