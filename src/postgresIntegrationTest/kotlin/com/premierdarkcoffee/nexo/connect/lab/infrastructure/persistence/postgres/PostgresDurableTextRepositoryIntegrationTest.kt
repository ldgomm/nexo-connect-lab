package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageConflictReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostgresDurableTextRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: PostgresDurableTextRepository

    @BeforeTest
    fun setUp() {
        dataSource =
            PostgresDataSourceFactory.create(
                PostgresDatabaseConfig.fromEnvironment(),
            )
        repository = PostgresDurableTextRepository(dataSource)
        resetDatabase()
        seedConversation()
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `commits a new message and advances the conversation once`() {
        val result = repository.persist(request(index = 1))

        val committed = assertIs<DurableTextRepositoryResult.Committed>(result)
        assertEquals("server-message-1", committed.serverMessageRef)
        assertEquals(1, committed.sequence.value)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.message_identities"))
        assertEquals(1, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
        assertEquals(1, scalarLong("SELECT version FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
    }

    @Test
    fun `replays an identical command without a second write`() {
        val first = assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request(index = 1)))
        val replay =
            assertIs<DurableTextRepositoryResult.ReplayExisting>(
                repository.persist(request(index = 1, serverMessageRef = "server-message-retry")),
            )

        assertEquals(first.serverMessageRef, replay.serverMessageRef)
        assertEquals(first.sequence, replay.sequence)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(1, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
        assertEquals(1, scalarLong("SELECT version FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
    }

    @Test
    fun `rejects every idempotency reuse mode without changing durable state`() {
        assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request(index = 1)))

        val idempotencyReuse =
            repository.persist(
                request(
                    index = 2,
                    idempotencyKey = "idempotency-key-1",
                ),
            )
        val clientRefReuse =
            repository.persist(
                request(
                    index = 3,
                    clientMessageRef = "client-message-1",
                ),
            )
        val payloadReuse =
            repository.persist(
                request(
                    index = 1,
                    body = "Changed body",
                    serverMessageRef = "server-message-payload-change",
                ),
            )

        assertEquals(
            MessageConflictReason.IDEMPOTENCY_KEY_REUSED,
            assertIs<DurableTextRepositoryResult.Conflict>(idempotencyReuse).reason,
        )
        assertEquals(
            MessageConflictReason.CLIENT_MESSAGE_REF_REUSED,
            assertIs<DurableTextRepositoryResult.Conflict>(clientRefReuse).reason,
        )
        assertEquals(
            MessageConflictReason.PAYLOAD_MISMATCH,
            assertIs<DurableTextRepositoryResult.Conflict>(payloadReuse).reason,
        )
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(1, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
    }

    @Test
    fun `rechecks persisted scope participant state and capability inside the transaction`() {
        val wrongScope =
            businessPrincipal.copy(
                organizationScopeRef = "organization-other",
                businessScopeRef = "business-other",
            )
        val scopeDenied = repository.persist(request(index = 1, principal = wrongScope))
        assertEquals(
            DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
            assertIs<DurableTextRepositoryResult.Denied>(scopeDenied).reason,
        )

        execute("UPDATE connect.conversation_participants SET status = 'BLOCKED' WHERE conversation_ref = 'conversation-1' AND subject_ref = 'business-subject-1'")
        val participantDenied = repository.persist(request(index = 2))
        assertEquals(
            DurableTextAuthorizationDecision.DENY_PARTICIPANT_STATE,
            assertIs<DurableTextRepositoryResult.Denied>(participantDenied).reason,
        )

        execute("UPDATE connect.conversation_participants SET status = 'ACTIVE', capabilities = ARRAY[]::text[] WHERE conversation_ref = 'conversation-1' AND subject_ref = 'business-subject-1'")
        val capabilityDenied = repository.persist(request(index = 3))
        assertEquals(
            DurableTextAuthorizationDecision.DENY_CAPABILITY,
            assertIs<DurableTextRepositoryResult.Denied>(capabilityDenied).reason,
        )

        assertEquals(0, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(0, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
    }

    @Test
    fun `serializes concurrent unique sends into a gapless per conversation order`() {
        val count = 12
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)

        try {
            val futures =
                (1..count).map { index ->
                    executor.submit<DurableTextRepositoryResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.persist(request(index = index))
                    }
                }

            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val sequences =
                results.map { result ->
                    assertIs<DurableTextRepositoryResult.Committed>(result).sequence.value
                }.sorted()

            assertEquals((1L..count.toLong()).toList(), sequences)
            assertEquals(count.toLong(), scalarLong("SELECT count(*) FROM connect.messages"))
            assertEquals(count.toLong(), scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
            assertEquals(count.toLong(), scalarLong("SELECT version FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `serializes concurrent identical retries into one commit and stable replays`() {
        val count = 12
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)

        try {
            val futures =
                (1..count).map { attempt ->
                    executor.submit<DurableTextRepositoryResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.persist(
                            request(
                                index = 1,
                                serverMessageRef = "server-message-attempt-$attempt",
                            ),
                        )
                    }
                }

            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val committed = results.filterIsInstance<DurableTextRepositoryResult.Committed>()
            val replays = results.filterIsInstance<DurableTextRepositoryResult.ReplayExisting>()

            assertEquals(1, committed.size)
            assertEquals(count - 1, replays.size)
            assertTrue(replays.all { it.serverMessageRef == committed.single().serverMessageRef })
            assertTrue(replays.all { it.sequence == committed.single().sequence })
            assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
            assertEquals(1, scalarLong("SELECT count(*) FROM connect.message_identities"))
            assertEquals(1, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rolls back sequence allocation when the message insert fails`() {
        assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request(index = 1)))

        assertFailsWith<SQLException> {
            repository.persist(
                request(
                    index = 2,
                    serverMessageRef = "server-message-1",
                ),
            )
        }

        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.message_identities"))
        assertEquals(1, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))
        assertEquals(1, scalarLong("SELECT version FROM connect.conversations WHERE conversation_ref = 'conversation-1'"))

        val next = assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request(index = 3)))
        assertEquals(2, next.sequence.value)
    }

    @Test
    fun `rejects reuse of a sender identity in another conversation`() {
        seedConversation(conversationRef = "conversation-2")
        assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request(index = 1)))

        val result =
            repository.persist(
                request(
                    index = 1,
                    conversationRef = "conversation-2",
                    serverMessageRef = "server-message-cross-conversation",
                ),
            )

        assertEquals(
            MessageConflictReason.SCOPE_MISMATCH,
            assertIs<DurableTextRepositoryResult.Conflict>(result).reason,
        )
        assertEquals(0, scalarLong("SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-2'"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
    }

    private fun resetDatabase() {
        execute(
            "TRUNCATE connect.message_identities, connect.messages, connect.conversation_participants, connect.conversations RESTART IDENTITY CASCADE",
        )
    }

    private fun seedConversation(conversationRef: String = "conversation-1") {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO connect.conversations (
                        conversation_ref, conversation_type, platform_scope_ref,
                        organization_scope_ref, business_scope_ref, status,
                        created_at, last_activity_at, last_message_sequence, version, schema_version
                    ) VALUES (?, 'BUSINESS_CLIENT', 'platform-1', 'organization-1',
                              'business-1', 'ACTIVE', ?, ?, 0, 0, 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, conversationRef)
                    statement.setTimestamp(2, Timestamp.from(BASE_TIME))
                    statement.setTimestamp(3, Timestamp.from(BASE_TIME))
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO connect.conversation_participants (
                        conversation_ref, subject_ref, actor_type, status,
                        capabilities, joined_at, left_at
                    ) VALUES
                        (?, 'business-subject-1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT'], ?, NULL),
                        (?, 'client-subject-1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT'], ?, NULL)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, conversationRef)
                    statement.setTimestamp(2, Timestamp.from(BASE_TIME))
                    statement.setString(3, conversationRef)
                    statement.setTimestamp(4, Timestamp.from(BASE_TIME))
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun request(
        index: Int,
        conversationRef: String = "conversation-1",
        principal: ConnectPrincipal = businessPrincipal,
        idempotencyKey: String = "idempotency-key-$index",
        clientMessageRef: String = "client-message-$index",
        body: String = "Message $index",
        serverMessageRef: String = "server-message-$index",
    ): DurableTextWriteRequest =
        DurableTextWriteRequest(
            principal = principal,
            command =
                SendTextMessageCommand(
                    conversationRef = conversationRef,
                    senderSubjectRef = principal.subjectRef,
                    identity =
                        ClientMessageIdentity(
                            clientMessageRef = clientMessageRef,
                            idempotencyKey = idempotencyKey,
                        ),
                    body = TextMessageBody(body),
                ),
            serverMessageRef = serverMessageRef,
            acceptedAtServer = BASE_TIME.plusSeconds(index.toLong()),
        )

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun scalarLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
        }

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-11T20:00:00Z")

        private val businessPrincipal =
            ConnectPrincipal(
                subjectRef = "business-subject-1",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )
    }
}
