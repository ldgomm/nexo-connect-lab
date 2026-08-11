package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

data class DurableTextAuthorizationContext(
    val scope: ConversationAccessScope,
    val conversationStatus: ConversationStatus,
    val participantStates: Set<ConversationParticipantCommandState>,
) {
    init {
        require(participantStates.isNotEmpty()) { "Participant command state must not be empty" }
        require(participantStates.map(ConversationParticipantCommandState::subjectRef).distinct().size == participantStates.size) {
            "A subject may have only one participant command state"
        }

        val scopedParticipants = scope.participants.map { it.subjectRef to it.actorType }.toSet()
        val stateParticipants = participantStates.map { it.subjectRef to it.actorType }.toSet()
        require(scopedParticipants == stateParticipants) {
            "Participant command state must exactly match conversation membership"
        }
    }

    fun stateFor(principal: ConnectPrincipal): ConversationParticipantCommandState? =
        participantStates.singleOrNull { state ->
            state.subjectRef == principal.subjectRef && state.actorType == principal.actorType
        }
}
