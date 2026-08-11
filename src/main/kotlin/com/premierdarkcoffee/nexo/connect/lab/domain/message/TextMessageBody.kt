package com.premierdarkcoffee.nexo.connect.lab.domain.message

@JvmInline
value class TextMessageBody(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Text message body must not be blank" }
        require('\u0000' !in value) { "Text message body must not contain NUL" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES) {
            "Text message body exceeds the UTF-8 byte limit"
        }
    }

    companion object {
        const val MAX_UTF8_BYTES = 16_384
    }
}
