package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApnsProviderTokenSourceTest {
    @Test
    fun `provider token uses ES256 P1363 and never renders authentication material`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val source = ApnsProviderTokenSource(
            teamId = TEAM_ID,
            keyId = KEY_ID,
            privateKey = pair.private as ECPrivateKey,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val token = source.authorization().use { authorization ->
            authorization.withChars { chars -> String(chars) }
        }
        val sections = token.split('.')

        assertEquals(3, sections.size)
        assertEquals("{\"alg\":\"ES256\",\"kid\":\"$KEY_ID\"}", decode(sections[0]))
        assertEquals("{\"iss\":\"$TEAM_ID\",\"iat\":${NOW.epochSecond}}", decode(sections[1]))
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(pair.public)
        verifier.update("${sections[0]}.${sections[1]}".toByteArray(StandardCharsets.US_ASCII))
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(sections[2])))

        assertFalse(source.toString().contains(TEAM_ID))
        assertFalse(source.toString().contains(KEY_ID))
        assertFalse(source.toString().contains(token))
        source.close()
    }

    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII)

    private companion object {
        const val TEAM_ID = "TEAMID1234"
        const val KEY_ID = "KEYID12345"
        val NOW: Instant = Instant.parse("2026-08-20T15:30:00Z")
    }
}
