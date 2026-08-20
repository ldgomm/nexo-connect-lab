package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import java.time.Duration

enum class NotificationDeliveryDiagnostic {
    ACCEPTED,
    REGISTRATION_NOT_ACTIVE,
    INVALID_REGISTRATION,
    RATE_LIMITED,
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    AUTHENTICATION_REJECTED,
    REQUEST_REJECTED,
    UNSUPPORTED_ENVIRONMENT,
    ADAPTER_FAILURE,
}

sealed interface NotificationProviderDeliveryResult {
    data class Delivered(val diagnostic: NotificationDeliveryDiagnostic = NotificationDeliveryDiagnostic.ACCEPTED) :
        NotificationProviderDeliveryResult

    data class RetryableFailure(
        val errorCode: NotificationFailureCode,
        val diagnostic: NotificationDeliveryDiagnostic,
    ) : NotificationProviderDeliveryResult {
        init {
            require(errorCode in RETRYABLE_NOTIFICATION_FAILURE_CODES) {
                "Retryable provider delivery requires a retryable failure code"
            }
        }
    }

    data class PermanentFailure(
        val errorCode: NotificationFailureCode,
        val diagnostic: NotificationDeliveryDiagnostic,
        val invalidTokenVersion: Long? = null,
    ) : NotificationProviderDeliveryResult {
        init {
            require(errorCode in PERMANENT_NOTIFICATION_FAILURE_CODES) {
                "Permanent provider delivery requires a permanent failure code"
            }
            require(invalidTokenVersion == null || invalidTokenVersion >= 1) {
                "invalidTokenVersion must be positive"
            }
            require(invalidTokenVersion == null || errorCode == NotificationFailureCode.REGISTRATION_REVOKED) {
                "Only an invalid registration may request token retirement"
            }
        }
    }
}

fun interface NotificationProvider {
    fun deliver(intent: NotificationOutboxIntent): NotificationProviderDeliveryResult
}

fun interface NotificationRetryPolicy {
    fun delayFor(errorCode: NotificationFailureCode, attemptCount: Int): Duration
}

class BoundedExponentialNotificationRetryPolicy(
    private val timeoutBase: Duration = Duration.ofSeconds(15),
    private val unavailableBase: Duration = Duration.ofSeconds(30),
    private val rateLimitedBase: Duration = Duration.ofMinutes(1),
    private val maximum: Duration = Duration.ofMinutes(15),
) : NotificationRetryPolicy {
    init {
        require(timeoutBase.isPositiveAndWithin(maximum))
        require(unavailableBase.isPositiveAndWithin(maximum))
        require(rateLimitedBase.isPositiveAndWithin(maximum))
        require(!maximum.isZero && !maximum.isNegative)
    }

    override fun delayFor(errorCode: NotificationFailureCode, attemptCount: Int): Duration {
        require(errorCode in RETRYABLE_NOTIFICATION_FAILURE_CODES)
        require(attemptCount >= 1) { "attemptCount must be positive" }

        val base = when (errorCode) {
            NotificationFailureCode.PROVIDER_TIMEOUT -> timeoutBase
            NotificationFailureCode.PROVIDER_RATE_LIMITED -> rateLimitedBase
            NotificationFailureCode.PROVIDER_UNAVAILABLE -> unavailableBase
            else -> error("Unsupported retryable notification failure code")
        }

        var delay = base
        repeat((attemptCount - 1).coerceAtMost(MAX_EXPONENTIAL_STEPS)) {
            delay = if (delay > maximum.dividedBy(2)) maximum else delay.multipliedBy(2)
        }
        return delay.coerceAtMost(maximum)
    }

    private fun Duration.isPositiveAndWithin(limit: Duration): Boolean = !isZero && !isNegative && this <= limit

    private companion object {
        const val MAX_EXPONENTIAL_STEPS = 10
    }
}

enum class NotificationDeliverySettlement {
    DELIVERED,
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    LEASE_DEFERRED,
}

data class SanitizedNotificationDeliveryEvent(
    val intentRef: String,
    val provider: PushProvider,
    val application: PushApplication,
    val environment: PushEnvironment,
    val attemptCount: Int,
    val settlement: NotificationDeliverySettlement,
    val diagnostic: NotificationDeliveryDiagnostic,
) {
    init {
        require(intentRef.isNotBlank())
        require(attemptCount >= 1)
    }

    fun toLogLine(): String = "CONNECT_NOTIFICATION_DELIVERY" +
        " intentRef=$intentRef" +
        " provider=${provider.name}" +
        " application=${application.name}" +
        " environment=${environment.name}" +
        " attemptCount=$attemptCount" +
        " settlement=${settlement.name}" +
        " diagnostic=${diagnostic.name}"
}

fun interface NotificationDeliveryObserver {
    fun record(event: SanitizedNotificationDeliveryEvent)

    companion object {
        val NOOP: NotificationDeliveryObserver = NotificationDeliveryObserver { }
    }
}

internal val RETRYABLE_NOTIFICATION_FAILURE_CODES: Set<NotificationFailureCode> = setOf(
    NotificationFailureCode.PROVIDER_TIMEOUT,
    NotificationFailureCode.PROVIDER_RATE_LIMITED,
    NotificationFailureCode.PROVIDER_UNAVAILABLE,
)

internal val PERMANENT_NOTIFICATION_FAILURE_CODES: Set<NotificationFailureCode> = setOf(
    NotificationFailureCode.REGISTRATION_REVOKED,
    NotificationFailureCode.PROVIDER_REJECTED,
)
