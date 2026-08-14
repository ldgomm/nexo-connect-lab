package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceActivitySnapshot
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseRefFactory
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedisPresenceLeaseStoreTest {
    @Test
    fun `acquires refreshes and releases only the current instance owner`() = runBlocking {
        val state = FakeRedisState()
        val store = store("instance-a", "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", state)

        val acquired = store.acquire(target()) as PresenceLeaseAcquireResult.Acquired
        val initialTtl = store.remainingTtlMillis(target())
        state.advance(400)
        val refreshed = store.refresh(acquired.handle)
        val refreshedTtl = store.remainingTtlMillis(target())
        val released = store.release(acquired.handle)

        assertEquals(PresenceLeaseMutationResult.APPLIED, refreshed)
        assertNotNull(initialTtl)
        assertNotNull(refreshedTtl)
        assertTrue(refreshedTtl > initialTtl - 400)
        assertEquals(PresenceLeaseMutationResult.APPLIED, released)
        assertEquals(-2L, store.remainingTtlMillis(target()))
        store.close()
    }

    @Test
    fun `reconnect rotates ownership and stale owner cannot refresh or delete`() = runBlocking {
        val state = FakeRedisState()
        val original = store("instance-a", "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", state)
        val reconnect = store("instance-b", "lease_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", state)
        val oldHandle = (original.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle
        val renewedHandle = (reconnect.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle

        assertNotEquals(oldHandle.leaseRef, renewedHandle.leaseRef)
        assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.refresh(oldHandle))
        assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.release(oldHandle))
        assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.refresh(renewedHandle))
        assertTrue(checkNotNull(reconnect.remainingTtlMillis(target())) > 0)
        assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.release(renewedHandle))
        original.close()
        reconnect.close()
    }

    @Test
    fun `crashed owner disappears through TTL without cleanup writes`() = runBlocking {
        val state = FakeRedisState()
        val crashed = store("instance-crashed", "lease_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", state)
        crashed.acquire(target()) as PresenceLeaseAcquireResult.Acquired
        crashed.close()

        state.advance(1_001)
        val inspector = store("instance-inspector", "lease_DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", state)

        assertEquals(-2L, inspector.remainingTtlMillis(target()))
        inspector.close()
    }

    @Test
    fun `flush rejects stale handles and rapid reconnect establishes one owner`() = runBlocking {
        val state = FakeRedisState()
        val original = store("instance-a", "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", state)
        val reconnect = store("instance-b", "lease_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", state)
        val oldHandle = (original.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle

        assertEquals(PresenceLeaseMutationResult.APPLIED, original.refresh(oldHandle))
        assertEquals(PresenceLeaseMutationResult.APPLIED, original.refresh(oldHandle))
        assertEquals(1, state.countMatching(original.redisDeviceLeasePattern(target().subjectTarget())))

        state.flush()

        assertEquals(PresenceActivitySnapshot.OFFLINE, original.read(target().subjectTarget()))
        assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.refresh(oldHandle))
        assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.release(oldHandle))

        val newHandle = (reconnect.acquire(target()) as PresenceLeaseAcquireResult.Acquired).handle
        assertNotEquals(oldHandle.leaseRef, newHandle.leaseRef)
        assertEquals(PresenceLeaseMutationResult.NOT_OWNER, original.refresh(oldHandle))
        assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.refresh(newHandle))
        assertEquals(PresenceLeaseMutationResult.APPLIED, reconnect.refresh(newHandle))
        assertEquals(1, state.countMatching(reconnect.redisDeviceLeasePattern(target().subjectTarget())))
        original.close()
        reconnect.close()
    }

    @Test
    fun `keys are namespace bound fixed length digests without raw identity`() {
        val store = store("instance-a", "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", FakeRedisState())
        val key = store.redisKey(target())

        assertTrue(key.startsWith(PresenceLeaseRedisKeyCodec.KEY_PREFIX))
        assertTrue(key.toByteArray().size <= PresenceLeaseRedisKeyCodec.MAX_KEY_BYTES)
        assertFalse("client-1" in key)
        assertFalse("device_" in key)
        store.close()
    }

    @Test
    fun `aggregates multiple device leases without revealing device topology`() = runBlocking {
        val state = FakeRedisState()
        val store = store("instance-a", "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", state)
        val firstTarget = target()
        val secondTarget = target().copy(deviceRef = "device_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
        val first = (store.acquire(firstTarget) as PresenceLeaseAcquireResult.Acquired).handle
        val second = (store.acquire(secondTarget) as PresenceLeaseAcquireResult.Acquired).handle

        assertEquals(PresenceActivitySnapshot.ONLINE, store.read(firstTarget.subjectTarget()))
        assertEquals(PresenceLeaseMutationResult.APPLIED, store.release(first))
        assertEquals(PresenceActivitySnapshot.ONLINE, store.read(firstTarget.subjectTarget()))
        assertEquals(PresenceLeaseMutationResult.APPLIED, store.release(second))
        assertEquals(PresenceActivitySnapshot.RECENTLY_ONLINE, store.read(firstTarget.subjectTarget()))

        state.advance(1_001)

        assertEquals(PresenceActivitySnapshot.OFFLINE, store.read(firstTarget.subjectTarget()))
        assertFalse("client-1" in store.redisDeviceLeasePattern(firstTarget.subjectTarget()))
        assertFalse("device_" in store.redisDeviceLeasePattern(firstTarget.subjectTarget()))
        assertFalse("client-1" in store.redisRecentMarkerKey(firstTarget.subjectTarget()))
        store.close()
    }

    private fun store(instanceRef: String, leaseRef: String, state: FakeRedisState): RedisPresenceLeaseStore =
        RedisPresenceLeaseStore(
            redisConfig = redisConfig(),
            leaseConfig =
            RedisPresenceLeaseConfig(
                instanceRef = instanceRef,
                leaseTtl = Duration.ofSeconds(1),
                refreshInterval = Duration.ofMillis(300),
                recentlyOnlineWindow = Duration.ofSeconds(1),
            ),
            provider = FakeProvider(state),
            leaseRefFactory = PresenceLeaseRefFactory { leaseRef },
        )

    private fun target(): PresenceLeaseTarget = PresenceLeaseTarget(
        subjectRef = "client-1",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
        deviceRef = "device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    )

    private fun redisConfig(): RedisEphemeralConfig = RedisEphemeralConfig(
        host = "redis",
        port = 6379,
        user = RedisEphemeralConfig.DEDICATED_APP_USER,
        password = "0123456789abcdef0123456789abcdef",
        keyNamespace = RedisEphemeralConfig.ISOLATED_KEY_NAMESPACE,
        channelNamespace = RedisEphemeralConfig.ISOLATED_CHANNEL_NAMESPACE,
        database = 0,
        connectTimeoutMillis = 2_000,
        commandTimeoutMillis = 1_000,
        reconnectMinDelayMillis = 100,
        reconnectMaxDelayMillis = 2_000,
        requestQueueSize = 256,
    )

    private class FakeProvider(private val state: FakeRedisState) : PresenceLeaseRedisConnectionProvider {
        override fun connect(): PresenceLeaseRedisConnection = FakeConnection(state)

        override fun close() = Unit
    }

    private class FakeConnection(private val state: FakeRedisState) : PresenceLeaseRedisConnection {
        override fun setWithTtl(key: String, owner: String, ttlMillis: Long): Boolean = state.set(key, owner, ttlMillis)

        override fun compareOwnerAndRefresh(key: String, owner: String, ttlMillis: Long): Boolean =
            state.refresh(key, owner, ttlMillis)

        override fun compareOwnerAndDelete(key: String, owner: String): Boolean = state.delete(key, owner)

        override fun setMarkerWithTtl(key: String, ttlMillis: Long): Boolean = state.set(key, "1", ttlMillis)

        override fun hasAnyMatchingKey(pattern: String): Boolean = state.hasAnyMatchingKey(pattern)

        override fun exists(key: String): Boolean = state.exists(key)

        override fun remainingTtlMillis(key: String): Long = state.pttl(key)

        override fun close() = Unit
    }

    private class FakeRedisState {
        private data class Entry(val owner: String, val expiresAtMillis: Long)

        private val entries = mutableMapOf<String, Entry>()
        private var nowMillis = 0L

        fun advance(millis: Long) {
            nowMillis += millis
        }

        fun flush() {
            entries.clear()
        }

        fun countMatching(pattern: String): Int {
            val prefix = pattern.removeSuffix("*")
            return entries.keys.toList().count { key -> key.startsWith(prefix) && current(key) != null }
        }

        fun set(key: String, owner: String, ttlMillis: Long): Boolean {
            entries[key] = Entry(owner, nowMillis + ttlMillis)
            return true
        }

        fun refresh(key: String, owner: String, ttlMillis: Long): Boolean {
            val current = current(key) ?: return false
            if (current.owner != owner) return false
            entries[key] = Entry(owner, nowMillis + ttlMillis)
            return true
        }

        fun delete(key: String, owner: String): Boolean {
            val current = current(key) ?: return false
            if (current.owner != owner) return false
            entries.remove(key)
            return true
        }

        fun pttl(key: String): Long {
            val current = current(key) ?: return -2
            return current.expiresAtMillis - nowMillis
        }

        fun hasAnyMatchingKey(pattern: String): Boolean {
            val prefix = pattern.removeSuffix("*")
            return entries.keys.toList().any { key -> key.startsWith(prefix) && current(key) != null }
        }

        fun exists(key: String): Boolean = current(key) != null

        private fun current(key: String): Entry? {
            val entry = entries[key] ?: return null
            if (entry.expiresAtMillis <= nowMillis) {
                entries.remove(key)
                return null
            }
            return entry
        }
    }
}
