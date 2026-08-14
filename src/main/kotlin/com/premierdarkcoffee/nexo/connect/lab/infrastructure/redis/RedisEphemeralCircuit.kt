package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

enum class RedisCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
    STOPPED,
}

data class RedisEphemeralReadiness(
    val available: Boolean,
    val circuitState: RedisCircuitState,
    val consecutiveFailures: Int,
    val retryAfterMillis: Long,
    val keyNamespace: String,
    val channelNamespace: String,
)

interface RedisEphemeralReadinessProbe {
    fun readiness(): RedisEphemeralReadiness
}

internal interface ManagedRedisEphemeralRuntime :
    RedisEphemeralReadinessProbe,
    AutoCloseable

internal interface RedisEphemeralConnection : AutoCloseable {
    fun ping(): Boolean
}

internal interface RedisEphemeralConnectionProvider : AutoCloseable {
    fun connect(): RedisEphemeralConnection
}

internal class RedisEphemeralRuntime(
    private val config: RedisEphemeralConfig,
    private val provider: RedisEphemeralConnectionProvider,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ManagedRedisEphemeralRuntime {
    private val circuitState = AtomicReference(RedisCircuitState.HALF_OPEN)
    private var connection: RedisEphemeralConnection? = null
    private var consecutiveFailures = 0
    private var nextRetryNanos = 0L

    @Synchronized
    override fun readiness(): RedisEphemeralReadiness {
        if (circuitState.get() == RedisCircuitState.STOPPED) return snapshot(available = false)

        val now = monotonicNanos()
        connection?.let { current ->
            if (runCatching { current.ping() }.getOrDefault(false)) {
                recordSuccess()
                return snapshot(available = true)
            }
            runCatching { current.close() }
            connection = null
            recordFailure(now)
            return snapshot(available = false, now = now)
        }

        if (now < nextRetryNanos) return snapshot(available = false, now = now)

        circuitState.set(RedisCircuitState.HALF_OPEN)
        val candidate = runCatching { provider.connect() }.getOrNull()
        if (candidate != null && runCatching { candidate.ping() }.getOrDefault(false)) {
            connection = candidate
            recordSuccess()
            return snapshot(available = true)
        }

        candidate?.let { runCatching { it.close() } }
        recordFailure(now)
        return snapshot(available = false, now = now)
    }

    @Synchronized
    override fun close() {
        if (circuitState.getAndSet(RedisCircuitState.STOPPED) == RedisCircuitState.STOPPED) return
        connection?.let { runCatching { it.close() } }
        connection = null
        runCatching { provider.close() }
    }

    private fun recordSuccess() {
        consecutiveFailures = 0
        nextRetryNanos = 0L
        circuitState.set(RedisCircuitState.CLOSED)
    }

    private fun recordFailure(now: Long) {
        consecutiveFailures = min(consecutiveFailures + 1, MAX_FAILURE_COUNT)
        val exponent = min(consecutiveFailures - 1, MAX_BACKOFF_EXPONENT)
        val delayMillis =
            min(
                config.reconnectMaxDelayMillis,
                config.reconnectMinDelayMillis * (1L shl exponent),
            )
        nextRetryNanos = now + Duration.ofMillis(delayMillis).toNanos()
        circuitState.set(RedisCircuitState.OPEN)
    }

    private fun snapshot(available: Boolean, now: Long = monotonicNanos()): RedisEphemeralReadiness =
        RedisEphemeralReadiness(
            available = available,
            circuitState = circuitState.get(),
            consecutiveFailures = consecutiveFailures,
            retryAfterMillis =
            if (nextRetryNanos <= now) {
                0
            } else {
                Duration.ofNanos(nextRetryNanos - now).toMillis()
            },
            keyNamespace = config.keyNamespace,
            channelNamespace = config.channelNamespace,
        )

    private companion object {
        const val MAX_FAILURE_COUNT = 31
        const val MAX_BACKOFF_EXPONENT = 20
    }
}
