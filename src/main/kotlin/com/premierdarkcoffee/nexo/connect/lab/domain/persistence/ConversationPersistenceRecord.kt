package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import java.time.Instant

data class ConversationPersistenceRecord(
    val conversationRef: String,
    val type: ConversationType,
    val platformScopeRef: String,
    val organizationScopeRef: String,
    val businessScopeRef: String,
    val status: ConversationStatus,
    val createdAt: Instant,
    val lastMessageSequence: ConversationSequence,
    val version: Long,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        requireBoundedPersistenceValue(
            conversationRef,
            "conversationRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            platformScopeRef,
            "platformScopeRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            organizationScopeRef,
            "organizationScopeRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            businessScopeRef,
            "businessScopeRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        require(type == ConversationType.BUSINESS_CLIENT && type.isImplemented) {
            "Only the implemented business-client conversation may be persisted"
        }
        require(version >= 0) { "Conversation version must not be negative" }
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported conversation persistence schema" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
