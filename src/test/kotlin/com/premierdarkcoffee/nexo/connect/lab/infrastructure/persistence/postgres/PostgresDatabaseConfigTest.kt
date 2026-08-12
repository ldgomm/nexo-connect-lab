package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresDatabaseConfigTest {
    @Test
    fun `loads the isolated PostgreSQL pool contract without exposing its password`() {
        val secret = "not-for-logs"
        val config =
            PostgresDatabaseConfig.fromEnvironment(
                mapOf(
                    "CONNECT_LAB_POSTGRES_APP_JDBC_URL" to "jdbc:postgresql://postgres:5432/nexo_connect_lab",
                    "CONNECT_LAB_POSTGRES_APP_USER" to "nexo_connect_lab_app",
                    "CONNECT_LAB_POSTGRES_APP_PASSWORD" to secret,
                    "CONNECT_LAB_POSTGRES_APP_MAX_POOL_SIZE" to "12",
                ),
            )

        assertEquals(12, config.maximumPoolSize)
        assertTrue(config.toString().contains("password=<redacted>"))
        assertFalse(config.toString().contains(secret))
    }

    @Test
    fun `rejects non PostgreSQL URLs and unsafe pool sizes`() {
        assertFailsWith<IllegalArgumentException> {
            PostgresDatabaseConfig(
                jdbcUrl = "jdbc:mysql://database/nexo_connect_lab",
                user = "nexo_connect_lab",
                password = "secret",
                maximumPoolSize = 4,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            PostgresDatabaseConfig(
                jdbcUrl = "jdbc:postgresql://postgres:5432/nexo_connect_lab",
                user = "nexo_connect_lab",
                password = "secret",
                maximumPoolSize = 65,
            )
        }
    }
}
