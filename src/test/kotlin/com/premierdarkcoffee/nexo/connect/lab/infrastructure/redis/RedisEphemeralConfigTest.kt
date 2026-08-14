package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RedisEphemeralConfigTest {
    @Test
    fun `loads the isolated typed Redis application configuration`() {
        val config = RedisEphemeralConfig.fromEnvironment(validEnvironment())

        assertEquals("redis", config.host)
        assertEquals(6379, config.port)
        assertEquals("nexo_connect_lab_app", config.user)
        assertEquals("nexo-connect-lab", config.keyNamespace)
        assertEquals("nexo.connect.realtime.v1", config.channelNamespace)
        assertEquals(256, config.requestQueueSize)
    }

    @Test
    fun `never renders the Redis application secret`() {
        val secret = "redis-app-secret-that-must-never-be-logged"
        val config =
            RedisEphemeralConfig.fromEnvironment(
                validEnvironment() + ("CONNECT_LAB_REDIS_APP_PASSWORD" to secret),
            )

        assertFalse(config.toString().contains(secret))
        assertContains(config.toString(), "password=<redacted>")
    }

    @Test
    fun `rejects a root identity or a foreign namespace`() {
        assertFailsWith<IllegalArgumentException> {
            RedisEphemeralConfig.fromEnvironment(
                validEnvironment() + ("CONNECT_LAB_REDIS_APP_USER" to "default"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RedisEphemeralConfig.fromEnvironment(
                validEnvironment() + ("CONNECT_LAB_REDIS_NAMESPACE" to "nexo-core"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RedisEphemeralConfig.fromEnvironment(
                validEnvironment() +
                    ("CONNECT_LAB_REDIS_CHANNEL_NAMESPACE" to "nexo.connect.realtime.v2"),
            )
        }
    }

    private fun validEnvironment(): Map<String, String> = mapOf(
        "CONNECT_LAB_REDIS_HOST" to "redis",
        "CONNECT_LAB_REDIS_PORT" to "6379",
        "CONNECT_LAB_REDIS_APP_USER" to "nexo_connect_lab_app",
        "CONNECT_LAB_REDIS_APP_PASSWORD" to "0123456789abcdef0123456789abcdef",
        "CONNECT_LAB_REDIS_NAMESPACE" to "nexo-connect-lab",
        "CONNECT_LAB_REDIS_CHANNEL_NAMESPACE" to "nexo.connect.realtime.v1",
        "CONNECT_LAB_REDIS_DATABASE" to "0",
        "CONNECT_LAB_REDIS_CONNECT_TIMEOUT_MILLIS" to "2000",
        "CONNECT_LAB_REDIS_COMMAND_TIMEOUT_MILLIS" to "1000",
        "CONNECT_LAB_REDIS_RECONNECT_MIN_DELAY_MILLIS" to "100",
        "CONNECT_LAB_REDIS_RECONNECT_MAX_DELAY_MILLIS" to "2000",
        "CONNECT_LAB_REDIS_REQUEST_QUEUE_SIZE" to "256",
    )
}
