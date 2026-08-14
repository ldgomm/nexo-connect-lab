package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedisPresenceLeaseIntegrationTest {
    @Test
    fun `real Redis expires crashes and renews reconnect ownership without durable state`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_PRESENCE_INTEGRATION") != "true") return@runBlocking

        val redisConfig = RedisEphemeralConfig.fromEnvironment()
        val original = store(redisConfig, "presence-integration-a")
        val reconnect = store(redisConfig, "presence-integration-b")
        val crash = store(redisConfig, "presence-integration-crash")
        val inspector = store(redisConfig, "presence-integration-inspector")

        try {
            val oldHandle = (original.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle
            val initialTtl = checkNotNull(original.remainingTtlMillis(target()))
            delay(250)
            assertEquals(PresenceLeaseMutationResult.APPLIED, original.refresh(oldHandle))
            val refreshedTtl = checkNotNull(original.remainingTtlMillis(target()))
            assertTrue(initialTtl in 1..LEASE_TTL_MILLIS)
            assertTrue(refreshedTtl > initialTtl - 250)

            val renewedHandle = (reconnect.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle
            assertNotEquals(oldHandle.leaseRef, renewedHandle.leaseRef)
            assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.refresh(oldHandle))
            assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.release(oldHandle))
            assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.refresh(renewedHandle))
            assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.release(renewedHandle))

            crash.acquire(crashTarget()) as PresenceLeaseAcquireResult.Acquired
            assertTrue(checkNotNull(crash.remainingTtlMillis(crashTarget())) > 0)
            crash.close()
            delay(LEASE_TTL_MILLIS + 350)
            assertEquals(-2L, inspector.remainingTtlMillis(crashTarget()))

            val key = inspector.redisKey(target())
            assertTrue(key.startsWith(PresenceLeaseRedisKeyCodec.KEY_PREFIX))
            assertTrue(key.toByteArray().size <= PresenceLeaseRedisKeyCodec.MAX_KEY_BYTES)
        } finally {
            original.close()
            reconnect.close()
            crash.close()
            inspector.close()
        }
    }

    private fun store(redisConfig: RedisEphemeralConfig, instanceRef: String): RedisPresenceLeaseStore =
        RedisPresenceLeaseStore(
            redisConfig = redisConfig,
            leaseConfig =
            RedisPresenceLeaseConfig(
                instanceRef = instanceRef,
                leaseTtl = Duration.ofMillis(LEASE_TTL_MILLIS),
                refreshInterval = Duration.ofMillis(200),
            ),
        )

    private fun target(): PresenceLeaseTarget = PresenceLeaseTarget(
        subjectRef = "presence-integration-client",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "presence-integration-platform",
        deviceRef = "device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    )

    private fun crashTarget(): PresenceLeaseTarget = target().copy(
        subjectRef = "presence-integration-crash-client",
        deviceRef = "device_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
    )

    private companion object {
        const val LEASE_TTL_MILLIS = 700L
    }
}
