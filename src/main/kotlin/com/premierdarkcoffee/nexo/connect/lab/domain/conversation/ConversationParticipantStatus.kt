package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

enum class ConversationParticipantStatus(
    val canSendDurableText: Boolean,
) {
    ACTIVE(canSendDurableText = true),
    LEFT(canSendDurableText = false),
    BLOCKED(canSendDurableText = false),
}
