package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceActivitySnapshot
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisPresenceAggregationIntegrationTest {
    @Test
    fun `real Redis keeps subject online until the final device leaves`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_PRESENCE_AGGREGATION_INTEGRATION") != "true") {
            return@runBlocking
        }

        val store =
            RedisPresenceLeaseStore(
                redisConfig = RedisEphemeralConfig.fromEnvironment(),
                leaseConfig =
                RedisPresenceLeaseConfig(
                    instanceRef = "presence-aggregation-integration",
                    leaseTtl = Duration.ofMillis(LEASE_TTL_MILLIS),
                    refreshInterval = Duration.ofMillis(REFRESH_INTERVAL_MILLIS),
                    recentlyOnlineWindow = Duration.ofMillis(RECENT_WINDOW_MILLIS),
                ),
            )
        val firstTarget = target("device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val secondTarget = target("device_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")

        try {
            val first = (store.acquire(firstTarget) as PresenceLeaseAcquireResult.Acquired).handle
            val second = (store.acquire(secondTarget) as PresenceLeaseAcquireResult.Acquired).handle

            assertEquals(PresenceActivitySnapshot.ONLINE, store.read(firstTarget.subjectTarget()))
            assertEquals(PresenceLeaseMutationResult.APPLIED, store.release(first))
            assertEquals(PresenceActivitySnapshot.ONLINE, store.read(firstTarget.subjectTarget()))
            assertEquals(PresenceLeaseMutationResult.APPLIED, store.release(second))
            assertEquals(PresenceActivitySnapshot.RECENTLY_ONLINE, store.read(firstTarget.subjectTarget()))

            delay(RECENT_WINDOW_MILLIS + EXPIRY_MARGIN_MILLIS)

            assertEquals(PresenceActivitySnapshot.OFFLINE, store.read(firstTarget.subjectTarget()))
        } finally {
            store.close()
        }
    }

    private fun target(deviceRef: String): PresenceLeaseTarget = PresenceLeaseTarget(
        subjectRef = "presence-aggregation-client",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "presence-aggregation-platform",
        deviceRef = deviceRef,
    )

    private companion object {
        const val LEASE_TTL_MILLIS = 900L
        const val REFRESH_INTERVAL_MILLIS = 300L
        const val RECENT_WINDOW_MILLIS = 700L
        const val EXPIRY_MARGIN_MILLIS = 300L
    }
}
