package com.premierdarkcoffee.nexo.connect.lab.backend.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerificationResult
import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerifier
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity.SyntheticRealtimeIdentityRegistry
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.bearer
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

const val REALTIME_AUTH_PROVIDER = "connect-realtime-bearer"

data class AuthenticatedConnectPrincipal(
    val connectPrincipal: ConnectPrincipal,
) : Principal

internal class AuthenticatedRealtimeRuntime(
    val json: Json,
    val clock: Clock,
    val eventIdFactory: () -> String,
)

private val RealtimeRuntimeKey =
    AttributeKey<AuthenticatedRealtimeRuntime>("NexoConnectLabAuthenticatedRealtimeRuntime")

internal fun Application.authenticatedRealtimeRuntime(): AuthenticatedRealtimeRuntime =
    attributes[RealtimeRuntimeKey]

internal fun Application.authenticatedRealtimeRuntimeOrNull(): AuthenticatedRealtimeRuntime? =
    attributes.getOrNull(RealtimeRuntimeKey)

fun Application.configureAuthenticatedRealtimeTransport() {
    installAuthenticatedRealtimeTransport(SyntheticRealtimeIdentityRegistry.fromEnvironment())
}

internal fun Application.installAuthenticatedRealtimeTransport(
    identityVerifier: IdentityVerifier,
    clock: Clock = Clock.systemUTC(),
    eventIdFactory: () -> String = { "event-${UUID.randomUUID()}" },
) {
    val protocolJson =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
            isLenient = false
        }

    attributes.put(
        RealtimeRuntimeKey,
        AuthenticatedRealtimeRuntime(protocolJson, clock, eventIdFactory),
    )

    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 15.seconds
        maxFrameSize = RealtimeProtocol.MAX_TEXT_FRAME_BYTES
        masking = false
    }

    install(Authentication) {
        bearer(REALTIME_AUTH_PROVIDER) {
            realm = "nexo-connect-lab-realtime"
            authenticate { credential ->
                when (val result = identityVerifier.verify(credential.token)) {
                    is IdentityVerificationResult.Authenticated ->
                        AuthenticatedConnectPrincipal(result.principal)

                    IdentityVerificationResult.Denied -> null
                }
            }
        }
    }
}
