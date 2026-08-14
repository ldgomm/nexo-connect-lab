package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.typing.EphemeralTypingLeaseStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey

private val RedisTypingLeaseStoreKey =
    AttributeKey<EphemeralTypingLeaseStore>("NexoConnectLabRedisTypingLeaseStore")

fun Application.redisTypingLeaseStoreOrNull(): EphemeralTypingLeaseStore? =
    attributes.getOrNull(RedisTypingLeaseStoreKey)

internal fun Application.installRedisTypingLeaseStore(store: EphemeralTypingLeaseStore) {
    check(redisTypingLeaseStoreOrNull() == null) { "Redis typing lease store is already installed" }
    attributes.put(RedisTypingLeaseStoreKey, store)
    monitor.subscribe(ApplicationStopped) {
        store.close()
        environment.log.info("CONNECT_TYPING_LEASES=CLOSED")
    }
}

fun Application.configureRedisTypingLeaseLifecycle() {
    val redisConfig = RedisEphemeralConfig.fromEnvironment()
    val typingConfig = RedisTypingLeaseConfig.fromEnvironment()
    installRedisTypingLeaseStore(RedisTypingLeaseStore(redisConfig, typingConfig))
    environment.log.info("CONNECT_TYPING_LEASES=READY")
}
