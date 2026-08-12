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

    @Test
    fun `accepts a bounded conversation subscription reference`() {
        val result =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                eventId = "event-subscribe-1",
                conversationRef = "conversation-1",
            ).validateEnvelope()

        assertEquals(ClientRealtimeFrameValidation.Valid, result)
    }

    @Test
    fun `rejects missing oversized and unexpected conversation references`() {
        val missing =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                eventId = "event-subscribe-missing",
            ).validateEnvelope()
        val oversized =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                eventId = "event-subscribe-oversized",
                conversationRef = "ñ".repeat(129),
            ).validateEnvelope()
        val unexpected =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.PING,
                eventId = "event-ping-with-conversation",
                conversationRef = "conversation-1",
            ).validateEnvelope()

        assertEquals(
            "INVALID_CONVERSATION_REF",
            assertIs<ClientRealtimeFrameValidation.Invalid>(missing).code,
        )
        assertEquals(
            "INVALID_CONVERSATION_REF",
            assertIs<ClientRealtimeFrameValidation.Invalid>(oversized).code,
        )
        assertEquals(
            "UNEXPECTED_CONVERSATION_REF",
            assertIs<ClientRealtimeFrameValidation.Invalid>(unexpected).code,
        )
    }
}
