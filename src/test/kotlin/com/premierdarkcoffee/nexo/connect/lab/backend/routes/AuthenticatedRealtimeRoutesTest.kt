package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.REALTIME_AUTH_PROVIDER
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.installAuthenticatedRealtimeTransport
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity.SyntheticTokenVerifier
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AuthenticatedRealtimeRoutesTest {
    private val protocolJson = Json { ignoreUnknownKeys = false }

    @Test
    fun `rejects a missing bearer token at the realtime authentication boundary`() = testApplication {
        configureRealtime()
        val response = client.get(TEST_REALTIME_AUTH_BOUNDARY_PATH)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticates the handshake and emits a versioned auth frame without the token`() =
        testApplication {
            configureRealtime()
            val realtimeClient = createClient { install(WebSockets) }

            realtimeClient.webSocket(
                request = {
                    url("/v1/realtime")
                    bearerAuth(BUSINESS_TOKEN)
                },
            ) {
                val auth = receiveServerFrame()

                assertEquals(1, auth.protocolMajor)
                assertEquals(ServerRealtimeFrameType.AUTH_OK, auth.type)
                assertEquals("2026-08-11T23:30:00Z", auth.serverTimestamp)
                assertEquals("synthetic-business", auth.subject?.subjectRef)
                assertEquals("BUSINESS", auth.subject?.actorType)
                assertFalse(protocolJson.encodeToString(auth).contains(BUSINESS_TOKEN))
            }
        }

    @Test
    fun `correlates application ping and pong`() = testApplication {
        configureRealtime()
        val realtimeClient = createClient { install(WebSockets) }

        realtimeClient.webSocket(
            request = {
                url("/v1/realtime")
                bearerAuth(CLIENT_TOKEN)
            },
        ) {
            assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame().type)
            send(
                Frame.Text(
                    protocolJson.encodeToString(
                        ClientRealtimeFrame(
                            protocolMajor = 1,
                            type = ClientRealtimeFrameType.PING,
                            eventId = "client-event-1",
                            correlationId = "correlation-1",
                        ),
                    ),
                ),
            )

            val pong = receiveServerFrame()
            assertEquals(ServerRealtimeFrameType.PONG, pong.type)
            assertEquals("correlation-1", pong.correlationId)
            assertEquals(null, pong.error)
        }
    }

    @Test
    fun `rejects incompatible protocol major explicitly`() = testApplication {
        configureRealtime()
        val realtimeClient = createClient { install(WebSockets) }

        realtimeClient.webSocket(
            request = {
                url("/v1/realtime")
                bearerAuth(CLIENT_TOKEN)
            },
        ) {
            receiveServerFrame()
            send(
                Frame.Text(
                    protocolJson.encodeToString(
                        ClientRealtimeFrame(
                            protocolMajor = 2,
                            type = ClientRealtimeFrameType.PING,
                            eventId = "client-event-v2",
                        ),
                    ),
                ),
            )

            val error = receiveServerFrame()
            assertEquals(ServerRealtimeFrameType.ERROR, error.type)
            assertEquals("INCOMPATIBLE_PROTOCOL_MAJOR", error.error?.code)
            assertEquals(false, error.error?.retryable)
        }
    }

    @Test
    fun `rejects binary frames and does not interpret them as commands`() = testApplication {
        configureRealtime()
        val realtimeClient = createClient { install(WebSockets) }

        realtimeClient.webSocket(
            request = {
                url("/v1/realtime")
                bearerAuth(CLIENT_TOKEN)
            },
        ) {
            receiveServerFrame()
            send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))

            val error = receiveServerFrame()
            assertEquals(ServerRealtimeFrameType.ERROR, error.type)
            assertEquals("BINARY_FRAMES_UNSUPPORTED", error.error?.code)
        }
    }

    @Test
    fun `keeps the connection usable after an unsupported future frame`() = testApplication {
        configureRealtime()
        val realtimeClient = createClient { install(WebSockets) }

        realtimeClient.webSocket(
            request = {
                url("/v1/realtime")
                bearerAuth(CLIENT_TOKEN)
            },
        ) {
            receiveServerFrame()
            send(
                Frame.Text(
                    protocolJson.encodeToString(
                        ClientRealtimeFrame(
                            protocolMajor = 1,
                            type = "SUBSCRIBE_CONVERSATION",
                            eventId = "client-event-future",
                        ),
                    ),
                ),
            )
            assertEquals("UNSUPPORTED_FRAME_TYPE", receiveServerFrame().error?.code)

            send(
                Frame.Text(
                    protocolJson.encodeToString(
                        ClientRealtimeFrame(
                            protocolMajor = 1,
                            type = ClientRealtimeFrameType.PING,
                            eventId = "client-event-after-error",
                        ),
                    ),
                ),
            )
            assertEquals(ServerRealtimeFrameType.PONG, receiveServerFrame().type)
        }
    }

    private fun ApplicationTestBuilder.configureRealtime() {
        application {
            installAuthenticatedRealtimeTransport(
                identityVerifier =
                    SyntheticTokenVerifier(
                        mapOf(
                            BUSINESS_TOKEN to
                                ConnectPrincipal(
                                    subjectRef = "synthetic-business",
                                    actorType = ConnectActorType.BUSINESS,
                                    platformScopeRef = "synthetic-platform",
                                    organizationScopeRef = "synthetic-organization",
                                    businessScopeRef = "synthetic-business-scope",
                                ),
                            CLIENT_TOKEN to
                                ConnectPrincipal(
                                    subjectRef = "synthetic-client",
                                    actorType = ConnectActorType.CLIENT,
                                    platformScopeRef = "synthetic-platform",
                                ),
                        ),
                    ),
                clock = Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), ZoneOffset.UTC),
                eventIdFactory = { "server-event-fixed" },
            )
            configureRouting()
            routing {
                authenticate(REALTIME_AUTH_PROVIDER) {
                    get(TEST_REALTIME_AUTH_BOUNDARY_PATH) {}
                }
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveServerFrame(): ServerRealtimeFrame {
        val frame = assertIs<Frame.Text>(incoming.receive())
        return protocolJson.decodeFromString(frame.readText())
    }

    private companion object {
        const val TEST_REALTIME_AUTH_BOUNDARY_PATH = "/__test/realtime-auth-boundary"
        const val BUSINESS_TOKEN = "business-token-c1-0123456789abcdef"
        const val CLIENT_TOKEN = "client-token-c1-0123456789abcdef12"
    }
}
