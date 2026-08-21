package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ApplyConversationBlockRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationBlockMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokeConversationBlockRequest
import com.premierdarkcoffee.nexo.connect.lab.application.safety.ConversationBlockAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.safety.ConversationBlockAuthorizationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.safety.DenyByDefaultConversationBlockAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationBlockStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScope
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScopeType
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresConversationSafetyRepositoryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var blockRepository: PostgresConversationBlockRepository
    private lateinit var blockAuthorizer: DenyByDefaultConversationBlockAuthorizer
    private lateinit var preferenceRepository: PostgresPushNotificationPreferenceRepository
    private lateinit var messageRepository: PostgresDurableTextRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        val blockRefSequence = AtomicInteger()
        val blockAuditSequence = AtomicInteger()
        val muteAuditSequence = AtomicInteger()
        blockRepository = PostgresConversationBlockRepository(
            dataSource = appDataSource,
            blockRefSupplier = { "conversation-block-${blockRefSequence.incrementAndGet()}" },
            auditRefSupplier = { "conversation-block-audit-${blockAuditSequence.incrementAndGet()}" },
        )
        blockAuthorizer = DenyByDefaultConversationBlockAuthorizer(blockRepository)
        preferenceRepository = PostgresPushNotificationPreferenceRepository(
            dataSource = appDataSource,
            auditRefSupplier = { "notification-mute-audit-${muteAuditSequence.incrementAndGet()}" },
        )
        messageRepository = PostgresDurableTextRepository(appDataSource)
        executeAdmin("TRUNCATE connect.conversations RESTART IDENTITY CASCADE")
        seedConversation()
    }

    @AfterTest
    fun tearDown() {
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `directional blocks are version fenced audited and deny communication in either direction`() {
        val businessBlock = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.apply(applyRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 0)),
        )
        assertTrue(businessBlock.created)
        assertTrue(businessBlock.changed)
        assertEquals(ConversationBlockStatus.ACTIVE, businessBlock.block.status)
        assertEquals(1L, businessBlock.block.version)

        val replay = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.apply(applyRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 1)),
        )
        assertFalse(replay.created)
        assertFalse(replay.changed)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.conversation_block_audit_events"))
        assertSame(
            ConversationBlockMutationResult.NotFoundOrDenied,
            blockRepository.apply(applyRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 0)),
        )

        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK,
            blockAuthorizer.authorize(authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT)),
        )
        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK,
            blockAuthorizer.authorize(authorizationRequest(CLIENT_PARTICIPANT, BUSINESS_PARTICIPANT)),
        )

        val reciprocal = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.apply(applyRequest(CLIENT_PRINCIPAL, BUSINESS_PARTICIPANT, expectedVersion = 0)),
        )
        assertTrue(reciprocal.created)
        assertEquals(2, scalarLong("SELECT count(*) FROM connect.conversation_blocks"))

        val businessRevoked = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.revoke(revokeRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 1)),
        )
        assertTrue(businessRevoked.changed)
        assertEquals(ConversationBlockStatus.REVOKED, businessRevoked.block.status)
        assertEquals(2L, businessRevoked.block.version)
        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK,
            blockAuthorizer.authorize(authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT)),
        )

        val clientRevoked = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.revoke(revokeRequest(CLIENT_PRINCIPAL, BUSINESS_PARTICIPANT, expectedVersion = 1)),
        )
        assertEquals(ConversationBlockStatus.REVOKED, clientRevoked.block.status)
        assertEquals(
            ConversationBlockAuthorizationDecision.ALLOW,
            blockAuthorizer.authorize(authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT)),
        )
        val revokedReplay = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.revoke(revokeRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 2)),
        )
        assertFalse(revokedReplay.changed)
        assertEquals(4, scalarLong("SELECT count(*) FROM connect.conversation_block_audit_events"))

        val reapplied = assertIs<ConversationBlockMutationResult.Updated>(
            blockRepository.apply(applyRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 2)),
        )
        assertTrue(reapplied.changed)
        assertEquals(ConversationBlockStatus.ACTIVE, reapplied.block.status)
        assertEquals(3L, reapplied.block.version)
        assertSame(
            ConversationBlockMutationResult.NotFoundOrDenied,
            blockRepository.revoke(revokeRequest(BUSINESS_PRINCIPAL, CLIENT_PARTICIPANT, expectedVersion = 2)),
        )
        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK,
            blockAuthorizer.authorize(authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT)),
        )
        assertEquals(5, scalarLong("SELECT count(*) FROM connect.conversation_block_audit_events"))
        assertEquals(
            3,
            scalarLong("SELECT count(*) FROM connect.conversation_block_audit_events WHERE action = 'APPLIED'"),
        )
        assertEquals(
            2,
            scalarLong("SELECT count(*) FROM connect.conversation_block_audit_events WHERE action = 'REVOKED'"),
        )

        assertFailsWith<SQLException> {
            executeApp("UPDATE connect.conversation_block_audit_events SET action = 'APPLIED'")
        }
    }

    @Test
    fun `scope guessing is uniform and an unresolved block authority never allows`() {
        assertSame(
            ConversationBlockMutationResult.NotFoundOrDenied,
            blockRepository.apply(
                applyRequest(CLIENT_OTHER_PLATFORM, BUSINESS_PARTICIPANT, expectedVersion = 0),
            ),
        )
        assertSame(
            ConversationBlockMutationResult.NotFoundOrDenied,
            blockRepository.apply(
                applyRequest(BUSINESS_PRINCIPAL, UNKNOWN_CLIENT, expectedVersion = 0),
            ),
        )
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.conversation_blocks"))

        val wrongScope = authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT).copy(
            scope = SAFETY_SCOPE.copy(platformScopeRef = "platform-2"),
        )
        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_NOT_FOUND_OR_SCOPE,
            blockAuthorizer.authorize(wrongScope),
        )

        appDataSource.close()
        assertEquals(
            ConversationBlockAuthorizationDecision.DENY_AUTHORITY_UNAVAILABLE,
            blockAuthorizer.authorize(authorizationRequest(BUSINESS_PARTICIPANT, CLIENT_PARTICIPANT)),
        )
    }

    @Test
    fun `notification mute appends immutable audit without changing durable delivery truth`() {
        seedClientPushDevice()
        val muted = assertIs<PutPushNotificationPreferenceResult.Updated>(
            preferenceRepository.put(preferenceRequest(muted = true, expectedVersion = 0, now = NOW.plusSeconds(1))),
        )
        assertEquals(1L, muted.preference.version)
        assertEquals(
            1,
            scalarLong("SELECT count(*) FROM connect.notification_mute_audit_events WHERE action = 'APPLIED'"),
        )

        val privacyOnlyUpdate = assertIs<PutPushNotificationPreferenceResult.Updated>(
            preferenceRepository.put(
                preferenceRequest(
                    muted = true,
                    expectedVersion = muted.preference.version,
                    now = NOW.plusSeconds(2),
                    lockScreenPrivacy = NotificationLockScreenPrivacy.HIDDEN,
                ),
            ),
        )
        assertEquals(2L, privacyOnlyUpdate.preference.version)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.notification_mute_audit_events"))

        assertIs<DurableTextRepositoryResult.Committed>(messageRepository.persist(messageRequest()))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.notification_outbox"))

        val unmuted = assertIs<PutPushNotificationPreferenceResult.Updated>(
            preferenceRepository.put(
                preferenceRequest(
                    muted = false,
                    expectedVersion = privacyOnlyUpdate.preference.version,
                    now = NOW.plusSeconds(3),
                ),
            ),
        )
        assertEquals(3L, unmuted.preference.version)
        assertEquals(
            1,
            scalarLong("SELECT count(*) FROM connect.notification_mute_audit_events WHERE action = 'REVOKED'"),
        )
        assertEquals(
            1,
            scalarLong(
                "SELECT count(*) FROM connect.notification_mute_audit_events " +
                    "WHERE action = 'REVOKED' AND resulting_version = 3",
            ),
        )
        assertFailsWith<SQLException> {
            executeApp("UPDATE connect.notification_mute_audit_events SET action = 'APPLIED'")
        }
    }

    private fun applyRequest(
        principal: ConnectPrincipal,
        target: ConversationSafetyParticipant,
        expectedVersion: Long,
    ): ApplyConversationBlockRequest = ApplyConversationBlockRequest(
        principal = principal,
        conversationRef = SAFETY_SCOPE.conversationRef,
        blockedSubjectRef = target.subjectRef,
        blockedActorType = target.actorType,
        expectedVersion = expectedVersion,
        now = NOW.plusSeconds(expectedVersion + 1),
    )

    private fun revokeRequest(
        principal: ConnectPrincipal,
        target: ConversationSafetyParticipant,
        expectedVersion: Long,
    ): RevokeConversationBlockRequest = RevokeConversationBlockRequest(
        principal = principal,
        conversationRef = SAFETY_SCOPE.conversationRef,
        blockedSubjectRef = target.subjectRef,
        blockedActorType = target.actorType,
        expectedVersion = expectedVersion,
        now = NOW.plusSeconds(expectedVersion + 10),
    )

    private fun authorizationRequest(
        first: ConversationSafetyParticipant,
        second: ConversationSafetyParticipant,
    ): ConversationBlockAuthorizationRequest = ConversationBlockAuthorizationRequest(SAFETY_SCOPE, first, second)

    private fun preferenceRequest(
        muted: Boolean,
        expectedVersion: Long,
        now: Instant,
        lockScreenPrivacy: NotificationLockScreenPrivacy = NotificationLockScreenPrivacy.GENERIC,
    ): PutPushNotificationPreferenceRequest = PutPushNotificationPreferenceRequest(
        principal = CLIENT_PRINCIPAL,
        conversationRef = SAFETY_SCOPE.conversationRef,
        registrationRef = CLIENT_REGISTRATION_REF,
        muted = muted,
        lockScreenPrivacy = lockScreenPrivacy,
        badgeMode = NotificationBadgeMode.SET_ONE,
        quietMode = NotificationQuietMode.OFF,
        expectedVersion = expectedVersion,
        now = now,
    )

    private fun messageRequest(): DurableTextWriteRequest = DurableTextWriteRequest(
        principal = BUSINESS_PRINCIPAL,
        command = SendTextMessageCommand(
            conversationRef = SAFETY_SCOPE.conversationRef,
            senderSubjectRef = BUSINESS_PRINCIPAL.subjectRef,
            identity = ClientMessageIdentity(
                clientMessageRef = "client-message-safety-1",
                idempotencyKey = "idempotency-safety-1",
            ),
            body = TextMessageBody("durable truth remains independent from notification mute"),
        ),
        serverMessageRef = "server-message-safety-1",
        acceptedAtServer = NOW.plusSeconds(4),
    )

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
                '$NOW', '$NOW', 0, 0, 1
            )
            """.trimIndent(),
        )
        executeAdmin(
            """
            INSERT INTO connect.conversation_participants (
                conversation_ref, subject_ref, actor_type, status,
                capabilities, joined_at, left_at
            ) VALUES
                ('conversation-1', 'business-subject-1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT'], '$NOW', NULL),
                ('conversation-1', 'client-subject-1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], '$NOW', NULL)
            """.trimIndent(),
        )
    }

    private fun seedClientPushDevice() {
        adminDataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO connect.push_device_registrations (
                    registration_ref, platform_scope_ref, organization_scope_ref,
                    business_scope_ref, subject_ref, actor_type, application,
                    provider, environment, device_fingerprint, token_fingerprint,
                    token_ciphertext, token_nonce, token_key_version, token_version,
                    status, created_at, rotated_at, revoked_at, updated_at, version
                ) VALUES (?, 'platform-1', NULL, NULL, 'client-subject-1', 'CLIENT', 'NEXO_CLIENT_IOS',
                          'APNS', 'SANDBOX', ?, ?, ?, ?, 1, 1,
                          'ACTIVE', ?, NULL, NULL, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, CLIENT_REGISTRATION_REF)
                statement.setString(2, "a".repeat(64))
                statement.setString(3, "b".repeat(64))
                statement.setBytes(4, ByteArray(32) { 3 })
                statement.setBytes(5, ByteArray(12) { 4 })
                statement.setTimestamp(6, Timestamp.from(NOW))
                statement.setTimestamp(7, Timestamp.from(NOW))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun executeAdmin(sql: String) {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun executeApp(sql: String) {
        appDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
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

    private fun applicationConfig(): PostgresDatabaseConfig = PostgresDatabaseConfig(
        jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
        user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
        password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
        maximumPoolSize = 8,
    )

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T18:00:00Z")
        const val CLIENT_REGISTRATION_REF = "client-registration-safety-1"
        val SAFETY_SCOPE = ConversationSafetyScope(
            type = ConversationSafetyScopeType.CONVERSATION,
            conversationRef = "conversation-1",
            platformScopeRef = "platform-1",
            organizationScopeRef = "organization-1",
            businessScopeRef = "business-1",
        )
        val BUSINESS_PARTICIPANT = ConversationSafetyParticipant("business-subject-1", ConnectActorType.BUSINESS)
        val CLIENT_PARTICIPANT = ConversationSafetyParticipant("client-subject-1", ConnectActorType.CLIENT)
        val UNKNOWN_CLIENT = ConversationSafetyParticipant("client-subject-unknown", ConnectActorType.CLIENT)
        val BUSINESS_PRINCIPAL = ConnectPrincipal(
            subjectRef = BUSINESS_PARTICIPANT.subjectRef,
            actorType = BUSINESS_PARTICIPANT.actorType,
            platformScopeRef = "platform-1",
            organizationScopeRef = "organization-1",
            businessScopeRef = "business-1",
        )
        val CLIENT_PRINCIPAL = ConnectPrincipal(
            subjectRef = CLIENT_PARTICIPANT.subjectRef,
            actorType = CLIENT_PARTICIPANT.actorType,
            platformScopeRef = "platform-1",
        )
        val CLIENT_OTHER_PLATFORM = CLIENT_PRINCIPAL.copy(platformScopeRef = "platform-2")
    }
}
