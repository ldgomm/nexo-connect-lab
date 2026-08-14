package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutDelivery
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutChannel
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.TypingSignalEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TypingSignalProtocolTest {
    private val codec = TypingSignalEnvelopeCodec(Json { ignoreUnknownKeys = false })

    @Test
    fun `typing start and stop require the frozen nested schema and conversation scope`() {
        listOf(ClientRealtimeFrameType.TYPING_START, ClientRealtimeFrameType.TYPING_STOP).forEach { type ->
            assertEquals(
                ClientRealtimeFrameValidation.Valid,
                ClientRealtimeFrame(
                    protocolMajor = 1,
                    type = type,
                    eventId = "event-$type",
                    conversationRef = "conversation-1",
                    typingSchemaVersion = 1,
                ).validateEnvelope(),
            )
        }
        val incompatible =
            ClientRealtimeFrame(
                protocolMajor = 1,
                type = ClientRealtimeFrameType.TYPING_START,
                eventId = "event-invalid",
                conversationRef = "conversation-1",
                typingSchemaVersion = 2,
            ).validateEnvelope()
        assertEquals(
            "INCOMPATIBLE_TYPING_SCHEMA_VERSION",
            assertIs<ClientRealtimeFrameValidation.Invalid>(incompatible).code,
        )
    }

    @Test
    fun `typing envelope is versioned bounded and carries no device or instance topology to clients`() {
        val signal = signal()
        val encoded = codec.encode(signal)
        val decoded =
            codec.decode(
                EphemeralRealtimeFanoutDelivery(RealtimeFanoutChannel.TYPING_STATE_CHANGED, encoded),
            )
        val clientPayload =
            RealtimeTypingPayload(
                subjectRef = signal.subjectRef,
                actorType = signal.actorType.name,
                active = signal.active,
                expiresInMillis = signal.expiresInMillis,
            )

        assertEquals(signal, decoded)
        assertEquals(1, clientPayload.schemaVersion)
        assertNull(
            codec.decode(
                EphemeralRealtimeFanoutDelivery(
                    RealtimeFanoutChannel.TYPING_STATE_CHANGED,
                    encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                ),
            ),
        )
    }

    private fun signal() = EphemeralTypingSignal(
        eventId = "typing-event-1",
        conversationRef = "conversation-1",
        subjectRef = "client-1",
        actorType = ConnectActorType.CLIENT,
        active = true,
        expiresInMillis = 6_000,
        occurredAt = Instant.parse("2026-08-14T05:00:00Z"),
        originInstanceRef = "instance-a",
    )
}
