package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import com.premierdarkcoffee.nexo.connect.lab.domain.push.requirePushReference
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PushTokenProtectionContext(
    val platformScopeRef: String,
    val organizationScopeRef: String?,
    val businessScopeRef: String?,
    val subjectRef: String,
    val actorType: ConnectActorType,
    val application: PushApplication,
    val provider: PushProvider,
    val environment: PushEnvironment,
) {
    init {
        requirePushReference(platformScopeRef, "platformScopeRef")
        requirePushReference(subjectRef, "subjectRef")
        organizationScopeRef?.let { requirePushReference(it, "organizationScopeRef") }
        businessScopeRef?.let { requirePushReference(it, "businessScopeRef") }
        require(application.owns(actorType)) { "Push application does not own actor type" }
        when (actorType) {
            ConnectActorType.SUPERADMIN,
            ConnectActorType.CLIENT,
            -> require(organizationScopeRef == null && businessScopeRef == null) {
                "Platform and participant push contexts must not carry organization scopes"
            }

            ConnectActorType.ADMIN ->
                require(organizationScopeRef != null && businessScopeRef == null) {
                    "Admin push contexts require only an organization scope"
                }

            ConnectActorType.BUSINESS ->
                require(organizationScopeRef != null && businessScopeRef != null) {
                    "Business push contexts require organization and business scopes"
                }
        }
    }

    internal fun authenticatedData(): ByteArray = listOf(
        CONTEXT_VERSION,
        platformScopeRef,
        organizationScopeRef.orEmpty(),
        businessScopeRef.orEmpty(),
        subjectRef,
        actorType.name,
        application.name,
        provider.name,
        environment.name,
    ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val CONTEXT_VERSION = "nexo-connect-push-token-v1"
    }
}

class ProtectedPushToken internal constructor(
    internal val fingerprint: String,
    internal val keyVersion: Int,
    nonce: ByteArray,
    ciphertext: ByteArray,
) : AutoCloseable {
    private var nonceMaterial: ByteArray? = nonce.copyOf()
    private var ciphertextMaterial: ByteArray? = ciphertext.copyOf()

    internal fun nonceCopy(): ByteArray = checkNotNull(nonceMaterial) { "Protected push token is closed" }.copyOf()

    internal fun ciphertextCopy(): ByteArray =
        checkNotNull(ciphertextMaterial) { "Protected push token is closed" }.copyOf()

    override fun close() {
        nonceMaterial?.fill(0)
        ciphertextMaterial?.fill(0)
        nonceMaterial = null
        ciphertextMaterial = null
    }

    override fun toString(): String = "ProtectedPushToken(keyVersion=$keyVersion, [REDACTED])"
}

class ProtectedPushTokenCodec(
    private val activeKeyVersion: Int,
    encryptionKeys: Map<Int, ByteArray>,
    fingerprintKey: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val lock = Any()
    private var encryptionKeyMaterial = encryptionKeys.mapValues { (_, value) -> value.copyOf() }
    private var fingerprintKeyMaterial: ByteArray? = fingerprintKey.copyOf()

    init {
        require(activeKeyVersion >= 1) { "activeKeyVersion must be positive" }
        require(encryptionKeys.isNotEmpty()) { "At least one encryption key is required" }
        require(encryptionKeys.keys.all { it >= 1 }) { "Encryption key versions must be positive" }
        require(encryptionKeys.values.all { it.size == AES_KEY_BYTES }) {
            "Push token encryption keys must be 256-bit"
        }
        require(activeKeyVersion in encryptionKeys) { "Active push token encryption key is missing" }
        require(fingerprintKey.size >= FINGERPRINT_KEY_MIN_BYTES) {
            "Push token fingerprint key must contain at least 256 bits"
        }
    }

    fun protect(secret: PushTokenSecret, context: PushTokenProtectionContext): ProtectedPushToken = synchronized(lock) {
        val encryptionKey = checkNotNull(encryptionKeyMaterial[activeKeyVersion]) {
            "Protected push token codec is closed"
        }
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        secret.withBytes { tokenBytes ->
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(encryptionKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            val authenticatedData = context.authenticatedData()
            try {
                cipher.updateAAD(authenticatedData)
                ProtectedPushToken(
                    fingerprint = fingerprint(TOKEN_FINGERPRINT_DOMAIN, tokenBytes),
                    keyVersion = activeKeyVersion,
                    nonce = nonce,
                    ciphertext = cipher.doFinal(tokenBytes),
                )
            } finally {
                authenticatedData.fill(0)
            }
        }
    }

    fun deviceFingerprint(deviceRef: String, context: PushTokenProtectionContext): String = synchronized(lock) {
        requirePushReference(deviceRef, "deviceRef")
        val contextBytes = context.authenticatedData()
        val deviceBytes = deviceRef.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(contextBytes.size + 1 + deviceBytes.size)
        try {
            contextBytes.copyInto(payload)
            deviceBytes.copyInto(payload, contextBytes.size + 1)
            fingerprint(DEVICE_FINGERPRINT_DOMAIN, payload)
        } finally {
            contextBytes.fill(0)
            deviceBytes.fill(0)
            payload.fill(0)
        }
    }

    internal fun revealForDelivery(
        protectedToken: ProtectedPushToken,
        context: PushTokenProtectionContext,
    ): ByteArray = synchronized(lock) {
        val encryptionKey = checkNotNull(encryptionKeyMaterial[protectedToken.keyVersion]) {
            "Protected push token encryption key is unavailable"
        }
        val nonce = protectedToken.nonceCopy()
        val ciphertext = protectedToken.ciphertextCopy()
        val authenticatedData = context.authenticatedData()
        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encryptionKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(authenticatedData)
            cipher.doFinal(ciphertext)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            authenticatedData.fill(0)
        }
    }

    private fun fingerprint(domain: ByteArray, payload: ByteArray): String {
        val key = checkNotNull(fingerprintKeyMaterial) { "Protected push token codec is closed" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        mac.update(domain)
        mac.update(0.toByte())
        return mac.doFinal(payload).toHex()
    }

    override fun close() {
        synchronized(lock) {
            encryptionKeyMaterial.values.forEach { it.fill(0) }
            encryptionKeyMaterial = emptyMap()
            fingerprintKeyMaterial?.fill(0)
            fingerprintKeyMaterial = null
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ProtectedPushTokenCodec {
            fun required(name: String): String = environment[name]?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing required environment variable: $name")

            val activeKeyVersion =
                required("CONNECT_LAB_PUSH_TOKEN_KEY_VERSION").toIntOrNull()
                    ?: error("CONNECT_LAB_PUSH_TOKEN_KEY_VERSION must be an integer")
            val encryptionKey =
                decodeKey(
                    "CONNECT_LAB_PUSH_TOKEN_ENCRYPTION_KEY_B64",
                    required("CONNECT_LAB_PUSH_TOKEN_ENCRYPTION_KEY_B64"),
                )
            val fingerprintKey =
                decodeKey(
                    "CONNECT_LAB_PUSH_TOKEN_FINGERPRINT_KEY_B64",
                    required("CONNECT_LAB_PUSH_TOKEN_FINGERPRINT_KEY_B64"),
                )
            return try {
                ProtectedPushTokenCodec(
                    activeKeyVersion = activeKeyVersion,
                    encryptionKeys = mapOf(activeKeyVersion to encryptionKey),
                    fingerprintKey = fingerprintKey,
                )
            } finally {
                encryptionKey.fill(0)
                fingerprintKey.fill(0)
            }
        }

        private fun decodeKey(name: String, encoded: String): ByteArray {
            val decoded =
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("$name must be valid Base64")
                }
            if (decoded.size != AES_KEY_BYTES) {
                decoded.fill(0)
                throw IllegalArgumentException("$name must decode to 256 bits")
            }
            return decoded
        }

        private const val AES_KEY_BYTES = 32
        private const val FINGERPRINT_KEY_MIN_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_ALGORITHM = "AES"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private val TOKEN_FINGERPRINT_DOMAIN: ByteArray = "push-token".toByteArray(StandardCharsets.US_ASCII)
        private val DEVICE_FINGERPRINT_DOMAIN: ByteArray = "push-device".toByteArray(StandardCharsets.US_ASCII)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
