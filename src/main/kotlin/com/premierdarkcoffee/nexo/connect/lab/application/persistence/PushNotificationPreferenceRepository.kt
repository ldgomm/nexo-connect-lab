package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushNotificationPreference
import com.premierdarkcoffee.nexo.connect.lab.domain.push.requirePushReference
import java.time.Instant

data class PutPushNotificationPreferenceRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val registrationRef: String,
    val muted: Boolean,
    val lockScreenPrivacy: NotificationLockScreenPrivacy,
    val badgeMode: NotificationBadgeMode,
    val quietMode: NotificationQuietMode,
    val expectedVersion: Long,
    val now: Instant,
) {
    init {
        require(principal.actorType == ConnectActorType.BUSINESS || principal.actorType == ConnectActorType.CLIENT) {
            "Push notification preferences require a business or client principal"
        }
        requirePushReference(conversationRef, "conversationRef")
        requirePushReference(registrationRef, "registrationRef")
        require(expectedVersion >= 0) { "expectedVersion must not be negative" }
    }
}

data class GetPushNotificationPreferenceRequest(
    val principal: ConnectPrincipal,
    val conversationRef: String,
    val registrationRef: String,
) {
    init {
        require(principal.actorType == ConnectActorType.BUSINESS || principal.actorType == ConnectActorType.CLIENT) {
            "Push notification preferences require a business or client principal"
        }
        requirePushReference(conversationRef, "conversationRef")
        requirePushReference(registrationRef, "registrationRef")
    }
}

sealed interface PutPushNotificationPreferenceResult {
    data class Updated(val preference: PushNotificationPreference, val created: Boolean) :
        PutPushNotificationPreferenceResult

    data object NotFoundOrDenied : PutPushNotificationPreferenceResult
}

sealed interface GetPushNotificationPreferenceResult {
    data class Found(val preference: PushNotificationPreference) : GetPushNotificationPreferenceResult

    data object NotFoundOrDenied : GetPushNotificationPreferenceResult
}

interface PushNotificationPreferenceRepository {
    fun put(request: PutPushNotificationPreferenceRequest): PutPushNotificationPreferenceResult

    fun get(request: GetPushNotificationPreferenceRequest): GetPushNotificationPreferenceResult
}
