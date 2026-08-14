package com.premierdarkcoffee.nexo.connect.lab.application.typing

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingSignalRateLimiterTest {
    @Test
    fun `accepts normal refreshes rejects floods and recovers after the bounded window`() {
        var now = 0L
        val limiter = TypingSignalRateLimiter(maximumSignals = 3, window = Duration.ofSeconds(1)) { now }

        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        now = Duration.ofSeconds(1).toNanos()
        assertTrue(limiter.tryAcquire())
    }
}
