package com.premierdarkcoffee.nexo.connect.lab.domain.message

enum class MessageType(
    val isEnabledForDurableText: Boolean,
) {
    TEXT(isEnabledForDurableText = true),
    IMAGE(isEnabledForDurableText = false),
    VOICE_NOTE(isEnabledForDurableText = false),
    VIDEO_FILE(isEnabledForDurableText = false),
    LOCATION(isEnabledForDurableText = false),
    PRODUCT_CARD(isEnabledForDurableText = false),
    SYSTEM(isEnabledForDurableText = false),
}
