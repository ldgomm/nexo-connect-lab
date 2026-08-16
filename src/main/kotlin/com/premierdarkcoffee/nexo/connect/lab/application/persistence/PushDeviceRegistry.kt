package com.premierdarkcoffee.nexo.connect.lab.application.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushDeviceRegistration
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import com.premierdarkcoffee.nexo.connect.lab.domain.push.requirePushReference

data class RegisterPushDeviceRequest(
    val principal: ConnectPrincipal,
    val deviceRef: String,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
    val token: PushTokenSecret,
) {
    init {
        requirePushReference(deviceRef, "deviceRef")
        require(application.owns(principal.actorType)) {
            "Push application does not own the principal actor type"
        }
    }
}

data class RotatePushDeviceRequest(
    val principal: ConnectPrincipal,
    val registrationRef: String,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
    val expectedVersion: Long,
    val token: PushTokenSecret,
) {
    init {
        requirePushReference(registrationRef, "registrationRef")
        require(application.owns(principal.actorType)) {
            "Push application does not own the principal actor type"
        }
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
    }
}

data class RevokePushDeviceRequest(
    val principal: ConnectPrincipal,
    val registrationRef: String,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
    val expectedVersion: Long,
) {
    init {
        requirePushReference(registrationRef, "registrationRef")
        require(application.owns(principal.actorType)) {
            "Push application does not own the principal actor type"
        }
        require(expectedVersion >= 1) { "expectedVersion must be positive" }
    }
}

data class ListActivePushDevicesRequest(
    val principal: ConnectPrincipal,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
) {
    init {
        require(application.owns(principal.actorType)) {
            "Push application does not own the principal actor type"
        }
    }
}

sealed interface RegisterPushDeviceResult {
    data class Registered(val registration: PushDeviceRegistration, val created: Boolean) : RegisterPushDeviceResult

    data object NotFoundOrDenied : RegisterPushDeviceResult
}

sealed interface RotatePushDeviceResult {
    data class Rotated(val registration: PushDeviceRegistration, val changed: Boolean) : RotatePushDeviceResult

    data object NotFoundOrDenied : RotatePushDeviceResult
}

sealed interface RevokePushDeviceResult {
    data class Revoked(val registration: PushDeviceRegistration) : RevokePushDeviceResult

    data object NotFoundOrDenied : RevokePushDeviceResult
}

data class ActivePushDevices(val registrations: List<PushDeviceRegistration>)

interface PushDeviceRegistry {
    fun register(request: RegisterPushDeviceRequest): RegisterPushDeviceResult

    fun rotate(request: RotatePushDeviceRequest): RotatePushDeviceResult

    fun revoke(request: RevokePushDeviceRequest): RevokePushDeviceResult

    fun listActive(request: ListActivePushDevicesRequest): ActivePushDevices
}
