package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlinx.serialization.Serializable

object RealtimeProtocol {
    const val MAJOR_VERSION = 1
    const val MAX_TEXT_FRAME_BYTES = 16_384L
    const val MAX_EVENT_ID_LENGTH = 128
    const val MAX_CORRELATION_ID_LENGTH = 128
    const val MAX_CONVERSATION_REF_UTF8_BYTES = 256
    const val MAX_CONVERSATION_SUBSCRIPTIONS = 100
    const val PING_PERIOD_SECONDS = 20
    const val IDLE_TIMEOUT_SECONDS = 15
    const val LIVE_FAN_OUT_SCOPE = "MULTI_APPLICATION_INSTANCE"
    const val TYPING_SCHEMA_VERSION = 1
    const val TYPING_LEASE_TTL_MILLIS = 6_000L
}

object ClientRealtimeFrameType {
    const val AUTH = "AUTH"
    const val PING = "PING"
    const val SUBSCRIBE_CONVERSATION = "SUBSCRIBE_CONVERSATION"
    const val ACK_DELIVERY = "ACK_DELIVERY"
    const val UPDATE_READ_CURSOR = "UPDATE_READ_CURSOR"
    const val TYPING_START = "TYPING_START"
    const val TYPING_STOP = "TYPING_STOP"
}

object ServerRealtimeFrameType {
    const val AUTH_OK = "AUTH_OK"
    const val ERROR = "ERROR"
    const val PONG = "PONG"
    const val CONVERSATION_SUBSCRIBED = "CONVERSATION_SUBSCRIBED"
    const val CONVERSATION_SYNCED = "CONVERSATION_SYNCED"
    const val MESSAGE_CREATED = "MESSAGE_CREATED"
    const val RECEIPT_CURSOR_UPDATED = "RECEIPT_CURSOR_UPDATED"
    const val TYPING_STATE_CHANGED = "TYPING_STATE_CHANGED"
}

@Serializable
data class ClientRealtimeFrame(
    val protocolMajor: Int,
    val type: String,
    val eventId: String,
    val correlationId: String? = null,
    val conversationRef: String? = null,
    val afterSequence: Long? = null,
    val receiptSequence: Long? = null,
    val typingSchemaVersion: Int? = null,
)

@Serializable
data class AuthenticatedRealtimeSubject(val subjectRef: String, val actorType: String)

@Serializable
data class RealtimeRoutingRefs(val connectionRef: String, val deviceRef: String, val sessionRef: String)

@Serializable
data class RealtimeProtocolError(val code: String, val retryable: Boolean)

@Serializable
data class RealtimeMessageCreatedPayload(
    val serverMessageRef: String,
    val sequence: Long,
    val senderSubjectRef: String,
    val senderActorType: String,
    val messageType: String,
    val body: String,
    val acceptedAtServer: String,
)

@Serializable
data class RealtimeReceiptCursorPayload(
    val subjectRef: String,
    val actorType: String,
    val highestDeliveredSequence: Long,
    val highestReadSequence: Long,
    val deliveredAt: String? = null,
    val readAt: String? = null,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class RealtimeTypingPayload(
    val schemaVersion: Int = RealtimeProtocol.TYPING_SCHEMA_VERSION,
    val subjectRef: String,
    val actorType: String,
    val active: Boolean,
    val expiresInMillis: Long,
)

@Serializable
data class ServerRealtimeFrame(
    val protocolMajor: Int = RealtimeProtocol.MAJOR_VERSION,
    val type: String,
    val eventId: String,
    val serverTimestamp: String,
    val correlationId: String? = null,
    val subject: AuthenticatedRealtimeSubject? = null,
    val routing: RealtimeRoutingRefs? = null,
    val error: RealtimeProtocolError? = null,
    val conversationRef: String? = null,
    val lastMessageSequence: Long? = null,
    val replayedMessageCount: Int? = null,
    val message: RealtimeMessageCreatedPayload? = null,
    val receipt: RealtimeReceiptCursorPayload? = null,
    val typing: RealtimeTypingPayload? = null,
)

sealed interface ClientRealtimeFrameValidation {
    data object Valid : ClientRealtimeFrameValidation

    data class Invalid(val code: String) : ClientRealtimeFrameValidation
}

fun ClientRealtimeFrame.validateEnvelope(): ClientRealtimeFrameValidation {
    if (protocolMajor != RealtimeProtocol.MAJOR_VERSION) {
        return ClientRealtimeFrameValidation.Invalid("INCOMPATIBLE_PROTOCOL_MAJOR")
    }
    if (type.isBlank() || type.length > 64 || type.any { !it.isUpperCase() && it != '_' }) {
        return ClientRealtimeFrameValidation.Invalid("INVALID_FRAME_TYPE")
    }
    if (eventId.isBlank() || eventId.length > RealtimeProtocol.MAX_EVENT_ID_LENGTH) {
        return ClientRealtimeFrameValidation.Invalid("INVALID_EVENT_ID")
    }
    if (correlationId?.let { it.isBlank() || it.length > RealtimeProtocol.MAX_CORRELATION_ID_LENGTH } == true) {
        return ClientRealtimeFrameValidation.Invalid("INVALID_CORRELATION_ID")
    }
    when (type) {
        ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION -> {
            val requestedConversationRef = conversationRef
            if (
                requestedConversationRef == null ||
                requestedConversationRef.isBlank() ||
                '\u0000' in requestedConversationRef ||
                requestedConversationRef.toByteArray(Charsets.UTF_8).size >
                RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES
            ) {
                return ClientRealtimeFrameValidation.Invalid("INVALID_CONVERSATION_REF")
            }
            if (afterSequence?.let { it < 0 } == true) {
                return ClientRealtimeFrameValidation.Invalid("INVALID_RESUME_SEQUENCE")
            }
            if (receiptSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RECEIPT_SEQUENCE")
            }
            if (typingSchemaVersion != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_TYPING_SCHEMA_VERSION")
            }
        }

        ClientRealtimeFrameType.ACK_DELIVERY,
        ClientRealtimeFrameType.UPDATE_READ_CURSOR,
        -> {
            val requestedConversationRef = conversationRef
            if (
                requestedConversationRef == null ||
                requestedConversationRef.isBlank() ||
                '\u0000' in requestedConversationRef ||
                requestedConversationRef.toByteArray(Charsets.UTF_8).size >
                RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES
            ) {
                return ClientRealtimeFrameValidation.Invalid("INVALID_CONVERSATION_REF")
            }
            if (afterSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RESUME_SEQUENCE")
            }
            if (receiptSequence == null || receiptSequence <= 0) {
                return ClientRealtimeFrameValidation.Invalid("INVALID_RECEIPT_SEQUENCE")
            }
            if (typingSchemaVersion != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_TYPING_SCHEMA_VERSION")
            }
        }

        ClientRealtimeFrameType.TYPING_START,
        ClientRealtimeFrameType.TYPING_STOP,
        -> {
            val requestedConversationRef = conversationRef
            if (
                requestedConversationRef == null ||
                requestedConversationRef.isBlank() ||
                '\u0000' in requestedConversationRef ||
                requestedConversationRef.toByteArray(Charsets.UTF_8).size >
                RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES
            ) {
                return ClientRealtimeFrameValidation.Invalid("INVALID_CONVERSATION_REF")
            }
            if (afterSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RESUME_SEQUENCE")
            }
            if (receiptSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RECEIPT_SEQUENCE")
            }
            if (typingSchemaVersion != RealtimeProtocol.TYPING_SCHEMA_VERSION) {
                return ClientRealtimeFrameValidation.Invalid("INCOMPATIBLE_TYPING_SCHEMA_VERSION")
            }
        }

        ClientRealtimeFrameType.AUTH,
        ClientRealtimeFrameType.PING,
        -> {
            if (conversationRef != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_CONVERSATION_REF")
            }
            if (afterSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RESUME_SEQUENCE")
            }
            if (receiptSequence != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_RECEIPT_SEQUENCE")
            }
            if (typingSchemaVersion != null) {
                return ClientRealtimeFrameValidation.Invalid("UNEXPECTED_TYPING_SCHEMA_VERSION")
            }
        }
    }
    return ClientRealtimeFrameValidation.Valid
}
