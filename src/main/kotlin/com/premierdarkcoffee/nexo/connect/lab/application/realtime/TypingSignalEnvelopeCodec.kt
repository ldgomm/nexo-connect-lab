package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.MAX_TYPING_SIGNAL_ENVELOPE_BYTES
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.TypingSignalEnvelope
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.TypingSignalEnvelopeValidation
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.validateTypingEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class TypingSignalEnvelopeCodec(private val json: Json) {
    fun encode(signal: EphemeralTypingSignal): String = json.encodeToString(
        TypingSignalEnvelope(
            schemaVersion = RealtimeProtocol.TYPING_SCHEMA_VERSION,
            eventId = signal.eventId,
            occurredAt = signal.occurredAt.toString(),
            conversationRef = signal.conversationRef,
            subjectRef = signal.subjectRef,
            actorType = signal.actorType.name,
            active = signal.active,
            expiresInMillis = signal.expiresInMillis,
            originInstanceRef = signal.originInstanceRef,
        ),
    )

    fun decode(delivery: EphemeralRealtimeFanoutDelivery): EphemeralTypingSignal? {
        if (delivery.channel != RealtimeFanoutChannel.TYPING_STATE_CHANGED) return null
        if (delivery.payload.toByteArray(Charsets.UTF_8).size > MAX_TYPING_SIGNAL_ENVELOPE_BYTES) return null
        val envelope =
            try {
                json.decodeFromString<TypingSignalEnvelope>(delivery.payload)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (envelope.validateTypingEnvelope() !is TypingSignalEnvelopeValidation.Valid) return null
        return EphemeralTypingSignal(
            eventId = envelope.eventId,
            conversationRef = envelope.conversationRef,
            subjectRef = envelope.subjectRef,
            actorType = ConnectActorType.valueOf(envelope.actorType),
            active = envelope.active,
            expiresInMillis = envelope.expiresInMillis,
            occurredAt = Instant.parse(envelope.occurredAt),
            originInstanceRef = envelope.originInstanceRef,
        )
    }
}
