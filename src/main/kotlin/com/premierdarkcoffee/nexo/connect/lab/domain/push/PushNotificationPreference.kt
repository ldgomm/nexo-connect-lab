package com.premierdarkcoffee.nexo.connect.lab.domain.push

import java.time.Instant

enum class NotificationLockScreenPrivacy {
    HIDDEN,
    GENERIC,
}

enum class NotificationBadgeMode {
    UNCHANGED,
    SET_ONE,
}

enum class NotificationQuietMode {
    OFF,
    ON,
}

enum class NotificationPresentationMode {
    BACKGROUND_ONLY,
    GENERIC_ALERT,
}

data class PushNotificationPreference(
    val conversationRef: String,
    val registrationRef: String,
    val muted: Boolean,
    val lockScreenPrivacy: NotificationLockScreenPrivacy,
    val badgeMode: NotificationBadgeMode,
    val quietMode: NotificationQuietMode,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        requirePushReference(conversationRef, "conversationRef")
        requirePushReference(registrationRef, "registrationRef")
        require(version >= 1) { "version must be positive" }
    }

    fun snapshot(): NotificationPreferenceSnapshot = NotificationPreferenceSnapshot(
        muted = muted,
        lockScreenPrivacy = lockScreenPrivacy,
        badgeMode = badgeMode,
        quietMode = quietMode,
    )
}

data class NotificationPreferenceSnapshot(
    val muted: Boolean,
    val lockScreenPrivacy: NotificationLockScreenPrivacy,
    val badgeMode: NotificationBadgeMode,
    val quietMode: NotificationQuietMode,
) {
    companion object {
        val DEFAULT: NotificationPreferenceSnapshot = NotificationPreferenceSnapshot(
            muted = false,
            lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
            badgeMode = NotificationBadgeMode.SET_ONE,
            quietMode = NotificationQuietMode.OFF,
        )
    }
}

data class NotificationPresentation(val mode: NotificationPresentationMode, val badgeMode: NotificationBadgeMode)

sealed interface NotificationPolicyDecision {
    data object SuppressedMuted : NotificationPolicyDecision

    data class Deliver(val presentation: NotificationPresentation) : NotificationPolicyDecision
}
