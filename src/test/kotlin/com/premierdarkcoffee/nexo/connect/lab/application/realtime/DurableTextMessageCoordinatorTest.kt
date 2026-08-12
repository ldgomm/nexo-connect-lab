package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageConflictReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DurableTextMessageCoordinatorTest {
    @Test
    fun `publishes exactly once only after a new durable commit`() = runBlocking {
        var repositoryReturned = false
        val events = mutableListOf<com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent>()
        val coordinator =
            coordinator(
                repository =
                    DurableTextRepository { request ->
                        assertEquals("server-message-1", request.serverMessageRef)
                        repositoryReturned = true
                        DurableTextRepositoryResult.Committed("server-message-1", ConversationSequence(7))
                    },
                publisher =
                    MessageCreatedEventPublisher { event ->
                        assertEquals(true, repositoryReturned)
                        events += event
                        MessageCreatedPublicationReport(2, 2)
                    },
            )

        val result = coordinator.send(request())

        assertIs<DurableTextRepositoryResult.Committed>(result)
        assertEquals(1, events.size)
        assertEquals("conversation-1", events.single().conversationRef)
        assertEquals("server-message-1", events.single().serverMessageRef)
        assertEquals(7L, events.single().sequence.value)
        assertEquals("business-subject", events.single().senderSubjectRef)
        assertEquals("hello durable world", events.single().body.value)
        assertEquals(Instant.parse("2026-08-12T09:45:00Z"), events.single().acceptedAtServer)
    }

    @Test
    fun `never republishes replay conflict or denial results`() = runBlocking {
        val results =
            listOf<DurableTextRepositoryResult>(
                DurableTextRepositoryResult.ReplayExisting("server-message-1", ConversationSequence(1)),
                DurableTextRepositoryResult.Conflict(MessageConflictReason.IDEMPOTENCY_KEY_REUSED),
                DurableTextRepositoryResult.Denied(DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP),
            )
        var publications = 0

        results.forEach { repositoryResult ->
            val coordinator =
                coordinator(
                    repository = DurableTextRepository { repositoryResult },
                    publisher =
                        MessageCreatedEventPublisher {
                            publications += 1
                            MessageCreatedPublicationReport(0, 0)
                        },
                )
            assertEquals(repositoryResult, coordinator.send(request()))
        }

        assertEquals(0, publications)
    }

    @Test
    fun `returns the durable commit when live publication fails`() = runBlocking {
        val committed = DurableTextRepositoryResult.Committed("server-message-1", ConversationSequence(1))
        val coordinator =
            coordinator(
                repository = DurableTextRepository { committed },
                publisher = MessageCreatedEventPublisher { error("synthetic socket fanout failure") },
            )

        assertEquals(committed, coordinator.send(request()))
    }

    private fun coordinator(
        repository: DurableTextRepository,
        publisher: MessageCreatedEventPublisher,
    ): DurableTextMessageCoordinator =
        DurableTextMessageCoordinator(
            repository = repository,
            eventPublisher = publisher,
            clock = Clock.fixed(Instant.parse("2026-08-12T09:45:00Z"), ZoneOffset.UTC),
            serverMessageRefFactory = { "server-message-1" },
        )

    private fun request() =
        SendDurableTextMessageRequest(
            principal =
                ConnectPrincipal(
                    subjectRef = "business-subject",
                    actorType = ConnectActorType.BUSINESS,
                    platformScopeRef = "platform",
                    organizationScopeRef = "organization",
                    businessScopeRef = "business",
                ),
            conversationRef = "conversation-1",
            clientMessageRef = "client-message-1",
            idempotencyKey = "idempotency-1",
            body = "hello durable world",
        )
}
