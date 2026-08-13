package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizedConversationEventHubTest {
    @Test
    fun `delivers only to subscribed principals that remain authorized`() = runBlocking {
        val deniedSubjects = ConcurrentHashMap.newKeySet<String>()
        val hub =
            AuthorizedConversationEventHub(
                authorizer =
                    ConversationSubscriptionAuthorizer { request ->
                        if (request.principal.subjectRef in deniedSubjects) {
                            ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
                        } else {
                            ConversationSubscriptionAuthorizationResult.Authorized(
                                request.conversationRef,
                                1,
                            )
                        }
                    },
                registrationRefFactory = sequentialRegistrationRefs(),
            )
        val businessEvents = mutableListOf<DurableMessageCreatedEvent>()
        val clientEvents = mutableListOf<DurableMessageCreatedEvent>()
        val outsiderEvents = mutableListOf<DurableMessageCreatedEvent>()
        val business = hub.register(businessPrincipal(), MessageCreatedEventSink { businessEvents += it })
        val client = hub.register(clientPrincipal(), MessageCreatedEventSink { clientEvents += it })
        hub.register(outsiderPrincipal(), MessageCreatedEventSink { outsiderEvents += it })
        hub.subscribe(business, CONVERSATION_REF)
        hub.subscribe(client, CONVERSATION_REF)

        val first = messageCreatedEvent()
        assertEquals(MessageCreatedPublicationReport(2, 2), hub.publish(first))
        deniedSubjects += "client-subject"
        val second = first.copy(sequence = ConversationSequence(2))
        assertEquals(MessageCreatedPublicationReport(2, 1), hub.publish(second))
        val third = first.copy(sequence = ConversationSequence(3))
        assertEquals(MessageCreatedPublicationReport(1, 1), hub.publish(third))

        assertEquals(listOf(first, second, third), businessEvents)
        assertEquals(listOf(first), clientEvents)
        assertEquals(emptyList(), outsiderEvents)
    }

    @Test
    fun `one failed socket does not block another authorized subscriber`() = runBlocking {
        val hub =
            AuthorizedConversationEventHub(
                authorizer =
                    ConversationSubscriptionAuthorizer { request ->
                        ConversationSubscriptionAuthorizationResult.Authorized(request.conversationRef, 0)
                    },
                registrationRefFactory = sequentialRegistrationRefs(),
            )
        val delivered = mutableListOf<DurableMessageCreatedEvent>()
        val failed = hub.register(businessPrincipal(), MessageCreatedEventSink { error("closed socket") })
        val healthy = hub.register(clientPrincipal(), MessageCreatedEventSink { delivered += it })
        hub.subscribe(failed, CONVERSATION_REF)
        hub.subscribe(healthy, CONVERSATION_REF)

        val event = messageCreatedEvent()
        assertEquals(MessageCreatedPublicationReport(2, 1), hub.publish(event))
        assertEquals(listOf(event), delivered)
    }

    @Test
    fun `receipt publication excludes only the originating registration`() = runBlocking {
        val hub =
            AuthorizedConversationEventHub(
                authorizer =
                    ConversationSubscriptionAuthorizer { request ->
                        ConversationSubscriptionAuthorizationResult.Authorized(request.conversationRef, 2)
                    },
                registrationRefFactory = sequentialRegistrationRefs(),
            )
        val originReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val secondDeviceReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val businessReceipts = mutableListOf<DurableReceiptCursorEvent>()
        val origin =
            hub.register(
                principal = clientPrincipal(),
                sink = MessageCreatedEventSink { },
                receiptSink = ReceiptCursorEventSink { originReceipts += it },
            )
        val secondDevice =
            hub.register(
                principal = clientPrincipal(),
                sink = MessageCreatedEventSink { },
                receiptSink = ReceiptCursorEventSink { secondDeviceReceipts += it },
            )
        val business =
            hub.register(
                principal = businessPrincipal(),
                sink = MessageCreatedEventSink { },
                receiptSink = ReceiptCursorEventSink { businessReceipts += it },
            )
        listOf(origin, secondDevice, business).forEach { hub.subscribe(it, CONVERSATION_REF) }
        val event = receiptCursorEvent()

        assertEquals(
            ReceiptCursorPublicationReport(eligibleSubscriptions = 2, deliveredSubscriptions = 2),
            hub.publishReceipt(event, excludedRegistration = origin),
        )
        assertEquals(emptyList(), originReceipts)
        assertEquals(listOf(event), secondDeviceReceipts)
        assertEquals(listOf(event), businessReceipts)
    }

    private fun messageCreatedEvent(): DurableMessageCreatedEvent =
        DurableMessageCreatedEvent(
            conversationRef = CONVERSATION_REF,
            serverMessageRef = "message-1",
            sequence = ConversationSequence(1),
            senderSubjectRef = "business-subject",
            senderActorType = ConnectActorType.BUSINESS,
            body = TextMessageBody("hello"),
            acceptedAtServer = Instant.parse("2026-08-12T09:45:00Z"),
        )

    private fun receiptCursorEvent(): DurableReceiptCursorEvent =
        DurableReceiptCursorEvent(
            DurableReceiptCursor(
                conversationRef = CONVERSATION_REF,
                subjectRef = "client-subject",
                actorType = ConnectActorType.CLIENT,
                highestDeliveredSequence = 2,
                highestReadSequence = 1,
                deliveredAt = Instant.parse("2026-08-12T14:20:00Z"),
                readAt = Instant.parse("2026-08-12T14:21:00Z"),
                updatedAt = Instant.parse("2026-08-12T14:21:00Z"),
                version = 2,
            ),
        )

    private fun businessPrincipal() =
        ConnectPrincipal(
            subjectRef = "business-subject",
            actorType = ConnectActorType.BUSINESS,
            platformScopeRef = "platform",
            organizationScopeRef = "organization",
            businessScopeRef = "business",
        )

    private fun clientPrincipal() =
        ConnectPrincipal(
            subjectRef = "client-subject",
            actorType = ConnectActorType.CLIENT,
            platformScopeRef = "platform",
        )

    private fun outsiderPrincipal() =
        ConnectPrincipal(
            subjectRef = "outsider-subject",
            actorType = ConnectActorType.CLIENT,
            platformScopeRef = "platform",
        )

    private fun sequentialRegistrationRefs(): () -> String {
        val next = AtomicInteger()
        return { "registration-${next.incrementAndGet()}" }
    }

    private companion object {
        const val CONVERSATION_REF = "conversation-1"
    }
}
