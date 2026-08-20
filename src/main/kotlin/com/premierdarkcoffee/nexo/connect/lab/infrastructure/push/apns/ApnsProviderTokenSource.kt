package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

class ApnsAuthorization internal constructor(value: CharArray) : AutoCloseable {
    private var material: CharArray? = value.copyOf()

    internal fun <T> withChars(action: (CharArray) -> T): T {
        val copy = checkNotNull(material) { "APNs authorization is closed" }.copyOf()
        return try {
            action(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    override fun close() {
        material?.fill('\u0000')
        material = null
    }

    override fun toString(): String = "ApnsAuthorization([REDACTED])"
}

fun interface ApnsAuthorizationSource {
    fun authorization(): ApnsAuthorization

    fun invalidate() = Unit
}

class ApnsProviderTokenSource(
    private val teamId: String,
    private val keyId: String,
    private val privateKey: ECPrivateKey,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheDuration: Duration = Duration.ofMinutes(50),
) : ApnsAuthorizationSource,
    AutoCloseable {
    private val lock = Any()
    private var cached: CachedProviderToken? = null

    init {
        require(teamId.matches(APPLE_IDENTIFIER_PATTERN)) {
            "APNs team ID must contain ten uppercase letters or digits"
        }
        require(keyId.matches(APPLE_IDENTIFIER_PATTERN)) {
            "APNs key ID must contain ten uppercase letters or digits"
        }
        require(privateKey.params.curve.field.fieldSize == P256_FIELD_BITS) {
            "APNs provider authentication requires a P-256 private key"
        }
        require(cacheDuration in MIN_CACHE_DURATION..MAX_CACHE_DURATION) {
            "APNs provider token cache duration must stay below the one-hour Apple limit"
        }
    }

    override fun authorization(): ApnsAuthorization = synchronized(lock) {
        val now = clock.instant()
        val current = cached
        if (current != null && current.isValidAt(now)) {
            return@synchronized ApnsAuthorization(current.value)
        }

        current?.close()
        val generated = generate(now)
        cached = generated
        ApnsAuthorization(generated.value)
    }

    private fun generate(now: Instant): CachedProviderToken {
        val header = "{\"alg\":\"ES256\",\"kid\":\"$keyId\"}".toByteArray(StandardCharsets.US_ASCII)
        val claims = "{\"iss\":\"$teamId\",\"iat\":${now.epochSecond}}".toByteArray(StandardCharsets.US_ASCII)
        val encodedHeader = BASE64_URL_ENCODER.encode(header)
        val encodedClaims = BASE64_URL_ENCODER.encode(claims)
        val signingInput = joinWithDot(encodedHeader, encodedClaims)
        var signature: ByteArray? = null
        var encodedSignature: ByteArray? = null
        var tokenBytes: ByteArray? = null
        try {
            val signer = Signature.getInstance(ES256_P1363_ALGORITHM)
            signer.initSign(privateKey)
            signer.update(signingInput)
            val generatedSignature = signer.sign()
            signature = generatedSignature
            require(generatedSignature.size == ES256_SIGNATURE_BYTES) { "Unexpected ES256 signature length" }
            val generatedEncodedSignature = BASE64_URL_ENCODER.encode(generatedSignature)
            encodedSignature = generatedEncodedSignature
            val generatedTokenBytes = joinWithDot(signingInput, generatedEncodedSignature)
            tokenBytes = generatedTokenBytes
            val tokenChars = CharArray(generatedTokenBytes.size) { index ->
                generatedTokenBytes[index].toInt().toChar()
            }
            return CachedProviderToken(
                value = tokenChars,
                issuedAt = now,
                expiresAt = now.plus(cacheDuration),
            )
        } finally {
            header.fill(0)
            claims.fill(0)
            encodedHeader.fill(0)
            encodedClaims.fill(0)
            signingInput.fill(0)
            signature?.fill(0)
            encodedSignature?.fill(0)
            tokenBytes?.fill(0)
        }
    }

    override fun close() {
        invalidate()
    }

    override fun invalidate() {
        synchronized(lock) {
            cached?.close()
            cached = null
        }
    }

    override fun toString(): String =
        "ApnsProviderTokenSource(teamId=<redacted>, keyId=<redacted>, privateKey=<redacted>, token=<redacted>)"

    private class CachedProviderToken(
        val value: CharArray,
        private val issuedAt: Instant,
        private val expiresAt: Instant,
    ) : AutoCloseable {
        fun isValidAt(now: Instant): Boolean = !now.isBefore(issuedAt) && now.isBefore(expiresAt)

        override fun close() {
            value.fill('\u0000')
        }
    }

    private companion object {
        const val P256_FIELD_BITS = 256
        const val ES256_SIGNATURE_BYTES = 64
        const val ES256_P1363_ALGORITHM = "SHA256withECDSAinP1363Format"
        val APPLE_IDENTIFIER_PATTERN: Regex = Regex("[A-Z0-9]{10}")
        val MIN_CACHE_DURATION: Duration = Duration.ofMinutes(1)
        val MAX_CACHE_DURATION: Duration = Duration.ofMinutes(55)
        val BASE64_URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun joinWithDot(left: ByteArray, right: ByteArray): ByteArray =
            ByteArray(left.size + 1 + right.size).also { joined ->
                left.copyInto(joined)
                joined[left.size] = '.'.code.toByte()
                right.copyInto(joined, left.size + 1)
            }
    }
}

object ApnsPrivateKeyLoader {
    private val BEGIN_MARKER = ("-----BEGIN " + "PRIVATE KEY-----").toByteArray(StandardCharsets.US_ASCII)
    private val END_MARKER = "-----END PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_PRIVATE_KEY_FILE_BYTES = 16 * 1024

    fun load(path: Path): ECPrivateKey {
        require(Files.isRegularFile(path)) { "APNs private key path must reference a regular file" }
        require(Files.size(path) in 1..MAX_PRIVATE_KEY_FILE_BYTES.toLong()) {
            "APNs private key file is empty or exceeds the limit"
        }

        val pem = Files.readAllBytes(path)
        var encoded: ByteArray? = null
        var der: ByteArray? = null
        try {
            val begin = pem.indexOf(BEGIN_MARKER)
            val end = pem.indexOf(END_MARKER, begin + BEGIN_MARKER.size)
            require(begin >= 0 && end > begin) { "APNs private key must use unencrypted PKCS#8 PEM" }

            val contentStart = begin + BEGIN_MARKER.size
            encoded = ByteArray(end - contentStart)
            var encodedSize = 0
            for (index in contentStart until end) {
                val byte = pem[index]
                if (!byte.toInt().toChar().isWhitespace()) {
                    encoded[encodedSize++] = byte
                }
            }
            require(encodedSize > 0) { "APNs private key payload is empty" }

            val compact = encoded.copyOf(encodedSize)
            try {
                der = Base64.getDecoder().decode(compact)
            } finally {
                compact.fill(0)
            }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            require(key is ECPrivateKey && key.params.curve.field.fieldSize == 256) {
                "APNs private key must be an EC P-256 key"
            }
            return key
        } finally {
            pem.fill(0)
            encoded?.fill(0)
            der?.fill(0)
        }
    }

    private fun ByteArray.indexOf(pattern: ByteArray, startAt: Int = 0): Int {
        if (pattern.isEmpty() || startAt < 0) return -1
        for (candidate in startAt..size - pattern.size) {
            var matches = true
            for (offset in pattern.indices) {
                if (this[candidate + offset] != pattern[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return candidate
        }
        return -1
    }
}
