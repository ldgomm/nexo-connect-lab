package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessagePayloadFingerprint

data class MessageIdentityLookupKey(
    val platformScopeRef: String,
    val senderSubjectRef: String,
    val value: String,
) {
    init {
        requireBoundedPersistenceValue(
            platformScopeRef,
            "platformScopeRef",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            senderSubjectRef,
            "senderSubjectRef",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            value,
            "identityValue",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
    }
}

data class MessageIdentityPersistenceRecord(
    val platformScopeRef: String,
    val conversationRef: String,
    val senderSubjectRef: String,
    val identity: ClientMessageIdentity,
    val payloadFingerprint: MessagePayloadFingerprint,
    val serverMessageRef: String,
    val sequence: ConversationSequence,
) {
    init {
        requireBoundedPersistenceValue(
            platformScopeRef,
            "platformScopeRef",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            conversationRef,
            "conversationRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            senderSubjectRef,
            "senderSubjectRef",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            identity.idempotencyKey,
            "idempotencyKey",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            identity.clientMessageRef,
            "clientMessageRef",
            PersistenceFieldLimits.INDEXED_IDENTITY_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            serverMessageRef,
            "serverMessageRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        require(sequence.value > ConversationSequence.INITIAL.value) {
            "A durable identity binding requires a positive sequence"
        }
    }

    fun idempotencyLookupKey(): MessageIdentityLookupKey =
        MessageIdentityLookupKey(
            platformScopeRef = platformScopeRef,
            senderSubjectRef = senderSubjectRef,
            value = identity.idempotencyKey,
        )

    fun clientMessageLookupKey(): MessageIdentityLookupKey =
        MessageIdentityLookupKey(
            platformScopeRef = platformScopeRef,
            senderSubjectRef = senderSubjectRef,
            value = identity.clientMessageRef,
        )
}
