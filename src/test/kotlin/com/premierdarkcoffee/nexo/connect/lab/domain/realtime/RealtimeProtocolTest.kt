package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun `accepts zero and positive durable resume sequences`() {
        listOf(0L, 42L).forEach { afterSequence ->
            val result =
                ClientRealtimeFrame(
                    protocolMajor = 1,
                    type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                    eventId = "event-resume-$afterSequence",
                    conversationRef = "conversation-1",
                    afterSequence = afterSequence,
                ).validateEnvelope()

            assertEquals(ClientRealtimeFrameValidation.Valid, result)
        }
    }

    @Test
    fun `rejects negative and contextually unexpected resume sequences`() {
        val negative =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                eventId = "event-negative-resume",
                conversationRef = "conversation-1",
                afterSequence = -1,
            ).validateEnvelope()
        val unexpected =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.PING,
                eventId = "event-unexpected-resume",
                afterSequence = 0,
            ).validateEnvelope()

        assertEquals(
            "INVALID_RESUME_SEQUENCE",
            assertIs<ClientRealtimeFrameValidation.Invalid>(negative).code,
        )
        assertEquals(
            "UNEXPECTED_RESUME_SEQUENCE",
            assertIs<ClientRealtimeFrameValidation.Invalid>(unexpected).code,
        )
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

    @Test
    fun `serializes the durable message created event without protocol ambiguity`() {
        val frame =
            ServerRealtimeFrame(
                type = ServerRealtimeFrameType.MESSAGE_CREATED,
                eventId = "event-1",
                serverTimestamp = "2026-08-12T09:45:01Z",
                conversationRef = "conversation-1",
                message =
                    RealtimeMessageCreatedPayload(
                        serverMessageRef = "message-1",
                        sequence = 9,
                        senderSubjectRef = "business-subject",
                        senderActorType = "BUSINESS",
                        messageType = "TEXT",
                        body = "hello",
                        acceptedAtServer = "2026-08-12T09:45:00Z",
                    ),
            )

        val encoded = Json.encodeToString(frame)
        val decoded = Json.decodeFromString<ServerRealtimeFrame>(encoded)

        assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, decoded.type)
        assertEquals("conversation-1", decoded.conversationRef)
        assertEquals("message-1", decoded.message?.serverMessageRef)
        assertEquals(9L, decoded.message?.sequence)
        assertEquals("TEXT", decoded.message?.messageType)
    }

    @Test
    fun `serializes an explicit durable synchronization boundary`() {
        val frame =
            ServerRealtimeFrame(
                type = ServerRealtimeFrameType.CONVERSATION_SYNCED,
                eventId = "event-synced-1",
                serverTimestamp = "2026-08-12T10:15:00Z",
                correlationId = "correlation-sync-1",
                conversationRef = "conversation-1",
                lastMessageSequence = 42,
                replayedMessageCount = 3,
            )

        val decoded = Json.decodeFromString<ServerRealtimeFrame>(Json.encodeToString(frame))

        assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, decoded.type)
        assertEquals(42L, decoded.lastMessageSequence)
        assertEquals(3, decoded.replayedMessageCount)
    }
}
