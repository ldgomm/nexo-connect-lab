package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

data class ConversationAccessScope(
    val conversationRef: String,
    val publicBusinessRef: String,
    val participantSubjectRefs: Set<String>,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require(publicBusinessRef.isNotBlank()) { "publicBusinessRef must not be blank" }
        require(participantSubjectRefs.isNotEmpty()) { "At least one participant is required" }
        require(participantSubjectRefs.none(String::isBlank)) {
            "Participant subject references must not be blank"
        }
    }
}
