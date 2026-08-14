package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedisRealtimeFanoutConfigTest {
    @Test
    fun `derives only the frozen v1 channels from the isolated Redis namespace`() {
        val config = RedisRealtimeFanoutConfig.fromEnvironment(
            redisConfig(),
            mapOf("CONNECT_LAB_INSTANCE_REF" to "node-1"),
        )

        assertEquals("node-1", config.instanceRef)
        assertEquals("nexo.connect.realtime.v1.message-created", config.messageCreatedChannel)
        assertEquals("nexo.connect.realtime.v1.receipt-advanced", config.receiptAdvancedChannel)
    }

    @Test
    fun `rejects channel drift and unsafe instance references`() {
        assertFailsWith<IllegalArgumentException> {
            RedisRealtimeFanoutConfig("node 1", "wrong", "wrong")
        }
        assertFailsWith<IllegalArgumentException> {
            RedisRealtimeFanoutConfig(
                "node-1",
                "nexo.connect.realtime.v2.message-created",
                RedisRealtimeFanoutConfig.EXPECTED_RECEIPT_CHANNEL,
            )
        }
    }

    private fun redisConfig() = RedisEphemeralConfig(
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
