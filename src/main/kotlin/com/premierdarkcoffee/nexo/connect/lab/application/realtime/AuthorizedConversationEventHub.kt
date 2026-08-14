package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface MessageCreatedEventPublisher {
    suspend fun publish(event: DurableMessageCreatedEvent): MessageCreatedPublicationReport
}

fun interface MessageCreatedEventSink {
    suspend fun emit(event: DurableMessageCreatedEvent)
}

fun interface ReceiptCursorEventSink {
    suspend fun emit(event: DurableReceiptCursorEvent)
}

fun interface TypingSignalSink {
    suspend fun emit(signal: EphemeralTypingSignal)
}

data class MessageCreatedPublicationReport(val eligibleSubscriptions: Int, val deliveredSubscriptions: Int)

data class ReceiptCursorPublicationReport(val eligibleSubscriptions: Int, val deliveredSubscriptions: Int)

data class TypingSignalPublicationReport(val eligibleSubscriptions: Int, val deliveredSubscriptions: Int)

class AuthorizedConversationEventHub(
    private val authorizer: ConversationSubscriptionAuthorizer,
    private val connectionRegistry: EphemeralRealtimeConnectionRegistry = EphemeralRealtimeConnectionRegistry(),
) : MessageCreatedEventPublisher {
    fun register(
        principal: ConnectPrincipal,
        sink: MessageCreatedEventSink,
        receiptSink: ReceiptCursorEventSink = ReceiptCursorEventSink { },
        typingSink: TypingSignalSink = TypingSignalSink { },
    ): RealtimeConnectionRegistration = connectionRegistry.register(principal, sink, receiptSink, typingSink)

    fun subscribe(registration: RealtimeConnectionRegistration, conversationRef: String) {
        connectionRegistry.subscribe(registration, conversationRef)
    }

    fun touch(registration: RealtimeConnectionRegistration): Boolean = connectionRegistry.touch(registration)

    fun unregister(registration: RealtimeConnectionRegistration) {
        connectionRegistry.unregister(registration)
    }

    override suspend fun publish(event: DurableMessageCreatedEvent): MessageCreatedPublicationReport {
        val candidates = connectionRegistry.candidates(event.conversationRef)
        var delivered = 0

        candidates.forEach { connection ->
            val authorization =
                try {
                    withContext(Dispatchers.IO) {
                        authorizer.authorize(
                            AuthorizeConversationSubscriptionRequest(
                                principal = connection.principal,
                                conversationRef = event.conversationRef,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ConversationSubscriptionAuthorizationResult.Unavailable
                }

            if (authorization is ConversationSubscriptionAuthorizationResult.Authorized) {
                try {
                    connection.messageSink.emit(event)
                    delivered += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed socket must not prevent delivery to other authorised subscribers.
                }
            } else {
                connection.subscribedConversationRefs -= event.conversationRef
            }
        }

        return MessageCreatedPublicationReport(
            eligibleSubscriptions = candidates.size,
            deliveredSubscriptions = delivered,
        )
    }

    suspend fun publishReceipt(
        event: DurableReceiptCursorEvent,
        excludedRegistration: RealtimeConnectionRegistration? = null,
    ): ReceiptCursorPublicationReport {
        val conversationRef = event.cursor.conversationRef
        val candidates = connectionRegistry.candidates(conversationRef, excludedRegistration)
        var delivered = 0

        candidates.forEach { connection ->
            val authorization =
                try {
                    withContext(Dispatchers.IO) {
                        authorizer.authorize(
                            AuthorizeConversationSubscriptionRequest(
                                principal = connection.principal,
                                conversationRef = conversationRef,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ConversationSubscriptionAuthorizationResult.Unavailable
                }

            if (authorization is ConversationSubscriptionAuthorizationResult.Authorized) {
                try {
                    connection.receiptSink.emit(event)
                    delivered += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed socket must not prevent delivery to other authorised subscribers.
                }
            } else {
                connection.subscribedConversationRefs -= conversationRef
            }
        }

        return ReceiptCursorPublicationReport(
            eligibleSubscriptions = candidates.size,
            deliveredSubscriptions = delivered,
        )
    }

    suspend fun publishRemoteMessage(
        envelope: RealtimeFanoutEnvelope,
        payloadLoader: AuthorisedDurableFanoutPayloadLoader,
    ): MessageCreatedPublicationReport {
        val candidates = messageCandidates(envelope.conversationRef)
        val payloads = mutableMapOf<ConnectPrincipal, DurableMessageCreatedEvent?>()
        var delivered = 0

        candidates.forEach { connection ->
            if (authorize(connection, envelope.conversationRef)) {
                val event =
                    if (payloads.containsKey(connection.principal)) {
                        payloads[connection.principal]
                    } else {
                        loadRemoteMessage(payloadLoader, connection.principal, envelope).also {
                            payloads[connection.principal] = it
                        }
                    }
                if (event != null && emitMessage(connection, event)) delivered += 1
            } else {
                connection.subscribedConversationRefs -= envelope.conversationRef
            }
        }
        return MessageCreatedPublicationReport(candidates.size, delivered)
    }

    suspend fun publishRemoteReceipt(
        envelope: RealtimeFanoutEnvelope,
        payloadLoader: AuthorisedDurableFanoutPayloadLoader,
    ): ReceiptCursorPublicationReport {
        val candidates = messageCandidates(envelope.conversationRef)
        val payloads = mutableMapOf<ConnectPrincipal, DurableReceiptCursorEvent?>()
        var delivered = 0

        candidates.forEach { connection ->
            if (authorize(connection, envelope.conversationRef)) {
                val event =
                    if (payloads.containsKey(connection.principal)) {
                        payloads[connection.principal]
                    } else {
                        loadRemoteReceipt(payloadLoader, connection.principal, envelope).also {
                            payloads[connection.principal] = it
                        }
                    }
                if (event != null && emitReceipt(connection, event)) delivered += 1
            } else {
                connection.subscribedConversationRefs -= envelope.conversationRef
            }
        }
        return ReceiptCursorPublicationReport(candidates.size, delivered)
    }

    suspend fun publishTyping(
        signal: EphemeralTypingSignal,
        excludedRegistration: RealtimeConnectionRegistration? = null,
    ): TypingSignalPublicationReport {
        val candidates = connectionRegistry.candidates(signal.conversationRef, excludedRegistration)
        var delivered = 0
        candidates.forEach { connection ->
            if (authorize(connection, signal.conversationRef)) {
                if (emitTyping(connection, signal)) delivered += 1
            } else {
                connection.subscribedConversationRefs -= signal.conversationRef
            }
        }
        return TypingSignalPublicationReport(candidates.size, delivered)
    }

    internal fun activeConnectionCount(): Int = connectionRegistry.activeConnectionCount()

    internal fun activeConnectionCount(principal: ConnectPrincipal): Int =
        connectionRegistry.activeConnectionCount(principal)

    private fun messageCandidates(conversationRef: String): List<RegisteredRealtimeConnection> =
        connectionRegistry.candidates(conversationRef)

    private suspend fun authorize(connection: RegisteredRealtimeConnection, conversationRef: String): Boolean = try {
        withContext(Dispatchers.IO) {
            authorizer.authorize(
                AuthorizeConversationSubscriptionRequest(
                    principal = connection.principal,
                    conversationRef = conversationRef,
                ),
            )
        } is ConversationSubscriptionAuthorizationResult.Authorized
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun loadRemoteMessage(
        payloadLoader: AuthorisedDurableFanoutPayloadLoader,
        principal: ConnectPrincipal,
        envelope: RealtimeFanoutEnvelope,
    ): DurableMessageCreatedEvent? = try {
        payloadLoader.loadMessage(principal, envelope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun loadRemoteReceipt(
        payloadLoader: AuthorisedDurableFanoutPayloadLoader,
        principal: ConnectPrincipal,
        envelope: RealtimeFanoutEnvelope,
    ): DurableReceiptCursorEvent? = try {
        payloadLoader.loadReceipt(principal, envelope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun emitMessage(
        connection: RegisteredRealtimeConnection,
        event: DurableMessageCreatedEvent,
    ): Boolean = try {
        connection.messageSink.emit(event)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun emitReceipt(
        connection: RegisteredRealtimeConnection,
        event: DurableReceiptCursorEvent,
    ): Boolean = try {
        connection.receiptSink.emit(event)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun emitTyping(connection: RegisteredRealtimeConnection, signal: EphemeralTypingSignal): Boolean =
        try {
            connection.typingSink.emit(signal)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
}
