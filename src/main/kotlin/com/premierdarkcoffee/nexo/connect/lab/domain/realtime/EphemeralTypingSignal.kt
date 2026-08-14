package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.serialization.Serializable
import java.time.Instant

data class EphemeralTypingSignal(
    val eventId: String,
    val conversationRef: String,
    val subjectRef: String,
    val actorType: ConnectActorType,
    val active: Boolean,
    val expiresInMillis: Long,
    val occurredAt: Instant,
    val originInstanceRef: String,
) {
    init {
        require(eventId.isBoundedTypingRef(128)) { "eventId must be bounded" }
        require(conversationRef.isBoundedTypingRef(RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES)) {
            "conversationRef must be bounded"
        }
        require(subjectRef.isBoundedTypingRef(256)) { "subjectRef must be bounded" }
        require(originInstanceRef.isBoundedTypingRef(128)) { "originInstanceRef must be bounded" }
        require(expiresInMillis in 0..RealtimeProtocol.TYPING_LEASE_TTL_MILLIS) {
            "expiresInMillis must be bounded by the typing lease TTL"
        }
        require(active || expiresInMillis == 0L) { "inactive typing signals must expire immediately" }
    }
}

@Serializable
data class TypingSignalEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val occurredAt: String,
    val conversationRef: String,
    val subjectRef: String,
    val actorType: String,
    val active: Boolean,
    val expiresInMillis: Long,
    val originInstanceRef: String,
)

sealed interface TypingSignalEnvelopeValidation {
    data object Valid : TypingSignalEnvelopeValidation

    data class Invalid(val code: String) : TypingSignalEnvelopeValidation
}

fun TypingSignalEnvelope.validateTypingEnvelope(): TypingSignalEnvelopeValidation {
    if (schemaVersion != RealtimeProtocol.TYPING_SCHEMA_VERSION) {
        return TypingSignalEnvelopeValidation.Invalid("UNKNOWN_TYPING_SCHEMA_VERSION")
    }
    if (!eventId.isBoundedTypingRef(128)) return TypingSignalEnvelopeValidation.Invalid("INVALID_EVENT_ID")
    if (!conversationRef.isBoundedTypingRef(RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES)) {
        return TypingSignalEnvelopeValidation.Invalid("INVALID_CONVERSATION_REF")
    }
    if (!subjectRef.isBoundedTypingRef(256)) return TypingSignalEnvelopeValidation.Invalid("INVALID_SUBJECT_REF")
    if (!originInstanceRef.isBoundedTypingRef(128)) {
        return TypingSignalEnvelopeValidation.Invalid("INVALID_ORIGIN_INSTANCE_REF")
    }
    if (runCatching { ConnectActorType.valueOf(actorType) }.isFailure) {
        return TypingSignalEnvelopeValidation.Invalid("INVALID_ACTOR_TYPE")
    }
    if (expiresInMillis !in 0..RealtimeProtocol.TYPING_LEASE_TTL_MILLIS || (!active && expiresInMillis != 0L)) {
        return TypingSignalEnvelopeValidation.Invalid("INVALID_EXPIRY")
    }
    if (runCatching { Instant.parse(occurredAt) }.isFailure) {
        return TypingSignalEnvelopeValidation.Invalid("INVALID_OCCURRED_AT")
    }
    return TypingSignalEnvelopeValidation.Valid
}

private fun String.isBoundedTypingRef(maximumBytes: Int): Boolean =
    isNotBlank() && '\u0000' !in this && toByteArray(Charsets.UTF_8).size <= maximumBytes

const val MAX_TYPING_SIGNAL_ENVELOPE_BYTES = 2_048
