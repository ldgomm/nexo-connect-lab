package com.premierdarkcoffee.nexo.connect.lab.application.presence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PresenceLeaseContractTest {
    @Test
    fun `accepts only bounded authenticated targets and opaque device references`() {
        val target =
            PresenceLeaseTarget(
                subjectRef = "client-1",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "platform-1",
                deviceRef = "device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            )

        assertTrue(target.subjectRef == "client-1")
        assertFailsWith<IllegalArgumentException> { target.copy(subjectRef = " ") }
        assertFailsWith<IllegalArgumentException> { target.copy(platformScopeRef = "x".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { target.copy(deviceRef = "client-selected-device") }
    }

    @Test
    fun `generates bounded opaque lease references`() {
        val leaseRef = SecurePresenceLeaseRefFactory().create()

        assertTrue(leaseRef.matches(Regex("lease_[A-Za-z0-9_-]{32}")))
    }
}
