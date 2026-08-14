package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.MAX_REALTIME_FANOUT_ENVELOPE_BYTES
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.REALTIME_FANOUT_SCHEMA_VERSION
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelopeValidation
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEventType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.validateEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class RealtimeFanoutEnvelopeCodec(private val json: Json) {
    fun encode(envelope: RealtimeFanoutEnvelope): String = json.encodeToString(envelope)

    fun decode(delivery: EphemeralRealtimeFanoutDelivery): RealtimeFanoutEnvelope? {
        if (delivery.payload.toByteArray(Charsets.UTF_8).size > MAX_REALTIME_FANOUT_ENVELOPE_BYTES) return null
        val envelope =
            try {
                json.decodeFromString<RealtimeFanoutEnvelope>(delivery.payload)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        val expectedEventType =
            when (delivery.channel) {
                RealtimeFanoutChannel.MESSAGE_CREATED -> RealtimeFanoutEventType.MESSAGE_CREATED
                RealtimeFanoutChannel.RECEIPT_ADVANCED -> RealtimeFanoutEventType.RECEIPT_ADVANCED
                RealtimeFanoutChannel.TYPING_STATE_CHANGED -> return null
            }
        return envelope.takeIf {
            it.validateEnvelope(expectedEventType) is RealtimeFanoutEnvelopeValidation.Valid
        }
    }
}

class DurableRealtimeFanoutEnvelopeFactory(private val originInstanceRef: String) {
    init {
        require(
            originInstanceRef.isNotBlank() &&
                '\u0000' !in originInstanceRef &&
                originInstanceRef.toByteArray(Charsets.UTF_8).size <= MAX_INSTANCE_REF_BYTES,
        ) { "originInstanceRef must be a bounded opaque reference" }
    }

    fun messageCreated(event: DurableMessageCreatedEvent): RealtimeFanoutEnvelope = RealtimeFanoutEnvelope(
        schemaVersion = REALTIME_FANOUT_SCHEMA_VERSION,
        eventId = stableEventId("message", event.conversationRef, event.serverMessageRef, event.sequence.value),
        eventType = RealtimeFanoutEventType.MESSAGE_CREATED,
        occurredAt = event.acceptedAtServer.toString(),
        conversationRef = event.conversationRef,
        aggregateSequence = event.sequence.value,
        originInstanceRef = originInstanceRef,
        payloadRef = event.serverMessageRef,
    )

    fun receiptAdvanced(event: DurableReceiptCursorEvent): RealtimeFanoutEnvelope {
        val cursor = event.cursor
        val payloadRef = "${cursor.actorType.name}:${cursor.subjectRef}"
        return RealtimeFanoutEnvelope(
            schemaVersion = REALTIME_FANOUT_SCHEMA_VERSION,
            eventId = stableEventId("receipt", cursor.conversationRef, payloadRef, cursor.version),
            eventType = RealtimeFanoutEventType.RECEIPT_ADVANCED,
            occurredAt = cursor.updatedAt.toString(),
            conversationRef = cursor.conversationRef,
            aggregateSequence = cursor.version,
            originInstanceRef = originInstanceRef,
            payloadRef = payloadRef,
        )
    }

    private fun stableEventId(
        type: String,
        conversationRef: String,
        payloadRef: String,
        aggregateSequence: Long,
    ): String {
        val canonical = "$type\u0000$conversationRef\u0000$payloadRef\u0000$aggregateSequence"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "fanout-${digest.joinToString("") { byte -> "%02x".format(byte) }}"
    }

    private companion object {
        const val MAX_INSTANCE_REF_BYTES = 128
    }
}
