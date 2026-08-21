package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationBlock
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.requireSafetyReference
import java.time.Instant

data class ApplyConversationBlockRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val blockedSubjectRef: String,
    val blockedActorType: ConnectActorType,
    val expectedVersion: Long,
    val now: Instant,
) {
    init {
        requireParticipantPrincipal(principal)
        requireSafetyReference(conversationRef, "conversationRef")
        requireSafetyReference(blockedSubjectRef, "blockedSubjectRef")
        requireParticipantActor(blockedActorType, "blockedActorType")
        require(principal.subjectRef != blockedSubjectRef || principal.actorType != blockedActorType) {
            "A participant cannot block itself"
        }
        require(expectedVersion >= 0) { "expectedVersion must not be negative" }
    }
}

data class RevokeConversationBlockRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val blockedSubjectRef: String,
    val blockedActorType: ConnectActorType,
    val expectedVersion: Long,
    val now: Instant,
) {
    init {
        requireParticipantPrincipal(principal)
        requireSafetyReference(conversationRef, "conversationRef")
        requireSafetyReference(blockedSubjectRef, "blockedSubjectRef")
        requireParticipantActor(blockedActorType, "blockedActorType")
        require(principal.subjectRef != blockedSubjectRef || principal.actorType != blockedActorType) {
            "A participant cannot revoke a self-block"
        }
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
    }
}

sealed interface ConversationBlockMutationResult {
    data class Updated(val block: ConversationBlock, val created: Boolean, val changed: Boolean) :
        ConversationBlockMutationResult

    data object NotFoundOrDenied : ConversationBlockMutationResult
}

interface ConversationBlockRepository {
    fun apply(request: ApplyConversationBlockRequest): ConversationBlockMutationResult

    fun revoke(request: RevokeConversationBlockRequest): ConversationBlockMutationResult
}

private fun requireParticipantPrincipal(principal: ConnectPrincipal) {
    requireParticipantActor(principal.actorType, "principal.actorType")
}

private fun requireParticipantActor(actorType: ConnectActorType, fieldName: String) {
    require(actorType == ConnectActorType.BUSINESS || actorType == ConnectActorType.CLIENT) {
        "$fieldName must identify a business or client participant"
    }
}
