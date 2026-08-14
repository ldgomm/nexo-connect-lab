package com.premierdarkcoffee.nexo.connect.lab.application.typing

import java.time.Duration
import java.util.ArrayDeque

class TypingSignalRateLimiter(
    private val maximumSignals: Int = DEFAULT_MAXIMUM_SIGNALS,
    window: Duration = DEFAULT_WINDOW,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val windowNanos = window.toNanos()
    private val acceptedAt = ArrayDeque<Long>()

    init {
        require(maximumSignals in 1..MAXIMUM_SIGNALS_BOUND) { "maximumSignals must be bounded" }
        require(!window.isZero && !window.isNegative && window <= MAXIMUM_WINDOW) { "window must be bounded" }
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = monotonicNanos()
        while (acceptedAt.isNotEmpty() && now - acceptedAt.first() >= windowNanos) {
            acceptedAt.removeFirst()
        }
        if (acceptedAt.size >= maximumSignals) return false
        acceptedAt.addLast(now)
        return true
    }

    companion object {
        const val DEFAULT_MAXIMUM_SIGNALS = 6
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(3)
        private const val MAXIMUM_SIGNALS_BOUND = 100
        private val MAXIMUM_WINDOW: Duration = Duration.ofMinutes(1)
    }
}
