package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.typing.EphemeralTypingLeaseStore
import com.premierdarkcoffee.nexo.connect.lab.application.typing.SecureTypingLeaseRefFactory
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseHandle
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefFactory
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseRefreshResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseReleaseResult
import com.premierdarkcoffee.nexo.connect.lab.application.typing.TypingLeaseTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

data class RedisTypingLeaseConfig(val instanceRef: String, val typingTtl: Duration = DEFAULT_TYPING_TTL) {
    init {
        require(instanceRef.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) && instanceRef.toByteArray().size <= 128) {
            "CONNECT_LAB_INSTANCE_REF must be a bounded opaque instance reference"
        }
        require(typingTtl in MINIMUM_TYPING_TTL..MAXIMUM_TYPING_TTL) {
            "Typing TTL must be between 500 milliseconds and 15 seconds"
        }
    }

    companion object {
        val DEFAULT_TYPING_TTL: Duration = Duration.ofSeconds(6)
        private val MINIMUM_TYPING_TTL: Duration = Duration.ofMillis(500)
        private val MAXIMUM_TYPING_TTL: Duration = Duration.ofSeconds(15)

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RedisTypingLeaseConfig =
            RedisTypingLeaseConfig(
                instanceRef =
                environment["CONNECT_LAB_INSTANCE_REF"]?.takeIf(String::isNotBlank)
                    ?: error("Missing required environment variable: CONNECT_LAB_INSTANCE_REF"),
            )
    }
}

internal class TypingLeaseRedisKeyCodec(private val keyNamespace: String) {
    init {
        require(keyNamespace == RedisEphemeralConfig.ISOLATED_KEY_NAMESPACE) {
            "Typing leases must remain in the isolated Connect Lab namespace"
        }
    }

    fun encode(target: TypingLeaseTarget): String {
        val conversationDigest = digest(target.conversationRef)
        val subjectDigest = digest("${target.platformScopeRef}\u0000${target.actorType.name}\u0000${target.subjectRef}")
        val deviceDigest = digest(target.deviceRef)
        return "$keyNamespace:typing:v1:c:$conversationDigest:s:$subjectDigest:d:$deviceDigest".also { key ->
            check(key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES) {
                "Typing lease key exceeded its frozen byte bound"
            }
        }
    }

    private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        const val MAX_KEY_BYTES = 224
        const val KEY_PREFIX = "nexo-connect-lab:typing:v1:"
    }
}

internal class RedisTypingLeaseStore(
    redisConfig: RedisEphemeralConfig,
    private val typingConfig: RedisTypingLeaseConfig,
    private val provider: PresenceLeaseRedisConnectionProvider =
        LettucePresenceLeaseRedisConnectionProvider(redisConfig),
    private val leaseRefFactory: TypingLeaseRefFactory = SecureTypingLeaseRefFactory(),
) : EphemeralTypingLeaseStore {
    override val leaseTtl: Duration = typingConfig.typingTtl
    private val keyCodec = TypingLeaseRedisKeyCodec(redisConfig.keyNamespace)
    private val closed = AtomicBoolean()
    private var connection: PresenceLeaseRedisConnection? = null

    override suspend fun start(target: TypingLeaseTarget): TypingLeaseAcquireResult = withContext(Dispatchers.IO) {
        val handle =
            TypingLeaseHandle(
                target = target,
                ownerInstanceRef = typingConfig.instanceRef,
                leaseRef = leaseRefFactory.create().also(::requireLeaseRef),
            )
        val applied = execute { current ->
            current.setWithTtl(keyCodec.encode(target), ownerValue(handle), typingConfig.typingTtl.toMillis())
        }
        if (applied == true) {
            TypingLeaseAcquireResult.Acquired(handle, typingConfig.typingTtl.toMillis())
        } else {
            TypingLeaseAcquireResult.Unavailable
        }
    }

    override suspend fun refresh(handle: TypingLeaseHandle): TypingLeaseRefreshResult = withContext(Dispatchers.IO) {
        if (handle.ownerInstanceRef != typingConfig.instanceRef) return@withContext TypingLeaseRefreshResult.NotOwner
        requireLeaseRef(handle.leaseRef)
        when (
            execute { current ->
                current.compareOwnerAndRefresh(
                    keyCodec.encode(handle.target),
                    ownerValue(handle),
                    typingConfig.typingTtl.toMillis(),
                )
            }
        ) {
            true -> TypingLeaseRefreshResult.Refreshed(typingConfig.typingTtl.toMillis())
            false -> TypingLeaseRefreshResult.NotOwner
            null -> TypingLeaseRefreshResult.Unavailable
        }
    }

    override suspend fun stop(handle: TypingLeaseHandle): TypingLeaseReleaseResult = withContext(Dispatchers.IO) {
        if (handle.ownerInstanceRef != typingConfig.instanceRef) return@withContext TypingLeaseReleaseResult.NOT_OWNER
        requireLeaseRef(handle.leaseRef)
        when (
            execute { current ->
                current.compareOwnerAndDelete(keyCodec.encode(handle.target), ownerValue(handle))
            }
        ) {
            true -> TypingLeaseReleaseResult.APPLIED
            false -> TypingLeaseReleaseResult.NOT_OWNER
            null -> TypingLeaseReleaseResult.UNAVAILABLE
        }
    }

    internal suspend fun remainingTtlMillis(target: TypingLeaseTarget): Long? =
        withContext(Dispatchers.IO) { execute { it.remainingTtlMillis(keyCodec.encode(target)) } }

    internal fun redisKey(target: TypingLeaseTarget): String = keyCodec.encode(target)

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.let { runCatching { it.close() } }
        connection = null
        runCatching { provider.close() }
    }

    @Synchronized
    private fun <T> execute(operation: (PresenceLeaseRedisConnection) -> T): T? {
        if (closed.get()) return null
        return try {
            val current = connection ?: provider.connect().also { connection = it }
            operation(current)
        } catch (_: Exception) {
            connection?.let { runCatching { it.close() } }
            connection = null
            null
        }
    }

    private fun ownerValue(handle: TypingLeaseHandle): String =
        "${handle.ownerInstanceRef}|${handle.leaseRef}".also { value ->
            check(value.toByteArray(Charsets.UTF_8).size <= 192) { "Typing lease owner value exceeded its byte bound" }
        }

    private fun requireLeaseRef(value: String) {
        require(value.matches(Regex("typing_[A-Za-z0-9_-]{32}"))) { "leaseRef must be opaque and bounded" }
    }
}
