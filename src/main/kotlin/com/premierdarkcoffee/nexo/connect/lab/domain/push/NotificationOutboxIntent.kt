package com.premierdarkcoffee.nexo.connect.lab.domain.push

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.time.Instant

enum class NotificationType {
    MESSAGE_CREATED,
}

enum class NotificationFailureCode {
    REGISTRATION_REVOKED,
    PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_REJECTED,
    LEASE_EXPIRED_MAX_ATTEMPTS,
    OPERATOR_DEAD_LETTER,
}

enum class NotificationOutboxStatus {
    PENDING,
    CLAIMED,
    RETRY_PENDING,
    DELIVERED,
    DEAD_LETTER,
}

data class NotificationOutboxIntent(
    val intentRef: String,
    val platformScopeRef: String,
    val organizationScopeRef: String?,
    val businessScopeRef: String?,
    val conversationRef: String,
    val serverMessageRef: String,
    val recipientSubjectRef: String,
    val recipientActorType: ConnectActorType,
    val registrationRef: String,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
    val type: NotificationType,
    val status: NotificationOutboxStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val lastErrorCode: NotificationFailureCode?,
    val deliveredAt: Instant?,
    val deadLetteredAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        requirePushReference(intentRef, "intentRef")
        requirePushReference(platformScopeRef, "platformScopeRef")
        organizationScopeRef?.let { requirePushReference(it, "organizationScopeRef") }
        businessScopeRef?.let { requirePushReference(it, "businessScopeRef") }
        requirePushReference(conversationRef, "conversationRef")
        requirePushReference(serverMessageRef, "serverMessageRef")
        requirePushReference(recipientSubjectRef, "recipientSubjectRef")
        requirePushReference(registrationRef, "registrationRef")
        require(recipientActorType == ConnectActorType.BUSINESS || recipientActorType == ConnectActorType.CLIENT) {
            "Notification recipients must be business or client actors"
        }
        require(application.owns(recipientActorType)) {
            "Notification application must own the recipient actor type"
        }
        requireScopeShape()
        require(attemptCount in 0..maxAttempts) { "attemptCount must be within maxAttempts" }
        require(maxAttempts in 1..32) { "maxAttempts must be between 1 and 32" }
        require(version >= 0) { "version must not be negative" }
        require(!nextAttemptAt.isBefore(createdAt)) { "nextAttemptAt must not precede createdAt" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
        require(leaseExpiresAt?.isAfter(updatedAt) != false) { "leaseExpiresAt must follow updatedAt" }
        require(deliveredAt?.isBefore(createdAt) != true) { "deliveredAt must not precede createdAt" }
        require(deadLetteredAt?.isBefore(createdAt) != true) {
            "deadLetteredAt must not precede createdAt"
        }
        requireStateShape()
    }

    private fun requireScopeShape() {
        when (recipientActorType) {
            ConnectActorType.CLIENT -> {
                require(organizationScopeRef == null && businessScopeRef == null) {
                    "Client notification scope must not carry organization or business scope"
                }
            }

            ConnectActorType.BUSINESS -> {
                require(organizationScopeRef != null && businessScopeRef != null) {
                    "Business notification scope requires organization and business scope"
                }
            }

            else -> error("Unsupported notification recipient actor type")
        }
    }

    private fun requireStateShape() {
        when (status) {
            NotificationOutboxStatus.PENDING -> {
                require(attemptCount == 0)
                require(leaseOwner == null && leaseExpiresAt == null)
                require(lastErrorCode == null && deliveredAt == null && deadLetteredAt == null)
            }

            NotificationOutboxStatus.CLAIMED -> {
                require(attemptCount >= 1)
                requireNotificationLeaseOwner(checkNotNull(leaseOwner))
                require(leaseExpiresAt != null)
                require(deliveredAt == null && deadLetteredAt == null)
            }

            NotificationOutboxStatus.RETRY_PENDING -> {
                require(attemptCount in 1 until maxAttempts)
                require(leaseOwner == null && leaseExpiresAt == null)
                require(lastErrorCode != null)
                require(deliveredAt == null && deadLetteredAt == null)
            }

            NotificationOutboxStatus.DELIVERED -> {
                require(attemptCount >= 1)
                require(leaseOwner == null && leaseExpiresAt == null)
                require(deliveredAt != null && deadLetteredAt == null)
            }

            NotificationOutboxStatus.DEAD_LETTER -> {
                require(attemptCount >= 1)
                require(leaseOwner == null && leaseExpiresAt == null)
                require(lastErrorCode != null)
                require(deliveredAt == null && deadLetteredAt != null)
            }
        }
    }
}

internal fun requireNotificationLeaseOwner(value: String) {
    require(value.isNotBlank()) { "leaseOwner must not be blank" }
    require(value.length <= 128) { "leaseOwner exceeds the limit" }
    require('\u0000' !in value) { "leaseOwner must not contain NUL" }
}
