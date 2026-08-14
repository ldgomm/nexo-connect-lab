package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabIdentityMode
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.connectLabConfig
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres.databaseReadinessProbeOrNull
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisEphemeralReadiness
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.redisEphemeralReadinessProbeOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REDIS_READINESS_HEADER = "X-Nexo-Connect-Redis-Readiness"
private const val REDIS_CIRCUIT_HEADER = "X-Nexo-Connect-Redis-Circuit"

private fun ConnectLabConfig.isConnectZeroReady(): Boolean = serviceName == "nexo-connect-lab" &&
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
        val redisReadiness = application.redisReadinessOrNull()

        call.response.headers.append(
            REDIS_READINESS_HEADER,
            when {
                redisReadiness == null -> "NOT_CONFIGURED"
                redisReadiness.available -> "READY"
                else -> "DEGRADED"
            },
        )
        redisReadiness?.let {
            call.response.headers.append(REDIS_CIRCUIT_HEADER, it.circuitState.name)
        }

        call.respondText(
            text = if (ready) "READY" else "NOT_READY",
            contentType = ContentType.Text.Plain,
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }

    get("/health/ready/ephemeral-redis") {
        val redisReadiness = application.redisReadinessOrNull()
        val ready = redisReadiness?.available == true

        call.response.headers.append(
            REDIS_CIRCUIT_HEADER,
            redisReadiness?.circuitState?.name ?: "NOT_CONFIGURED",
        )
        call.respondText(
            text =
            when {
                redisReadiness == null -> "REDIS_NOT_CONFIGURED"
                ready -> "REDIS_READY"
                else -> "REDIS_DEGRADED"
            },
            contentType = ContentType.Text.Plain,
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}

private suspend fun Application.redisReadinessOrNull(): RedisEphemeralReadiness? = withContext(Dispatchers.IO) {
    runCatching { redisEphemeralReadinessProbeOrNull()?.readiness() }.getOrNull()
}
