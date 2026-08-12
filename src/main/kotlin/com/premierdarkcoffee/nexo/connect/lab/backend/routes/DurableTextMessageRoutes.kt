package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.SendDurableTextMessageRequest
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.REALTIME_AUTH_PROVIDER
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.authenticatedRealtimeRuntime
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class DurableTextMessageHttpRequest(
    val clientMessageRef: String,
    val idempotencyKey: String,
    val body: String,
)

@Serializable
private data class DurableTextMessageHttpResponse(
    val status: String,
    val serverMessageRef: String,
    val sequence: Long,
)

@Serializable
private data class DurableTextMessageHttpError(
    val code: String,
    val retryable: Boolean,
)

fun Route.durableTextMessageRoutes(application: Application) {
    val runtime = application.authenticatedRealtimeRuntime()

    authenticate(REALTIME_AUTH_PROVIDER) {
        post("/v1/conversations/{conversationRef}/messages") {
            val authenticated = call.principal<AuthenticatedConnectPrincipal>()
            if (authenticated == null) {
                call.respondMessageError(HttpStatusCode.Unauthorized, "AUTHENTICATION_REQUIRED", false)
                return@post
            }
            val coordinator = runtime.durableTextMessageCoordinator
            if (coordinator == null) {
                call.respondMessageError(
                    HttpStatusCode.ServiceUnavailable,
                    "MESSAGE_SERVICE_UNAVAILABLE",
                    true,
                )
                return@post
            }
            val conversationRef = call.parameters["conversationRef"]
            if (!conversationRef.isValidMessageIdentifier()) {
                call.respondMessageError(HttpStatusCode.BadRequest, "INVALID_MESSAGE_COMMAND", false)
                return@post
            }
            if ((call.request.contentLength() ?: 0L) > MAX_MESSAGE_COMMAND_BYTES) {
                call.respondMessageError(HttpStatusCode.PayloadTooLarge, "MESSAGE_COMMAND_TOO_LARGE", false)
                return@post
            }

            val rawRequest =
                try {
                    call.receiveText()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.respondMessageError(HttpStatusCode.BadRequest, "INVALID_MESSAGE_COMMAND", false)
                    return@post
                }
            if (rawRequest.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_COMMAND_BYTES) {
                call.respondMessageError(HttpStatusCode.PayloadTooLarge, "MESSAGE_COMMAND_TOO_LARGE", false)
                return@post
            }
            val request =
                try {
                    runtime.json.decodeFromString<DurableTextMessageHttpRequest>(rawRequest)
                } catch (_: SerializationException) {
                    call.respondMessageError(HttpStatusCode.BadRequest, "INVALID_MESSAGE_COMMAND", false)
                    return@post
                } catch (_: IllegalArgumentException) {
                    call.respondMessageError(HttpStatusCode.BadRequest, "INVALID_MESSAGE_COMMAND", false)
                    return@post
                }
            if (
                !request.clientMessageRef.isValidMessageIdentifier() ||
                !request.idempotencyKey.isValidMessageIdentifier() ||
                !request.body.isValidTextMessageBody()
            ) {
                call.respondMessageError(HttpStatusCode.BadRequest, "INVALID_MESSAGE_COMMAND", false)
                return@post
            }

            val result =
                try {
                    coordinator.send(
                        SendDurableTextMessageRequest(
                            principal = authenticated.connectPrincipal,
                            conversationRef = checkNotNull(conversationRef),
                            clientMessageRef = request.clientMessageRef,
                            idempotencyKey = request.idempotencyKey,
                            body = request.body,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.respondMessageError(
                        HttpStatusCode.ServiceUnavailable,
                        "MESSAGE_SERVICE_UNAVAILABLE",
                        true,
                    )
                    return@post
                }

            when (result) {
                is DurableTextRepositoryResult.Committed ->
                    call.respondMessageSuccess(
                        statusCode = HttpStatusCode.Created,
                        status = "COMMITTED",
                        serverMessageRef = result.serverMessageRef,
                        sequence = result.sequence.value,
                    )

                is DurableTextRepositoryResult.ReplayExisting ->
                    call.respondMessageSuccess(
                        statusCode = HttpStatusCode.OK,
                        status = "REPLAY_EXISTING",
                        serverMessageRef = result.serverMessageRef,
                        sequence = result.sequence.value,
                    )

                is DurableTextRepositoryResult.Conflict ->
                    call.respondMessageError(
                        HttpStatusCode.Conflict,
                        "MESSAGE_IDEMPOTENCY_CONFLICT",
                        false,
                    )

                is DurableTextRepositoryResult.Denied ->
                    call.respondMessageError(
                        HttpStatusCode.NotFound,
                        "MESSAGE_NOT_FOUND_OR_DENIED",
                        false,
                    )
            }
        }
    }
}

private fun String?.isValidMessageIdentifier(): Boolean =
    this != null &&
        isNotBlank() &&
        '\u0000' !in this &&
        toByteArray(Charsets.UTF_8).size <= RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES

private fun String.isValidTextMessageBody(): Boolean =
    isNotBlank() &&
        '\u0000' !in this &&
        toByteArray(Charsets.UTF_8).size <= TextMessageBody.MAX_UTF8_BYTES

private suspend fun ApplicationCall.respondMessageSuccess(
    statusCode: HttpStatusCode,
    status: String,
    serverMessageRef: String,
    sequence: Long,
) {
    val runtime = application.authenticatedRealtimeRuntime()
    respondText(
        text =
            runtime.json.encodeToString(
                DurableTextMessageHttpResponse(
                    status = status,
                    serverMessageRef = serverMessageRef,
                    sequence = sequence,
                ),
            ),
        contentType = ContentType.Application.Json,
        status = statusCode,
    )
}

private suspend fun ApplicationCall.respondMessageError(
    statusCode: HttpStatusCode,
    code: String,
    retryable: Boolean,
) {
    val runtime = application.authenticatedRealtimeRuntime()
    respondText(
        text = runtime.json.encodeToString(DurableTextMessageHttpError(code, retryable)),
        contentType = ContentType.Application.Json,
        status = statusCode,
    )
}

private const val MAX_MESSAGE_COMMAND_BYTES = 20_480
