package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlin.test.*

class ReadinessRoutesTest {
    @Test
    fun `reports ready after isolated typed configuration loads`() = testApplication {
        configure()

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("READY", response.bodyAsText())
    }

    @Test
    fun `reports unavailable when typed configuration is not installed`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { configureRouting() }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("NOT_READY", response.bodyAsText())
    }

    @Test
    fun `keeps liveness independent from readiness`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { configureRouting() }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("LIVE", response.bodyAsText())
    }
}
