package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabIdentityMode
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.connectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.databaseReadinessProbeOrNull
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
        !nexoDbDirectAccess &&
        databaseLifecycleEnabled

fun Route.readinessRoutes(application: Application) {
    get("/health/ready") {
        val configurationReady =
            runCatching { application.connectLabConfig }
                .getOrNull()
                ?.isConnectZeroReady() == true
        val databaseReady =
            runCatching { application.databaseReadinessProbeOrNull()?.isReady() }
                .getOrDefault(false) == true
        val ready = configurationReady && databaseReady

        call.respondText(
            text = if (ready) "READY" else "NOT_READY",
            contentType = ContentType.Text.Plain,
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}
