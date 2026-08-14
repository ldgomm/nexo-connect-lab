package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import com.premierdarkcoffee.nexo.connect.lab.application.presence.EphemeralPresenceLeaseStore
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseAcquireResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseHandle
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.presence.PresenceLeaseTarget
import com.premierdarkcoffee.nexo.connect.lab.backend.realtime.installAuthenticatedRealtimeTransport
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity.SyntheticTokenVerifier
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticatedRealtimePresenceLeaseTest {
    @Test
    fun `authenticated websocket acquires heartbeats and releases its ephemeral lease`() = testApplication {
        val store = RecordingPresenceLeaseStore()
        application {
            installAuthenticatedRealtimeTransport(
                identityVerifier =
                SyntheticTokenVerifier(
                    mapOf(
                        TOKEN to
                            ConnectPrincipal(
                                subjectRef = "presence-client",
                                actorType = ConnectActorType.CLIENT,
                                platformScopeRef = "presence-platform",
                            ),
                    ),
                ),
                presenceLeaseStore = store,
            )
            configureRouting()
        }
        val realtimeClient = createClient { install(WebSockets) }

        realtimeClient.webSocket(
            request = {
                url("/v1/realtime")
                bearerAuth(TOKEN)
            },
        ) {
            assertTrue(incoming.receive() is Frame.Text)
            delay(140)
            assertEquals(1, store.acquireCount.get())
            assertTrue(store.refreshCount.get() >= 1)
        }

        withTimeout(2_000) {
            while (store.releaseCount.get() == 0) delay(10)
        }
        assertEquals(1, store.releaseCount.get())
        assertEquals("presence-client", store.lastTarget?.subjectRef)
        assertTrue(store.lastTarget?.deviceRef?.startsWith("device_") == true)
    }

    private class RecordingPresenceLeaseStore : EphemeralPresenceLeaseStore {
        override val refreshInterval: Duration = Duration.ofMillis(40)
        val acquireCount = AtomicInteger()
        val refreshCount = AtomicInteger()
        val releaseCount = AtomicInteger()

        @Volatile
        var lastTarget: PresenceLeaseTarget? = null

        override suspend fun acquire(target: PresenceLeaseTarget): PresenceLeaseAcquireResult {
            acquireCount.incrementAndGet()
            lastTarget = target
            return PresenceLeaseAcquireResult.Acquired(
                PresenceLeaseHandle(
                    target = target,
                    ownerInstanceRef = "test-instance",
                    leaseRef = "lease_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                ),
            )
        }

        override suspend fun refresh(handle: PresenceLeaseHandle): PresenceLeaseMutationResult {
            refreshCount.incrementAndGet()
            return PresenceLeaseMutationResult.APPLIED
        }

        override suspend fun release(handle: PresenceLeaseHandle): PresenceLeaseMutationResult {
            releaseCount.incrementAndGet()
            return PresenceLeaseMutationResult.APPLIED
        }

        override fun close() = Unit
    }

    private companion object {
        const val TOKEN = "presence-token-0123456789abcdef"
    }
}
