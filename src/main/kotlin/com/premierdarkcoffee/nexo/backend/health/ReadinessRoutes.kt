package com.premierdarkcoffee.nexo.backend.health

import com.premierdarkcoffee.nexo.infrastructure.config.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun ConnectLabConfig.isConnectZeroReady(): Boolean =
    serviceName == "nexo-connect-lab" &&
        httpPort == 8282 &&
        composeProject == "nexo-connect-lab" &&
        databaseName == "nexo_connect_lab" &&
        redisNamespace == "nexo-connect-lab" &&
        mediaBucket == "nexo-connect-lab-media" &&
        identityMode == ConnectLabIdentityMode.SYNTHETIC &&
        !nexoIntegrationEnabled &&
        !callsEnabled &&
        !e2eeClaim &&
        !nexoDbDirectAccess

fun Route.readinessRoutes(application: Application) {
    get("/health/ready") {
        val ready =
            runCatching { application.connectLabConfig }
                .getOrNull()
                ?.isConnectZeroReady() == true

        call.respondText(
            text = if (ready) "READY" else "NOT_READY",
            contentType = ContentType.Text.Plain,
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}
