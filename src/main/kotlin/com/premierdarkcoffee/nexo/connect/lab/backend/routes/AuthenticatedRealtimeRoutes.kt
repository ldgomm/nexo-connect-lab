package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.REALTIME_AUTH_PROVIDER
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.authenticatedRealtimeRuntime
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorizeConversationSubscriptionRequest
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.AuthenticatedRealtimeSubject
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameValidation
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocolError
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.validateEnvelope
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

fun Route.authenticatedRealtimeRoutes(application: Application) {
    val runtime = application.authenticatedRealtimeRuntime()

    authenticate(REALTIME_AUTH_PROVIDER) {
        webSocket("/v1/realtime") {
            val authenticated = call.principal<AuthenticatedConnectPrincipal>()
            if (authenticated == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "AUTHENTICATION_REQUIRED"))
                return@webSocket
            }

            sendServerFrame(
                runtime,
                ServerRealtimeFrame(
                    type = ServerRealtimeFrameType.AUTH_OK,
                    eventId = runtime.eventIdFactory(),
                    serverTimestamp = runtime.clock.instant().toString(),
                    subject =
                        AuthenticatedRealtimeSubject(
                            subjectRef = authenticated.connectPrincipal.subjectRef,
                            actorType = authenticated.connectPrincipal.actorType.name,
                        ),
                ),
            )

            val subscribedConversationRefs = linkedSetOf<String>()
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        if (text.toByteArray(Charsets.UTF_8).size > RealtimeProtocol.MAX_TEXT_FRAME_BYTES) {
                            closeWithError(runtime, "FRAME_TOO_LARGE", CloseReason.Codes.TOO_BIG)
                            break
                        }

                        val clientFrame =
                            try {
                                runtime.json.decodeFromString<ClientRealtimeFrame>(text)
                            } catch (_: SerializationException) {
                                closeWithError(runtime, "INVALID_JSON_FRAME", CloseReason.Codes.NOT_CONSISTENT)
                                break
                            } catch (_: IllegalArgumentException) {
                                closeWithError(runtime, "INVALID_JSON_FRAME", CloseReason.Codes.NOT_CONSISTENT)
                                break
                            }

                        when (val validation = clientFrame.validateEnvelope()) {
                            ClientRealtimeFrameValidation.Valid ->
                                handleClientFrame(
                                    runtime = runtime,
                                    authenticated = authenticated,
                                    subscribedConversationRefs = subscribedConversationRefs,
                                    frame = clientFrame,
                                )
                            is ClientRealtimeFrameValidation.Invalid -> {
                                val closeCode =
                                    if (validation.code == "INCOMPATIBLE_PROTOCOL_MAJOR") {
                                        CloseReason.Codes.PROTOCOL_ERROR
                                    } else {
                                        CloseReason.Codes.VIOLATED_POLICY
                                    }
                                closeWithError(runtime, validation.code, closeCode, clientFrame.correlationId)
                                break
                            }
                        }
                    }

                    is Frame.Binary -> {
                        closeWithError(runtime, "BINARY_FRAMES_UNSUPPORTED", CloseReason.Codes.CANNOT_ACCEPT)
                        break
                    }

                    else -> Unit
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleClientFrame(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    subscribedConversationRefs: MutableSet<String>,
    frame: ClientRealtimeFrame,
) {
    when (frame.type) {
        ClientRealtimeFrameType.PING ->
            sendServerFrame(
                runtime,
                ServerRealtimeFrame(
                    type = ServerRealtimeFrameType.PONG,
                    eventId = runtime.eventIdFactory(),
                    serverTimestamp = runtime.clock.instant().toString(),
                    correlationId = frame.correlationId ?: frame.eventId,
                ),
            )

        ClientRealtimeFrameType.AUTH ->
            sendProtocolError(runtime, "ALREADY_AUTHENTICATED", false, frame.correlationId)

        ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION ->
            subscribeConversation(
                runtime = runtime,
                authenticated = authenticated,
                subscribedConversationRefs = subscribedConversationRefs,
                frame = frame,
            )

        else ->
            sendProtocolError(runtime, "UNSUPPORTED_FRAME_TYPE", false, frame.correlationId)
    }
}

private suspend fun DefaultWebSocketServerSession.subscribeConversation(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    subscribedConversationRefs: MutableSet<String>,
    frame: ClientRealtimeFrame,
) {
    val conversationRef = checkNotNull(frame.conversationRef)
    val authorization =
        try {
            withContext(Dispatchers.IO) {
                runtime.conversationSubscriptionAuthorizer.authorize(
                    AuthorizeConversationSubscriptionRequest(
                        principal = authenticated.connectPrincipal,
                        conversationRef = conversationRef,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConversationSubscriptionAuthorizationResult.Unavailable
        }

    when (authorization) {
        is ConversationSubscriptionAuthorizationResult.Authorized -> {
            if (
                conversationRef !in subscribedConversationRefs &&
                subscribedConversationRefs.size >= runtime.maxConversationSubscriptions
            ) {
                sendProtocolError(runtime, "SUBSCRIPTION_LIMIT_REACHED", false, frame.correlationId)
                return
            }

            subscribedConversationRefs += conversationRef
            sendServerFrame(
                runtime,
                ServerRealtimeFrame(
                    type = ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                    eventId = runtime.eventIdFactory(),
                    serverTimestamp = runtime.clock.instant().toString(),
                    correlationId = frame.correlationId ?: frame.eventId,
                    conversationRef = authorization.conversationRef,
                    lastMessageSequence = authorization.lastMessageSequence,
                ),
            )
        }

        ConversationSubscriptionAuthorizationResult.NotFoundOrDenied ->
            sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, frame.correlationId)

        ConversationSubscriptionAuthorizationResult.Unavailable ->
            sendProtocolError(runtime, "SUBSCRIPTION_SERVICE_UNAVAILABLE", true, frame.correlationId)
    }
}

private suspend fun DefaultWebSocketServerSession.closeWithError(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    code: String,
    closeCode: CloseReason.Codes,
    correlationId: String? = null,
) {
    sendProtocolError(runtime, code, false, correlationId)
    close(CloseReason(closeCode, code))
}

private suspend fun DefaultWebSocketServerSession.sendProtocolError(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    code: String,
    retryable: Boolean,
    correlationId: String? = null,
) {
    sendServerFrame(
        runtime,
        ServerRealtimeFrame(
            type = ServerRealtimeFrameType.ERROR,
            eventId = runtime.eventIdFactory(),
            serverTimestamp = runtime.clock.instant().toString(),
            correlationId = correlationId,
            error = RealtimeProtocolError(code = code, retryable = retryable),
        ),
    )
}

private suspend fun DefaultWebSocketServerSession.sendServerFrame(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    frame: ServerRealtimeFrame,
) {
    send(Frame.Text(runtime.json.encodeToString(frame)))
}
