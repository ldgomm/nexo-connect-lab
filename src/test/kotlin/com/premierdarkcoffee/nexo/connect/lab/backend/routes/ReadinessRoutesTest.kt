package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.configureTypedConfiguration
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.ManagedDatabaseRuntime
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.installManagedDatabaseRuntime
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.ManagedRedisEphemeralRuntime
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisCircuitState
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisEphemeralReadiness
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.installManagedRedisEphemeralRuntime
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessRoutesTest {
    @Test
    fun `reports ready only when typed configuration and PostgreSQL are ready`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            installManagedDatabaseRuntime(FakeDatabaseRuntime(ready = true))
            installManagedRedisEphemeralRuntime(FakeRedisRuntime(ready = true))
            configureRouting()
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("READY", response.bodyAsText())
        assertEquals("READY", response.headers["X-Nexo-Connect-Redis-Readiness"])
        assertEquals("CLOSED", response.headers["X-Nexo-Connect-Redis-Circuit"])
    }

    @Test
    fun `keeps durable readiness available when ephemeral Redis is degraded`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            installManagedDatabaseRuntime(FakeDatabaseRuntime(ready = true))
            installManagedRedisEphemeralRuntime(FakeRedisRuntime(ready = false))
            configureRouting()
        }

        val durableResponse = client.get("/health/ready")
        val redisResponse = client.get("/health/ready/ephemeral-redis")

        assertEquals(HttpStatusCode.OK, durableResponse.status)
        assertEquals("READY", durableResponse.bodyAsText())
        assertEquals("DEGRADED", durableResponse.headers["X-Nexo-Connect-Redis-Readiness"])
        assertEquals(HttpStatusCode.ServiceUnavailable, redisResponse.status)
        assertEquals("REDIS_DEGRADED", redisResponse.bodyAsText())
        assertEquals("OPEN", redisResponse.headers["X-Nexo-Connect-Redis-Circuit"])
    }

    @Test
    fun `reports explicit Redis readiness when the ephemeral boundary is available`() = testApplication {
        environment { config = validConfig() }
        application {
            configureTypedConfiguration()
            installManagedDatabaseRuntime(FakeDatabaseRuntime(ready = true))
            installManagedRedisEphemeralRuntime(FakeRedisRuntime(ready = true))
            configureRouting()
        }

        val response = client.get("/health/ready/ephemeral-redis")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("REDIS_READY", response.bodyAsText())
        assertEquals("CLOSED", response.headers["X-Nexo-Connect-Redis-Circuit"])
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

    @Test
    fun `closes the managed Redis runtime when Ktor stops`() {
        val runtime = FakeRedisRuntime(ready = true)

        testApplication {
            application {
                installManagedRedisEphemeralRuntime(runtime)
                configureRouting()
            }
            startApplication()
        }

        assertTrue(runtime.closed)
    }

    private class FakeDatabaseRuntime(private val ready: Boolean) : ManagedDatabaseRuntime {
        var closed = false

        override fun isReady(): Boolean = ready && !closed

        override fun close() {
            closed = true
        }
    }

    private class FakeRedisRuntime(private val ready: Boolean) : ManagedRedisEphemeralRuntime {
        var closed = false

        override fun readiness(): RedisEphemeralReadiness = RedisEphemeralReadiness(
            available = ready && !closed,
            circuitState =
            when {
                closed -> RedisCircuitState.STOPPED
                ready -> RedisCircuitState.CLOSED
                else -> RedisCircuitState.OPEN
            },
            consecutiveFailures = if (ready) 0 else 1,
            retryAfterMillis = if (ready) 0 else 100,
            keyNamespace = "nexo-connect-lab",
            channelNamespace = "nexo.connect.realtime.v1",
        )

        override fun close() {
            closed = true
        }
    }

    private fun validConfig(): MapApplicationConfig = MapApplicationConfig(
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
