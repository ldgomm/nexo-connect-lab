package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.configureTypedConfiguration
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.ManagedDatabaseRuntime
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.installManagedDatabaseRuntime
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlin.test.*

class ReadinessRoutesTest {
    @Test
    fun `reports ready only when typed configuration and PostgreSQL are ready`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            installManagedDatabaseRuntime(FakeDatabaseRuntime(ready = true))
            configureRouting()
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("READY", response.bodyAsText())
    }

    @Test
    fun `reports unavailable when database runtime is absent`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            configureRouting()
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("NOT_READY", response.bodyAsText())
    }

    @Test
    fun `reports unavailable when database probe fails`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            installManagedDatabaseRuntime(FakeDatabaseRuntime(ready = false))
            configureRouting()
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("NOT_READY", response.bodyAsText())
    }

    @Test
    fun `keeps liveness independent from database readiness`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { configureRouting() }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("LIVE", response.bodyAsText())
    }

    @Test
    fun `closes the managed database runtime when Ktor stops`() {
        val runtime = FakeDatabaseRuntime(ready = true)

        testApplication {
            environment { config = validConfig() }
            application {
                configureTypedConfiguration()
                installManagedDatabaseRuntime(runtime)
                configureRouting()
            }
            startApplication()
        }

        assertTrue(runtime.closed)
    }

    private class FakeDatabaseRuntime(
        private val ready: Boolean,
    ) : ManagedDatabaseRuntime {
        var closed = false

        override fun isReady(): Boolean = ready && !closed

        override fun close() {
            closed = true
        }
    }

    private fun validConfig(): MapApplicationConfig =
        MapApplicationConfig(
            "nexoConnectLab.serviceName" to "nexo-connect-lab",
            "nexoConnectLab.environment" to "test",
            "nexoConnectLab.httpPort" to "8282",
            "nexoConnectLab.composeProject" to "nexo-connect-lab",
            "nexoConnectLab.databaseName" to "nexo_connect_lab",
            "nexoConnectLab.redisNamespace" to "nexo-connect-lab",
            "nexoConnectLab.mediaBucket" to "nexo-connect-lab-media",
            "nexoConnectLab.identityMode" to "synthetic",
            "nexoConnectLab.nexoIntegrationEnabled" to "false",
            "nexoConnectLab.callsEnabled" to "false",
            "nexoConnectLab.e2eeClaim" to "false",
            "nexoConnectLab.nexoDbDirectAccess" to "false",
            "nexoConnectLab.databaseLifecycleEnabled" to "true",
        )
}
