package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration

internal class LettuceRedisEphemeralConnectionProvider(private val config: RedisEphemeralConfig) :
    RedisEphemeralConnectionProvider {
    private val client: RedisClient =
        RedisClient.create(
            RedisURI.Builder.redis(config.host, config.port)
                .withAuthentication(config.user, config.password.toCharArray())
                .withDatabase(config.database)
                .withTimeout(Duration.ofMillis(config.commandTimeoutMillis))
                .withClientName("nexo-connect-lab")
                .build(),
        ).apply {
            options =
                ClientOptions.builder()
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

    override fun connect(): RedisEphemeralConnection = LettuceRedisEphemeralConnection(client.connect())

    override fun close() {
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2))
    }
}

private class LettuceRedisEphemeralConnection(private val connection: StatefulRedisConnection<String, String>) :
    RedisEphemeralConnection {
    override fun ping(): Boolean = connection.sync().ping() == "PONG"

    override fun close() {
        connection.close()
    }
}

private val RedisEphemeralRuntimeKey =
    AttributeKey<ManagedRedisEphemeralRuntime>("NexoConnectLabRedisEphemeralRuntime")

internal fun Application.installManagedRedisEphemeralRuntime(runtime: ManagedRedisEphemeralRuntime) {
    check(redisEphemeralReadinessProbeOrNull() == null) { "Redis ephemeral runtime is already installed" }
    attributes.put(RedisEphemeralRuntimeKey, runtime)
    monitor.subscribe(ApplicationStopped) {
        runtime.close()
        environment.log.info("CONNECT_REDIS_EPHEMERAL=CLOSED")
    }
}

fun Application.redisEphemeralReadinessProbeOrNull(): RedisEphemeralReadinessProbe? =
    attributes.getOrNull(RedisEphemeralRuntimeKey)

fun Application.configureRedisEphemeralLifecycle() {
    val config = RedisEphemeralConfig.fromEnvironment()
    val runtime = RedisEphemeralRuntime(config, LettuceRedisEphemeralConnectionProvider(config))
    installManagedRedisEphemeralRuntime(runtime)

    val initialState = runtime.readiness()
    environment.log.info("CONNECT_REDIS_EPHEMERAL={}", if (initialState.available) "READY" else "DEGRADED")
}
