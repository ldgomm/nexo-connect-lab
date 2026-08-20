package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import java.time.Instant

data class RetireInvalidPushRegistrationRequest(
    val intent: NotificationOutboxIntent,
    val expectedTokenVersion: Long,
    val now: Instant,
) {
    init {
        require(intent.status == NotificationOutboxStatus.CLAIMED) {
            "Invalid-token retirement requires a claimed notification intent"
        }
        require(expectedTokenVersion >= 1) { "expectedTokenVersion must be positive" }
        require(!now.isBefore(intent.createdAt)) { "now must not precede the notification intent" }
    }
}

sealed interface InvalidPushRegistrationRetirementResult {
    data object Retired : InvalidPushRegistrationRetirementResult

    data object TokenRotated : InvalidPushRegistrationRetirementResult

    data object NotFoundOrDenied : InvalidPushRegistrationRetirementResult
}

fun interface InvalidPushRegistrationRetirer {
    fun retire(request: RetireInvalidPushRegistrationRequest): InvalidPushRegistrationRetirementResult

    companion object {
        val UNAVAILABLE: InvalidPushRegistrationRetirer =
            InvalidPushRegistrationRetirer { error("Invalid push registration retirement is unavailable") }
    }
}
