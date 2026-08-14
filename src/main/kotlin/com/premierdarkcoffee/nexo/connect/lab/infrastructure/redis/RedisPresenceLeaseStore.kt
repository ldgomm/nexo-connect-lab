package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.EphemeralPresenceLeaseStore
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseHandle
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseRefFactory
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.application.presence.SecurePresenceLeaseRefFactory
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

data class RedisPresenceLeaseConfig(
    val instanceRef: String,
    val leaseTtl: Duration = DEFAULT_LEASE_TTL,
    val refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
) {
    init {
        require(
            instanceRef.matches(INSTANCE_REF_PATTERN) &&
                instanceRef.toByteArray(Charsets.UTF_8).size <= MAX_INSTANCE_REF_BYTES,
        ) { "CONNECT_LAB_INSTANCE_REF must be a bounded opaque instance reference" }
        require(leaseTtl >= MINIMUM_LEASE_TTL && leaseTtl <= MAXIMUM_LEASE_TTL) {
            "Presence lease TTL must be between 500 milliseconds and 5 minutes"
        }
        require(!refreshInterval.isZero && !refreshInterval.isNegative && refreshInterval.multipliedBy(2) < leaseTtl) {
            "Presence refresh interval must be positive and less than half the lease TTL"
        }
    }

    companion object {
        val DEFAULT_LEASE_TTL: Duration = Duration.ofSeconds(45)
        val DEFAULT_REFRESH_INTERVAL: Duration = Duration.ofSeconds(15)
        private val MINIMUM_LEASE_TTL: Duration = Duration.ofMillis(500)
        private val MAXIMUM_LEASE_TTL: Duration = Duration.ofMinutes(5)
        private const val MAX_INSTANCE_REF_BYTES = 128
        private val INSTANCE_REF_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RedisPresenceLeaseConfig =
            RedisPresenceLeaseConfig(
                instanceRef =
                environment["CONNECT_LAB_INSTANCE_REF"]?.takeIf(String::isNotBlank)
                    ?: error("Missing required environment variable: CONNECT_LAB_INSTANCE_REF"),
            )
    }
}

internal class PresenceLeaseRedisKeyCodec(private val keyNamespace: String) {
    init {
        require(keyNamespace == RedisEphemeralConfig.ISOLATED_KEY_NAMESPACE) {
            "Presence leases must remain in the isolated Connect Lab namespace"
        }
    }

    fun encode(target: PresenceLeaseTarget): String {
        val subjectDigest =
            digest("${target.platformScopeRef}\u0000${target.actorType.name}\u0000${target.subjectRef}")
        val deviceDigest = digest(target.deviceRef)
        return "$keyNamespace:presence:v1:s:$subjectDigest:d:$deviceDigest".also { key ->
            check(key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES) {
                "Presence lease key exceeded its frozen byte bound"
            }
        }
    }

    private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        const val MAX_KEY_BYTES = 160
        const val KEY_PREFIX = "nexo-connect-lab:presence:v1:"
    }
}

internal interface PresenceLeaseRedisConnection : AutoCloseable {
    fun setWithTtl(key: String, owner: String, ttlMillis: Long): Boolean

    fun compareOwnerAndRefresh(key: String, owner: String, ttlMillis: Long): Boolean

    fun compareOwnerAndDelete(key: String, owner: String): Boolean

    fun remainingTtlMillis(key: String): Long
}

internal interface PresenceLeaseRedisConnectionProvider : AutoCloseable {
    fun connect(): PresenceLeaseRedisConnection
}

internal class LettucePresenceLeaseRedisConnectionProvider(private val redisConfig: RedisEphemeralConfig) :
    PresenceLeaseRedisConnectionProvider {
    private val client = RedisClient.create(redisUri(redisConfig)).apply { options = clientOptions(redisConfig) }

    override fun connect(): PresenceLeaseRedisConnection = LettucePresenceLeaseRedisConnection(client.connect())

    override fun close() {
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2))
    }

    private companion object {
        fun redisUri(config: RedisEphemeralConfig): RedisURI = RedisURI.Builder.redis(config.host, config.port)
            .withAuthentication(config.user, config.password.toCharArray())
            .withDatabase(config.database)
            .withTimeout(Duration.ofMillis(config.commandTimeoutMillis))
            .withClientName("nexo-connect-lab-presence")
            .build()

        fun clientOptions(config: RedisEphemeralConfig): ClientOptions = ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .requestQueueSize(config.requestQueueSize)
            .socketOptions(
                SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis))
                    .build(),
            )
            .build()
    }
}

private class LettucePresenceLeaseRedisConnection(private val connection: StatefulRedisConnection<String, String>) :
    PresenceLeaseRedisConnection {
    override fun setWithTtl(key: String, owner: String, ttlMillis: Long): Boolean =
        connection.sync().set(key, owner, io.lettuce.core.SetArgs.Builder.px(ttlMillis)) == "OK"

    override fun compareOwnerAndRefresh(key: String, owner: String, ttlMillis: Long): Boolean =
        connection.sync().eval<Long>(
            COMPARE_AND_REFRESH_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            owner,
            ttlMillis.toString(),
        ) == 1L

    override fun compareOwnerAndDelete(key: String, owner: String): Boolean = connection.sync().eval<Long>(
        COMPARE_AND_DELETE_SCRIPT,
        ScriptOutputType.INTEGER,
        arrayOf(key),
        owner,
    ) == 1L

    override fun remainingTtlMillis(key: String): Long = connection.sync().pttl(key)

    override fun close() {
        connection.close()
    }

    private companion object {
        const val COMPARE_AND_REFRESH_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end"
        const val COMPARE_AND_DELETE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                "return redis.call('DEL', KEYS[1]) else return 0 end"
    }
}

internal class RedisPresenceLeaseStore(
    redisConfig: RedisEphemeralConfig,
    private val leaseConfig: RedisPresenceLeaseConfig,
    private val provider: PresenceLeaseRedisConnectionProvider =
        LettucePresenceLeaseRedisConnectionProvider(redisConfig),
    private val leaseRefFactory: PresenceLeaseRefFactory = SecurePresenceLeaseRefFactory(),
) : EphemeralPresenceLeaseStore {
    override val refreshInterval: Duration = leaseConfig.refreshInterval
    private val keyCodec = PresenceLeaseRedisKeyCodec(redisConfig.keyNamespace)
    private val closed = AtomicBoolean()
    private var connection: PresenceLeaseRedisConnection? = null

    override suspend fun acquire(target: PresenceLeaseTarget): PresenceLeaseAcquireResult =
        withContext(Dispatchers.IO) {
            val handle =
                PresenceLeaseHandle(
                    target = target,
                    ownerInstanceRef = leaseConfig.instanceRef,
                    leaseRef = leaseRefFactory.create().also(::requireLeaseRef),
                )
            val applied = execute { current ->
                current.setWithTtl(keyCodec.encode(target), ownerValue(handle), leaseConfig.leaseTtl.toMillis())
            }
            if (applied == true) PresenceLeaseAcquireResult.Acquired(handle) else PresenceLeaseAcquireResult.Unavailable
        }

    override suspend fun refresh(handle: PresenceLeaseHandle): PresenceLeaseMutationResult =
        mutate(handle) { current, key, owner ->
            current.compareOwnerAndRefresh(key, owner, leaseConfig.leaseTtl.toMillis())
        }

    override suspend fun release(handle: PresenceLeaseHandle): PresenceLeaseMutationResult =
        mutate(handle) { current, key, owner -> current.compareOwnerAndDelete(key, owner) }

    internal suspend fun remainingTtlMillis(target: PresenceLeaseTarget): Long? =
        withContext(Dispatchers.IO) { execute { it.remainingTtlMillis(keyCodec.encode(target)) } }

    internal fun redisKey(target: PresenceLeaseTarget): String = keyCodec.encode(target)

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.let { runCatching { it.close() } }
        connection = null
        runCatching { provider.close() }
    }

    private suspend fun mutate(
        handle: PresenceLeaseHandle,
        operation: (PresenceLeaseRedisConnection, String, String) -> Boolean,
    ): PresenceLeaseMutationResult = withContext(Dispatchers.IO) {
        if (handle.ownerInstanceRef != leaseConfig.instanceRef) {
            return@withContext PresenceLeaseMutationResult.NOT_OWNER
        }
        requireLeaseRef(handle.leaseRef)
        when (execute { current -> operation(current, keyCodec.encode(handle.target), ownerValue(handle)) }) {
            true -> PresenceLeaseMutationResult.APPLIED
            false -> PresenceLeaseMutationResult.NOT_OWNER
            null -> PresenceLeaseMutationResult.UNAVAILABLE
        }
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

    private fun ownerValue(handle: PresenceLeaseHandle): String =
        "${handle.ownerInstanceRef}|${handle.leaseRef}".also { value ->
            check(value.toByteArray(Charsets.UTF_8).size <= MAX_OWNER_VALUE_BYTES) {
                "Presence lease owner value exceeded its frozen byte bound"
            }
        }

    private fun requireLeaseRef(value: String) {
        require(value.matches(LEASE_REF_PATTERN)) { "leaseRef must be opaque and bounded" }
    }

    private companion object {
        const val MAX_OWNER_VALUE_BYTES = 192
        val LEASE_REF_PATTERN = Regex("lease_[A-Za-z0-9_-]{32}")
    }
}
