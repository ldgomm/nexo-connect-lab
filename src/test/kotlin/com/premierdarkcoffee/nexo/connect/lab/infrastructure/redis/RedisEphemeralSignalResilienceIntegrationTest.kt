package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceActivitySnapshot
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefreshResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseReleaseResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RedisEphemeralSignalResilienceIntegrationTest {
    @Test
    fun `real Redis survives flush skew duplicate refresh crash and rapid reconnect`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_EPHEMERAL_RESILIENCE_INTEGRATION") != "true") {
            return@runBlocking
        }
        assertEquals("315360000", System.getenv("CONNECT_LAB_TEST_CLOCK_OFFSET_SECONDS"))

        val redisConfig = RedisEphemeralConfig.fromEnvironment()
        val adminPassword = requiredEnvironment("CONNECT_LAB_REDIS_PASSWORD")
        val adminClient = RedisClient.create(adminUri(redisConfig, adminPassword))
        val adminConnection = adminClient.connect()
        val presenceA = presenceStore(redisConfig, "resilience-instance-a")
        val presenceB = presenceStore(redisConfig, "resilience-instance-b")
        val presenceInspector = presenceStore(redisConfig, "resilience-inspector")
        val typingA = typingStore(redisConfig, "resilience-instance-a")
        val typingB = typingStore(redisConfig, "resilience-instance-b")

        try {
            adminConnection.sync().flushdb()
            val oldPresence = assertIs<PresenceLeaseAcquireResult.Acquired>(presenceA.acquire(presenceTarget())).handle
            val oldTyping = assertIs<TypingLeaseAcquireResult.Acquired>(typingA.start(typingTarget())).handle

            assertEquals(PresenceLeaseMutationResult.APPLIED, presenceA.refresh(oldPresence))
            assertEquals(PresenceLeaseMutationResult.APPLIED, presenceA.refresh(oldPresence))
            assertIs<TypingLeaseRefreshResult.Refreshed>(typingA.refresh(oldTyping))
            assertIs<TypingLeaseRefreshResult.Refreshed>(typingA.refresh(oldTyping))
            assertEquals(1, countKeys(adminConnection, PRESENCE_DEVICE_PATTERN))
            assertEquals(1, countKeys(adminConnection, TYPING_PATTERN))
            assertTrue(checkNotNull(presenceA.remainingTtlMillis(presenceTarget())) in 1..LEASE_TTL_MILLIS)
            assertTrue(checkNotNull(typingA.remainingTtlMillis(typingTarget())) in 1..LEASE_TTL_MILLIS)

            adminConnection.sync().flushdb()

            assertEquals(PresenceActivitySnapshot.OFFLINE, presenceInspector.read(presenceTarget().subjectTarget()))
            assertEquals(-2L, presenceA.remainingTtlMillis(presenceTarget()))
            assertEquals(-2L, typingA.remainingTtlMillis(typingTarget()))
            assertEquals(PresenceLeaseMutationResult.NOT_OWNER, presenceA.refresh(oldPresence))
            assertEquals(PresenceLeaseMutationResult.NOT_OWNER, presenceA.release(oldPresence))
            assertIs<TypingLeaseRefreshResult.NotOwner>(typingA.refresh(oldTyping))
            assertEquals(TypingLeaseReleaseResult.NOT_OWNER, typingA.stop(oldTyping))

            val newPresence = assertIs<PresenceLeaseAcquireResult.Acquired>(presenceB.acquire(presenceTarget())).handle
            val newTyping = assertIs<TypingLeaseAcquireResult.Acquired>(typingB.start(typingTarget())).handle
            assertNotEquals(oldPresence.leaseRef, newPresence.leaseRef)
            assertNotEquals(oldTyping.leaseRef, newTyping.leaseRef)
            assertEquals(PresenceLeaseMutationResult.NOT_OWNER, presenceA.refresh(oldPresence))
            assertIs<TypingLeaseRefreshResult.NotOwner>(typingA.refresh(oldTyping))
            assertEquals(PresenceLeaseMutationResult.APPLIED, presenceB.refresh(newPresence))
            assertEquals(PresenceLeaseMutationResult.APPLIED, presenceB.refresh(newPresence))
            assertIs<TypingLeaseRefreshResult.Refreshed>(typingB.refresh(newTyping))
            assertIs<TypingLeaseRefreshResult.Refreshed>(typingB.refresh(newTyping))
            assertEquals(1, countKeys(adminConnection, PRESENCE_DEVICE_PATTERN))
            assertEquals(1, countKeys(adminConnection, TYPING_PATTERN))

            presenceB.close()
            typingB.close()
            delay(LEASE_TTL_MILLIS + RECENTLY_ONLINE_MILLIS + EXPIRY_MARGIN_MILLIS)

            assertEquals(PresenceActivitySnapshot.OFFLINE, presenceInspector.read(presenceTarget().subjectTarget()))
            assertEquals(0, countKeys(adminConnection, PRESENCE_PATTERN))
            assertEquals(0, countKeys(adminConnection, TYPING_PATTERN))
        } finally {
            runCatching { adminConnection.sync().flushdb() }
            presenceA.close()
            presenceB.close()
            presenceInspector.close()
            typingA.close()
            typingB.close()
            adminConnection.close()
            adminClient.shutdown(Duration.ZERO, Duration.ofSeconds(2))
        }
    }

    private fun presenceStore(redisConfig: RedisEphemeralConfig, instanceRef: String) = RedisPresenceLeaseStore(
        redisConfig = redisConfig,
        leaseConfig = RedisPresenceLeaseConfig(
            instanceRef = instanceRef,
            leaseTtl = Duration.ofMillis(LEASE_TTL_MILLIS),
            refreshInterval = Duration.ofMillis(REFRESH_INTERVAL_MILLIS),
            recentlyOnlineWindow = Duration.ofMillis(RECENTLY_ONLINE_MILLIS),
        ),
    )

    private fun typingStore(redisConfig: RedisEphemeralConfig, instanceRef: String) = RedisTypingLeaseStore(
        redisConfig = redisConfig,
        typingConfig = RedisTypingLeaseConfig(instanceRef, Duration.ofMillis(LEASE_TTL_MILLIS)),
    )

    private fun presenceTarget() = PresenceLeaseTarget(
        subjectRef = "resilience-client",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "resilience-platform",
        deviceRef = "device_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
    )

    private fun typingTarget() = TypingLeaseTarget(
        subjectRef = "resilience-client",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "resilience-platform",
        conversationRef = "resilience-conversation",
        deviceRef = "device_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
    )

    private fun countKeys(connection: StatefulRedisConnection<String, String>, pattern: String): Int {
        var cursor: ScanCursor = ScanCursor.INITIAL
        var count = 0
        do {
            val result = connection.sync().scan(cursor, ScanArgs.Builder.matches(pattern).limit(64))
            count += result.keys.size
            cursor = result
        } while (!cursor.isFinished)
        return count
    }

    private fun adminUri(config: RedisEphemeralConfig, password: String): RedisURI =
        RedisURI.Builder.redis(config.host, config.port)
            .withAuthentication("default", password.toCharArray())
            .withDatabase(config.database)
            .withTimeout(Duration.ofMillis(config.commandTimeoutMillis))
            .withClientName("nexo-connect-lab-resilience-admin")
            .build()

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("Missing required environment variable: $name")

    private companion object {
        const val LEASE_TTL_MILLIS = 900L
        const val REFRESH_INTERVAL_MILLIS = 200L
        const val RECENTLY_ONLINE_MILLIS = 600L
        const val EXPIRY_MARGIN_MILLIS = 450L
        const val PRESENCE_PATTERN = "nexo-connect-lab:presence:v1:*"
        const val PRESENCE_DEVICE_PATTERN = "nexo-connect-lab:presence:v1:*:d:*"
        const val TYPING_PATTERN = "nexo-connect-lab:typing:v1:*"
    }
}
