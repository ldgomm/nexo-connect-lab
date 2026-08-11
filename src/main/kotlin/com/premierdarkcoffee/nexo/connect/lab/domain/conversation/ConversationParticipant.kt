package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType

data class ConversationParticipant(
    val subjectRef: String,
    val actorType: ConnectActorType,
) {
    init {
        require(subjectRef.isNotBlank()) { "subjectRef must not be blank" }
    }
}
