package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class LivenessRoutesTest {
    @Test
    fun `reports process liveness without checking dependencies`() = testApplication {
        application { routing { livenessRoutes() } }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("LIVE", response.bodyAsText())
    }
}
