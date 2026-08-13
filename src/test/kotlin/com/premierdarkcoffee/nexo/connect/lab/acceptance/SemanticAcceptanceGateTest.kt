package com.premierdarkcoffee.nexo.connect.lab.acceptance

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameValidation
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.validateEnvelope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SemanticAcceptanceGateTest {
    private val protocolJson = Json { ignoreUnknownKeys = false }

    @Test
    fun `reformatted fixture preserves protocol semantics`() {
        val compact =
            """{"protocolMajor":1,"type":"PING","eventId":"semantic-event","correlationId":"semantic-correlation"}"""
        val reformatted =
            """
            {
              "correlationId": "semantic-correlation",
              "eventId": "semantic-event",
              "type": "PING",
              "protocolMajor": 1
            }
            """.trimIndent()

        val compactFrame = protocolJson.decodeFromString<ClientRealtimeFrame>(compact)
        val reformattedFrame = protocolJson.decodeFromString<ClientRealtimeFrame>(reformatted)

        assertEquals(compactFrame, reformattedFrame)
        assertEquals(ClientRealtimeFrameValidation.Valid, compactFrame.validateEnvelope())
        assertEquals(ClientRealtimeFrameValidation.Valid, reformattedFrame.validateEnvelope())
    }

    @Test
    fun `semantic mutation is rejected despite valid json and equivalent layout`() {
        val mutated =
            """
            {
              "correlationId": "semantic-correlation",
              "eventId": "semantic-event",
              "type": "PING",
              "protocolMajor": 2
            }
            """.trimIndent()

        val validation = protocolJson.decodeFromString<ClientRealtimeFrame>(mutated).validateEnvelope()

        assertEquals(
            "INCOMPATIBLE_PROTOCOL_MAJOR",
            assertIs<ClientRealtimeFrameValidation.Invalid>(validation).code,
        )
    }
}
