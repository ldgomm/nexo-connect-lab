package com.premierdarkcoffee.nexo.connect.lab.backend.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeTransportHardeningTest {
    @Test
    fun `bounds concurrent connections and releases leases exactly once`() {
        val limiter = RealtimeConnectionLimiter(maximumConnections = 1)

        val first = assertNotNull(limiter.tryAcquire())
        assertEquals(1, limiter.activeConnectionCount())
        assertNull(limiter.tryAcquire())

        first.close()
        first.close()
        assertEquals(0, limiter.activeConnectionCount())
        assertNotNull(limiter.tryAcquire()).close()
        assertEquals(0, limiter.activeConnectionCount())
    }

    @Test
    fun `serializes outbound frames through one writer`() = runBlocking {
        val writes = mutableListOf<String>()
        val sender =
            BoundedRealtimeOutboundSender(
                scope = this,
                capacity = 2,
                sendTimeoutMillis = 1_000,
                writeText = { writes += it },
                closeSlowConsumer = { error("healthy writer must not close") },
            )

        sender.send("first", awaitDelivery = true)
        sender.send("second", awaitDelivery = true)

        assertEquals(listOf("first", "second"), writes)
        sender.shutdown()
    }

    @Test
    fun `closes a slow consumer when the bounded queue overflows`() = runBlocking {
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val closeCount = AtomicInteger(0)
        val sender =
            BoundedRealtimeOutboundSender(
                scope = this,
                capacity = 1,
                sendTimeoutMillis = 5_000,
                writeText = {
                    writerStarted.complete(Unit)
                    releaseWriter.await()
                },
                closeSlowConsumer = { closeCount.incrementAndGet() },
            )

        val first = async { runCatching { sender.send("first", awaitDelivery = true) } }
        writerStarted.await()
        sender.send("second")

        val overflow = runCatching { sender.send("third") }.exceptionOrNull()
        assertTrue(overflow is SlowRealtimeConsumerException)
        assertEquals(1, closeCount.get())

        releaseWriter.complete(Unit)
        first.await()
        sender.shutdown()
    }

    @Test
    fun `rejects unbounded hardening configuration`() {
        assertFailsWith<IllegalArgumentException> {
            RealtimeTransportHardeningConfig(outboundQueueCapacity = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RealtimeTransportHardeningConfig(maxConcurrentConnections = Int.MAX_VALUE)
        }
        assertEquals(
            "SINGLE_APPLICATION_INSTANCE",
            RealtimeTransportHardeningConfig.LIVE_FAN_OUT_SCOPE,
        )
    }
}
