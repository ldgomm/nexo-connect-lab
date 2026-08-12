package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import kotlinx.serialization.Serializable

object RealtimeProtocol {
    const val MAJOR_VERSION = 1
    const val MAX_TEXT_FRAME_BYTES = 16_384L
    const val MAX_EVENT_ID_LENGTH = 128
    const val MAX_CORRELATION_ID_LENGTH = 128
}

object ClientRealtimeFrameType {
    const val AUTH = "AUTH"
    const val PING = "PING"
}

object ServerRealtimeFrameType {
    const val AUTH_OK = "AUTH_OK"
    const val ERROR = "ERROR"
    const val PONG = "PONG"
}

@Serializable
data class ClientRealtimeFrame(
    val protocolMajor: Int,
    val type: String,
    val eventId: String,
    val correlationId: String? = null,
)

@Serializable
data class AuthenticatedRealtimeSubject(
    val subjectRef: String,
    val actorType: String,
)

@Serializable
data class RealtimeProtocolError(
    val code: String,
    val retryable: Boolean,
)

@Serializable
data class ServerRealtimeFrame(
    val protocolMajor: Int = RealtimeProtocol.MAJOR_VERSION,
    val type: String,
    val eventId: String,
    val serverTimestamp: String,
    val correlationId: String? = null,
    val subject: AuthenticatedRealtimeSubject? = null,
    val error: RealtimeProtocolError? = null,
)

sealed interface ClientRealtimeFrameValidation {
    data object Valid : ClientRealtimeFrameValidation

    data class Invalid(
        val code: String,
    ) : ClientRealtimeFrameValidation
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
    return ClientRealtimeFrameValidation.Valid
}
