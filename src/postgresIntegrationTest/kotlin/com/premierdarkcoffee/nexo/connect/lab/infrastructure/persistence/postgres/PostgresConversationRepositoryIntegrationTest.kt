package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationConflictReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationDenialReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresConversationRepositoryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var repository: PostgresConversationRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(b4ApplicationConfig())
        repository = PostgresConversationRepository(appDataSource)
        resetDatabase()
    }

    @AfterTest
    fun tearDown() {
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `creates one durable conversation and opens it for both explicit participants`() {
        val created = assertIs<ConversationCreationResult.Created>(repository.create(createRequest()))

        assertEquals("conversation-1", created.conversation.scope.conversationRef)
        assertEquals(2, created.conversation.scope.participants.size)
        assertEquals(0, created.conversation.lastMessageSequence.value)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.conversations"))
        assertEquals(2, scalarLong("SELECT count(*) FROM connect.conversation_participants"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.business_client_conversation_keys"))

        val openedByBusiness =
            assertIs<OpenConversationResult.Opened>(
                repository.open(OpenConversationRequest(businessPrincipal, "conversation-1")),
            )
        val openedByClient =
            assertIs<OpenConversationResult.Opened>(
                repository.open(OpenConversationRequest(clientPrincipal, "conversation-1")),
            )

        assertEquals(created.conversation, openedByBusiness.conversation)
        assertEquals(created.conversation, openedByClient.conversation)
    }

    @Test
    fun `replays the scoped participant pair even when a retry proposes another ref`() {
        val first = assertIs<ConversationCreationResult.Created>(repository.create(createRequest()))
        val replay =
            assertIs<ConversationCreationResult.Existing>(
                repository.create(createRequest(conversationRef = "conversation-retry")),
            )

        assertEquals(first.conversation, replay.conversation)
        assertEquals("conversation-1", replay.conversation.scope.conversationRef)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.conversations"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.business_client_conversation_keys"))
    }

    @Test
    fun `serializes concurrent creation into one conversation and stable replays`() {
        val count = 12
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)

        try {
            val futures =
                (1..count).map { attempt ->
                    executor.submit<ConversationCreationResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.create(createRequest(conversationRef = "conversation-attempt-$attempt"))
                    }
                }

            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val created = results.filterIsInstance<ConversationCreationResult.Created>()
            val existing = results.filterIsInstance<ConversationCreationResult.Existing>()

            assertEquals(1, created.size)
            assertEquals(count - 1, existing.size)
            val durableRef = created.single().conversation.scope.conversationRef
            assertTrue(existing.all { it.conversation.scope.conversationRef == durableRef })
            assertEquals(1, scalarLong("SELECT count(*) FROM connect.conversations"))
            assertEquals(2, scalarLong("SELECT count(*) FROM connect.conversation_participants"))
            assertEquals(1, scalarLong("SELECT count(*) FROM connect.business_client_conversation_keys"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejects conversation ref reuse for another direct pair without extra writes`() {
        assertIs<ConversationCreationResult.Created>(repository.create(createRequest()))

        val conflict =
            assertIs<ConversationCreationResult.Conflict>(
                repository.create(
                    createRequest(
                        conversationRef = "conversation-1",
                        clientSubjectRef = "client-subject-2",
                    ),
                ),
            )

        assertEquals(
            ConversationCreationConflictReason.CONVERSATION_REF_ALREADY_BOUND,
            conflict.reason,
        )
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.conversations"))
        assertEquals(2, scalarLong("SELECT count(*) FROM connect.conversation_participants"))
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.business_client_conversation_keys"))
    }

    @Test
    fun `denies client initiated creation without durable writes`() {
        val result =
            repository.create(
                CreateBusinessClientConversationRequest(
                    principal = clientPrincipal,
                    command =
                    CreateBusinessClientConversationCommand(
                        conversationRef = "conversation-client-created",
                        clientSubjectRef = "another-client-subject",
                        requestedAt = BASE_TIME,
                    ),
                ),
            )

        assertEquals(
            ConversationCreationDenialReason.CREATOR_NOT_SCOPED_BUSINESS,
            assertIs<ConversationCreationResult.Denied>(result).reason,
        )
        assertEquals(0, scalarLong("SELECT count(*) FROM connect.conversations"))
    }

    @Test
    fun `does not distinguish absent conversations from unauthorized conversations`() {
        assertIs<ConversationCreationResult.Created>(repository.create(createRequest()))
        val outsider = clientPrincipal.copy(subjectRef = "client-outsider")
        val wrongBusiness =
            businessPrincipal.copy(
                subjectRef = "business-outsider",
                businessScopeRef = "business-other",
            )
        val superadmin =
            ConnectPrincipal(
                subjectRef = "superadmin-1",
                actorType = ConnectActorType.SUPERADMIN,
                platformScopeRef = "platform-1",
            )

        listOf(outsider, wrongBusiness, superadmin).forEach { principal ->
            assertSame(
                OpenConversationResult.NotFoundOrDenied,
                repository.open(OpenConversationRequest(principal, "conversation-1")),
            )
        }
        assertSame(
            OpenConversationResult.NotFoundOrDenied,
            repository.open(OpenConversationRequest(clientPrincipal, "conversation-absent")),
        )
    }

    @Test
    fun `a created conversation is immediately compatible with durable text persistence`() {
        assertIs<ConversationCreationResult.Created>(repository.create(createRequest()))
        val messageRepository = PostgresDurableTextRepository(appDataSource)

        val committed =
            assertIs<DurableTextRepositoryResult.Committed>(
                messageRepository.persist(
                    DurableTextWriteRequest(
                        principal = businessPrincipal,
                        command =
                        SendTextMessageCommand(
                            conversationRef = "conversation-1",
                            senderSubjectRef = businessPrincipal.subjectRef,
                            identity =
                            ClientMessageIdentity(
                                clientMessageRef = "client-message-1",
                                idempotencyKey = "idempotency-key-1",
                            ),
                            body = TextMessageBody("Created then sent"),
                        ),
                        serverMessageRef = "server-message-1",
                        acceptedAtServer = BASE_TIME.plusSeconds(1),
                    ),
                ),
            )

        assertEquals(1, committed.sequence.value)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.messages"))
    }

    @Test
    fun `rolls back conversation and memberships when the final key insert fails`() {
        executeAdmin(
            """
            CREATE OR REPLACE FUNCTION connect.reject_b4_key_for_test()
            RETURNS trigger LANGUAGE plpgsql AS ${'$'}function${'$'}
            BEGIN
                RAISE EXCEPTION 'forced B4 key failure';
            END
            ${'$'}function${'$'};
            """.trimIndent(),
        )
        executeAdmin(
            """
            CREATE TRIGGER reject_b4_key_for_test
            BEFORE INSERT ON connect.business_client_conversation_keys
            FOR EACH ROW EXECUTE FUNCTION connect.reject_b4_key_for_test()
            """.trimIndent(),
        )

        try {
            assertFailsWith<SQLException> { repository.create(createRequest()) }
            assertEquals(0, scalarLong("SELECT count(*) FROM connect.conversations"))
            assertEquals(0, scalarLong("SELECT count(*) FROM connect.conversation_participants"))
            assertEquals(0, scalarLong("SELECT count(*) FROM connect.business_client_conversation_keys"))
        } finally {
            executeAdmin(
                "DROP TRIGGER IF EXISTS reject_b4_key_for_test ON connect.business_client_conversation_keys",
            )
            executeAdmin(
                "DROP FUNCTION IF EXISTS connect.reject_b4_key_for_test()",
            )
        }
    }

    private fun createRequest(
        conversationRef: String = "conversation-1",
        clientSubjectRef: String = "client-subject-1",
    ): CreateBusinessClientConversationRequest = CreateBusinessClientConversationRequest(
        principal = businessPrincipal,
        command =
        CreateBusinessClientConversationCommand(
            conversationRef = conversationRef,
            clientSubjectRef = clientSubjectRef,
            requestedAt = BASE_TIME,
        ),
    )

    private fun b4ApplicationConfig(): PostgresDatabaseConfig = PostgresDatabaseConfig(
        jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
        user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
        password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
        maximumPoolSize = 16,
    )

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

    private fun resetDatabase() {
        executeAdmin(
            "TRUNCATE connect.notification_outbox, connect.push_device_registrations, connect.business_client_conversation_keys, connect.message_identities, connect.messages, connect.conversation_participants, connect.conversations CASCADE",
        )
    }

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

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-11T22:00:00Z")

        private val businessPrincipal =
            ConnectPrincipal(
                subjectRef = "business-subject-1",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
            )

        private val clientPrincipal =
            ConnectPrincipal(
                subjectRef = "client-subject-1",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "platform-1",
            )
    }
}
