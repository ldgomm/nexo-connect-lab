package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

import com.premierdarkcoffee.nexo.connect.lab.application.presence.EphemeralPresenceLeaseStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey

private val RedisPresenceLeaseStoreKey =
    AttributeKey<EphemeralPresenceLeaseStore>("NexoConnectLabRedisPresenceLeaseStore")

fun Application.redisPresenceLeaseStoreOrNull(): EphemeralPresenceLeaseStore? =
    attributes.getOrNull(RedisPresenceLeaseStoreKey)

internal fun Application.installRedisPresenceLeaseStore(store: EphemeralPresenceLeaseStore) {
    check(redisPresenceLeaseStoreOrNull() == null) { "Redis presence lease store is already installed" }
    attributes.put(RedisPresenceLeaseStoreKey, store)
    monitor.subscribe(ApplicationStopped) {
        store.close()
        environment.log.info("CONNECT_PRESENCE_LEASES=CLOSED")
    }
}

fun Application.configureRedisPresenceLeaseLifecycle() {
    val redisConfig = RedisEphemeralConfig.fromEnvironment()
    val leaseConfig = RedisPresenceLeaseConfig.fromEnvironment()
    installRedisPresenceLeaseStore(RedisPresenceLeaseStore(redisConfig, leaseConfig))
    environment.log.info("CONNECT_PRESENCE_LEASES=READY")
}
