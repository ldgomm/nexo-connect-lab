package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeRoutingProtocolTest {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }

    @Test
    fun `auth ok exposes only server generated opaque routing references`() {
        val routing =
            RealtimeRoutingRefs(
                connectionRef = "connection_0123456789abcdefghijklmnopqrstuv",
                deviceRef = "device_0123456789abcdefghijklmnopqrstuv",
                sessionRef = "session_0123456789abcdefghijklmnopqrstuv",
            )
        val encoded =
            json.encodeToString(
                ServerRealtimeFrame(
                    type = ServerRealtimeFrameType.AUTH_OK,
                    eventId = "event-routing-1",
                    serverTimestamp = "2026-08-14T07:00:00Z",
                    subject = AuthenticatedRealtimeSubject("client-1", "CLIENT"),
                    routing = routing,
                ),
            )

        assertEquals(routing, json.decodeFromString<ServerRealtimeFrame>(encoded).routing)
        assertTrue(encoded.contains("\"connectionRef\""))
        assertFalse(encoded.contains("bearer"))
        assertFalse(encoded.contains("token"))
    }

    @Test
    fun `client cannot inject connection device or session routing targets`() {
        listOf("connectionRef", "deviceRef", "sessionRef").forEach { forbiddenField ->
            assertFailsWith<SerializationException> {
                json.decodeFromString<ClientRealtimeFrame>(
                    """{"protocolMajor":1,"type":"PING","eventId":"event-1","$forbiddenField":"guessed"}""",
                )
            }
        }
    }
}
