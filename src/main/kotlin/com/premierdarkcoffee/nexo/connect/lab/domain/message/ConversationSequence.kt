package com.premierdarkcoffee.nexo.connect.lab.domain.message

@JvmInline
value class ConversationSequence(
    val value: Long,
) {
    init {
        require(value >= 0) { "Conversation sequence must not be negative" }
    }

    fun next(): ConversationSequence {
        check(value < Long.MAX_VALUE) { "Conversation sequence is exhausted" }
        return ConversationSequence(value + 1)
    }

    companion object {
        val INITIAL = ConversationSequence(0)
    }
}
