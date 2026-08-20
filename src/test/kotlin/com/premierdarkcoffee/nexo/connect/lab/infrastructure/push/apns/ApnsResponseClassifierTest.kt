package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProviderDeliveryResult
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApnsResponseClassifierTest {
    @Test
    fun `APNs response taxonomy is closed and deterministic`() {
        assertEquals(ApnsResponseCode.ACCEPTED, classify(200).code)

        val badToken = classify(400, "BadDeviceToken")
        assertEquals(ApnsResponseCode.INVALID_DEVICE_TOKEN, badToken.code)
        assertEquals(
            NotificationFailureCode.REGISTRATION_REVOKED,
            assertIs<NotificationProviderDeliveryResult.PermanentFailure>(badToken.delivery).errorCode,
        )

        val unregistered = classify(410, "Unregistered")
        assertEquals(ApnsResponseCode.UNREGISTERED, unregistered.code)
        assertEquals(
            NotificationFailureCode.REGISTRATION_REVOKED,
            assertIs<NotificationProviderDeliveryResult.PermanentFailure>(unregistered.delivery).errorCode,
        )

        val rateLimited = classify(429, "TooManyRequests")
        assertEquals(ApnsResponseCode.RATE_LIMITED, rateLimited.code)
        assertEquals(
            NotificationFailureCode.PROVIDER_RATE_LIMITED,
            assertIs<NotificationProviderDeliveryResult.RetryableFailure>(rateLimited.delivery).errorCode,
        )

        listOf(500, 502, 503).forEach { status ->
            val unavailable = classify(status, "ServiceUnavailable")
            assertEquals(ApnsResponseCode.PROVIDER_UNAVAILABLE, unavailable.code)
            assertEquals(
                NotificationFailureCode.PROVIDER_UNAVAILABLE,
                assertIs<NotificationProviderDeliveryResult.RetryableFailure>(unavailable.delivery).errorCode,
            )
        }

        assertEquals(ApnsResponseCode.AUTHENTICATION_REJECTED, classify(403, "InvalidProviderToken").code)
        val expired = classify(403, "ExpiredProviderToken")
        assertEquals(ApnsResponseCode.EXPIRED_PROVIDER_TOKEN, expired.code)
        assertIs<NotificationProviderDeliveryResult.RetryableFailure>(expired.delivery)
        assertIs<NotificationProviderDeliveryResult.RetryableFailure>(classify(400, "IdleTimeout").delivery)
        assertEquals(ApnsResponseCode.REQUEST_REJECTED, classify(400, "PayloadTooLarge").code)
        assertEquals(
            ApnsResponseCode.TRANSPORT_TIMEOUT,
            ApnsResponseClassifier.classify(
                ApnsSandboxTransportResult.Timeout,
            ).code,
        )
    }

    private fun classify(status: Int, reason: String? = null): ClassifiedApnsResponse {
        val body = reason?.let { "{\"reason\":\"$it\"}".toByteArray() } ?: ByteArray(0)
        return ApnsResponseClassifier.classify(
            ApnsSandboxTransportResult.Response(ApnsSandboxResponse(status, body)),
        )
    }
}
