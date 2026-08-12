package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealtimeProtocolTest {
    @Test
    fun `accepts the canonical major version and bounded identifiers`() {
        val result =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.PING,
                eventId = "event-client-1",
                correlationId = "correlation-1",
            ).validateEnvelope()

        assertEquals(ClientRealtimeFrameValidation.Valid, result)
    }

    @Test
    fun `rejects an incompatible major version explicitly`() {
        val result =
            ClientRealtimeFrame(
                protocolMajor = 2,
                type = ClientRealtimeFrameType.PING,
                eventId = "event-client-1",
            ).validateEnvelope()

        assertEquals(
            "INCOMPATIBLE_PROTOCOL_MAJOR",
            assertIs<ClientRealtimeFrameValidation.Invalid>(result).code,
        )
    }

    @Test
    fun `rejects malformed correlation identifiers`() {
        val result =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.PING,
                eventId = "event-client-1",
                correlationId = " ",
            ).validateEnvelope()

        assertEquals(
            "INVALID_CORRELATION_ID",
            assertIs<ClientRealtimeFrameValidation.Invalid>(result).code,
        )
    }
}
