package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptCursorRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxRepository
import com.premierdarkcoffee.nexo.connect.lab.application.push.InvalidPushRegistrationRetirer
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolver
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.connectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.ProtectedPushTokenCodec
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey
import org.flywaydb.core.Flyway

interface DatabaseReadinessProbe {
    fun isReady(): Boolean
}

internal interface ManagedDatabaseRuntime :
    DatabaseReadinessProbe,
    AutoCloseable

internal class PostgresDatabaseRuntime(
    private val dataSource: HikariDataSource,
    private val managedCloseables: List<AutoCloseable> = emptyList(),
) : ManagedDatabaseRuntime {
    override fun isReady(): Boolean {
        if (dataSource.isClosed) return false

        return runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.queryTimeout = 2
                    statement.executeQuery().use { result ->
                        result.next() && result.getInt(1) == 1 && !result.next()
                    }
                }
            }
        }.getOrDefault(false)
    }

    override fun close() {
        managedCloseables.asReversed().forEach { closeable -> runCatching(closeable::close) }
        if (!dataSource.isClosed) dataSource.close()
    }
}

private val DatabaseRuntimeKey =
    AttributeKey<ManagedDatabaseRuntime>("NexoConnectLabPostgresDatabaseRuntime")

private val ConversationRepositoryKey =
    AttributeKey<ConversationRepository>("NexoConnectLabConversationRepository")

private val DurableTextRepositoryKey =
    AttributeKey<DurableTextRepository>("NexoConnectLabDurableTextRepository")

private val DurableMessageHistoryRepositoryKey =
    AttributeKey<DurableMessageHistoryRepository>("NexoConnectLabDurableMessageHistoryRepository")

private val DurableReceiptCursorRepositoryKey =
    AttributeKey<DurableReceiptCursorRepository>("NexoConnectLabDurableReceiptCursorRepository")

private val NotificationOutboxRepositoryKey =
    AttributeKey<NotificationOutboxRepository>("NexoConnectLabNotificationOutboxRepository")

private val PushDeliveryTokenResolverKey =
    AttributeKey<PushDeliveryTokenResolver>("NexoConnectLabPushDeliveryTokenResolver")

private val InvalidPushRegistrationRetirerKey =
    AttributeKey<InvalidPushRegistrationRetirer>("NexoConnectLabInvalidPushRegistrationRetirer")

internal fun Application.installManagedDatabaseRuntime(
    runtime: ManagedDatabaseRuntime,
    conversationRepository: ConversationRepository? = null,
    durableTextRepository: DurableTextRepository? = null,
    durableMessageHistoryRepository: DurableMessageHistoryRepository? = null,
    durableReceiptCursorRepository: DurableReceiptCursorRepository? = null,
    notificationOutboxRepository: NotificationOutboxRepository? = null,
    pushDeliveryTokenResolver: PushDeliveryTokenResolver? = null,
    invalidPushRegistrationRetirer: InvalidPushRegistrationRetirer? = null,
) {
    check(databaseReadinessProbeOrNull() == null) { "PostgreSQL database runtime is already installed" }
    attributes.put(DatabaseRuntimeKey, runtime)
    conversationRepository?.let { repository ->
        check(conversationRepositoryOrNull() == null) { "Conversation repository is already installed" }
        attributes.put(ConversationRepositoryKey, repository)
    }
    durableTextRepository?.let { repository ->
        check(durableTextRepositoryOrNull() == null) { "Durable text repository is already installed" }
        attributes.put(DurableTextRepositoryKey, repository)
    }
    durableMessageHistoryRepository?.let { repository ->
        check(durableMessageHistoryRepositoryOrNull() == null) {
            "Durable message history repository is already installed"
        }
        attributes.put(DurableMessageHistoryRepositoryKey, repository)
    }
    durableReceiptCursorRepository?.let { repository ->
        check(durableReceiptCursorRepositoryOrNull() == null) {
            "Durable receipt cursor repository is already installed"
        }
        attributes.put(DurableReceiptCursorRepositoryKey, repository)
    }
    notificationOutboxRepository?.let { repository ->
        check(notificationOutboxRepositoryOrNull() == null) {
            "Notification outbox repository is already installed"
        }
        attributes.put(NotificationOutboxRepositoryKey, repository)
    }
    pushDeliveryTokenResolver?.let { resolver ->
        check(pushDeliveryTokenResolverOrNull() == null) {
            "Push delivery token resolver is already installed"
        }
        attributes.put(PushDeliveryTokenResolverKey, resolver)
    }
    invalidPushRegistrationRetirer?.let { retirer ->
        check(invalidPushRegistrationRetirerOrNull() == null) {
            "Invalid push registration retirer is already installed"
        }
        attributes.put(InvalidPushRegistrationRetirerKey, retirer)
    }
    monitor.subscribe(ApplicationStopped) {
        runtime.close()
        environment.log.info("CONNECT_DATABASE_POOL=CLOSED")
    }
}

fun Application.databaseReadinessProbeOrNull(): DatabaseReadinessProbe? = attributes.getOrNull(DatabaseRuntimeKey)

fun Application.conversationRepositoryOrNull(): ConversationRepository? =
    attributes.getOrNull(ConversationRepositoryKey)

fun Application.durableTextRepositoryOrNull(): DurableTextRepository? = attributes.getOrNull(DurableTextRepositoryKey)

fun Application.durableMessageHistoryRepositoryOrNull(): DurableMessageHistoryRepository? =
    attributes.getOrNull(DurableMessageHistoryRepositoryKey)

fun Application.durableReceiptCursorRepositoryOrNull(): DurableReceiptCursorRepository? =
    attributes.getOrNull(DurableReceiptCursorRepositoryKey)

fun Application.notificationOutboxRepositoryOrNull(): NotificationOutboxRepository? =
    attributes.getOrNull(NotificationOutboxRepositoryKey)

fun Application.pushDeliveryTokenResolverOrNull(): PushDeliveryTokenResolver? =
    attributes.getOrNull(PushDeliveryTokenResolverKey)

fun Application.invalidPushRegistrationRetirerOrNull(): InvalidPushRegistrationRetirer? =
    attributes.getOrNull(InvalidPushRegistrationRetirerKey)

fun Application.configurePostgresDatabaseLifecycle() {
    if (!connectLabConfig.databaseLifecycleEnabled) return

    val dataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
    var tokenCodec: ProtectedPushTokenCodec? = null
    try {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .validate()

        tokenCodec =
            if (connectLabConfig.notificationDeliveryEnabled) {
                ProtectedPushTokenCodec.fromEnvironment()
            } else {
                null
            }
        val runtime = PostgresDatabaseRuntime(dataSource, listOfNotNull(tokenCodec))
        check(runtime.isReady()) { "PostgreSQL readiness probe failed during application startup" }
        installManagedDatabaseRuntime(
            runtime = runtime,
            conversationRepository = PostgresConversationRepository(dataSource),
            durableTextRepository = PostgresDurableTextRepository(dataSource),
            durableMessageHistoryRepository = PostgresDurableMessageHistoryRepository(dataSource),
            durableReceiptCursorRepository = PostgresDurableReceiptCursorRepository(dataSource),
            notificationOutboxRepository = PostgresNotificationOutboxRepository(dataSource),
            pushDeliveryTokenResolver = tokenCodec?.let { codec ->
                PostgresPushDeliveryTokenResolver(
                    dataSource = dataSource,
                    tokenCodec = codec,
                )
            },
            invalidPushRegistrationRetirer = PostgresInvalidPushRegistrationRetirer(dataSource),
        )
        environment.log.info("CONNECT_DATABASE_POOL=READY")
    } catch (failure: Throwable) {
        tokenCodec?.close()
        dataSource.close()
        throw failure
    }
}
