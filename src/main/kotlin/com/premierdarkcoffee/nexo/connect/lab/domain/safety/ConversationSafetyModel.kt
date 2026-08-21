package com.premierdarkcoffee.nexo.connect.lab.domain.safety

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class ConversationSafetyScopeType {
    CONVERSATION,
}

data class ConversationSafetyScope(
    val type: ConversationSafetyScopeType,
    val conversationRef: String,
    val platformScopeRef: String,
    val organizationScopeRef: String,
    val businessScopeRef: String,
) {
    init {
        requireSafetyReference(conversationRef, "conversationRef")
        requireSafetyReference(platformScopeRef, "platformScopeRef")
        requireSafetyReference(organizationScopeRef, "organizationScopeRef")
        requireSafetyReference(businessScopeRef, "businessScopeRef")
    }
}

data class ConversationSafetyParticipant(val subjectRef: String, val actorType: ConnectActorType) {
    init {
        requireSafetyReference(subjectRef, "subjectRef")
        require(actorType == ConnectActorType.BUSINESS || actorType == ConnectActorType.CLIENT) {
            "Conversation safety controls require a business or client participant"
        }
    }
}

data class ConversationBlockDirection(
    val blocker: ConversationSafetyParticipant,
    val blocked: ConversationSafetyParticipant,
) {
    init {
        require(blocker != blocked) { "A participant cannot block itself" }
    }

    fun connects(first: ConversationSafetyParticipant, second: ConversationSafetyParticipant): Boolean =
        (blocker == first && blocked == second) || (blocker == second && blocked == first)
}

enum class ConversationBlockStatus {
    ACTIVE,
    REVOKED,
}

data class ConversationBlock(
    val blockRef: String,
    val scope: ConversationSafetyScope,
    val direction: ConversationBlockDirection,
    val status: ConversationBlockStatus,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        requireSafetyReference(blockRef, "blockRef")
        require(version >= 1) { "version must be positive" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
        require(revokedAt?.isBefore(createdAt) != true) { "revokedAt must not precede createdAt" }
        require((status == ConversationBlockStatus.REVOKED) == (revokedAt != null)) {
            "revokedAt must match block status"
        }
    }
}

enum class ConversationSafetyControl {
    BLOCK,
    NOTIFICATION_MUTE,
}

enum class ConversationSafetyAuditAction {
    APPLIED,
    REVOKED,
}

data class ConversationBlockAuditEvent(
    val auditRef: String,
    val blockRef: String,
    val scope: ConversationSafetyScope,
    val direction: ConversationBlockDirection,
    val action: ConversationSafetyAuditAction,
    val resultingVersion: Long,
    val occurredAt: Instant,
) {
    init {
        requireSafetyReference(auditRef, "auditRef")
        requireSafetyReference(blockRef, "blockRef")
        require(resultingVersion >= 1) { "resultingVersion must be positive" }
    }
}

data class NotificationMuteAuditEvent(
    val auditRef: String,
    val scope: ConversationSafetyScope,
    val owner: ConversationSafetyParticipant,
    val registrationRef: String,
    val action: ConversationSafetyAuditAction,
    val resultingVersion: Long,
    val occurredAt: Instant,
) {
    init {
        requireSafetyReference(auditRef, "auditRef")
        requireSafetyReference(registrationRef, "registrationRef")
        require(resultingVersion >= 1) { "resultingVersion must be positive" }
    }
}

internal fun requireSafetyReference(value: String, fieldName: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_SAFETY_REFERENCE_UTF8_BYTES) {
        "$fieldName exceeds the conversation safety reference limit"
    }
}

internal const val MAX_SAFETY_REFERENCE_UTF8_BYTES: Int = 256
