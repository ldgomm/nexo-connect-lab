package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DeadLetterNotificationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.MarkNotificationDeliveredRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RecordNotificationFailureRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import java.time.Clock
import java.time.Duration

data class NotificationDeliveryRunSummary(
    val claimed: Int,
    val delivered: Int,
    val retryScheduled: Int,
    val deadLettered: Int,
    val leaseDeferred: Int,
) {
    init {
        require(claimed >= 0)
        require(delivered >= 0)
        require(retryScheduled >= 0)
        require(deadLettered >= 0)
        require(leaseDeferred >= 0)
        require(delivered + retryScheduled + deadLettered + leaseDeferred == claimed)
    }
}

class NotificationOutboxDeliveryWorker(
    private val repository: NotificationOutboxRepository,
    providers: Map<PushProvider, NotificationProvider>,
    private val leaseOwner: String,
    private val clock: Clock = Clock.systemUTC(),
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val claimLimit: Int = 25,
    private val retryPolicy: NotificationRetryPolicy = BoundedExponentialNotificationRetryPolicy(),
    private val invalidRegistrationRetirer: InvalidPushRegistrationRetirer =
        InvalidPushRegistrationRetirer.UNAVAILABLE,
    private val observer: NotificationDeliveryObserver = NotificationDeliveryObserver.NOOP,
) {
    private val providers = providers.toMap()

    init {
        ClaimNotificationOutboxRequest(
            leaseOwner = leaseOwner,
            now = clock.instant(),
            leaseDuration = leaseDuration,
            limit = claimLimit,
        )
        require(this.providers.isNotEmpty()) { "At least one notification provider is required" }
    }

    @Synchronized
    fun runOnce(): NotificationDeliveryRunSummary {
        val batch = repository.claim(
            ClaimNotificationOutboxRequest(
                leaseOwner = leaseOwner,
                now = clock.instant(),
                leaseDuration = leaseDuration,
                limit = claimLimit,
            ),
        )

        var delivered = 0
        var retryScheduled = 0
        var deadLettered = 0
        var leaseDeferred = 0

        batch.intents.forEach { intent ->
            val delivery = deliverWithoutLeakingFailure(intent)
            val settlement = settle(intent, delivery)
            when (settlement) {
                NotificationDeliverySettlement.DELIVERED -> delivered += 1
                NotificationDeliverySettlement.RETRY_SCHEDULED -> retryScheduled += 1
                NotificationDeliverySettlement.DEAD_LETTERED -> deadLettered += 1
                NotificationDeliverySettlement.LEASE_DEFERRED -> leaseDeferred += 1
            }
            recordSanitized(intent, delivery.diagnostic(), settlement)
        }

        return NotificationDeliveryRunSummary(
            claimed = batch.intents.size,
            delivered = delivered,
            retryScheduled = retryScheduled,
            deadLettered = deadLettered,
            leaseDeferred = leaseDeferred,
        )
    }

    private fun deliverWithoutLeakingFailure(intent: NotificationOutboxIntent): NotificationProviderDeliveryResult =
        try {
            providers[intent.provider]?.deliver(intent)
                ?: NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.PROVIDER_REJECTED,
                    diagnostic = NotificationDeliveryDiagnostic.REQUEST_REJECTED,
                )
        } catch (_: Exception) {
            NotificationProviderDeliveryResult.RetryableFailure(
                errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                diagnostic = NotificationDeliveryDiagnostic.ADAPTER_FAILURE,
            )
        }

    private fun settle(
        intent: NotificationOutboxIntent,
        delivery: NotificationProviderDeliveryResult,
    ): NotificationDeliverySettlement = try {
        val now = clock.instant()
        val mutation = when (delivery) {
            is NotificationProviderDeliveryResult.Delivered -> repository.markDelivered(
                MarkNotificationDeliveredRequest(
                    intentRef = intent.intentRef,
                    leaseOwner = leaseOwner,
                    expectedVersion = intent.version,
                    now = now,
                ),
            )

            is NotificationProviderDeliveryResult.RetryableFailure -> repository.recordFailure(
                RecordNotificationFailureRequest(
                    intentRef = intent.intentRef,
                    leaseOwner = leaseOwner,
                    expectedVersion = intent.version,
                    now = now,
                    retryAt = now.plus(retryPolicy.delayFor(delivery.errorCode, intent.attemptCount)),
                    errorCode = delivery.errorCode,
                ),
            )

            is NotificationProviderDeliveryResult.PermanentFailure ->
                settlePermanentFailure(intent, delivery, now)
        }
        mutation.toSettlement()
    } catch (_: Exception) {
        NotificationDeliverySettlement.LEASE_DEFERRED
    }

    private fun settlePermanentFailure(
        intent: NotificationOutboxIntent,
        delivery: NotificationProviderDeliveryResult.PermanentFailure,
        now: java.time.Instant,
    ): NotificationOutboxMutationResult {
        val invalidTokenVersion = delivery.invalidTokenVersion
            ?: return deadLetter(intent, delivery.errorCode, now)

        return when (
            invalidRegistrationRetirer.retire(
                RetireInvalidPushRegistrationRequest(
                    intent = intent,
                    expectedTokenVersion = invalidTokenVersion,
                    now = now,
                ),
            )
        ) {
            InvalidPushRegistrationRetirementResult.Retired,
            InvalidPushRegistrationRetirementResult.NotFoundOrDenied,
            -> deadLetter(intent, delivery.errorCode, now)

            InvalidPushRegistrationRetirementResult.TokenRotated -> {
                val retryCode = NotificationFailureCode.PROVIDER_UNAVAILABLE
                repository.recordFailure(
                    RecordNotificationFailureRequest(
                        intentRef = intent.intentRef,
                        leaseOwner = leaseOwner,
                        expectedVersion = intent.version,
                        now = now,
                        retryAt = now.plus(retryPolicy.delayFor(retryCode, intent.attemptCount)),
                        errorCode = retryCode,
                    ),
                )
            }
        }
    }

    private fun deadLetter(
        intent: NotificationOutboxIntent,
        errorCode: NotificationFailureCode,
        now: java.time.Instant,
    ): NotificationOutboxMutationResult = repository.deadLetter(
        DeadLetterNotificationRequest(
            intentRef = intent.intentRef,
            leaseOwner = leaseOwner,
            expectedVersion = intent.version,
            now = now,
            errorCode = errorCode,
        ),
    )

    private fun NotificationOutboxMutationResult.toSettlement(): NotificationDeliverySettlement = when (this) {
        NotificationOutboxMutationResult.NotFoundOrDenied -> NotificationDeliverySettlement.LEASE_DEFERRED

        is NotificationOutboxMutationResult.Updated -> when (intent.status) {
            NotificationOutboxStatus.DELIVERED -> NotificationDeliverySettlement.DELIVERED
            NotificationOutboxStatus.RETRY_PENDING -> NotificationDeliverySettlement.RETRY_SCHEDULED
            NotificationOutboxStatus.DEAD_LETTER -> NotificationDeliverySettlement.DEAD_LETTERED
            else -> NotificationDeliverySettlement.LEASE_DEFERRED
        }
    }

    private fun recordSanitized(
        intent: NotificationOutboxIntent,
        diagnostic: NotificationDeliveryDiagnostic,
        settlement: NotificationDeliverySettlement,
    ) {
        try {
            observer.record(
                SanitizedNotificationDeliveryEvent(
                    intentRef = intent.intentRef,
                    provider = intent.provider,
                    application = intent.application,
                    environment = intent.environment,
                    attemptCount = intent.attemptCount,
                    settlement = settlement,
                    diagnostic = diagnostic,
                ),
            )
        } catch (_: Exception) {
            // Observability must not change durable delivery settlement.
        }
    }

    private fun NotificationProviderDeliveryResult.diagnostic(): NotificationDeliveryDiagnostic = when (this) {
        is NotificationProviderDeliveryResult.Delivered -> diagnostic
        is NotificationProviderDeliveryResult.RetryableFailure -> diagnostic
        is NotificationProviderDeliveryResult.PermanentFailure -> diagnostic
    }
}
