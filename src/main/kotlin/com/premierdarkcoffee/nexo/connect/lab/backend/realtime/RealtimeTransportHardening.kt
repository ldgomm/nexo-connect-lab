package com.premierdarkcoffee.nexo.connect.lab.backend.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class RealtimeTransportHardeningConfig(
    val maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
    val outboundQueueCapacity: Int = DEFAULT_OUTBOUND_QUEUE_CAPACITY,
    val outboundSendTimeoutMillis: Long = DEFAULT_OUTBOUND_SEND_TIMEOUT_MILLIS,
) {
    init {
        require(maxConcurrentConnections in 1..MAX_MAX_CONCURRENT_CONNECTIONS) {
            "maxConcurrentConnections must be between 1 and $MAX_MAX_CONCURRENT_CONNECTIONS"
        }
        require(outboundQueueCapacity in 1..MAX_OUTBOUND_QUEUE_CAPACITY) {
            "outboundQueueCapacity must be between 1 and $MAX_OUTBOUND_QUEUE_CAPACITY"
        }
        require(outboundSendTimeoutMillis in 100..MAX_OUTBOUND_SEND_TIMEOUT_MILLIS) {
            "outboundSendTimeoutMillis must be bounded"
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_CONNECTIONS = 512
        const val MAX_MAX_CONCURRENT_CONNECTIONS = 10_000
        const val DEFAULT_OUTBOUND_QUEUE_CAPACITY = 64
        const val MAX_OUTBOUND_QUEUE_CAPACITY = 1_024
        const val DEFAULT_OUTBOUND_SEND_TIMEOUT_MILLIS = 5_000L
        const val MAX_OUTBOUND_SEND_TIMEOUT_MILLIS = 30_000L
        const val LIVE_FAN_OUT_SCOPE = "SINGLE_APPLICATION_INSTANCE"
    }
}

internal class RealtimeConnectionLimiter(
    private val maximumConnections: Int,
) {
    private val activeConnections = AtomicInteger(0)

    init {
        require(maximumConnections > 0) { "maximumConnections must be positive" }
    }

    fun tryAcquire(): RealtimeConnectionLease? {
        while (true) {
            val current = activeConnections.get()
            if (current >= maximumConnections) return null
            if (activeConnections.compareAndSet(current, current + 1)) {
                return RealtimeConnectionLease(::release)
            }
        }
    }

    internal fun activeConnectionCount(): Int = activeConnections.get()

    private fun release() {
        check(activeConnections.decrementAndGet() >= 0) {
            "Realtime connection limiter released more than acquired"
        }
    }
}

internal class RealtimeConnectionLease(
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

internal class SlowRealtimeConsumerException : IllegalStateException("Realtime outbound consumer is unavailable")

internal class BoundedRealtimeOutboundSender(
    scope: CoroutineScope,
    capacity: Int,
    private val sendTimeoutMillis: Long,
    private val writeText: suspend (String) -> Unit,
    private val closeSlowConsumer: suspend () -> Unit,
) {
    private data class PendingFrame(
        val text: String,
        val delivery: CompletableDeferred<Unit>?,
    )

    private val outbound = Channel<PendingFrame>(capacity)
    private val slowConsumerClosed = AtomicBoolean(false)
    private val writer: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (pending in outbound) {
                    if (slowConsumerClosed.get()) {
                        pending.delivery?.completeExceptionally(SlowRealtimeConsumerException())
                        break
                    }
                    try {
                        withTimeout(sendTimeoutMillis) { writeText(pending.text) }
                        pending.delivery?.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        pending.delivery?.cancel(cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        pending.delivery?.completeExceptionally(failure)
                        markSlowConsumer()
                        break
                    }
                }
            } finally {
                outbound.close()
                while (true) {
                    val pending = outbound.tryReceive().getOrNull() ?: break
                    pending.delivery?.completeExceptionally(SlowRealtimeConsumerException())
                }
            }
        }

    suspend fun send(
        text: String,
        awaitDelivery: Boolean = false,
    ) {
        if (slowConsumerClosed.get()) throw SlowRealtimeConsumerException()
        val delivery = if (awaitDelivery) CompletableDeferred<Unit>() else null
        val pending = PendingFrame(text, delivery)
        if (!outbound.trySend(pending).isSuccess) {
            delivery?.completeExceptionally(SlowRealtimeConsumerException())
            markSlowConsumer()
            throw SlowRealtimeConsumerException()
        }
        delivery?.await()
    }

    suspend fun shutdown() {
        outbound.close()
        writer.cancelAndJoin()
    }

    private suspend fun markSlowConsumer() {
        if (slowConsumerClosed.compareAndSet(false, true)) {
            outbound.close()
            try {
                closeSlowConsumer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The socket may already be closed; the bounded queue remains terminal.
            }
        }
    }
}
