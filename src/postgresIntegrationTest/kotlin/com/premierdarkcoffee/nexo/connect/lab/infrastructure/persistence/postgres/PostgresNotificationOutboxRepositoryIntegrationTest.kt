package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.GetPushNotificationPreferenceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.GetPushNotificationPreferenceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.MarkNotificationDeliveredRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RecordNotificationFailureRequest
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryDiagnostic
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryRunSummary
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationOutboxDeliveryWorker
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProvider
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProviderDeliveryResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableConversationCatchUp
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.DurableConversationCatchUpResult
import com.premierdarkcoffee.nexo.connect.lab.application.realtime.LoadDurableConversationCatchUpRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresNotificationOutboxRepositoryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var messageRepository: PostgresDurableTextRepository
    private lateinit var outboxRepository: PostgresNotificationOutboxRepository
    private lateinit var preferenceRepository: PostgresPushNotificationPreferenceRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        messageRepository = PostgresDurableTextRepository(appDataSource)
        outboxRepository = PostgresNotificationOutboxRepository(appDataSource)
        preferenceRepository = PostgresPushNotificationPreferenceRepository(appDataSource)
        resetDatabase()
        seedConversation()
    }

    @AfterTest
    fun tearDown() {
        runCatching {
            executeAdmin("DROP TRIGGER IF EXISTS reject_notification_outbox_insert ON connect.notification_outbox")
        }
        runCatching { executeAdmin("DROP FUNCTION IF EXISTS connect.reject_notification_outbox_insert()") }
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `commits message and one minimised intent per active recipient device atomically`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'a')
        seedPushDevice("client-registration-2", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'b')
        seedPushDevice(
            "sender-registration",
            "business-subject-1",
            "BUSINESS",
            "NEXO_BUSINESS_IOS",
            "organization-1",
            "business-1",
            'c',
        )
        seedPushDevice(
            "client-registration-other-platform",
            "client-subject-1",
            "CLIENT",
            "NEXO_CLIENT_IOS",
            null,
            null,
            'd',
        )
        executeAdmin(
            "UPDATE connect.push_device_registrations SET platform_scope_ref = 'platform-2' " +
                "WHERE registration_ref = 'client-registration-other-platform'",
        )

        val committed = assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        assertEquals("server-message-1", committed.serverMessageRef)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(2, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
        assertEquals(
            2,
            scalarLong(
                """
                SELECT count(*)
                FROM connect.notification_outbox
                WHERE server_message_ref = 'server-message-1'
                  AND recipient_subject_ref = 'client-subject-1'
                  AND recipient_actor_type = 'CLIENT'
                  AND status = 'PENDING'
                  AND attempt_count = 0
                  AND max_attempts = 5
                """.trimIndent(),
            ),
        )
        assertEquals(
            0,
            scalarLong(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'connect'
                  AND table_name = 'notification_outbox'
                  AND column_name IN ('body', 'token', 'token_ciphertext', 'token_fingerprint', 'token_nonce')
                """.trimIndent(),
            ),
        )

        val replay = messageRepository.persist(messageRequest(serverMessageRef = "server-message-retry"))
        assertIs<DurableTextRepositoryResult.ReplayExisting>(replay)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(2, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
    }

    @Test
    fun `creates a business-scoped intent when the client sends`() {
        seedPushDevice(
            "business-registration-1",
            "business-subject-1",
            "BUSINESS",
            "NEXO_BUSINESS_IOS",
            "organization-1",
            "business-1",
            'e',
        )

        val committed =
            assertIs<DurableTextRepositoryResult.Committed>(
                messageRepository.persist(
                    messageRequest(
                        principal = CLIENT_PRINCIPAL,
                        serverMessageRef = "server-message-business-target",
                        clientMessageRef = "client-message-business-target",
                        idempotencyKey = "idempotency-business-target",
                    ),
                ),
            )
        assertEquals("server-message-business-target", committed.serverMessageRef)
        assertEquals(
            1,
            scalarLong(
                """
                SELECT count(*)
                FROM connect.notification_outbox
                WHERE recipient_subject_ref = 'business-subject-1'
                  AND recipient_actor_type = 'BUSINESS'
                  AND organization_scope_ref = 'organization-1'
                  AND business_scope_ref = 'business-1'
                  AND application = 'NEXO_BUSINESS_IOS'
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `message remains durable when no recipient device is registered`() {
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))

        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
    }

    @Test
    fun `muted recipient device creates no push intent and ownership is uniformly fenced`() {
        seedPushDevice("muted-client-registration", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'a')

        val created = putPreference(
            registrationRef = "muted-client-registration",
            muted = true,
            lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
            badgeMode = NotificationBadgeMode.SET_ONE,
            quietMode = NotificationQuietMode.OFF,
        )
        assertTrue(created.created)
        assertEquals(1L, created.preference.version)
        assertTrue(created.preference.muted)

        val updated = assertIs<PutPushNotificationPreferenceResult.Updated>(
            preferenceRepository.put(
                preferenceRequest(
                    registrationRef = "muted-client-registration",
                    muted = true,
                    lockScreenPrivacy = NotificationLockScreenPrivacy.HIDDEN,
                    badgeMode = NotificationBadgeMode.UNCHANGED,
                    quietMode = NotificationQuietMode.ON,
                    expectedVersion = created.preference.version,
                ),
            ),
        )
        assertFalse(updated.created)
        assertEquals(2L, updated.preference.version)
        assertSame(
            PutPushNotificationPreferenceResult.NotFoundOrDenied,
            preferenceRepository.put(
                preferenceRequest(
                    registrationRef = "muted-client-registration",
                    muted = false,
                    lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
                    badgeMode = NotificationBadgeMode.SET_ONE,
                    quietMode = NotificationQuietMode.OFF,
                    expectedVersion = created.preference.version,
                ),
            ),
        )
        assertIs<GetPushNotificationPreferenceResult.Found>(
            preferenceRepository.get(
                GetPushNotificationPreferenceRequest(
                    principal = CLIENT_PRINCIPAL,
                    conversationRef = "conversation-1",
                    registrationRef = "muted-client-registration",
                ),
            ),
        )
        assertSame(
            GetPushNotificationPreferenceResult.NotFoundOrDenied,
            preferenceRepository.get(
                GetPushNotificationPreferenceRequest(
                    principal = BUSINESS_PRINCIPAL,
                    conversationRef = "conversation-1",
                    registrationRef = "muted-client-registration",
                ),
            ),
        )
        assertSame(
            PutPushNotificationPreferenceResult.NotFoundOrDenied,
            preferenceRepository.put(
                PutPushNotificationPreferenceRequest(
                    principal = BUSINESS_PRINCIPAL,
                    conversationRef = "conversation-1",
                    registrationRef = "muted-client-registration",
                    muted = false,
                    lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
                    badgeMode = NotificationBadgeMode.SET_ONE,
                    quietMode = NotificationQuietMode.OFF,
                    expectedVersion = updated.preference.version,
                    now = BASE_TIME.plusSeconds(2),
                ),
            ),
        )

        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))

        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
    }

    @Test
    fun `per-device privacy badge and quiet choices are frozen into durable intents`() {
        seedPushDevice("generic-registration", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'a')
        seedPushDevice("hidden-registration", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'b')
        seedPushDevice("quiet-registration", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'c')

        putPreference(
            registrationRef = "generic-registration",
            muted = false,
            lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
            badgeMode = NotificationBadgeMode.SET_ONE,
            quietMode = NotificationQuietMode.OFF,
        )
        putPreference(
            registrationRef = "hidden-registration",
            muted = false,
            lockScreenPrivacy = NotificationLockScreenPrivacy.HIDDEN,
            badgeMode = NotificationBadgeMode.SET_ONE,
            quietMode = NotificationQuietMode.OFF,
        )
        putPreference(
            registrationRef = "quiet-registration",
            muted = false,
            lockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
            badgeMode = NotificationBadgeMode.SET_ONE,
            quietMode = NotificationQuietMode.ON,
        )

        assertSame(
            PutPushNotificationPreferenceResult.NotFoundOrDenied,
            preferenceRepository.put(
                preferenceRequest(
                    registrationRef = "generic-registration",
                    muted = true,
                    lockScreenPrivacy = NotificationLockScreenPrivacy.HIDDEN,
                    badgeMode = NotificationBadgeMode.UNCHANGED,
                    quietMode = NotificationQuietMode.ON,
                    expectedVersion = 0,
                ),
            ),
        )

        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))

        assertEquals(3, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_outbox " +
                    "WHERE registration_ref = 'generic-registration' " +
                    "AND presentation_mode = 'GENERIC_ALERT' AND badge_mode = 'SET_ONE'",
            ),
        )
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_outbox " +
                    "WHERE registration_ref = 'hidden-registration' " +
                    "AND presentation_mode = 'BACKGROUND_ONLY' AND badge_mode = 'SET_ONE'",
            ),
        )
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_outbox " +
                    "WHERE registration_ref = 'quiet-registration' " +
                    "AND presentation_mode = 'BACKGROUND_ONLY' AND badge_mode = 'UNCHANGED'",
            ),
        )
    }

    @Test
    fun `outbox insertion failure rolls back message identity and sequence`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'd')
        executeAdmin(
            """
            CREATE OR REPLACE FUNCTION connect.reject_notification_outbox_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS ${'$'}function${'$'}
            BEGIN
                RAISE EXCEPTION 'forced notification outbox failure';
            END;
            ${'$'}function${'$'}
            """.trimIndent(),
        )
        executeAdmin(
            """
            CREATE TRIGGER reject_notification_outbox_insert
            BEFORE INSERT ON connect.notification_outbox
            FOR EACH ROW EXECUTE FUNCTION connect.reject_notification_outbox_insert()
            """.trimIndent(),
        )

        assertFailsWith<SQLException> { messageRepository.persist(messageRequest()) }

        assertEquals(0, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.message_identities"))
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.notification_outbox"))
        assertEquals(
            0,
            scalarLong(
                "SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'",
            ),
        )
    }

    @Test
    fun `claim lease fences workers and delivery requires the current owner and version`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'e')
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))

        val firstClaim = outboxRepository.claim(claimRequest("worker-1", BASE_TIME.plusSeconds(2), 10)).intents.single()
        assertEquals(NotificationOutboxStatus.CLAIMED, firstClaim.status)
        assertEquals(1, firstClaim.attemptCount)
        assertTrue(outboxRepository.claim(claimRequest("worker-2", BASE_TIME.plusSeconds(5), 10)).intents.isEmpty())
        assertSame(
            NotificationOutboxMutationResult.NotFoundOrDenied,
            outboxRepository.markDelivered(
                MarkNotificationDeliveredRequest(
                    intentRef = firstClaim.intentRef,
                    leaseOwner = "worker-2",
                    expectedVersion = firstClaim.version,
                    now = BASE_TIME.plusSeconds(6),
                ),
            ),
        )

        val reclaimed = outboxRepository.claim(claimRequest("worker-2", BASE_TIME.plusSeconds(13), 10)).intents.single()
        assertEquals(2, reclaimed.attemptCount)
        val delivered =
            assertIs<NotificationOutboxMutationResult.Updated>(
                outboxRepository.markDelivered(
                    MarkNotificationDeliveredRequest(
                        intentRef = reclaimed.intentRef,
                        leaseOwner = "worker-2",
                        expectedVersion = reclaimed.version,
                        now = BASE_TIME.plusSeconds(14),
                    ),
                ),
            ).intent
        assertEquals(NotificationOutboxStatus.DELIVERED, delivered.status)
        assertTrue(outboxRepository.claim(claimRequest("worker-3", BASE_TIME.plusSeconds(30), 10)).intents.isEmpty())
    }

    @Test
    fun `expired final lease becomes an auditable dead letter`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'f')
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        executeAdmin("UPDATE connect.notification_outbox SET max_attempts = 1")

        val claimed = outboxRepository.claim(claimRequest("worker-1", BASE_TIME.plusSeconds(2), 5)).intents.single()
        assertEquals(1, claimed.attemptCount)
        assertTrue(outboxRepository.claim(claimRequest("worker-2", BASE_TIME.plusSeconds(8), 10)).intents.isEmpty())
        assertEquals(
            1,
            scalarLong(
                """
                SELECT count(*)
                FROM connect.notification_outbox
                WHERE status = 'DEAD_LETTER'
                  AND last_error_code = 'LEASE_EXPIRED_MAX_ATTEMPTS'
                  AND lease_owner IS NULL
                  AND lease_expires_at IS NULL
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `bounded retry becomes dead letter at max attempts`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'f')
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        executeAdmin("UPDATE connect.notification_outbox SET max_attempts = 2")

        val first = outboxRepository.claim(claimRequest("worker-1", BASE_TIME.plusSeconds(2), 10)).intents.single()
        val retry =
            assertIs<NotificationOutboxMutationResult.Updated>(
                outboxRepository.recordFailure(
                    RecordNotificationFailureRequest(
                        intentRef = first.intentRef,
                        leaseOwner = "worker-1",
                        expectedVersion = first.version,
                        now = BASE_TIME.plusSeconds(3),
                        retryAt = BASE_TIME.plusSeconds(30),
                        errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    ),
                ),
            ).intent
        assertEquals(NotificationOutboxStatus.RETRY_PENDING, retry.status)
        assertTrue(outboxRepository.claim(claimRequest("worker-2", BASE_TIME.plusSeconds(20), 10)).intents.isEmpty())

        val second = outboxRepository.claim(claimRequest("worker-2", BASE_TIME.plusSeconds(31), 10)).intents.single()
        assertEquals(2, second.attemptCount)
        val dead =
            assertIs<NotificationOutboxMutationResult.Updated>(
                outboxRepository.recordFailure(
                    RecordNotificationFailureRequest(
                        intentRef = second.intentRef,
                        leaseOwner = "worker-2",
                        expectedVersion = second.version,
                        now = BASE_TIME.plusSeconds(32),
                        retryAt = BASE_TIME.plusSeconds(60),
                        errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    ),
                ),
            ).intent
        assertEquals(NotificationOutboxStatus.DEAD_LETTER, dead.status)
        assertEquals(NotificationFailureCode.PROVIDER_UNAVAILABLE, dead.lastErrorCode)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.notification_outbox WHERE status = 'DEAD_LETTER'"))
    }

    @Test
    fun `invalid current token is cryptographically erased before dead letter settlement`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'a')
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        val now = BASE_TIME.plusSeconds(2)
        val worker =
            NotificationOutboxDeliveryWorker(
                repository = outboxRepository,
                providers =
                mapOf(
                    PushProvider.APNS to
                        NotificationProvider {
                            NotificationProviderDeliveryResult.PermanentFailure(
                                errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                                diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                                invalidTokenVersion = 1,
                            )
                        },
                ),
                leaseOwner = "invalid-token-worker",
                clock = Clock.fixed(now, ZoneOffset.UTC),
                invalidRegistrationRetirer = PostgresInvalidPushRegistrationRetirer(appDataSource),
            )

        assertEquals(NotificationDeliveryRunSummary(1, 0, 0, 1, 0), worker.runOnce())
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.push_device_registrations " +
                    "WHERE registration_ref = 'client-registration-1' AND status = 'REVOKED' " +
                    "AND token_fingerprint IS NULL AND token_ciphertext IS NULL " +
                    "AND token_nonce IS NULL AND token_key_version IS NULL AND revoked_at IS NOT NULL",
            ),
        )
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_outbox " +
                    "WHERE status = 'DEAD_LETTER' AND last_error_code = 'REGISTRATION_REVOKED'",
            ),
        )
    }

    @Test
    fun `outage rotation and reconnect preserve one durable message and one catch up event`() {
        seedPushDevice("client-registration-1", "client-subject-1", "CLIENT", "NEXO_CLIENT_IOS", null, null, 'b')
        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        assertIs<DurableTextRepositoryResult.ReplayExisting>(
            messageRepository.persist(messageRequest(serverMessageRef = "server-message-duplicate")),
        )

        val outageWorker =
            deliveryWorker(BASE_TIME.plusSeconds(2)) {
                NotificationProviderDeliveryResult.RetryableFailure(
                    errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
                )
            }
        assertEquals(NotificationDeliveryRunSummary(1, 0, 1, 0, 0), outageWorker.runOnce())
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.notification_outbox"))

        val rotationRaceWorker =
            deliveryWorker(BASE_TIME.plusSeconds(33)) {
                rotateSeededRegistrationToVersionTwo()
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
                    invalidTokenVersion = 1,
                )
            }
        assertEquals(NotificationDeliveryRunSummary(1, 0, 1, 0, 0), rotationRaceWorker.runOnce())
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.push_device_registrations " +
                    "WHERE registration_ref = 'client-registration-1' AND status = 'ACTIVE' AND token_version = 2",
            ),
        )

        val recoveredWorker =
            deliveryWorker(BASE_TIME.plusSeconds(94)) { NotificationProviderDeliveryResult.Delivered() }
        assertEquals(NotificationDeliveryRunSummary(1, 1, 0, 0, 0), recoveredWorker.runOnce())
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_outbox " +
                    "WHERE status = 'DELIVERED' AND attempt_count = 3",
            ),
        )

        val catchUp = DurableConversationCatchUp(PostgresDurableMessageHistoryRepository(appDataSource))
        val recovered =
            assertIs<DurableConversationCatchUpResult.Loaded>(
                catchUp.load(
                    LoadDurableConversationCatchUpRequest(
                        principal = CLIENT_PRINCIPAL,
                        conversationRef = "conversation-1",
                        afterSequence = 0,
                        snapshotLastMessageSequence = 1,
                    ),
                ),
            )
        assertEquals(listOf("server-message-1"), recovered.events.map { it.serverMessageRef })
        val alreadyCaughtUp =
            assertIs<DurableConversationCatchUpResult.Loaded>(
                catchUp.load(
                    LoadDurableConversationCatchUpRequest(
                        principal = CLIENT_PRINCIPAL,
                        conversationRef = "conversation-1",
                        afterSequence = 1,
                        snapshotLastMessageSequence = 1,
                    ),
                ),
            )
        assertTrue(alreadyCaughtUp.events.isEmpty())
    }

    private fun deliveryWorker(now: Instant, provider: NotificationProvider): NotificationOutboxDeliveryWorker =
        NotificationOutboxDeliveryWorker(
            repository = outboxRepository,
            providers =
            mapOf(
                PushProvider.APNS to provider,
            ),
            leaseOwner = "offline-recovery-worker",
            clock = Clock.fixed(now, ZoneOffset.UTC),
            invalidRegistrationRetirer = PostgresInvalidPushRegistrationRetirer(appDataSource),
        )

    private fun rotateSeededRegistrationToVersionTwo() {
        adminDataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE connect.push_device_registrations
                SET token_fingerprint = ?, token_ciphertext = ?, token_nonce = ?,
                    token_key_version = 1, token_version = 2,
                    rotated_at = ?, updated_at = ?, version = version + 1
                WHERE registration_ref = 'client-registration-1'
                  AND status = 'ACTIVE'
                  AND token_version = 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "c".repeat(64))
                statement.setBytes(2, ByteArray(32) { 3 })
                statement.setBytes(3, ByteArray(12) { 4 })
                statement.setTimestamp(4, Timestamp.from(BASE_TIME.plusSeconds(33)))
                statement.setTimestamp(5, Timestamp.from(BASE_TIME.plusSeconds(33)))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun claimRequest(owner: String, now: Instant, durationSeconds: Long) = ClaimNotificationOutboxRequest(
        leaseOwner = owner,
        now = now,
        leaseDuration = Duration.ofSeconds(durationSeconds),
        limit = 10,
    )

    private fun putPreference(
        registrationRef: String,
        muted: Boolean,
        lockScreenPrivacy: NotificationLockScreenPrivacy,
        badgeMode: NotificationBadgeMode,
        quietMode: NotificationQuietMode,
    ): PutPushNotificationPreferenceResult.Updated = assertIs(
        preferenceRepository.put(
            preferenceRequest(
                registrationRef = registrationRef,
                muted = muted,
                lockScreenPrivacy = lockScreenPrivacy,
                badgeMode = badgeMode,
                quietMode = quietMode,
                expectedVersion = 0,
            ),
        ),
    )

    private fun preferenceRequest(
        registrationRef: String,
        muted: Boolean,
        lockScreenPrivacy: NotificationLockScreenPrivacy,
        badgeMode: NotificationBadgeMode,
        quietMode: NotificationQuietMode,
        expectedVersion: Long,
    ): PutPushNotificationPreferenceRequest = PutPushNotificationPreferenceRequest(
        principal = CLIENT_PRINCIPAL,
        conversationRef = "conversation-1",
        registrationRef = registrationRef,
        muted = muted,
        lockScreenPrivacy = lockScreenPrivacy,
        badgeMode = badgeMode,
        quietMode = quietMode,
        expectedVersion = expectedVersion,
        now = BASE_TIME.plusSeconds(1),
    )

    private fun resetDatabase() {
        executeAdmin(
            "TRUNCATE connect.notification_outbox, connect.push_device_registrations, connect.message_identities, connect.messages, connect.conversation_participants, connect.conversations RESTART IDENTITY CASCADE",
        )
    }

    private fun seedConversation() {
        executeAdmin(
            """
            INSERT INTO connect.conversations (
                conversation_ref, conversation_type, platform_scope_ref,
                organization_scope_ref, business_scope_ref, status,
                created_at, last_activity_at, last_message_sequence, version, schema_version
            ) VALUES (
                'conversation-1', 'BUSINESS_CLIENT', 'platform-1',
                'organization-1', 'business-1', 'ACTIVE',
                '${BASE_TIME}', '${BASE_TIME}', 0, 0, 1
            )
            """.trimIndent(),
        )
        executeAdmin(
            """
            INSERT INTO connect.conversation_participants (
                conversation_ref, subject_ref, actor_type, status,
                capabilities, joined_at, left_at
            ) VALUES
                ('conversation-1', 'business-subject-1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT'], '${BASE_TIME}', NULL),
                ('conversation-1', 'client-subject-1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], '${BASE_TIME}', NULL)
            """.trimIndent(),
        )
    }

    private fun seedPushDevice(
        registrationRef: String,
        subjectRef: String,
        actorType: String,
        application: String,
        organizationScopeRef: String?,
        businessScopeRef: String?,
        fingerprintCharacter: Char,
    ) {
        require(fingerprintCharacter in "0123456789abcdef") {
            "fingerprintCharacter must be lowercase hexadecimal"
        }
        adminDataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO connect.push_device_registrations (
                    registration_ref, platform_scope_ref, organization_scope_ref,
                    business_scope_ref, subject_ref, actor_type, application,
                    provider, environment, device_fingerprint, token_fingerprint,
                    token_ciphertext, token_nonce, token_key_version, token_version,
                    status, created_at, rotated_at, revoked_at, updated_at, version
                ) VALUES (?, 'platform-1', ?, ?, ?, ?, ?, 'APNS', 'SANDBOX', ?, ?, ?, ?, 1, 1,
                          'ACTIVE', ?, NULL, NULL, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, registrationRef)
                statement.setString(2, organizationScopeRef)
                statement.setString(3, businessScopeRef)
                statement.setString(4, subjectRef)
                statement.setString(5, actorType)
                statement.setString(6, application)
                statement.setString(7, fingerprintCharacter.toString().repeat(64))
                statement.setString(8, fingerprintCharacter.uppercaseChar().lowercaseChar().toString().repeat(64))
                statement.setBytes(9, ByteArray(32) { fingerprintCharacter.code.toByte() })
                statement.setBytes(10, ByteArray(12) { (fingerprintCharacter.code + 1).toByte() })
                statement.setTimestamp(11, Timestamp.from(BASE_TIME))
                statement.setTimestamp(12, Timestamp.from(BASE_TIME))
                statement.executeUpdate()
            }
        }
    }

    private fun messageRequest(
        principal: ConnectPrincipal = BUSINESS_PRINCIPAL,
        serverMessageRef: String = "server-message-1",
        clientMessageRef: String = "client-message-1",
        idempotencyKey: String = "idempotency-key-1",
    ) = DurableTextWriteRequest(
        principal = principal,
        command =
        SendTextMessageCommand(
            conversationRef = "conversation-1",
            senderSubjectRef = principal.subjectRef,
            identity =
            ClientMessageIdentity(
                clientMessageRef = clientMessageRef,
                idempotencyKey = idempotencyKey,
            ),
            body = TextMessageBody("private-message-body"),
        ),
        serverMessageRef = serverMessageRef,
        acceptedAtServer = BASE_TIME.plusSeconds(1),
    )

    private fun applicationConfig(): PostgresDatabaseConfig = PostgresDatabaseConfig(
        jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
        user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
        password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
        maximumPoolSize = 8,
    )

    private fun executeAdmin(sql: String) {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun scalarLong(sql: String): Long = adminDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
    }

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

    companion object {
        private val BASE_TIME = Instant.parse("2026-08-16T18:00:00Z")
        private val BUSINESS_PRINCIPAL =
            ConnectPrincipal(
                subjectRef = "business-subject-1",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )
        private val CLIENT_PRINCIPAL =
            ConnectPrincipal(
                subjectRef = "client-subject-1",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "platform-1",
                organizationScopeRef = null,
                businessScopeRef = null,
            )
    }
}
