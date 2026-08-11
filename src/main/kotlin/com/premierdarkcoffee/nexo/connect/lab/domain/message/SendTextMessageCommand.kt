package com.premierdarkcoffee.nexo.connect.lab.domain.message

data class SendTextMessageCommand(
    val conversationRef: String,
    val senderSubjectRef: String,
    val identity: ClientMessageIdentity,
    val body: TextMessageBody,
) {
    val type: MessageType = MessageType.TEXT
    val payloadFingerprint: MessagePayloadFingerprint = MessagePayloadFingerprint.forText(body)

    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require(senderSubjectRef.isNotBlank()) { "senderSubjectRef must not be blank" }
    }
}
