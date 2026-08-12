package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import java.time.Instant

data class CreateBusinessClientConversationCommand(
    val conversationRef: String,
    val clientSubjectRef: String,
    val requestedAt: Instant,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require('\u0000' !in conversationRef) { "conversationRef must not contain NUL" }
        require(clientSubjectRef.isNotBlank()) { "clientSubjectRef must not be blank" }
        require('\u0000' !in clientSubjectRef) { "clientSubjectRef must not contain NUL" }
    }
}
