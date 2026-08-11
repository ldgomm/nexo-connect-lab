package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType

data class ConversationParticipantCommandState(
    val subjectRef: String,
    val actorType: ConnectActorType,
    val status: ConversationParticipantStatus,
    val capabilities: Set<ConversationCapability>,
) {
    init {
        require(subjectRef.isNotBlank()) { "subjectRef must not be blank" }
    }
}
