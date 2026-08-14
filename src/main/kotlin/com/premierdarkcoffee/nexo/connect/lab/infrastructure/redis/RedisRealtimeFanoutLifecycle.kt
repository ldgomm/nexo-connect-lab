package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutDelivery
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutPublishResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeFanoutTransport
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeFanoutChannel
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.MAX_REALTIME_FANOUT_ENVELOPE_BYTES
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class RedisRealtimeFanoutConfig(
    val instanceRef: String,
    val messageCreatedChannel: String,
    val receiptAdvancedChannel: String,
    val typingStateChangedChannel: String = EXPECTED_TYPING_CHANNEL,
) {
    init {
        require(
            instanceRef.matches(INSTANCE_REF_PATTERN) &&
                instanceRef.toByteArray(Charsets.UTF_8).size <= MAX_INSTANCE_REF_BYTES,
        ) { "CONNECT_LAB_INSTANCE_REF must be a bounded opaque instance reference" }
        require(messageCreatedChannel == EXPECTED_MESSAGE_CHANNEL) {
            "The message-created channel must preserve the frozen v1 namespace"
        }
        require(receiptAdvancedChannel == EXPECTED_RECEIPT_CHANNEL) {
            "The receipt-advanced channel must preserve the frozen v1 namespace"
        }
        require(typingStateChangedChannel == EXPECTED_TYPING_CHANNEL) {
            "The typing-state channel must preserve the frozen v1 namespace"
        }
    }

    fun channel(channel: RealtimeFanoutChannel): String = when (channel) {
        RealtimeFanoutChannel.MESSAGE_CREATED -> messageCreatedChannel
        RealtimeFanoutChannel.RECEIPT_ADVANCED -> receiptAdvancedChannel
        RealtimeFanoutChannel.TYPING_STATE_CHANGED -> typingStateChangedChannel
    }

    fun logicalChannel(redisChannel: String): RealtimeFanoutChannel? = when (redisChannel) {
        messageCreatedChannel -> RealtimeFanoutChannel.MESSAGE_CREATED
        receiptAdvancedChannel -> RealtimeFanoutChannel.RECEIPT_ADVANCED
        typingStateChangedChannel -> RealtimeFanoutChannel.TYPING_STATE_CHANGED
        else -> null
    }

    companion object {
        const val EXPECTED_MESSAGE_CHANNEL = "nexo.connect.realtime.v1.message-created"
        const val EXPECTED_RECEIPT_CHANNEL = "nexo.connect.realtime.v1.receipt-advanced"
        const val EXPECTED_TYPING_CHANNEL = "nexo.connect.realtime.v1.typing-state"
        private const val MAX_INSTANCE_REF_BYTES = 128
        private val INSTANCE_REF_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

        fun fromEnvironment(
            redisConfig: RedisEphemeralConfig,
            environment: Map<String, String> = System.getenv(),
        ): RedisRealtimeFanoutConfig {
            val instanceRef =
                environment["CONNECT_LAB_INSTANCE_REF"]?.takeIf(String::isNotBlank)
                    ?: error("Missing required environment variable: CONNECT_LAB_INSTANCE_REF")
            return RedisRealtimeFanoutConfig(
                instanceRef = instanceRef,
                messageCreatedChannel = "${redisConfig.channelNamespace}.message-created",
                receiptAdvancedChannel = "${redisConfig.channelNamespace}.receipt-advanced",
                typingStateChangedChannel = "${redisConfig.channelNamespace}.typing-state",
            )
        }
    }
}

internal class LettuceRedisRealtimeFanoutTransport(
    private val redisConfig: RedisEphemeralConfig,
    private val fanoutConfig: RedisRealtimeFanoutConfig,
) : EphemeralRealtimeFanoutTransport {
    override val localInstanceRef: String = fanoutConfig.instanceRef

    private val client = RedisClient.create(redisUri(redisConfig)).apply { options = clientOptions(redisConfig) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbound = Channel<EphemeralRealtimeFanoutDelivery>(redisConfig.requestQueueSize)
    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val publisherLock = Any()

    @Volatile
    private var publisherConnection: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var subscriberConnection: StatefulRedisPubSubConnection<String, String>? = null

    private var consumerJob: Job? = null
    private var subscriberJob: Job? = null

    override fun start(consumer: suspend (EphemeralRealtimeFanoutDelivery) -> Unit) {
        check(!stopped.get()) { "Realtime fan-out transport is stopped" }
        check(started.compareAndSet(false, true)) { "Realtime fan-out transport is already started" }

        consumerJob =
            scope.launch {
                for (delivery in inbound) {
                    try {
                        consumer(delivery)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A malformed or unavailable durable payload is repaired through catch-up.
                    }
                }
            }
        subscriberJob = scope.launch { subscriptionLoop() }
    }

    override suspend fun publish(
        channel: RealtimeFanoutChannel,
        payload: String,
    ): EphemeralRealtimeFanoutPublishResult {
        if (stopped.get()) return EphemeralRealtimeFanoutPublishResult.Stopped
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REALTIME_FANOUT_ENVELOPE_BYTES) {
            return EphemeralRealtimeFanoutPublishResult.Rejected
        }

        return withContext(Dispatchers.IO) {
            try {
                val subscriberCount =
                    synchronized(publisherLock) {
                        val connection = publisherConnection?.takeIf { it.isOpen } ?: client.connect().also {
                            publisherConnection = it
                        }
                        connection.sync().publish(fanoutConfig.channel(channel), payload)
                    }
                EphemeralRealtimeFanoutPublishResult.Published(subscriberCount)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(publisherLock) {
                    publisherConnection?.let { runCatching { it.close() } }
                    publisherConnection = null
                }
                EphemeralRealtimeFanoutPublishResult.Unavailable
            }
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        inbound.close()
        consumerJob?.cancel()
        subscriberJob?.cancel()
        scope.cancel()
        subscriberConnection?.let { runCatching { it.close() } }
        subscriberConnection = null
        synchronized(publisherLock) {
            publisherConnection?.let { runCatching { it.close() } }
            publisherConnection = null
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2))
    }

    private suspend fun subscriptionLoop() {
        var retryDelayMillis = redisConfig.reconnectMinDelayMillis
        while (scope.isActive && !stopped.get()) {
            try {
                val connection = client.connectPubSub()
                subscriberConnection = connection
                connection.addListener(
                    object : RedisPubSubAdapter<String, String>() {
                        override fun message(channel: String, message: String) {
                            val logicalChannel = fanoutConfig.logicalChannel(channel) ?: return
                            if (message.toByteArray(Charsets.UTF_8).size <= MAX_REALTIME_FANOUT_ENVELOPE_BYTES) {
                                inbound.trySend(EphemeralRealtimeFanoutDelivery(logicalChannel, message))
                            }
                        }
                    },
                )
                connection.sync().subscribe(
                    fanoutConfig.messageCreatedChannel,
                    fanoutConfig.receiptAdvancedChannel,
                    fanoutConfig.typingStateChangedChannel,
                )
                retryDelayMillis = redisConfig.reconnectMinDelayMillis
                while (scope.isActive && !stopped.get() && connection.isOpen) {
                    delay(SUBSCRIBER_HEALTH_INTERVAL_MILLIS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Redis is optional for durable correctness; retry without stopping the application.
            } finally {
                subscriberConnection?.let { runCatching { it.close() } }
                subscriberConnection = null
            }

            if (scope.isActive && !stopped.get()) {
                delay(retryDelayMillis)
                retryDelayMillis = min(redisConfig.reconnectMaxDelayMillis, retryDelayMillis * 2)
            }
        }
    }

    private companion object {
        const val SUBSCRIBER_HEALTH_INTERVAL_MILLIS = 250L

        fun redisUri(config: RedisEphemeralConfig): RedisURI = RedisURI.Builder.redis(config.host, config.port)
            .withAuthentication(config.user, config.password.toCharArray())
            .withDatabase(config.database)
            .withTimeout(Duration.ofMillis(config.commandTimeoutMillis))
            .withClientName("nexo-connect-lab-${config.keyNamespace}")
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

private val RedisRealtimeFanoutTransportKey =
    AttributeKey<EphemeralRealtimeFanoutTransport>("NexoConnectLabRedisRealtimeFanoutTransport")

fun Application.redisRealtimeFanoutTransportOrNull(): EphemeralRealtimeFanoutTransport? =
    attributes.getOrNull(RedisRealtimeFanoutTransportKey)

internal fun Application.installRedisRealtimeFanoutTransport(transport: EphemeralRealtimeFanoutTransport) {
    check(redisRealtimeFanoutTransportOrNull() == null) { "Realtime fan-out transport is already installed" }
    attributes.put(RedisRealtimeFanoutTransportKey, transport)
    monitor.subscribe(ApplicationStopped) {
        transport.close()
        environment.log.info("CONNECT_REALTIME_FANOUT=CLOSED")
    }
}

fun Application.configureRedisRealtimeFanoutLifecycle() {
    val redisConfig = RedisEphemeralConfig.fromEnvironment()
    val fanoutConfig = RedisRealtimeFanoutConfig.fromEnvironment(redisConfig)
    installRedisRealtimeFanoutTransport(LettuceRedisRealtimeFanoutTransport(redisConfig, fanoutConfig))
    environment.log.info("CONNECT_REALTIME_FANOUT=STARTING")
}
