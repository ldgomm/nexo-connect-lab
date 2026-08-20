package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingDenialReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListConversationsRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationListCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PostgresConversationListingIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var conversationRepository: PostgresConversationRepository
    private lateinit var messageRepository: PostgresDurableTextRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        conversationRepository = PostgresConversationRepository(appDataSource)
        messageRepository = PostgresDurableTextRepository(appDataSource)
        resetDatabase()
    }

    @AfterTest
    fun tearDown() {
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `lists only explicit participants within the principal durable scope`() {
        val businessTwo =
            businessPrincipal.copy(
                subjectRef = "business-subject-2",
                businessScopeRef = "business-2",
            )
        val platformTwoBusiness =
            businessPrincipal.copy(
                subjectRef = "business-subject-platform-2",
                platformScopeRef = "platform-2",
                organizationScopeRef = "organization-2",
                businessScopeRef = "business-platform-2",
            )

        createConversation(businessPrincipal, "client-subject-1", "conversation-1", BASE_TIME)
        createConversation(businessPrincipal, "client-subject-2", "conversation-2", BASE_TIME.plusSeconds(1))
        createConversation(businessTwo, "client-subject-1", "conversation-3", BASE_TIME.plusSeconds(2))
        createConversation(platformTwoBusiness, "client-subject-1", "conversation-4", BASE_TIME.plusSeconds(3))
        executeAdmin(
            """
            UPDATE connect.conversation_participants
            SET status = 'LEFT', left_at = '${BASE_TIME.plusSeconds(4)}'
            WHERE conversation_ref = 'conversation-1'
              AND subject_ref = 'client-subject-1'
            """.trimIndent(),
        )

        assertEquals(
            listOf("conversation-2", "conversation-1"),
            listedRefs(list(businessPrincipal)),
        )
        assertEquals(
            listOf("conversation-3", "conversation-1"),
            listedRefs(list(clientPrincipal)),
        )
        assertEquals(
            listOf("conversation-4"),
            listedRefs(list(clientPrincipal.copy(platformScopeRef = "platform-2"))),
        )
        assertEquals(
            emptyList(),
            listedRefs(list(clientPrincipal.copy(subjectRef = "client-outsider"))),
        )
        assertEquals(
            emptyList(),
            listedRefs(
                list(
                    businessPrincipal.copy(
                        organizationScopeRef = "organization-other",
                        businessScopeRef = "business-other",
                    ),
                ),
            ),
        )

        val adminResult = conversationRepository.listForParticipant(ListConversationsRequest(adminPrincipal))
        assertEquals(
            ConversationListingDenialReason.PRINCIPAL_TYPE_NOT_SUPPORTED,
            assertIs<ConversationListingResult.Denied>(adminResult).reason,
        )
        val superadminResult = conversationRepository.listForParticipant(ListConversationsRequest(superadminPrincipal))
        assertEquals(
            ConversationListingDenialReason.PRINCIPAL_TYPE_NOT_SUPPORTED,
            assertIs<ConversationListingResult.Denied>(superadminResult).reason,
        )
    }

    @Test
    fun `uses deterministic exclusive keyset pagination and durable activity order`() {
        createConversation(businessPrincipal, "client-subject-a", "conversation-a", BASE_TIME)
        createConversation(businessPrincipal, "client-subject-c", "conversation-c", BASE_TIME)
        createConversation(businessPrincipal, "client-subject-b", "conversation-b", BASE_TIME)

        val firstPage = list(businessPrincipal, pageSize = 2)
        assertEquals(listOf("conversation-c", "conversation-b"), listedRefs(firstPage))
        assertEquals("conversation-b", assertNotNull(firstPage.nextCursor).conversationRef)

        val secondPage = list(businessPrincipal, pageSize = 2, cursor = firstPage.nextCursor)
        assertEquals(listOf("conversation-a"), listedRefs(secondPage))
        assertNull(secondPage.nextCursor)

        val afterEnd = list(businessPrincipal, pageSize = 2, cursor = secondPage.items.single().cursor())
        assertEquals(emptyList(), listedRefs(afterEnd))
        assertNull(afterEnd.nextCursor)

        val firstMessage = persistMessage("conversation-a", index = 1, acceptedAt = BASE_TIME.plusSeconds(20))
        assertIs<DurableTextRepositoryResult.Committed>(firstMessage)
        val afterMessage = list(businessPrincipal)
        assertEquals(listOf("conversation-a", "conversation-c", "conversation-b"), listedRefs(afterMessage))
        assertEquals(BASE_TIME.plusSeconds(20), afterMessage.items.first().lastActivityAt)
        assertEquals(1, afterMessage.items.first().conversation.lastMessageSequence.value)

        val replay = persistMessage("conversation-a", index = 1, acceptedAt = BASE_TIME.plusSeconds(30))
        assertIs<DurableTextRepositoryResult.ReplayExisting>(replay)
        assertEquals(BASE_TIME.plusSeconds(20), list(businessPrincipal).items.first().lastActivityAt)

        val olderAcceptedMessage = persistMessage("conversation-a", index = 2, acceptedAt = BASE_TIME.plusSeconds(10))
        assertIs<DurableTextRepositoryResult.Committed>(olderAcceptedMessage)
        val afterOlderAcceptedMessage = list(businessPrincipal)
        assertEquals(BASE_TIME.plusSeconds(20), afterOlderAcceptedMessage.items.first().lastActivityAt)
        assertEquals(2, afterOlderAcceptedMessage.items.first().conversation.lastMessageSequence.value)
    }

    @Test
    fun `rolls back activity and sequence when message persistence fails`() {
        createConversation(businessPrincipal, "client-subject-1", "conversation-1", BASE_TIME)
        executeAdmin(
            """
            CREATE OR REPLACE FUNCTION connect.reject_b5_message_for_test()
            RETURNS trigger LANGUAGE plpgsql AS ${'$'}function${'$'}
            BEGIN
                RAISE EXCEPTION 'forced B5 message failure';
            END
            ${'$'}function${'$'};
            """.trimIndent(),
        )
        executeAdmin(
            """
            CREATE TRIGGER reject_b5_message_for_test
            BEFORE INSERT ON connect.messages
            FOR EACH ROW EXECUTE FUNCTION connect.reject_b5_message_for_test()
            """.trimIndent(),
        )

        try {
            assertFailsWith<SQLException> {
                persistMessage("conversation-1", index = 1, acceptedAt = BASE_TIME.plusSeconds(100))
            }
            assertEquals(
                0,
                scalarLong(
                    "SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'conversation-1'",
                ),
            )
            assertEquals(
                BASE_TIME,
                scalarInstant(
                    "SELECT last_activity_at FROM connect.conversations WHERE conversation_ref = 'conversation-1'",
                ),
            )
            assertEquals(0, scalarLong("SELECT count(*) FROM connect.messages"))
        } finally {
            executeAdmin("DROP TRIGGER IF EXISTS reject_b5_message_for_test ON connect.messages")
            executeAdmin("DROP FUNCTION IF EXISTS connect.reject_b5_message_for_test()")
        }
    }

    private fun createConversation(
        business: ConnectPrincipal,
        clientSubjectRef: String,
        conversationRef: String,
        requestedAt: Instant,
    ) {
        assertIs<ConversationCreationResult.Created>(
            conversationRepository.create(
                CreateBusinessClientConversationRequest(
                    principal = business,
                    command =
                    CreateBusinessClientConversationCommand(
                        conversationRef = conversationRef,
                        clientSubjectRef = clientSubjectRef,
                        requestedAt = requestedAt,
                    ),
                ),
            ),
        )
    }

    private fun list(
        principal: ConnectPrincipal,
        pageSize: Int = ListConversationsRequest.DEFAULT_PAGE_SIZE,
        cursor: ConversationListCursor? = null,
    ) = assertIs<ConversationListingResult.Listed>(
        conversationRepository.listForParticipant(
            ListConversationsRequest(
                principal = principal,
                pageSize = pageSize,
                cursor = cursor,
            ),
        ),
    ).page

    private fun listedRefs(
        result: com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationListPage,
    ): List<String> = result.items.map { it.conversationRef }

    private fun persistMessage(conversationRef: String, index: Int, acceptedAt: Instant): DurableTextRepositoryResult =
        messageRepository.persist(
            DurableTextWriteRequest(
                principal = businessPrincipal,
                command =
                SendTextMessageCommand(
                    conversationRef = conversationRef,
                    senderSubjectRef = businessPrincipal.subjectRef,
                    identity =
                    ClientMessageIdentity(
                        clientMessageRef = "client-message-$index",
                        idempotencyKey = "idempotency-key-$index",
                    ),
                    body = TextMessageBody("Message $index"),
                ),
                serverMessageRef = "server-message-$index",
                acceptedAtServer = acceptedAt,
            ),
        )

    private fun applicationConfig(): PostgresDatabaseConfig = PostgresDatabaseConfig(
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

    private fun scalarInstant(sql: String): Instant = adminDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                check(resultSet.next())
                resultSet.getTimestamp(1).toInstant()
            }
        }
    }

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-11T23:00:00Z")

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

        private val adminPrincipal =
            ConnectPrincipal(
                subjectRef = "admin-subject-1",
                actorType = ConnectActorType.ADMIN,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
            )

        private val superadminPrincipal =
            ConnectPrincipal(
                subjectRef = "superadmin-subject-1",
                actorType = ConnectActorType.SUPERADMIN,
                platformScopeRef = "platform-1",
            )
    }
}
