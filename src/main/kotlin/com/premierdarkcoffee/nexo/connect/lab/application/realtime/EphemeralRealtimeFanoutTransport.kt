package com.premierdarkcoffee.nexo.connect.lab.application.realtime

enum class RealtimeFanoutChannel {
    MESSAGE_CREATED,
    RECEIPT_ADVANCED,
}

data class EphemeralRealtimeFanoutDelivery(val channel: RealtimeFanoutChannel, val payload: String)

sealed interface EphemeralRealtimeFanoutPublishResult {
    data class Published(val subscriberCount: Long) : EphemeralRealtimeFanoutPublishResult

    data object Unavailable : EphemeralRealtimeFanoutPublishResult

    data object Rejected : EphemeralRealtimeFanoutPublishResult

    data object Stopped : EphemeralRealtimeFanoutPublishResult
}

interface EphemeralRealtimeFanoutTransport : AutoCloseable {
    val localInstanceRef: String

    fun start(consumer: suspend (EphemeralRealtimeFanoutDelivery) -> Unit)

    suspend fun publish(channel: RealtimeFanoutChannel, payload: String): EphemeralRealtimeFanoutPublishResult
}
