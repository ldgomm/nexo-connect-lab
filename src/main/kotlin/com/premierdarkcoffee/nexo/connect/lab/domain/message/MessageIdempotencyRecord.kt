package com.premierdarkcoffee.nexo.connect.lab.domain.message

data class MessageIdempotencyRecord(
    val conversationRef: String,
    val senderSubjectRef: String,
    val identity: ClientMessageIdentity,
    val payloadFingerprint: MessagePayloadFingerprint,
    val serverMessageRef: String,
    val sequence: ConversationSequence,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require(senderSubjectRef.isNotBlank()) { "senderSubjectRef must not be blank" }
        require(serverMessageRef.isNotBlank()) { "serverMessageRef must not be blank" }
        require(sequence.value > ConversationSequence.INITIAL.value) {
            "A persisted message sequence must be positive"
        }
    }
}
