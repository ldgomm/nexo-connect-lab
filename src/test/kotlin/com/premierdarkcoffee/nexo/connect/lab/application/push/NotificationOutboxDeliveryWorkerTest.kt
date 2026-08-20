package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DeadLetterNotificationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.MarkNotificationDeliveredRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxClaimBatch
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RecordNotificationFailureRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationOutboxDeliveryWorkerTest {
    @Test
    fun `provider outage schedules durable retry without losing the notification intent`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        val events = mutableListOf<SanitizedNotificationDeliveryEvent>()
        val worker = worker(
            repository = repository,
            provider = NotificationProvider {
                NotificationProviderDeliveryResult.RetryableFailure(
                    errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
                )
            },
            observer = NotificationDeliveryObserver(events::add),
        )

        val summary = worker.runOnce()

        assertEquals(NotificationDeliveryRunSummary(1, 0, 1, 0, 0), summary)
        assertEquals(NotificationOutboxStatus.RETRY_PENDING, repository.intent.status)
        assertEquals("server-message-1", repository.intent.serverMessageRef)
        assertEquals(NotificationFailureCode.PROVIDER_UNAVAILABLE, repository.intent.lastErrorCode)
        assertNull(repository.markDeliveredRequest)
        assertNull(repository.deadLetterRequest)
        assertEquals(NotificationDeliverySettlement.RETRY_SCHEDULED, events.single().settlement)
    }

    @Test
    fun `permanent rejection is dead lettered and observability stays sanitised`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        val events = mutableListOf<SanitizedNotificationDeliveryEvent>()
        val worker = worker(
            repository = repository,
            provider = NotificationProvider {
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                )
            },
            observer = NotificationDeliveryObserver(events::add),
        )

        val summary = worker.runOnce()

        assertEquals(NotificationDeliveryRunSummary(1, 0, 0, 1, 0), summary)
        assertEquals(NotificationOutboxStatus.DEAD_LETTER, repository.intent.status)
        val rendered = events.single().toLogLine()
        assertFalse(rendered.contains(DEVICE_TOKEN_SENTINEL))
        assertFalse(rendered.contains(AUTHORIZATION_SENTINEL))
        assertFalse(rendered.contains(MESSAGE_BODY_SENTINEL))
        assertTrue(rendered.contains("diagnostic=INVALID_REGISTRATION"))
    }

    @Test
    fun `accepted provider response marks the claimed intent delivered`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        val worker = worker(
            repository = repository,
            provider = NotificationProvider { NotificationProviderDeliveryResult.Delivered() },
        )

        assertEquals(NotificationDeliveryRunSummary(1, 1, 0, 0, 0), worker.runOnce())
        assertEquals(NotificationOutboxStatus.DELIVERED, repository.intent.status)
        assertIs<MarkNotificationDeliveredRequest>(repository.markDeliveredRequest)
    }

    @Test
    fun `invalid current token is retired before its intent becomes dead letter`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        var retirement: RetireInvalidPushRegistrationRequest? = null
        val worker = worker(
            repository = repository,
            provider = NotificationProvider {
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                    invalidTokenVersion = 7,
                )
            },
            invalidRegistrationRetirer = InvalidPushRegistrationRetirer { request ->
                retirement = request
                InvalidPushRegistrationRetirementResult.Retired
            },
        )

        assertEquals(NotificationDeliveryRunSummary(1, 0, 0, 1, 0), worker.runOnce())
        assertEquals(7, retirement?.expectedTokenVersion)
        assertEquals("push-registration-1", retirement?.intent?.registrationRef)
        assertEquals(NotificationOutboxStatus.DEAD_LETTER, repository.intent.status)
        assertEquals(NotificationFailureCode.REGISTRATION_REVOKED, repository.intent.lastErrorCode)
    }

    @Test
    fun `rotation that wins an invalid-token race schedules retry for the replacement token`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        val worker = worker(
            repository = repository,
            provider = NotificationProvider {
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                    invalidTokenVersion = 1,
                )
            },
            invalidRegistrationRetirer = InvalidPushRegistrationRetirer {
                InvalidPushRegistrationRetirementResult.TokenRotated
            },
        )

        assertEquals(NotificationDeliveryRunSummary(1, 0, 1, 0, 0), worker.runOnce())
        assertEquals(NotificationOutboxStatus.RETRY_PENDING, repository.intent.status)
        assertEquals(NotificationFailureCode.PROVIDER_UNAVAILABLE, repository.intent.lastErrorCode)
        assertNull(repository.deadLetterRequest)
    }

    @Test
    fun `retirement outage defers the lease instead of dropping invalid-token cleanup`() {
        val repository = RecordingOutboxRepository(claimedIntent())
        val worker = worker(
            repository = repository,
            provider = NotificationProvider {
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                    invalidTokenVersion = 1,
                )
            },
        )

        assertEquals(NotificationDeliveryRunSummary(1, 0, 0, 0, 1), worker.runOnce())
        assertEquals(NotificationOutboxStatus.CLAIMED, repository.intent.status)
        assertNull(repository.deadLetterRequest)
        assertNull(repository.recordFailureRequest)
    }

    private fun worker(
        repository: RecordingOutboxRepository,
        provider: NotificationProvider,
        invalidRegistrationRetirer: InvalidPushRegistrationRetirer = InvalidPushRegistrationRetirer.UNAVAILABLE,
        observer: NotificationDeliveryObserver = NotificationDeliveryObserver.NOOP,
    ): NotificationOutboxDeliveryWorker = NotificationOutboxDeliveryWorker(
        repository = repository,
        providers = mapOf(PushProvider.APNS to provider),
        leaseOwner = LEASE_OWNER,
        clock = Clock.fixed(SETTLEMENT_TIME, ZoneOffset.UTC),
        invalidRegistrationRetirer = invalidRegistrationRetirer,
        observer = observer,
    )

    private class RecordingOutboxRepository(initial: NotificationOutboxIntent) : NotificationOutboxRepository {
        var intent = initial
        var markDeliveredRequest: MarkNotificationDeliveredRequest? = null
        var recordFailureRequest: RecordNotificationFailureRequest? = null
        var deadLetterRequest: DeadLetterNotificationRequest? = null

        override fun claim(request: ClaimNotificationOutboxRequest): NotificationOutboxClaimBatch =
            NotificationOutboxClaimBatch(listOf(intent))

        override fun markDelivered(request: MarkNotificationDeliveredRequest): NotificationOutboxMutationResult {
            markDeliveredRequest = request
            intent = intent.copy(
                status = NotificationOutboxStatus.DELIVERED,
                leaseOwner = null,
                leaseExpiresAt = null,
                deliveredAt = request.now,
                updatedAt = request.now,
                version = intent.version + 1,
            )
            return NotificationOutboxMutationResult.Updated(intent)
        }

        override fun recordFailure(request: RecordNotificationFailureRequest): NotificationOutboxMutationResult {
            recordFailureRequest = request
            intent = intent.copy(
                status = NotificationOutboxStatus.RETRY_PENDING,
                nextAttemptAt = request.retryAt,
                leaseOwner = null,
                leaseExpiresAt = null,
                lastErrorCode = request.errorCode,
                updatedAt = request.now,
                version = intent.version + 1,
            )
            return NotificationOutboxMutationResult.Updated(intent)
        }

        override fun deadLetter(request: DeadLetterNotificationRequest): NotificationOutboxMutationResult {
            deadLetterRequest = request
            intent = intent.copy(
                status = NotificationOutboxStatus.DEAD_LETTER,
                leaseOwner = null,
                leaseExpiresAt = null,
                lastErrorCode = request.errorCode,
                deadLetteredAt = request.now,
                updatedAt = request.now,
                version = intent.version + 1,
            )
            return NotificationOutboxMutationResult.Updated(intent)
        }
    }

    private companion object {
        const val LEASE_OWNER = "connect-apns-worker-1"
        const val DEVICE_TOKEN_SENTINEL = "device-token-must-never-appear"
        const val AUTHORIZATION_SENTINEL = "provider-jwt-must-never-appear"
        const val MESSAGE_BODY_SENTINEL = "private-message-body-must-never-appear"
        val CREATED_AT: Instant = Instant.parse("2026-08-20T15:00:00Z")
        val SETTLEMENT_TIME: Instant = CREATED_AT.plusSeconds(10)

        fun claimedIntent(): NotificationOutboxIntent = NotificationOutboxIntent(
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
            nextAttemptAt = CREATED_AT,
            leaseOwner = LEASE_OWNER,
            leaseExpiresAt = CREATED_AT.plusSeconds(30),
            lastErrorCode = null,
            deliveredAt = null,
            deadLetteredAt = null,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
            version = 1,
        )
    }
}
