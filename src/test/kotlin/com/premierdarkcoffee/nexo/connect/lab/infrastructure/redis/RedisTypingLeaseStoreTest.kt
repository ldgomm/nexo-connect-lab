package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefFactory
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefreshResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseReleaseResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedisTypingLeaseStoreTest {
    @Test
    fun `starts refreshes stops expires and hides raw routing identities`() = runBlocking {
        val state = FakeRedisState()
        val store = store(state)
        val acquired = assertIs<TypingLeaseAcquireResult.Acquired>(store.start(target()))
        state.advance(300)

        assertIs<TypingLeaseRefreshResult.Refreshed>(store.refresh(acquired.handle))
        assertTrue(checkNotNull(store.remainingTtlMillis(target())) > 500)
        val key = store.redisKey(target())
        assertTrue(key.startsWith(TypingLeaseRedisKeyCodec.KEY_PREFIX))
        assertTrue(key.toByteArray().size <= TypingLeaseRedisKeyCodec.MAX_KEY_BYTES)
        assertFalse("conversation-1" in key)
        assertFalse("client-1" in key)
        assertFalse("device_" in key)
        assertEquals(TypingLeaseReleaseResult.APPLIED, store.stop(acquired.handle))
        assertEquals(-2L, store.remainingTtlMillis(target()))

        store.start(target())
        state.advance(1_001)
        assertEquals(-2L, store.remainingTtlMillis(target()))
        store.close()
    }

    private fun store(state: FakeRedisState) = RedisTypingLeaseStore(
        redisConfig = redisConfig(),
        typingConfig = RedisTypingLeaseConfig("instance-a", Duration.ofSeconds(1)),
        provider = FakeProvider(state),
        leaseRefFactory = TypingLeaseRefFactory { "typing_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
    )

    private fun target() = TypingLeaseTarget(
        subjectRef = "client-1",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
        conversationRef = "conversation-1",
        deviceRef = "device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    )

    private fun redisConfig() = RedisEphemeralConfig(
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
        override fun setWithTtl(key: String, owner: String, ttlMillis: Long) = state.set(key, owner, ttlMillis)

        override fun compareOwnerAndRefresh(key: String, owner: String, ttlMillis: Long) =
            state.refresh(key, owner, ttlMillis)

        override fun compareOwnerAndDelete(key: String, owner: String) = state.delete(key, owner)

        override fun remainingTtlMillis(key: String) = state.pttl(key)

        override fun close() = Unit
    }

    private class FakeRedisState {
        private data class Entry(val owner: String, val expiresAt: Long)

        private val entries = mutableMapOf<String, Entry>()
        private var now = 0L

        fun advance(millis: Long) {
            now += millis
        }

        fun set(key: String, owner: String, ttlMillis: Long): Boolean {
            entries[key] = Entry(owner, now + ttlMillis)
            return true
        }

        fun refresh(key: String, owner: String, ttlMillis: Long): Boolean {
            val current = current(key) ?: return false
            if (current.owner != owner) return false
            entries[key] = Entry(owner, now + ttlMillis)
            return true
        }

        fun delete(key: String, owner: String): Boolean {
            if (current(key)?.owner != owner) return false
            entries.remove(key)
            return true
        }

        fun pttl(key: String): Long = current(key)?.let { it.expiresAt - now } ?: -2

        private fun current(key: String): Entry? {
            val value = entries[key] ?: return null
            if (value.expiresAt <= now) {
                entries.remove(key)
                return null
            }
            return value
        }
    }
}
