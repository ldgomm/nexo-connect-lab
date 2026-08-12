package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.nio.charset.StandardCharsets
import java.time.Instant

data class DurableMessageCreatedEvent(
    val conversationRef: String,
    val serverMessageRef: String,
    val sequence: ConversationSequence,
    val senderSubjectRef: String,
    val senderActorType: ConnectActorType,
    val body: TextMessageBody,
    val acceptedAtServer: Instant,
) {
    init {
        requireBoundedRealtimeReference(conversationRef, "conversationRef")
        requireBoundedRealtimeReference(serverMessageRef, "serverMessageRef")
        requireBoundedRealtimeReference(senderSubjectRef, "senderSubjectRef")
        require(sequence.value > ConversationSequence.INITIAL.value) {
            "A message-created event requires a positive durable sequence"
        }
        require(senderActorType == ConnectActorType.BUSINESS || senderActorType == ConnectActorType.CLIENT) {
            "A message-created event requires a business or client sender"
        }
    }
}

private fun requireBoundedRealtimeReference(
    value: String,
    fieldName: String,
) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= RealtimeProtocol.MAX_CONVERSATION_REF_UTF8_BYTES) {
        "$fieldName exceeds the realtime reference limit"
    }
}
