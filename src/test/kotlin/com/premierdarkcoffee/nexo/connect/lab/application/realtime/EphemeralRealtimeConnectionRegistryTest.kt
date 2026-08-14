package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EphemeralRealtimeConnectionRegistryTest {
    @Test
    fun `default routing references are independent opaque identifiers`() {
        val factory = OpaqueRealtimeRouteRefFactory()
        val connectionRef = factory.connectionRef()
        val deviceRef = factory.deviceRef()
        val sessionRef = factory.sessionRef()

        assertTrue(connectionRef.matches(Regex("connection_[A-Za-z0-9_-]{32}")))
        assertTrue(deviceRef.matches(Regex("device_[A-Za-z0-9_-]{32}")))
        assertTrue(sessionRef.matches(Regex("session_[A-Za-z0-9_-]{32}")))
        assertNotEquals(connectionRef.substringAfter('_'), deviceRef.substringAfter('_'))
        assertNotEquals(deviceRef.substringAfter('_'), sessionRef.substringAfter('_'))
        assertFalse(listOf(connectionRef, deviceRef, sessionRef).any { it.contains(PRINCIPAL.subjectRef) })
    }

    @Test
    fun `same subject may hold multiple device routes and only exact registration can control one`() {
        val registry = deterministicRegistry()
        val first = registry.register(PRINCIPAL, MessageCreatedEventSink { }, ReceiptCursorEventSink { })
        val second = registry.register(PRINCIPAL, MessageCreatedEventSink { }, ReceiptCursorEventSink { })
        registry.subscribe(first, CONVERSATION_REF)
        registry.subscribe(second, CONVERSATION_REF)

        assertEquals(2, registry.activeConnectionCount(PRINCIPAL))
        assertEquals(2, registry.candidates(CONVERSATION_REF).size)
        assertEquals(1, registry.candidates(CONVERSATION_REF, first).size)

        val guessed = first.copy(deviceRef = opaque("device", 999))
        assertFalse(registry.touch(guessed))
        assertFailsWith<IllegalStateException> { registry.subscribe(guessed, CONVERSATION_REF) }
        registry.unregister(guessed)
        assertEquals(2, registry.activeConnectionCount())

        registry.unregister(first)
        assertEquals(1, registry.activeConnectionCount())
    }

    @Test
    fun `stale route is removed by ttl while refreshed route remains active`() {
        val now = AtomicLong()
        val registry = deterministicRegistry(now)
        val stale = registry.register(PRINCIPAL, MessageCreatedEventSink { }, ReceiptCursorEventSink { })
        val refreshed = registry.register(PRINCIPAL, MessageCreatedEventSink { }, ReceiptCursorEventSink { })
        registry.subscribe(stale, CONVERSATION_REF)
        registry.subscribe(refreshed, CONVERSATION_REF)

        now.set(Duration.ofSeconds(6).toNanos())
        assertTrue(registry.touch(refreshed))
        now.set(Duration.ofSeconds(11).toNanos())

        assertEquals(listOf(refreshed), registry.candidates(CONVERSATION_REF).map { it.registration })
        assertFalse(registry.touch(stale))
    }

    private fun deterministicRegistry(now: AtomicLong = AtomicLong()): EphemeralRealtimeConnectionRegistry {
        val next = AtomicInteger()
        return EphemeralRealtimeConnectionRegistry(
            ttl = Duration.ofSeconds(10),
            maximumConnections = 8,
            registrationRefFactory = { opaque("connection", next.incrementAndGet()) },
            deviceRefFactory = { opaque("device", next.incrementAndGet()) },
            sessionRefFactory = { opaque("session", next.incrementAndGet()) },
            monotonicNanos = now::get,
        )
    }

    private companion object {
        const val CONVERSATION_REF = "conversation-routing-1"
        val PRINCIPAL = ConnectPrincipal("client-routing-1", ConnectActorType.CLIENT, "platform-routing-1")

        fun opaque(kind: String, value: Int): String = "${kind}_${value.toString().padStart(32, '0')}"
    }
}
