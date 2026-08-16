package com.premierdarkcoffee.nexo.connect.lab.domain.push

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class PushApplication {
    NEXO_CLIENT_IOS,
    NEXO_BUSINESS_IOS,
    NEXO_ADMIN_IOS,
    ;

    fun owns(actorType: ConnectActorType): Boolean = when (this) {
        NEXO_CLIENT_IOS -> actorType == ConnectActorType.CLIENT
        NEXO_BUSINESS_IOS -> actorType == ConnectActorType.BUSINESS
        NEXO_ADMIN_IOS -> actorType == ConnectActorType.ADMIN || actorType == ConnectActorType.SUPERADMIN
    }
}

enum class PushProvider {
    APNS,
}

enum class PushEnvironment {
    SANDBOX,
    PRODUCTION,
}

enum class PushDeviceRegistrationStatus {
    ACTIVE,
    REVOKED,
}

data class PushDeviceRegistration(
    val registrationRef: String,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
    val status: PushDeviceRegistrationStatus,
    val tokenVersion: Long,
    val createdAt: Instant,
    val rotatedAt: Instant?,
    val revokedAt: Instant?,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        requirePushReference(registrationRef, "registrationRef")
        require(tokenVersion >= 1) { "tokenVersion must be positive" }
        require(version >= tokenVersion) { "version must cover every token version" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
        require(rotatedAt?.isBefore(createdAt) != true) { "rotatedAt must not precede createdAt" }
        require(revokedAt?.isBefore(createdAt) != true) { "revokedAt must not precede createdAt" }
        require((tokenVersion == 1L) == (rotatedAt == null)) {
            "rotatedAt must match tokenVersion"
        }
        require((status == PushDeviceRegistrationStatus.REVOKED) == (revokedAt != null)) {
            "revokedAt must match registration status"
        }
    }
}

internal fun requirePushReference(value: String, fieldName: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_PUSH_REFERENCE_UTF8_BYTES) {
        "$fieldName exceeds the push reference limit"
    }
}

internal const val MAX_PUSH_REFERENCE_UTF8_BYTES: Int = 256
