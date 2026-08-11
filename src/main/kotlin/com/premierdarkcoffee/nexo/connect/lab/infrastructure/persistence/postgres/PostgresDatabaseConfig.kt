package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

class PostgresDatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    internal val password: String,
    val maximumPoolSize: Int,
) {
    init {
        require(jdbcUrl.matches(Regex("^jdbc:postgresql://[^\\s\\u0000]+$"))) {
            "CONNECT_LAB_POSTGRES_JDBC_URL must be a PostgreSQL JDBC URL"
        }
        require(user.isNotBlank() && '\u0000' !in user) {
            "CONNECT_LAB_POSTGRES_USER must be non-blank and contain no NUL"
        }
        require(password.isNotBlank() && '\u0000' !in password) {
            "CONNECT_LAB_POSTGRES_PASSWORD must be non-blank and contain no NUL"
        }
        require(maximumPoolSize in 1..64) {
            "CONNECT_LAB_POSTGRES_MAX_POOL_SIZE must be between 1 and 64"
        }
    }

    override fun toString(): String =
        "PostgresDatabaseConfig(jdbcUrl=<configured>, user=$user, password=<redacted>, maximumPoolSize=$maximumPoolSize)"

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PostgresDatabaseConfig {
            fun required(name: String): String =
                environment[name]?.takeIf(String::isNotBlank)
                    ?: error("Missing required environment variable: $name")

            val maximumPoolSize =
                required("CONNECT_LAB_POSTGRES_MAX_POOL_SIZE").toIntOrNull()
                    ?: error("CONNECT_LAB_POSTGRES_MAX_POOL_SIZE must be an integer")

            return PostgresDatabaseConfig(
                jdbcUrl = required("CONNECT_LAB_POSTGRES_JDBC_URL"),
                user = required("CONNECT_LAB_POSTGRES_USER"),
                password = required("CONNECT_LAB_POSTGRES_PASSWORD"),
                maximumPoolSize = maximumPoolSize,
            )
        }
    }
}

object PostgresDataSourceFactory {
    fun create(config: PostgresDatabaseConfig): HikariDataSource {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                maximumPoolSize = config.maximumPoolSize
                minimumIdle = 0
                connectionTimeout = 5_000
                validationTimeout = 3_000
                initializationFailTimeout = 5_000
                isAutoCommit = true
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "nexo-connect-lab-postgres"
            }

        return HikariDataSource(hikariConfig)
    }
}
