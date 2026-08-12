package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

data class SendDurableTextMessageRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val clientMessageRef: String,
    val idempotencyKey: String,
    val body: String,
)

class DurableTextMessageCoordinator(
    private val repository: DurableTextRepository,
    private val eventPublisher: MessageCreatedEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val serverMessageRefFactory: () -> String = { "message-${UUID.randomUUID()}" },
    lockStripeCount: Int = DEFAULT_LOCK_STRIPES,
) {
    private val conversationLocks: Array<Mutex>

    init {
        require(lockStripeCount in 1..MAX_LOCK_STRIPES) {
            "lockStripeCount must be between 1 and $MAX_LOCK_STRIPES"
        }
        conversationLocks = Array(lockStripeCount) { Mutex() }
    }

    suspend fun send(request: SendDurableTextMessageRequest): DurableTextRepositoryResult {
        val command =
            SendTextMessageCommand(
                conversationRef = request.conversationRef,
                senderSubjectRef = request.principal.subjectRef,
                identity =
                    ClientMessageIdentity(
                        clientMessageRef = request.clientMessageRef,
                        idempotencyKey = request.idempotencyKey,
                    ),
                body = TextMessageBody(request.body),
            )
        val acceptedAtServer = clock.instant()
        val writeRequest =
            DurableTextWriteRequest(
                principal = request.principal,
                command = command,
                serverMessageRef = serverMessageRefFactory(),
                acceptedAtServer = acceptedAtServer,
            )

        return lockFor(request.conversationRef).withLock {
            val result = withContext(Dispatchers.IO) { repository.persist(writeRequest) }
            if (result is DurableTextRepositoryResult.Committed) {
                val event =
                    DurableMessageCreatedEvent(
                        conversationRef = request.conversationRef,
                        serverMessageRef = result.serverMessageRef,
                        sequence = result.sequence,
                        senderSubjectRef = request.principal.subjectRef,
                        senderActorType = request.principal.actorType,
                        body = command.body,
                        acceptedAtServer = acceptedAtServer,
                    )
                try {
                    eventPublisher.publish(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Durable commit remains authoritative; C4 catch-up repairs missed live delivery.
                }
            }
            result
        }
    }

    private fun lockFor(conversationRef: String): Mutex {
        val index = (conversationRef.hashCode().ushr(1)) % conversationLocks.size
        return conversationLocks[index]
    }

    private companion object {
        const val DEFAULT_LOCK_STRIPES = 64
        const val MAX_LOCK_STRIPES = 1_024
    }
}
