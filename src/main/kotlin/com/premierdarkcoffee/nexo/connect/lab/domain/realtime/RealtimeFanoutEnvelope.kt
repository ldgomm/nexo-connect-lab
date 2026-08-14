package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlinx.serialization.Serializable
import java.time.Instant

object RealtimeFanoutEventType {
    const val MESSAGE_CREATED = "MESSAGE_CREATED"
    const val RECEIPT_ADVANCED = "RECEIPT_ADVANCED"
}

@Serializable
data class RealtimeFanoutEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val occurredAt: String,
    val conversationRef: String,
    val aggregateSequence: Long,
    val originInstanceRef: String,
    val payloadRef: String,
)

sealed interface RealtimeFanoutEnvelopeValidation {
    data object Valid : RealtimeFanoutEnvelopeValidation

    data class Invalid(val code: String) : RealtimeFanoutEnvelopeValidation
}

fun RealtimeFanoutEnvelope.validateEnvelope(expectedEventType: String): RealtimeFanoutEnvelopeValidation {
    if (schemaVersion != SCHEMA_VERSION) {
        return RealtimeFanoutEnvelopeValidation.Invalid("UNKNOWN_SCHEMA_VERSION")
    }
    if (eventType != expectedEventType || eventType !in SUPPORTED_EVENT_TYPES) {
        return RealtimeFanoutEnvelopeValidation.Invalid("EVENT_TYPE_CHANNEL_MISMATCH")
    }
    if (!eventId.isBoundedFanoutReference(MAX_EVENT_ID_BYTES)) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_EVENT_ID")
    }
    if (!conversationRef.isBoundedFanoutReference(RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES)) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_CONVERSATION_REF")
    }
    if (!originInstanceRef.isBoundedFanoutReference(MAX_INSTANCE_REF_BYTES)) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_ORIGIN_INSTANCE_REF")
    }
    if (!payloadRef.isBoundedFanoutReference(MAX_PAYLOAD_REF_BYTES)) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_PAYLOAD_REF")
    }
    if (aggregateSequence <= 0) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_AGGREGATE_SEQUENCE")
    }
    if (runCatching { Instant.parse(occurredAt) }.isFailure) {
        return RealtimeFanoutEnvelopeValidation.Invalid("INVALID_OCCURRED_AT")
    }
    return RealtimeFanoutEnvelopeValidation.Valid
}

private fun String.isBoundedFanoutReference(maxBytes: Int): Boolean = isNotBlank() &&
    '\u0000' !in this &&
    toByteArray(Charsets.UTF_8).size <= maxBytes

const val REALTIME_FANOUT_SCHEMA_VERSION = 1
const val MAX_REALTIME_FANOUT_ENVELOPE_BYTES = 2_048

private const val SCHEMA_VERSION = REALTIME_FANOUT_SCHEMA_VERSION
private const val MAX_EVENT_ID_BYTES = 128
private const val MAX_INSTANCE_REF_BYTES = 128
private const val MAX_PAYLOAD_REF_BYTES = 512
private val SUPPORTED_EVENT_TYPES =
    setOf(
        RealtimeFanoutEventType.MESSAGE_CREATED,
        RealtimeFanoutEventType.RECEIPT_ADVANCED,
    )
