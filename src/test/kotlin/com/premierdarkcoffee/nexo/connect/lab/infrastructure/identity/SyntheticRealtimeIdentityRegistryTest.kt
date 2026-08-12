package com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity

import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerificationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SyntheticRealtimeIdentityRegistryTest {
    @Test
    fun `maps distinct untracked tokens to fixed scoped principals`() {
        val verifier =
            SyntheticRealtimeIdentityRegistry.fromEnvironment(
                mapOf(
                    "CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN" to "b".repeat(64),
                    "CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN" to "c".repeat(64),
                ),
            )

        val business =
            assertIs<IdentityVerificationResult.Authenticated>(verifier.verify("b".repeat(64))).principal
        val client =
            assertIs<IdentityVerificationResult.Authenticated>(verifier.verify("c".repeat(64))).principal

        assertEquals(ConnectActorType.BUSINESS, business.actorType)
        assertEquals("synthetic-organization-c1", business.organizationScopeRef)
        assertEquals("synthetic-business-scope-c1", business.businessScopeRef)
        assertEquals(ConnectActorType.CLIENT, client.actorType)
        assertEquals(null, client.organizationScopeRef)
        assertIs<IdentityVerificationResult.Denied>(verifier.verify("unknown"))
    }

    @Test
    fun `rejects missing short or shared synthetic secrets`() {
        assertFailsWith<IllegalStateException> {
            SyntheticRealtimeIdentityRegistry.fromEnvironment(emptyMap())
        }
        assertFailsWith<IllegalStateException> {
            SyntheticRealtimeIdentityRegistry.fromEnvironment(
                mapOf(
                    "CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN" to "short",
                    "CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN" to "c".repeat(64),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticRealtimeIdentityRegistry.fromEnvironment(
                mapOf(
                    "CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN" to "s".repeat(64),
                    "CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN" to "s".repeat(64),
                ),
            )
        }
    }
}
