package com.premierdarkcoffee.nexo.connect.lab.domain.push

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NotificationOutboxIntentTest {
    @Test
    fun `claimed intent carries references only and never message or token content`() {
        val intent = claimedIntent()
        val rendered = intent.toString()

        assertFalse(rendered.contains("secret-provider-token"))
        assertFalse(rendered.contains("private-message-body"))
    }

    @Test
    fun `rejects invalid scope state and oversized lease`() {
        assertFailsWith<IllegalArgumentException> {
            claimedIntent(
                recipientActorType = ConnectActorType.CLIENT,
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClaimNotificationOutboxRequest(
                leaseOwner = "worker-1",
                now = BASE_TIME,
                leaseDuration = Duration.ofMinutes(16),
                limit = 1,
            )
        }
    }

    private fun claimedIntent(
        recipientActorType: ConnectActorType = ConnectActorType.CLIENT,
        organizationScopeRef: String? = null,
        businessScopeRef: String? = null,
    ): NotificationOutboxIntent = NotificationOutboxIntent(
        intentRef = "notification-1",
        platformScopeRef = "platform-1",
        organizationScopeRef = organizationScopeRef,
        businessScopeRef = businessScopeRef,
        conversationRef = "conversation-1",
        serverMessageRef = "server-message-1",
        recipientSubjectRef = "client-subject-1",
        recipientActorType = recipientActorType,
        registrationRef = "registration-1",
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
        type = NotificationType.MESSAGE_CREATED,
        status = NotificationOutboxStatus.CLAIMED,
        attemptCount = 1,
        maxAttempts = 5,
        nextAttemptAt = BASE_TIME,
        leaseOwner = "worker-1",
        leaseExpiresAt = BASE_TIME.plusSeconds(30),
        lastErrorCode = null,
        deliveredAt = null,
        deadLetteredAt = null,
        createdAt = BASE_TIME,
        updatedAt = BASE_TIME,
        version = 1,
    )

    companion object {
        private val BASE_TIME = Instant.parse("2026-08-16T18:00:00Z")
    }
}
