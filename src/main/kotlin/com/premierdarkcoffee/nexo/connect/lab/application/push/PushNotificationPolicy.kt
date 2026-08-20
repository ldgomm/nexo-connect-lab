package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPolicyDecision
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPreferenceSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPresentation
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPresentationMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode

fun interface NotificationQuietModeHook {
    fun isQuiet(preference: NotificationPreferenceSnapshot): Boolean

    companion object {
        val PREFERENCE: NotificationQuietModeHook = NotificationQuietModeHook { preference ->
            preference.quietMode == NotificationQuietMode.ON
        }
    }
}

class PushNotificationPolicy(
    private val quietModeHook: NotificationQuietModeHook = NotificationQuietModeHook.PREFERENCE,
) {
    fun decide(preference: NotificationPreferenceSnapshot): NotificationPolicyDecision {
        if (preference.muted) return NotificationPolicyDecision.SuppressedMuted

        val quiet = quietModeHook.isQuiet(preference)
        val presentationMode = if (
            quiet || preference.lockScreenPrivacy == NotificationLockScreenPrivacy.HIDDEN
        ) {
            NotificationPresentationMode.BACKGROUND_ONLY
        } else {
            NotificationPresentationMode.GENERIC_ALERT
        }
        val badgeMode = if (quiet) NotificationBadgeMode.UNCHANGED else preference.badgeMode

        return NotificationPolicyDecision.Deliver(
            NotificationPresentation(
                mode = presentationMode,
                badgeMode = badgeMode,
            ),
        )
    }
}
