package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import java.time.Instant

data class DurableConversationSnapshot(
    val scope: ConversationAccessScope,
    val status: ConversationStatus,
    val participantStates: Set<ConversationParticipantCommandState>,
    val createdAt: Instant,
    val lastMessageSequence: ConversationSequence,
) {
    init {
        require(participantStates.isNotEmpty()) { "Participant state must not be empty" }
        require(participantStates.map(ConversationParticipantCommandState::subjectRef).distinct().size == participantStates.size) {
            "A participant state subject may appear only once"
        }

        val scopedParticipants = scope.participants.map { it.subjectRef to it.actorType }.toSet()
        val stateParticipants = participantStates.map { it.subjectRef to it.actorType }.toSet()
        require(scopedParticipants == stateParticipants) {
            "Participant state must exactly match durable conversation membership"
        }
    }
}
