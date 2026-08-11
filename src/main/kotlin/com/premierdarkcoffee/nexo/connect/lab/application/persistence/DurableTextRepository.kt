package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageConflictReason
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import java.time.Instant

data class DurableTextWriteRequest(
    val principal: ConnectPrincipal,
    val command: SendTextMessageCommand,
    val serverMessageRef: String,
    val acceptedAtServer: Instant,
) {
    init {
        require(serverMessageRef.isNotBlank()) { "serverMessageRef must not be blank" }
    }
}

sealed interface DurableTextRepositoryResult {
    data class Committed(
        val serverMessageRef: String,
        val sequence: ConversationSequence,
    ) : DurableTextRepositoryResult

    data class ReplayExisting(
        val serverMessageRef: String,
        val sequence: ConversationSequence,
    ) : DurableTextRepositoryResult

    data class Conflict(
        val reason: MessageConflictReason,
    ) : DurableTextRepositoryResult

    data class Denied(
        val reason: DurableTextAuthorizationDecision,
    ) : DurableTextRepositoryResult {
        init {
            require(reason != DurableTextAuthorizationDecision.ALLOW) {
                "A denied repository result requires a denial reason"
            }
        }
    }
}

fun interface DurableTextRepository {
    /**
     * This is a blocking durable boundary. A [DurableTextRepositoryResult.Committed]
     * result may be returned only after the database transaction commits.
     */
    fun persist(request: DurableTextWriteRequest): DurableTextRepositoryResult
}
