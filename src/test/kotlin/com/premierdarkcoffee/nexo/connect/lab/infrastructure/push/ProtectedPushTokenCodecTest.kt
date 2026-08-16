package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ProtectedPushTokenCodecTest {
    @Test
    fun `protects tokens with scoped authenticated encryption and redacted values`() {
        codec().use { codec ->
            PushTokenSecret.fromBytes(TOKEN_BYTES).use { secret ->
                codec.protect(secret, CLIENT_CONTEXT).use { protectedToken ->
                    val plaintext = codec.revealForDelivery(protectedToken, CLIENT_CONTEXT)
                    try {
                        assertContentEquals(TOKEN_BYTES, plaintext)
                    } finally {
                        plaintext.fill(0)
                    }

                    assertFalse(protectedToken.ciphertextCopy().containsSubsequence(TOKEN_BYTES))
                    assertFalse(protectedToken.toString().contains(TOKEN_TEXT))
                    assertFalse(secret.toString().contains(TOKEN_TEXT))

                    assertFailsWith<AEADBadTagException> {
                        codec.revealForDelivery(
                            protectedToken,
                            CLIENT_CONTEXT.copy(subjectRef = "client-subject-other"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `uses stable opaque fingerprints without leaking owner or device references`() {
        codec().use { codec ->
            PushTokenSecret.fromBytes(TOKEN_BYTES).use { firstSecret ->
                PushTokenSecret.fromBytes(TOKEN_BYTES).use { secondSecret ->
                    val first = codec.protect(firstSecret, CLIENT_CONTEXT)
                    val second = codec.protect(secondSecret, CLIENT_CONTEXT.copy(subjectRef = "client-other"))
                    try {
                        assertEquals(first.fingerprint, second.fingerprint)
                        assertEquals(64, first.fingerprint.length)
                        assertFalse(first.fingerprint.contains(TOKEN_TEXT))
                    } finally {
                        first.close()
                        second.close()
                    }
                }
            }

            val firstDevice = codec.deviceFingerprint("device-private-ref", CLIENT_CONTEXT)
            val secondDevice =
                codec.deviceFingerprint(
                    "device-private-ref",
                    CLIENT_CONTEXT.copy(subjectRef = "client-other"),
                )
            assertNotEquals(firstDevice, secondDevice)
            assertFalse(firstDevice.contains("device-private-ref"))
        }
    }

    @Test
    fun `rejects invalid actor application and scope ownership`() {
        assertFailsWith<IllegalArgumentException> {
            CLIENT_CONTEXT.copy(application = PushApplication.NEXO_ADMIN_IOS)
        }
        assertFailsWith<IllegalArgumentException> {
            CLIENT_CONTEXT.copy(organizationScopeRef = "organization-forbidden")
        }
        assertFailsWith<IllegalArgumentException> {
            CLIENT_CONTEXT.copy(
                actorType = ConnectActorType.BUSINESS,
                application = PushApplication.NEXO_BUSINESS_IOS,
                organizationScopeRef = "organization-1",
                businessScopeRef = null,
            )
        }
    }

    private fun codec(): ProtectedPushTokenCodec = ProtectedPushTokenCodec(
        activeKeyVersion = 7,
        encryptionKeys = mapOf(7 to ByteArray(32) { index -> (index + 1).toByte() }),
        fingerprintKey = ByteArray(32) { index -> (index + 33).toByte() },
    )

    companion object {
        private const val TOKEN_TEXT = "device-token-secret-0123456789"
        private val TOKEN_BYTES = TOKEN_TEXT.toByteArray()

        private val CLIENT_CONTEXT =
            PushTokenProtectionContext(
                platformScopeRef = "platform-1",
                organizationScopeRef = null,
                businessScopeRef = null,
                subjectRef = "client-subject-1",
                actorType = ConnectActorType.CLIENT,
                application = PushApplication.NEXO_CLIENT_IOS,
                provider = PushProvider.APNS,
                environment = PushEnvironment.SANDBOX,
            )
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty() || candidate.size > size) return false
    return indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}
