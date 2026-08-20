package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryDiagnostic
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProviderDeliveryResult
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolution
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolver
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApnsSandboxNotificationProviderTest {
    @Test
    fun `sandbox request carries only minimal references and redacts device and authorization secrets`() {
        val capture = CapturingTransport()
        val provider = provider(capture)

        val result = provider.deliver(intent())

        assertIs<NotificationProviderDeliveryResult.Delivered>(result)
        assertEquals(DEVICE_TOKEN, capture.deviceToken)
        assertEquals(AUTHORIZATION, capture.authorization)
        assertEquals(CLIENT_TOPIC, capture.topic)
        assertTrue(capture.payload.contains("\"content-available\":1"))
        assertTrue(capture.payload.contains("conversation-1"))
        assertTrue(capture.payload.contains("server-message-1"))
        assertFalse(capture.payload.contains(PRIVATE_BODY_SENTINEL))
        assertFalse(capture.renderedRequest.contains(DEVICE_TOKEN))
        assertFalse(capture.renderedRequest.contains(AUTHORIZATION))
        assertTrue(capture.renderedRequest.contains("deviceToken=<redacted>"))
        assertTrue(capture.renderedRequest.contains("authorization=<redacted>"))
    }

    @Test
    fun `provider outage remains retryable and production is rejected before transport`() {
        val unavailable = provider(ApnsSandboxTransport { ApnsSandboxTransportResult.Unavailable })
        val outage = assertIs<NotificationProviderDeliveryResult.RetryableFailure>(unavailable.deliver(intent()))
        assertEquals(NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE, outage.diagnostic)

        var called = false
        val sandboxOnly = provider(
            ApnsSandboxTransport {
                called = true
                ApnsSandboxTransportResult.Unavailable
            },
        )
        val rejected = assertIs<NotificationProviderDeliveryResult.PermanentFailure>(
            sandboxOnly.deliver(intent().copy(environment = PushEnvironment.PRODUCTION)),
        )
        assertEquals(NotificationDeliveryDiagnostic.UNSUPPORTED_ENVIRONMENT, rejected.diagnostic)
        assertFalse(called)
    }

    @Test
    fun `expired provider token is invalidated before the durable retry`() {
        var invalidations = 0
        val authorizationSource = object : ApnsAuthorizationSource {
            override fun authorization(): ApnsAuthorization = ApnsAuthorization(AUTHORIZATION.toCharArray())

            override fun invalidate() {
                invalidations += 1
            }
        }
        val provider = ApnsSandboxNotificationProvider(
            configuration = configuration(),
            tokenResolver = FixedTokenResolver(DEVICE_TOKEN.toByteArray()),
            authorizationSource = authorizationSource,
            transport = ApnsSandboxTransport {
                ApnsSandboxTransportResult.Response(
                    ApnsSandboxResponse(403, "{\"reason\":\"ExpiredProviderToken\"}".toByteArray()),
                )
            },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        assertIs<NotificationProviderDeliveryResult.RetryableFailure>(provider.deliver(intent()))
        assertEquals(1, invalidations)
    }

    @Test
    fun `inactive registration is uniformly rejected without requesting provider credentials`() {
        var authorizationRequested = false
        val provider = ApnsSandboxNotificationProvider(
            configuration = configuration(),
            tokenResolver = object : PushDeliveryTokenResolver {
                override fun <T> withActiveToken(
                    intent: NotificationOutboxIntent,
                    action: (PushTokenSecret) -> T,
                ): PushDeliveryTokenResolution<T> = PushDeliveryTokenResolution.NotFoundOrDenied
            },
            authorizationSource = ApnsAuthorizationSource {
                authorizationRequested = true
                ApnsAuthorization(AUTHORIZATION.toCharArray())
            },
            transport = ApnsSandboxTransport { error("transport must not be called") },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val result = assertIs<NotificationProviderDeliveryResult.PermanentFailure>(provider.deliver(intent()))

        assertEquals(NotificationDeliveryDiagnostic.REGISTRATION_NOT_ACTIVE, result.diagnostic)
        assertFalse(authorizationRequested)
        assertFalse(provider.toString().contains(AUTHORIZATION))
        assertFalse(configuration().toString().contains("private-key-secret-path"))
    }

    private fun provider(transport: ApnsSandboxTransport): ApnsSandboxNotificationProvider =
        ApnsSandboxNotificationProvider(
            configuration = configuration(),
            tokenResolver = FixedTokenResolver(DEVICE_TOKEN.toByteArray()),
            authorizationSource = ApnsAuthorizationSource { ApnsAuthorization(AUTHORIZATION.toCharArray()) },
            transport = transport,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun configuration(): ApnsSandboxConfiguration = ApnsSandboxConfiguration(
        teamId = "TEAMID1234",
        keyId = "KEYID12345",
        privateKeyPath = Path.of("private-key-secret-path.p8"),
        topics = mapOf(
            PushApplication.NEXO_CLIENT_IOS to CLIENT_TOPIC,
            PushApplication.NEXO_BUSINESS_IOS to "com.nexo.business",
        ),
    )

    private class FixedTokenResolver(private val token: ByteArray) : PushDeliveryTokenResolver {
        override fun <T> withActiveToken(
            intent: NotificationOutboxIntent,
            action: (PushTokenSecret) -> T,
        ): PushDeliveryTokenResolution<T> = PushTokenSecret.fromBytes(token).use { secret ->
            PushDeliveryTokenResolution.Resolved(action(secret))
        }
    }

    private class CapturingTransport : ApnsSandboxTransport {
        lateinit var deviceToken: String
        lateinit var authorization: String
        lateinit var topic: String
        lateinit var payload: String
        lateinit var renderedRequest: String

        override fun send(request: ApnsSandboxRequest): ApnsSandboxTransportResult {
            renderedRequest = request.toString()
            topic = request.topic
            request.withMaterial { deviceTokenChars, authorizationChars, payloadBytes ->
                deviceToken = String(deviceTokenChars)
                authorization = String(authorizationChars)
                payload = String(payloadBytes)
            }
            return ApnsSandboxTransportResult.Response(ApnsSandboxResponse(200, ByteArray(0)))
        }
    }

    private companion object {
        const val DEVICE_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val AUTHORIZATION = "provider-jwt-secret-value"
        const val CLIENT_TOPIC = "com.nexo.client"
        const val PRIVATE_BODY_SENTINEL = "private message body"
        val NOW: Instant = Instant.parse("2026-08-20T16:00:00Z")

        fun intent(): NotificationOutboxIntent = NotificationOutboxIntent(
            intentRef = "notification-intent-1",
            platformScopeRef = "platform-1",
            organizationScopeRef = null,
            businessScopeRef = null,
            conversationRef = "conversation-1",
            serverMessageRef = "server-message-1",
            recipientSubjectRef = "client-1",
            recipientActorType = ConnectActorType.CLIENT,
            registrationRef = "push-registration-1",
            application = PushApplication.NEXO_CLIENT_IOS,
            provider = PushProvider.APNS,
            environment = PushEnvironment.SANDBOX,
            type = NotificationType.MESSAGE_CREATED,
            status = NotificationOutboxStatus.CLAIMED,
            attemptCount = 1,
            maxAttempts = 4,
            nextAttemptAt = NOW,
            leaseOwner = "connect-apns-worker-1",
            leaseExpiresAt = NOW.plusSeconds(30),
            lastErrorCode = null,
            deliveredAt = null,
            deadLetteredAt = null,
            createdAt = NOW,
            updatedAt = NOW,
            version = 1,
        )
    }
}
