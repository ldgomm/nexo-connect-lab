package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

enum class ConversationStatus(
    val acceptsDurableText: Boolean,
) {
    ACTIVE(acceptsDurableText = true),
    MUTED(acceptsDurableText = true),
    BLOCKED(acceptsDurableText = false),
    CLOSED(acceptsDurableText = false),
    ARCHIVED(acceptsDurableText = false),
}
