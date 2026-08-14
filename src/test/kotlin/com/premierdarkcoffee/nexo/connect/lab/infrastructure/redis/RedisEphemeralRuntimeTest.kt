package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisEphemeralRuntimeTest {
    @Test
    fun `opens the circuit without failing when Redis is absent and retries with bounded backoff`() {
        var now = 1_000_000_000L
        val provider = FakeProvider(connectFailures = 1)
        val runtime = RedisEphemeralRuntime(config(), provider) { now }

        val unavailable = runtime.readiness()
        val suppressedRetry = runtime.readiness()

        assertFalse(unavailable.available)
        assertEquals(RedisCircuitState.OPEN, unavailable.circuitState)
        assertEquals(100, unavailable.retryAfterMillis)
        assertEquals(1, provider.connectAttempts)
        assertFalse(suppressedRetry.available)
        now += 100_000_000L
        val recovered = runtime.readiness()
        assertTrue(recovered.available)
        assertEquals(RedisCircuitState.CLOSED, recovered.circuitState)
        assertEquals(2, provider.connectAttempts)
    }

    @Test
    fun `degrades after a ping failure and closes every owned resource`() {
        var now = 0L
        val firstConnection = FakeConnection(mutableListOf(true, false))
        val provider = FakeProvider(connections = ArrayDeque(listOf(firstConnection)))
        val runtime = RedisEphemeralRuntime(config(), provider) { now }

        assertTrue(runtime.readiness().available)
        now += 1_000_000L
        val degraded = runtime.readiness()
        runtime.close()
        val stopped = runtime.readiness()

        assertFalse(degraded.available)
        assertEquals(RedisCircuitState.OPEN, degraded.circuitState)
        assertTrue(firstConnection.closed)
        assertTrue(provider.closed)
        assertEquals(RedisCircuitState.STOPPED, stopped.circuitState)
    }

    private class FakeProvider(
        private var connectFailures: Int = 0,
        private val connections: ArrayDeque<FakeConnection> = ArrayDeque(),
    ) : RedisEphemeralConnectionProvider {
        var connectAttempts = 0
        var closed = false

        override fun connect(): RedisEphemeralConnection {
            connectAttempts++
            if (connectFailures > 0) {
                connectFailures--
                error("synthetic unavailable Redis")
            }
            return connections.removeFirstOrNull() ?: FakeConnection(mutableListOf(true))
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeConnection(private val pingResults: MutableList<Boolean>) : RedisEphemeralConnection {
        var closed = false

        override fun ping(): Boolean = pingResults.removeFirstOrNull() ?: false

        override fun close() {
            closed = true
        }
    }

    private fun config(): RedisEphemeralConfig = RedisEphemeralConfig(
        host = "redis",
        port = 6379,
        user = "nexo_connect_lab_app",
        password = "0123456789abcdef0123456789abcdef",
        keyNamespace = "nexo-connect-lab",
        channelNamespace = "nexo.connect.realtime.v1",
        database = 0,
        connectTimeoutMillis = 2_000,
        commandTimeoutMillis = 1_000,
        reconnectMinDelayMillis = 100,
        reconnectMaxDelayMillis = 2_000,
        requestQueueSize = 256,
    )
}
