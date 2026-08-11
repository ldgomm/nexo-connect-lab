package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessagePayloadFingerprint
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessageStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessageType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.time.Instant

data class TextMessagePersistenceRecord(
    val serverMessageRef: String,
    val conversationRef: String,
    val sequence: ConversationSequence,
    val senderSubjectRef: String,
    val senderActorType: ConnectActorType,
    val body: TextMessageBody,
    val acceptedAtServer: Instant,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val type: MessageType = MessageType.TEXT
    val status: MessageStatus = MessageStatus.PERSISTED
    val payloadFingerprint: MessagePayloadFingerprint = MessagePayloadFingerprint.forText(body)

    init {
        requireBoundedPersistenceValue(
            serverMessageRef,
            "serverMessageRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            conversationRef,
            "conversationRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            senderSubjectRef,
            "senderSubjectRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        require(sequence.value > ConversationSequence.INITIAL.value) {
            "A persisted message sequence must be positive"
        }
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported text message persistence schema" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
