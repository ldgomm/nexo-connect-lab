package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableRealtimeFanoutEnvelopeFactory
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutDelivery
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutChannel
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutEnvelopeCodec
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RealtimeFanoutEnvelopeTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val codec = RealtimeFanoutEnvelopeCodec(json)
    private val factory = DurableRealtimeFanoutEnvelopeFactory("instance-a")

    @Test
    fun `encodes a stable minimal message notification without durable payload or credentials`() {
        val event = messageEvent()
        val envelope = factory.messageCreated(event)
        val encoded = codec.encode(envelope)
        val decoded =
            codec.decode(
                EphemeralRealtimeFanoutDelivery(
                    channel = RealtimeFanoutChannel.MESSAGE_CREATED,
                    payload = encoded,
                ),
            )

        assertEquals(envelope, decoded)
        assertEquals(envelope.eventId, factory.messageCreated(event).eventId)
        assertFalse(encoded.contains(event.body.value))
        assertFalse(encoded.contains("bearerToken", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
    }

    @Test
    fun `rejects unknown schema versions and channel event mismatches`() {
        val envelope = factory.messageCreated(messageEvent())
        val unknownVersion = codec.encode(envelope.copy(schemaVersion = 2))
        val wrongChannel = codec.encode(envelope)

        assertNull(
            codec.decode(
                EphemeralRealtimeFanoutDelivery(
                    RealtimeFanoutChannel.MESSAGE_CREATED,
                    unknownVersion,
                ),
            ),
        )
        assertNull(
            codec.decode(
                EphemeralRealtimeFanoutDelivery(
                    RealtimeFanoutChannel.RECEIPT_ADVANCED,
                    wrongChannel,
                ),
            ),
        )
    }

    @Test
    fun `receipt notification references durable cursor identity and version`() {
        val cursor =
            DurableReceiptCursor(
                conversationRef = "conversation-1",
                subjectRef = "client-1",
                actorType = ConnectActorType.CLIENT,
                highestDeliveredSequence = 7,
                highestReadSequence = 5,
                deliveredAt = Instant.parse("2026-08-14T04:00:00Z"),
                readAt = Instant.parse("2026-08-14T04:01:00Z"),
                updatedAt = Instant.parse("2026-08-14T04:01:00Z"),
                version = 3,
            )
        val envelope = factory.receiptAdvanced(DurableReceiptCursorEvent(cursor))

        assertEquals(3, envelope.aggregateSequence)
        assertEquals("CLIENT:client-1", envelope.payloadRef)
        assertNotNull(
            codec.decode(
                EphemeralRealtimeFanoutDelivery(
                    RealtimeFanoutChannel.RECEIPT_ADVANCED,
                    codec.encode(envelope),
                ),
            ),
        )
        assertNotEquals(envelope.eventId, factory.messageCreated(messageEvent()).eventId)
    }

    private fun messageEvent(): DurableMessageCreatedEvent = DurableMessageCreatedEvent(
        conversationRef = "conversation-1",
        serverMessageRef = "message-7",
        sequence = ConversationSequence(7),
        senderSubjectRef = "business-1",
        senderActorType = ConnectActorType.BUSINESS,
        body = TextMessageBody("durable body must stay in PostgreSQL"),
        acceptedAtServer = Instant.parse("2026-08-14T04:00:00Z"),
    )
}
