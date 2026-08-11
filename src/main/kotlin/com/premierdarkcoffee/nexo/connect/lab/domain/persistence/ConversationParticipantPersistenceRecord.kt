package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.time.Instant

data class ConversationParticipantPersistenceRecord(
    val conversationRef: String,
    val subjectRef: String,
    val actorType: ConnectActorType,
    val status: ConversationParticipantStatus,
    val capabilities: Set<ConversationCapability>,
    val joinedAt: Instant,
    val leftAt: Instant? = null,
) {
    init {
        requireBoundedPersistenceValue(
            conversationRef,
            "conversationRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        requireBoundedPersistenceValue(
            subjectRef,
            "subjectRef",
            PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
        )
        require(status != ConversationParticipantStatus.ACTIVE || leftAt == null) {
            "An active participant cannot have leftAt"
        }
        require(status != ConversationParticipantStatus.LEFT || leftAt != null) {
            "A participant who left requires leftAt"
        }
        require(leftAt == null || !leftAt.isBefore(joinedAt)) {
            "leftAt cannot precede joinedAt"
        }
    }
}
