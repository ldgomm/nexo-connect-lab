package com.premierdarkcoffee.nexo.connect.lab.application.presence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

data class PresenceLeaseTarget(
    val subjectRef: String,
    val actorType: ConnectActorType,
    val platformScopeRef: String,
    val deviceRef: String,
) {
    init {
        requireBoundedRef(subjectRef, "subjectRef", MAX_SUBJECT_REF_BYTES)
        requireBoundedRef(platformScopeRef, "platformScopeRef", MAX_SCOPE_REF_BYTES)
        require(deviceRef.matches(DEVICE_REF_PATTERN)) {
            "deviceRef must be a bounded opaque server reference"
        }
    }

    private fun requireBoundedRef(value: String, name: String, maximumBytes: Int) {
        require(value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= maximumBytes) {
            "$name must be non-blank, NUL-free, and bounded"
        }
    }

    private companion object {
        const val MAX_SUBJECT_REF_BYTES = 256
        const val MAX_SCOPE_REF_BYTES = 128
        val DEVICE_REF_PATTERN = Regex("device_[A-Za-z0-9_-]{32}")
    }

    fun subjectTarget(): PresenceSubjectTarget = PresenceSubjectTarget(
        subjectRef = subjectRef,
        actorType = actorType,
        platformScopeRef = platformScopeRef,
    )
}

data class PresenceLeaseHandle(val target: PresenceLeaseTarget, val ownerInstanceRef: String, val leaseRef: String)

sealed interface PresenceLeaseAcquireResult {
    data class Acquired(val handle: PresenceLeaseHandle) : PresenceLeaseAcquireResult

    data object Unavailable : PresenceLeaseAcquireResult
}

enum class PresenceLeaseMutationResult {
    APPLIED,
    NOT_OWNER,
    UNAVAILABLE,
}

fun interface PresenceLeaseRefFactory {
    fun create(): String
}

class SecurePresenceLeaseRefFactory(private val secureRandom: SecureRandom = SecureRandom()) :
    PresenceLeaseRefFactory {
    override fun create(): String {
        val entropy = ByteArray(ENTROPY_BYTES)
        secureRandom.nextBytes(entropy)
        return "lease_${Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)}"
    }

    private companion object {
        const val ENTROPY_BYTES = 24
    }
}

interface EphemeralPresenceLeaseStore : AutoCloseable {
    val refreshInterval: Duration

    suspend fun acquire(target: PresenceLeaseTarget): PresenceLeaseAcquireResult

    suspend fun refresh(handle: PresenceLeaseHandle): PresenceLeaseMutationResult

    suspend fun release(handle: PresenceLeaseHandle): PresenceLeaseMutationResult
}
