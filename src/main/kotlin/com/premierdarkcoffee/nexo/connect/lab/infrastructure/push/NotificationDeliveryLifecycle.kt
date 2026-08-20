package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push

import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryCycle
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryObserver
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationOutboxDeliveryWorker
import com.premierdarkcoffee.nexo.connect.lab.application.push.ScheduledNotificationDeliveryRuntime
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.connectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.invalidPushRegistrationRetirerOrNull
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.notificationOutboxRepositoryOrNull
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.pushDeliveryTokenResolverOrNull
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns.ApnsPrivateKeyLoader
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns.ApnsProviderTokenSource
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns.ApnsSandboxConfiguration
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns.ApnsSandboxNotificationProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey

internal interface ManagedNotificationDeliveryRuntime : AutoCloseable

private class DefaultManagedNotificationDeliveryRuntime(
    private val runtime: ScheduledNotificationDeliveryRuntime,
    private val providerTokenSource: ApnsProviderTokenSource,
) : ManagedNotificationDeliveryRuntime {
    fun start() = runtime.start()

    override fun close() {
        try {
            runtime.close()
        } finally {
            providerTokenSource.close()
        }
    }
}

private val NotificationDeliveryRuntimeKey =
    AttributeKey<ManagedNotificationDeliveryRuntime>("NexoConnectLabNotificationDeliveryRuntime")

internal fun Application.notificationDeliveryRuntimeOrNull(): ManagedNotificationDeliveryRuntime? =
    attributes.getOrNull(NotificationDeliveryRuntimeKey)

fun Application.configureNotificationDeliveryLifecycle() {
    if (!connectLabConfig.notificationDeliveryEnabled) return
    check(notificationDeliveryRuntimeOrNull() == null) { "Notification delivery runtime is already installed" }

    val repository = checkNotNull(notificationOutboxRepositoryOrNull()) {
        "Notification delivery requires the PostgreSQL outbox repository"
    }
    val tokenResolver = checkNotNull(pushDeliveryTokenResolverOrNull()) {
        "Notification delivery requires the protected token resolver"
    }
    val invalidRegistrationRetirer = checkNotNull(invalidPushRegistrationRetirerOrNull()) {
        "Notification delivery requires invalid-token retirement"
    }
    val configuration = ApnsSandboxConfiguration.fromEnvironment()
    val privateKey = ApnsPrivateKeyLoader.load(configuration.privateKeyPath)
    val providerTokenSource =
        ApnsProviderTokenSource(
            teamId = configuration.teamId,
            keyId = configuration.keyId,
            privateKey = privateKey,
        )
    try {
        val worker =
            NotificationOutboxDeliveryWorker(
                repository = repository,
                providers =
                mapOf(
                    PushProvider.APNS to
                        ApnsSandboxNotificationProvider(
                            configuration = configuration,
                            tokenResolver = tokenResolver,
                            authorizationSource = providerTokenSource,
                        ),
                ),
                leaseOwner = notificationLeaseOwner(),
                invalidRegistrationRetirer = invalidRegistrationRetirer,
                observer = NotificationDeliveryObserver { event ->
                    environment.log.info(event.toLogLine())
                },
            )
        val runtime =
            DefaultManagedNotificationDeliveryRuntime(
                runtime = ScheduledNotificationDeliveryRuntime(NotificationDeliveryCycle(worker::runOnce)),
                providerTokenSource = providerTokenSource,
            )
        attributes.put(NotificationDeliveryRuntimeKey, runtime)
        monitor.subscribe(ApplicationStopping) {
            runtime.close()
            environment.log.info("CONNECT_NOTIFICATION_DELIVERY=STOPPED")
        }
        runtime.start()
        environment.log.info("CONNECT_NOTIFICATION_DELIVERY=READY")
    } catch (failure: Throwable) {
        providerTokenSource.close()
        throw failure
    }
}

private fun notificationLeaseOwner(environment: Map<String, String> = System.getenv()): String {
    val instanceRef = environment["CONNECT_LAB_INSTANCE_REF"]?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing required environment variable: CONNECT_LAB_INSTANCE_REF")
    return "notification-$instanceRef"
}
