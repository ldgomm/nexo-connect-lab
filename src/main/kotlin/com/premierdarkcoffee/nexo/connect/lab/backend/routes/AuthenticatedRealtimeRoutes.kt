package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptAdvance
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.EphemeralPresenceLeaseStore
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseHandle
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.AuthorizeConversationSubscriptionRequest
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ConversationSubscriptionAuthorizationResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableConversationCatchUpResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.EphemeralRealtimeConnectionRegistry
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.LoadDurableConversationCatchUpRequest
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.MessageCreatedEventSink
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.RealtimeConnectionRegistration
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.ReceiptCursorEventSink
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.BoundedRealtimeOutboundSender
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.REALTIME_AUTH_PROVIDER
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.authenticatedRealtimeRuntime
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.AuthenticatedRealtimeSubject
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameValidation
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeMessageCreatedPayload
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocol
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeProtocolError
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeReceiptCursorPayload
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeRoutingRefs
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.validateEnvelope
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.math.min

private val RealtimeOutboundSenderKey =
    AttributeKey<BoundedRealtimeOutboundSender>("NexoConnectLabRealtimeOutboundSender")

fun Route.authenticatedRealtimeRoutes(application: Application) {
    val runtime = application.authenticatedRealtimeRuntime()

    authenticate(REALTIME_AUTH_PROVIDER) {
        webSocket("/v1/realtime") {
            val authenticated = call.principal<AuthenticatedConnectPrincipal>()
            if (authenticated == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "AUTHENTICATION_REQUIRED"))
                return@webSocket
            }

            val connectionLease = runtime.connectionLimiter.tryAcquire()
            if (connectionLease == null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "CONNECTION_LIMIT_REACHED"))
                return@webSocket
            }
            val outboundSender =
                BoundedRealtimeOutboundSender(
                    scope = this,
                    capacity = runtime.hardeningConfig.outboundQueueCapacity,
                    sendTimeoutMillis = runtime.hardeningConfig.outboundSendTimeoutMillis,
                    writeText = { encoded -> send(Frame.Text(encoded)) },
                    closeSlowConsumer = {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "SLOW_CONSUMER"))
                    },
                )
            call.attributes.put(RealtimeOutboundSenderKey, outboundSender)
            val registration =
                try {
                    runtime.conversationEventHub.register(
                        principal = authenticated.connectPrincipal,
                        sink =
                        MessageCreatedEventSink { event ->
                            sendMessageCreated(runtime, event)
                        },
                        receiptSink =
                        ReceiptCursorEventSink { event ->
                            sendReceiptCursor(runtime, event)
                        },
                    )
                } catch (failure: Exception) {
                    outboundSender.shutdown()
                    connectionLease.close()
                    throw failure
                }
            val presenceTarget =
                PresenceLeaseTarget(
                    subjectRef = authenticated.connectPrincipal.subjectRef,
                    actorType = authenticated.connectPrincipal.actorType,
                    platformScopeRef = authenticated.connectPrincipal.platformScopeRef,
                    deviceRef = registration.deviceRef,
                )
            var presenceHandle = acquirePresenceLease(runtime.presenceLeaseStore, presenceTarget)
            val refreshIntervalMillis =
                min(
                    EphemeralRealtimeConnectionRegistry.REFRESH_INTERVAL.toMillis(),
                    runtime.presenceLeaseStore?.refreshInterval?.toMillis()
                        ?: EphemeralRealtimeConnectionRegistry.REFRESH_INTERVAL.toMillis(),
                )
            val registryRefreshJob =
                launch {
                    while (isActive) {
                        delay(refreshIntervalMillis)
                        if (!runtime.conversationEventHub.touch(registration)) {
                            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "ROUTING_LEASE_EXPIRED"))
                            break
                        }
                        presenceHandle =
                            refreshPresenceLease(
                                store = runtime.presenceLeaseStore,
                                target = presenceTarget,
                                current = presenceHandle,
                            )
                    }
                }

            try {
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
                        routing =
                        RealtimeRoutingRefs(
                            connectionRef = registration.registrationRef,
                            deviceRef = registration.deviceRef,
                            sessionRef = registration.sessionRef,
                        ),
                    ),
                )

                val subscribedConversationRefs = linkedSetOf<String>()
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            if (!runtime.conversationEventHub.touch(registration)) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.TRY_AGAIN_LATER,
                                        "ROUTING_LEASE_EXPIRED",
                                    ),
                                )
                                break
                            }
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
                                        registration = registration,
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
            } finally {
                registryRefreshJob.cancelAndJoin()
                releasePresenceLease(runtime.presenceLeaseStore, presenceHandle)
                runtime.conversationEventHub.unregister(registration)
                outboundSender.shutdown()
                connectionLease.close()
            }
        }
    }
}

private suspend fun acquirePresenceLease(
    store: EphemeralPresenceLeaseStore?,
    target: PresenceLeaseTarget,
): PresenceLeaseHandle? = when (val result = store?.acquire(target)) {
    is PresenceLeaseAcquireResult.Acquired -> result.handle
    PresenceLeaseAcquireResult.Unavailable, null -> null
}

private suspend fun refreshPresenceLease(
    store: EphemeralPresenceLeaseStore?,
    target: PresenceLeaseTarget,
    current: PresenceLeaseHandle?,
): PresenceLeaseHandle? {
    if (store == null) return null
    if (current == null) return acquirePresenceLease(store, target)
    return when (store.refresh(current)) {
        PresenceLeaseMutationResult.APPLIED,
        PresenceLeaseMutationResult.UNAVAILABLE,
        -> current

        PresenceLeaseMutationResult.NOT_OWNER -> acquirePresenceLease(store, target)
    }
}

private suspend fun releasePresenceLease(store: EphemeralPresenceLeaseStore?, handle: PresenceLeaseHandle?) {
    if (store == null || handle == null) return
    store.release(handle)
}

private suspend fun DefaultWebSocketServerSession.handleClientFrame(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    registration: RealtimeConnectionRegistration,
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
                registration = registration,
                subscribedConversationRefs = subscribedConversationRefs,
                frame = frame,
            )

        ClientRealtimeFrameType.ACK_DELIVERY ->
            advanceReceiptCursor(
                runtime = runtime,
                authenticated = authenticated,
                registration = registration,
                subscribedConversationRefs = subscribedConversationRefs,
                frame = frame,
                advance = DurableReceiptAdvance.DELIVERY,
            )

        ClientRealtimeFrameType.UPDATE_READ_CURSOR ->
            advanceReceiptCursor(
                runtime = runtime,
                authenticated = authenticated,
                registration = registration,
                subscribedConversationRefs = subscribedConversationRefs,
                frame = frame,
                advance = DurableReceiptAdvance.READ,
            )

        else ->
            sendProtocolError(runtime, "UNSUPPORTED_FRAME_TYPE", false, frame.correlationId)
    }
}

private suspend fun DefaultWebSocketServerSession.subscribeConversation(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    registration: RealtimeConnectionRegistration,
    subscribedConversationRefs: MutableSet<String>,
    frame: ClientRealtimeFrame,
) {
    val conversationRef = checkNotNull(frame.conversationRef)
    if (frame.afterSequence != null) {
        subscribeConversationWithCatchUp(
            runtime = runtime,
            authenticated = authenticated,
            registration = registration,
            subscribedConversationRefs = subscribedConversationRefs,
            frame = frame,
        )
        return
    }

    when (val authorization = authorizeConversation(runtime, authenticated, conversationRef)) {
        is ConversationSubscriptionAuthorizationResult.Authorized -> {
            if (!hasSubscriptionCapacity(runtime, subscribedConversationRefs, conversationRef)) {
                sendProtocolError(runtime, "SUBSCRIPTION_LIMIT_REACHED", false, frame.correlationId)
                return
            }

            subscribedConversationRefs += conversationRef
            runtime.conversationEventHub.subscribe(registration, conversationRef)
            sendConversationSubscribed(runtime, frame, authorization)
        }

        ConversationSubscriptionAuthorizationResult.NotFoundOrDenied ->
            sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, frame.correlationId)

        ConversationSubscriptionAuthorizationResult.Unavailable ->
            sendProtocolError(runtime, "SUBSCRIPTION_SERVICE_UNAVAILABLE", true, frame.correlationId)
    }
}

private suspend fun DefaultWebSocketServerSession.subscribeConversationWithCatchUp(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    registration: RealtimeConnectionRegistration,
    subscribedConversationRefs: MutableSet<String>,
    frame: ClientRealtimeFrame,
) {
    val conversationRef = checkNotNull(frame.conversationRef)
    val afterSequence = checkNotNull(frame.afterSequence)
    val coordinator = runtime.durableTextMessageCoordinator
    val catchUp = runtime.durableConversationCatchUp
    if (coordinator == null || catchUp == null) {
        sendProtocolError(runtime, "CATCH_UP_SERVICE_UNAVAILABLE", true, frame.correlationId)
        return
    }

    coordinator.synchronizeConversation(conversationRef) {
        when (val authorization = authorizeConversation(runtime, authenticated, conversationRef)) {
            is ConversationSubscriptionAuthorizationResult.Authorized -> {
                if (!hasSubscriptionCapacity(runtime, subscribedConversationRefs, conversationRef)) {
                    sendProtocolError(runtime, "SUBSCRIPTION_LIMIT_REACHED", false, frame.correlationId)
                    return@synchronizeConversation
                }
                if (afterSequence > authorization.lastMessageSequence) {
                    sendProtocolError(runtime, "INVALID_RESUME_SEQUENCE", false, frame.correlationId)
                    return@synchronizeConversation
                }

                val result =
                    try {
                        withContext(Dispatchers.IO) {
                            catchUp.load(
                                LoadDurableConversationCatchUpRequest(
                                    principal = authenticated.connectPrincipal,
                                    conversationRef = conversationRef,
                                    afterSequence = afterSequence,
                                    snapshotLastMessageSequence = authorization.lastMessageSequence,
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }

                when (result) {
                    is DurableConversationCatchUpResult.Loaded -> {
                        sendConversationSubscribed(runtime, frame, authorization)
                        result.events.forEach { event -> sendMessageCreated(runtime, event) }
                        if (
                            !sendDurableReceiptSnapshot(
                                runtime = runtime,
                                authenticated = authenticated,
                                conversationRef = conversationRef,
                                correlationId = frame.correlationId ?: frame.eventId,
                            )
                        ) {
                            return@synchronizeConversation
                        }
                        subscribedConversationRefs += conversationRef
                        runtime.conversationEventHub.subscribe(registration, conversationRef)
                        sendServerFrame(
                            runtime,
                            ServerRealtimeFrame(
                                type = ServerRealtimeFrameType.CONVERSATION_SYNCED,
                                eventId = runtime.eventIdFactory(),
                                serverTimestamp = runtime.clock.instant().toString(),
                                correlationId = frame.correlationId ?: frame.eventId,
                                conversationRef = conversationRef,
                                lastMessageSequence = result.snapshotLastMessageSequence,
                                replayedMessageCount = result.events.size,
                            ),
                        )
                    }

                    DurableConversationCatchUpResult.NotFoundOrDenied ->
                        sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, frame.correlationId)

                    DurableConversationCatchUpResult.WindowExceeded ->
                        sendProtocolError(runtime, "CATCH_UP_WINDOW_EXCEEDED", false, frame.correlationId)

                    null ->
                        sendProtocolError(runtime, "CATCH_UP_SERVICE_UNAVAILABLE", true, frame.correlationId)
                }
            }

            ConversationSubscriptionAuthorizationResult.NotFoundOrDenied ->
                sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, frame.correlationId)

            ConversationSubscriptionAuthorizationResult.Unavailable ->
                sendProtocolError(runtime, "SUBSCRIPTION_SERVICE_UNAVAILABLE", true, frame.correlationId)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.advanceReceiptCursor(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    registration: RealtimeConnectionRegistration,
    subscribedConversationRefs: Set<String>,
    frame: ClientRealtimeFrame,
    advance: DurableReceiptAdvance,
) {
    val conversationRef = checkNotNull(frame.conversationRef)
    val sequence = checkNotNull(frame.receiptSequence)
    if (conversationRef !in subscribedConversationRefs) {
        sendProtocolError(runtime, "CONVERSATION_NOT_SUBSCRIBED", false, frame.correlationId)
        return
    }
    val service = runtime.durableReceiptCursorService
    if (service == null) {
        sendProtocolError(runtime, "RECEIPT_SERVICE_UNAVAILABLE", true, frame.correlationId)
        return
    }

    val result =
        try {
            service.advance(
                AdvanceDurableReceiptCursorRequest(
                    principal = authenticated.connectPrincipal,
                    conversationRef = conversationRef,
                    sequence = sequence,
                    advance = advance,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    when (result) {
        is AdvanceDurableReceiptCursorResult.Recorded -> {
            val event = DurableReceiptCursorEvent(result.cursor)
            sendReceiptCursor(runtime, event, frame.correlationId ?: frame.eventId)
            if (result.advanced) {
                runtime.multiInstanceFanout.publishReceipt(event, excludedRegistration = registration)
            }
        }

        AdvanceDurableReceiptCursorResult.InvalidSequence ->
            sendProtocolError(runtime, "INVALID_RECEIPT_SEQUENCE", false, frame.correlationId)

        AdvanceDurableReceiptCursorResult.NotFoundOrDenied ->
            sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, frame.correlationId)

        null ->
            sendProtocolError(runtime, "RECEIPT_SERVICE_UNAVAILABLE", true, frame.correlationId)
    }
}

private suspend fun DefaultWebSocketServerSession.sendDurableReceiptSnapshot(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    conversationRef: String,
    correlationId: String,
): Boolean {
    val service = runtime.durableReceiptCursorService ?: return true
    val result =
        try {
            service.load(
                LoadDurableReceiptCursorsRequest(
                    principal = authenticated.connectPrincipal,
                    conversationRef = conversationRef,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    return when (result) {
        is LoadDurableReceiptCursorsResult.Loaded -> {
            result.cursors.forEach { cursor ->
                sendReceiptCursor(runtime, DurableReceiptCursorEvent(cursor))
            }
            true
        }

        LoadDurableReceiptCursorsResult.NotFoundOrDenied -> {
            sendProtocolError(runtime, "CONVERSATION_NOT_FOUND_OR_DENIED", false, correlationId)
            false
        }

        null -> {
            sendProtocolError(runtime, "RECEIPT_SERVICE_UNAVAILABLE", true, correlationId)
            false
        }
    }
}

private suspend fun authorizeConversation(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    authenticated: AuthenticatedConnectPrincipal,
    conversationRef: String,
): ConversationSubscriptionAuthorizationResult = try {
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

private fun hasSubscriptionCapacity(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    subscribedConversationRefs: Set<String>,
    conversationRef: String,
): Boolean = conversationRef in subscribedConversationRefs ||
    subscribedConversationRefs.size < runtime.maxConversationSubscriptions

private suspend fun DefaultWebSocketServerSession.sendConversationSubscribed(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    frame: ClientRealtimeFrame,
    authorization: ConversationSubscriptionAuthorizationResult.Authorized,
) {
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

private suspend fun DefaultWebSocketServerSession.sendMessageCreated(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    event: DurableMessageCreatedEvent,
) {
    sendServerFrame(
        runtime,
        ServerRealtimeFrame(
            type = ServerRealtimeFrameType.MESSAGE_CREATED,
            eventId = runtime.eventIdFactory(),
            serverTimestamp = runtime.clock.instant().toString(),
            conversationRef = event.conversationRef,
            message =
            RealtimeMessageCreatedPayload(
                serverMessageRef = event.serverMessageRef,
                sequence = event.sequence.value,
                senderSubjectRef = event.senderSubjectRef,
                senderActorType = event.senderActorType.name,
                messageType = "TEXT",
                body = event.body.value,
                acceptedAtServer = event.acceptedAtServer.toString(),
            ),
        ),
    )
}

private suspend fun DefaultWebSocketServerSession.sendReceiptCursor(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    event: DurableReceiptCursorEvent,
    correlationId: String? = null,
) {
    val cursor = event.cursor
    sendServerFrame(
        runtime,
        ServerRealtimeFrame(
            type = ServerRealtimeFrameType.RECEIPT_CURSOR_UPDATED,
            eventId = runtime.eventIdFactory(),
            serverTimestamp = runtime.clock.instant().toString(),
            correlationId = correlationId,
            conversationRef = cursor.conversationRef,
            receipt =
            RealtimeReceiptCursorPayload(
                subjectRef = cursor.subjectRef,
                actorType = cursor.actorType.name,
                highestDeliveredSequence = cursor.highestDeliveredSequence,
                highestReadSequence = cursor.highestReadSequence,
                deliveredAt = cursor.deliveredAt?.toString(),
                readAt = cursor.readAt?.toString(),
                updatedAt = cursor.updatedAt.toString(),
                version = cursor.version,
            ),
        ),
    )
}

private suspend fun DefaultWebSocketServerSession.closeWithError(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    code: String,
    closeCode: CloseReason.Codes,
    correlationId: String? = null,
) {
    sendProtocolError(runtime, code, false, correlationId, awaitDelivery = true)
    close(CloseReason(closeCode, code))
}

private suspend fun DefaultWebSocketServerSession.sendProtocolError(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    code: String,
    retryable: Boolean,
    correlationId: String? = null,
    awaitDelivery: Boolean = false,
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
        awaitDelivery = awaitDelivery,
    )
}

private suspend fun DefaultWebSocketServerSession.sendServerFrame(
    runtime: com.premierdarkcoffee.nexo.connect.lab.backend.realtime.AuthenticatedRealtimeRuntime,
    frame: ServerRealtimeFrame,
    awaitDelivery: Boolean = false,
) {
    call.attributes[RealtimeOutboundSenderKey].send(
        text = runtime.json.encodeToString(frame),
        awaitDelivery = awaitDelivery,
    )
}
