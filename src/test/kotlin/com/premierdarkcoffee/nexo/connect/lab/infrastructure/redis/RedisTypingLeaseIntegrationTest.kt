package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefreshResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseReleaseResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedisTypingLeaseIntegrationTest {
    @Test
    fun `real isolated Redis refreshes stops and expires bounded typing leases`() = runBlocking {
        if (System.getenv("CONNECT_LAB_REDIS_TYPING_INTEGRATION") != "true") return@runBlocking
        val redisConfig = RedisEphemeralConfig.fromEnvironment()
        val store = store(redisConfig, "typing-integration-a")
        val inspector = store(redisConfig, "typing-integration-inspector")
        try {
            val acquired = assertIs<TypingLeaseAcquireResult.Acquired>(store.start(target()))
            delay(200)
            assertIs<TypingLeaseRefreshResult.Refreshed>(store.refresh(acquired.handle))
            assertTrue(checkNotNull(store.remainingTtlMillis(target())) > 0)
            assertEquals(TypingLeaseReleaseResult.APPLIED, store.stop(acquired.handle))
            assertEquals(-2L, inspector.remainingTtlMillis(target()))

            store.start(crashTarget())
            store.close()
            delay(TTL_MILLIS + 300)
            assertEquals(-2L, inspector.remainingTtlMillis(crashTarget()))
        } finally {
            store.close()
            inspector.close()
        }
    }

    private fun store(redisConfig: RedisEphemeralConfig, instanceRef: String) = RedisTypingLeaseStore(
        redisConfig = redisConfig,
        typingConfig = RedisTypingLeaseConfig(instanceRef, Duration.ofMillis(TTL_MILLIS)),
    )

    private fun target() = TypingLeaseTarget(
        subjectRef = "typing-integration-client",
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "typing-integration-platform",
        conversationRef = "typing-integration-conversation",
        deviceRef = "device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    )

    private fun crashTarget() = target().copy(
        subjectRef = "typing-integration-crash-client",
        deviceRef = "device_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
    )

    private companion object {
        const val TTL_MILLIS = 700L
    }
}
