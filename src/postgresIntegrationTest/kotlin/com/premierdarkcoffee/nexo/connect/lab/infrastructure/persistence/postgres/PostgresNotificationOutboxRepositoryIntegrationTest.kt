package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.MarkNotificationDeliveredRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RecordNotificationFailureRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresNotificationOutboxRepositoryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var messageRepository: PostgresDurableTextRepository
    private lateinit var outboxRepository: PostgresNotificationOutboxRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        messageRepository = PostgresDurableTextRepository(appDataSource)
        outboxRepository = PostgresNotificationOutboxRepository(appDataSource)
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

    private fun claimRequest(owner: String, now: Instant, durationSeconds: Long) = ClaimNotificationOutboxRequest(
        leaseOwner = owner,
        now = now,
        leaseDuration = Duration.ofSeconds(durationSeconds),
        limit = 10,
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
