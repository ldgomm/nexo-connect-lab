package com.premierdarkcoffee.nexo.connect.lab.domain.message

import java.security.MessageDigest

@JvmInline
value class MessagePayloadFingerprint private constructor(
    val value: String,
) {
    companion object {
        fun fromPersistedValue(value: String): MessagePayloadFingerprint {
            require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
                "Persisted payload fingerprint is invalid"
            }
            return MessagePayloadFingerprint(value)
        }

        fun forText(body: TextMessageBody): MessagePayloadFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("TEXT\n".toByteArray(Charsets.UTF_8))
            val hash = digest.digest(body.value.toByteArray(Charsets.UTF_8))
            return MessagePayloadFingerprint(
                "sha256:" + hash.joinToString("") { byte ->
                    byte.toUByte().toString(radix = 16).padStart(2, '0')
                },
            )
        }
    }
}
