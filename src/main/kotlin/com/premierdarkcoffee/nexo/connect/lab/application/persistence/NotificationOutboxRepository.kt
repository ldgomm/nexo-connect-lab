package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.requireNotificationLeaseOwner
import com.premierdarkcoffee.nexo.connect.lab.domain.push.requirePushReference
import java.time.Duration
import java.time.Instant

data class ClaimNotificationOutboxRequest(
    val leaseOwner: String,
    val now: Instant,
    val leaseDuration: Duration,
    val limit: Int,
) {
    init {
        requireNotificationLeaseOwner(leaseOwner)
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "leaseDuration must be positive"
        }
        require(leaseDuration <= MAX_NOTIFICATION_LEASE_DURATION) {
            "leaseDuration exceeds the maximum"
        }
        require(limit in 1..MAX_NOTIFICATION_CLAIM_BATCH) {
            "limit must be between 1 and $MAX_NOTIFICATION_CLAIM_BATCH"
        }
    }
}

data class NotificationOutboxClaimBatch(val intents: List<NotificationOutboxIntent>) {
    init {
        require(intents.size <= MAX_NOTIFICATION_CLAIM_BATCH)
    }
}

data class MarkNotificationDeliveredRequest(
    val intentRef: String,
    val leaseOwner: String,
    val expectedVersion: Long,
    val now: Instant,
) {
    init {
        requirePushReference(intentRef, "intentRef")
        requireNotificationLeaseOwner(leaseOwner)
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
    }
}

data class RecordNotificationFailureRequest(
    val intentRef: String,
    val leaseOwner: String,
    val expectedVersion: Long,
    val now: Instant,
    val retryAt: Instant,
    val errorCode: NotificationFailureCode,
) {
    init {
        requirePushReference(intentRef, "intentRef")
        requireNotificationLeaseOwner(leaseOwner)
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
        require(retryAt.isAfter(now)) { "retryAt must follow now" }
        require(retryAt <= now.plus(MAX_NOTIFICATION_RETRY_DELAY)) {
            "retryAt exceeds the maximum delay"
        }
    }
}

data class DeadLetterNotificationRequest(
    val intentRef: String,
    val leaseOwner: String,
    val expectedVersion: Long,
    val now: Instant,
    val errorCode: NotificationFailureCode,
) {
    init {
        requirePushReference(intentRef, "intentRef")
        requireNotificationLeaseOwner(leaseOwner)
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
    }
}

sealed interface NotificationOutboxMutationResult {
    data class Updated(val intent: NotificationOutboxIntent) : NotificationOutboxMutationResult

    data object NotFoundOrDenied : NotificationOutboxMutationResult
}

interface NotificationOutboxRepository {
    fun claim(request: ClaimNotificationOutboxRequest): NotificationOutboxClaimBatch

    fun markDelivered(request: MarkNotificationDeliveredRequest): NotificationOutboxMutationResult

    fun recordFailure(request: RecordNotificationFailureRequest): NotificationOutboxMutationResult

    fun deadLetter(request: DeadLetterNotificationRequest): NotificationOutboxMutationResult
}

const val MAX_NOTIFICATION_CLAIM_BATCH: Int = 100
val MAX_NOTIFICATION_LEASE_DURATION: Duration = Duration.ofMinutes(15)
val MAX_NOTIFICATION_RETRY_DELAY: Duration = Duration.ofHours(24)
