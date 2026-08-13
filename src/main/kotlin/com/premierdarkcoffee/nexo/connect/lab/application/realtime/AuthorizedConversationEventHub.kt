package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface MessageCreatedEventPublisher {
    suspend fun publish(event: DurableMessageCreatedEvent): MessageCreatedPublicationReport
}

fun interface MessageCreatedEventSink {
    suspend fun emit(event: DurableMessageCreatedEvent)
}

fun interface ReceiptCursorEventSink {
    suspend fun emit(event: DurableReceiptCursorEvent)
}

data class RealtimeConnectionRegistration(
    val registrationRef: String,
)

data class MessageCreatedPublicationReport(
    val eligibleSubscriptions: Int,
    val deliveredSubscriptions: Int,
)

data class ReceiptCursorPublicationReport(
    val eligibleSubscriptions: Int,
    val deliveredSubscriptions: Int,
)

class AuthorizedConversationEventHub(
    private val authorizer: ConversationSubscriptionAuthorizer,
    private val registrationRefFactory: () -> String = { "socket-${UUID.randomUUID()}" },
) : MessageCreatedEventPublisher {
    private data class ConnectionState(
        val principal: ConnectPrincipal,
        val subscribedConversationRefs: MutableSet<String>,
        val messageSink: MessageCreatedEventSink,
        val receiptSink: ReceiptCursorEventSink,
    )

    private val connections = ConcurrentHashMap<String, ConnectionState>()

    fun register(
        principal: ConnectPrincipal,
        sink: MessageCreatedEventSink,
        receiptSink: ReceiptCursorEventSink = ReceiptCursorEventSink { },
    ): RealtimeConnectionRegistration {
        val registration = RealtimeConnectionRegistration(registrationRefFactory())
        check(
            connections.putIfAbsent(
                registration.registrationRef,
                ConnectionState(
                    principal = principal,
                    subscribedConversationRefs = ConcurrentHashMap.newKeySet(),
                    messageSink = sink,
                    receiptSink = receiptSink,
                ),
            ) == null,
        ) { "Realtime registration reference collision" }
        return registration
    }

    fun subscribe(
        registration: RealtimeConnectionRegistration,
        conversationRef: String,
    ) {
        val connection = checkNotNull(connections[registration.registrationRef]) {
            "Realtime connection is not registered"
        }
        connection.subscribedConversationRefs += conversationRef
    }

    fun unregister(registration: RealtimeConnectionRegistration) {
        connections.remove(registration.registrationRef)
    }

    override suspend fun publish(event: DurableMessageCreatedEvent): MessageCreatedPublicationReport {
        val candidates =
            connections.values.filter { connection ->
                event.conversationRef in connection.subscribedConversationRefs
            }
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
        val candidates =
            connections.entries.filter { (registrationRef, connection) ->
                registrationRef != excludedRegistration?.registrationRef &&
                    conversationRef in connection.subscribedConversationRefs
            }
        var delivered = 0

        candidates.forEach { (_, connection) ->
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
}
