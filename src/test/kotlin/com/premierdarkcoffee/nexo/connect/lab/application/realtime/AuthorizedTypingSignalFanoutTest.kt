package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizedTypingSignalFanoutTest {
    @Test
    fun `typing reaches only freshly authorised subscribers and excludes the origin connection`() = runBlocking {
        val revoked = mutableSetOf<String>()
        val hub =
            AuthorizedConversationEventHub(
                ConversationSubscriptionAuthorizer { request ->
                    if (request.conversationRef == CONVERSATION && request.principal.subjectRef !in revoked) {
                        ConversationSubscriptionAuthorizationResult.Authorized(CONVERSATION, 0)
                    } else {
                        ConversationSubscriptionAuthorizationResult.NotFoundOrDenied
                    }
                },
            )
        val originEvents = mutableListOf<EphemeralTypingSignal>()
        val recipientEvents = mutableListOf<EphemeralTypingSignal>()
        val leakedEvents = mutableListOf<EphemeralTypingSignal>()
        val origin = hub.register(
            principal("origin"),
            MessageCreatedEventSink {
            },
            typingSink = TypingSignalSink(originEvents::add),
        )
        val recipient =
            hub.register(
                principal("recipient"),
                MessageCreatedEventSink { },
                typingSink = TypingSignalSink(recipientEvents::add),
            )
        val outsider =
            hub.register(
                principal("outsider"),
                MessageCreatedEventSink { },
                typingSink = TypingSignalSink(leakedEvents::add),
            )
        hub.subscribe(origin, CONVERSATION)
        hub.subscribe(recipient, CONVERSATION)
        hub.subscribe(outsider, OTHER_CONVERSATION)

        val report = hub.publishTyping(signal(), excludedRegistration = origin)
        assertEquals(1, report.deliveredSubscriptions)
        assertEquals(emptyList(), originEvents)
        assertEquals(listOf(signal()), recipientEvents)
        assertEquals(emptyList(), leakedEvents)

        revoked += "recipient"
        hub.publishTyping(signal().copy(eventId = "typing-2"), excludedRegistration = origin)
        assertEquals(1, recipientEvents.size)
        assertEquals(0, leakedEvents.size)
    }

    private fun signal() = EphemeralTypingSignal(
        eventId = "typing-1",
        conversationRef = CONVERSATION,
        subjectRef = "origin",
        actorType = ConnectActorType.CLIENT,
        active = true,
        expiresInMillis = 6_000,
        occurredAt = Instant.parse("2026-08-14T05:00:00Z"),
        originInstanceRef = "instance-a",
    )

    private fun principal(subjectRef: String) = ConnectPrincipal(
        subjectRef = subjectRef,
        actorType = ConnectActorType.CLIENT,
        platformScopeRef = "platform-1",
    )

    private companion object {
        const val CONVERSATION = "conversation-1"
        const val OTHER_CONVERSATION = "conversation-2"
    }
}
