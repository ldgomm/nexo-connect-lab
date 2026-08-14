package com.premierdarkcoffee.nexo.connect.lab.application.typing

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

data class TypingLeaseTarget(
    val subjectRef: String,
    val actorType: ConnectActorType,
    val platformScopeRef: String,
    val conversationRef: String,
    val deviceRef: String,
) {
    init {
        requireBounded(subjectRef, "subjectRef", 256)
        requireBounded(platformScopeRef, "platformScopeRef", 128)
        requireBounded(conversationRef, "conversationRef", 256)
        require(deviceRef.matches(Regex("device_[A-Za-z0-9_-]{32}"))) {
            "deviceRef must be a bounded opaque server reference"
        }
    }

    private fun requireBounded(value: String, name: String, maximumBytes: Int) {
        require(value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= maximumBytes) {
            "$name must be non-blank, NUL-free, and bounded"
        }
    }
}

data class TypingLeaseHandle(val target: TypingLeaseTarget, val ownerInstanceRef: String, val leaseRef: String)

sealed interface TypingLeaseAcquireResult {
    data class Acquired(val handle: TypingLeaseHandle, val expiresInMillis: Long) : TypingLeaseAcquireResult

    data object Unavailable : TypingLeaseAcquireResult
}

sealed interface TypingLeaseRefreshResult {
    data class Refreshed(val expiresInMillis: Long) : TypingLeaseRefreshResult

    data object NotOwner : TypingLeaseRefreshResult

    data object Unavailable : TypingLeaseRefreshResult
}

enum class TypingLeaseReleaseResult {
    APPLIED,
    NOT_OWNER,
    UNAVAILABLE,
}

fun interface TypingLeaseRefFactory {
    fun create(): String
}

class SecureTypingLeaseRefFactory(private val secureRandom: SecureRandom = SecureRandom()) : TypingLeaseRefFactory {
    override fun create(): String {
        val entropy = ByteArray(24)
        secureRandom.nextBytes(entropy)
        return "typing_${Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)}"
    }
}

interface EphemeralTypingLeaseStore : AutoCloseable {
    val leaseTtl: Duration

    suspend fun start(target: TypingLeaseTarget): TypingLeaseAcquireResult

    suspend fun refresh(handle: TypingLeaseHandle): TypingLeaseRefreshResult

    suspend fun stop(handle: TypingLeaseHandle): TypingLeaseReleaseResult
}
