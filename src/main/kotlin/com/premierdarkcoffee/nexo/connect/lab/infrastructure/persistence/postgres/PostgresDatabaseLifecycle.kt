package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.connectLabConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import org.flywaydb.core.Flyway

interface DatabaseReadinessProbe {
    fun isReady(): Boolean
}

internal interface ManagedDatabaseRuntime : DatabaseReadinessProbe, AutoCloseable

internal class PostgresDatabaseRuntime(
    private val dataSource: HikariDataSource,
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
        if (!dataSource.isClosed) dataSource.close()
    }
}

private val DatabaseRuntimeKey =
    AttributeKey<ManagedDatabaseRuntime>("NexoConnectLabPostgresDatabaseRuntime")

private val ConversationRepositoryKey =
    AttributeKey<ConversationRepository>("NexoConnectLabConversationRepository")

private val DurableTextRepositoryKey =
    AttributeKey<DurableTextRepository>("NexoConnectLabDurableTextRepository")

internal fun Application.installManagedDatabaseRuntime(
    runtime: ManagedDatabaseRuntime,
    conversationRepository: ConversationRepository? = null,
    durableTextRepository: DurableTextRepository? = null,
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
    monitor.subscribe(ApplicationStopped) {
        runtime.close()
        log.info("CONNECT_DATABASE_POOL=CLOSED")
    }
}

fun Application.databaseReadinessProbeOrNull(): DatabaseReadinessProbe? =
    attributes.getOrNull(DatabaseRuntimeKey)

fun Application.conversationRepositoryOrNull(): ConversationRepository? =
    attributes.getOrNull(ConversationRepositoryKey)

fun Application.durableTextRepositoryOrNull(): DurableTextRepository? =
    attributes.getOrNull(DurableTextRepositoryKey)

fun Application.configurePostgresDatabaseLifecycle() {
    if (!connectLabConfig.databaseLifecycleEnabled) return

    val dataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
    try {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .validate()

        val runtime = PostgresDatabaseRuntime(dataSource)
        check(runtime.isReady()) { "PostgreSQL readiness probe failed during application startup" }
        installManagedDatabaseRuntime(
            runtime = runtime,
            conversationRepository = PostgresConversationRepository(dataSource),
            durableTextRepository = PostgresDurableTextRepository(dataSource),
        )
        log.info("CONNECT_DATABASE_POOL=READY")
    } catch (failure: Throwable) {
        dataSource.close()
        throw failure
    }
}
