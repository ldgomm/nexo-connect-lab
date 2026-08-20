package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPolicyDecision
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPreferenceSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPresentationMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class PushNotificationPolicyTest {
    private val policy = PushNotificationPolicy()

    @Test
    fun `muted preference suppresses notification delivery`() {
        val decision = policy.decide(preference(muted = true))

        assertSame(NotificationPolicyDecision.SuppressedMuted, decision)
    }

    @Test
    fun `generic privacy emits a fixed generic alert with the configured badge policy`() {
        val delivery = assertIs<NotificationPolicyDecision.Deliver>(policy.decide(preference()))

        assertEquals(NotificationPresentationMode.GENERIC_ALERT, delivery.presentation.mode)
        assertEquals(NotificationBadgeMode.SET_ONE, delivery.presentation.badgeMode)
    }

    @Test
    fun `hidden lock screen privacy removes the alert while preserving the badge contract`() {
        val delivery = assertIs<NotificationPolicyDecision.Deliver>(
            policy.decide(preference(lockScreenPrivacy = NotificationLockScreenPrivacy.HIDDEN)),
        )

        assertEquals(NotificationPresentationMode.BACKGROUND_ONLY, delivery.presentation.mode)
        assertEquals(NotificationBadgeMode.SET_ONE, delivery.presentation.badgeMode)
    }

    @Test
    fun `quiet mode hook removes the alert and leaves the badge unchanged`() {
        val forcedQuietPolicy = PushNotificationPolicy(NotificationQuietModeHook { true })
        val delivery = assertIs<NotificationPolicyDecision.Deliver>(
            forcedQuietPolicy.decide(preference(quietMode = NotificationQuietMode.OFF)),
        )

        assertEquals(NotificationPresentationMode.BACKGROUND_ONLY, delivery.presentation.mode)
        assertEquals(NotificationBadgeMode.UNCHANGED, delivery.presentation.badgeMode)
    }

    private fun preference(
        muted: Boolean = false,
        lockScreenPrivacy: NotificationLockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
        badgeMode: NotificationBadgeMode = NotificationBadgeMode.SET_ONE,
        quietMode: NotificationQuietMode = NotificationQuietMode.OFF,
    ): NotificationPreferenceSnapshot = NotificationPreferenceSnapshot(
        muted = muted,
        lockScreenPrivacy = lockScreenPrivacy,
        badgeMode = badgeMode,
        quietMode = quietMode,
    )
}
