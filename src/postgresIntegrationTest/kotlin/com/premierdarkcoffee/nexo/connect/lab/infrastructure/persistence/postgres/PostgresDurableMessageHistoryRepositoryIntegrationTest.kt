package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableMessageHistoryRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryPage
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PostgresDurableMessageHistoryRepositoryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var conversationRepository: PostgresConversationRepository
    private lateinit var messageRepository: PostgresDurableTextRepository
    private lateinit var historyRepository: PostgresDurableMessageHistoryRepository

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        conversationRepository = PostgresConversationRepository(appDataSource)
        messageRepository = PostgresDurableTextRepository(appDataSource)
        historyRepository = PostgresDurableMessageHistoryRepository(appDataSource)
        resetDatabase()
    }

    @AfterTest
    fun tearDown() {
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `loads committed bodies only for an explicit participant without an absence oracle`() {
        createConversation("conversation-visible", clientPrincipal.subjectRef)
        createConversation("conversation-empty", "client-subject-empty")
        createConversation("conversation-other-client", "client-subject-other")

        persistMessage(
            conversationRef = "conversation-visible",
            principal = businessPrincipal,
            index = 1,
            body = "Business says hello",
            acceptedAt = BASE_TIME.plusSeconds(10),
        )
        persistMessage(
            conversationRef = "conversation-visible",
            principal = clientPrincipal,
            index = 2,
            body = "Client replies",
            acceptedAt = BASE_TIME.plusSeconds(20),
        )

        val businessPage = loadPage(businessPrincipal, "conversation-visible")
        assertEquals(listOf(2L, 1L), businessPage.items.map { it.sequence.value })
        assertEquals(listOf("Client replies", "Business says hello"), businessPage.items.map { it.body.value })
        assertEquals(
            listOf(ConnectActorType.CLIENT, ConnectActorType.BUSINESS),
            businessPage.items.map { it.senderActorType },
        )
        assertEquals(
            businessPage,
            loadPage(clientPrincipal, "conversation-visible"),
        )
        assertEquals(emptyList(), loadPage(businessPrincipal, "conversation-empty").items)

        val deniedRequests =
            listOf(
                LoadDurableMessageHistoryRequest(
                    principal = clientPrincipal.copy(subjectRef = "client-outsider"),
                    conversationRef = "conversation-visible",
                ),
                LoadDurableMessageHistoryRequest(
                    principal = clientPrincipal.copy(platformScopeRef = "platform-other"),
                    conversationRef = "conversation-visible",
                ),
                LoadDurableMessageHistoryRequest(
                    principal =
                        businessPrincipal.copy(
                            organizationScopeRef = "organization-other",
                            businessScopeRef = "business-other",
                        ),
                    conversationRef = "conversation-visible",
                ),
                LoadDurableMessageHistoryRequest(
                    principal = adminPrincipal,
                    conversationRef = "conversation-visible",
                ),
                LoadDurableMessageHistoryRequest(
                    principal = superadminPrincipal,
                    conversationRef = "conversation-visible",
                ),
                LoadDurableMessageHistoryRequest(
                    principal = businessPrincipal,
                    conversationRef = "conversation-missing",
                ),
                LoadDurableMessageHistoryRequest(
                    principal = clientPrincipal,
                    conversationRef = "conversation-other-client",
                ),
            )

        deniedRequests.forEach { request ->
            assertEquals(
                DurableMessageHistoryResult.NotFoundOrDenied,
                historyRepository.load(request),
            )
        }
    }

    @Test
    fun `uses exclusive sequence keyset pages that remain stable when newer messages arrive`() {
        createConversation("conversation-history", clientPrincipal.subjectRef)
        val acceptedOffsets = listOf(50L, 10L, 40L, 20L, 30L)
        acceptedOffsets.forEachIndexed { index, offset ->
            persistMessage(
                conversationRef = "conversation-history",
                principal = businessPrincipal,
                index = index + 1,
                body = "Message ${index + 1}",
                acceptedAt = BASE_TIME.plusSeconds(offset),
            )
        }

        val firstPage = loadPage(businessPrincipal, "conversation-history", pageSize = 2)
        assertEquals(listOf(5L, 4L), firstPage.items.map { it.sequence.value })
        assertEquals(4, firstPage.nextCursor?.beforeSequence?.value)

        persistMessage(
            conversationRef = "conversation-history",
            principal = clientPrincipal,
            index = 6,
            body = "Message 6",
            acceptedAt = BASE_TIME.plusSeconds(5),
        )

        val secondPage =
            loadPage(
                principal = businessPrincipal,
                conversationRef = "conversation-history",
                pageSize = 2,
                cursor = firstPage.nextCursor,
            )
        assertEquals(listOf(3L, 2L), secondPage.items.map { it.sequence.value })
        assertEquals(2, secondPage.nextCursor?.beforeSequence?.value)

        val thirdPage =
            loadPage(
                principal = businessPrincipal,
                conversationRef = "conversation-history",
                pageSize = 2,
                cursor = secondPage.nextCursor,
            )
        assertEquals(listOf(1L), thirdPage.items.map { it.sequence.value })
        assertNull(thirdPage.nextCursor)
        assertEquals(
            listOf(6L, 5L),
            loadPage(businessPrincipal, "conversation-history", pageSize = 2).items.map { it.sequence.value },
        )
    }

    @Test
    fun `history reads are durable across repository recreation and never mutate conversation state`() {
        createConversation("conversation-durable", clientPrincipal.subjectRef)
        repeat(3) { index ->
            persistMessage(
                conversationRef = "conversation-durable",
                principal = businessPrincipal,
                index = index + 1,
                body = "Durable ${index + 1}",
                acceptedAt = BASE_TIME.plusSeconds((index + 1).toLong()),
            )
        }
        val stateBefore = conversationState("conversation-durable")

        val firstRead = loadPage(clientPrincipal, "conversation-durable")
        historyRepository = PostgresDurableMessageHistoryRepository(appDataSource)
        val secondRead = loadPage(clientPrincipal, "conversation-durable")

        assertEquals(firstRead, secondRead)
        assertEquals(listOf("Durable 3", "Durable 2", "Durable 1"), secondRead.items.map { it.body.value })
        assertEquals(stateBefore, conversationState("conversation-durable"))
        assertEquals(3, scalarLong("SELECT count(*) FROM connect.messages WHERE conversation_ref = 'conversation-durable'"))
        assertEquals(3, scalarLong("SELECT count(*) FROM connect.message_identities WHERE conversation_ref = 'conversation-durable'"))
    }

    private fun createConversation(
        conversationRef: String,
        clientSubjectRef: String,
    ) {
        assertIs<ConversationCreationResult.Created>(
            conversationRepository.create(
                CreateBusinessClientConversationRequest(
                    principal = businessPrincipal,
                    command =
                        CreateBusinessClientConversationCommand(
                            conversationRef = conversationRef,
                            clientSubjectRef = clientSubjectRef,
                            requestedAt = BASE_TIME,
                        ),
                ),
            ),
        )
    }

    private fun persistMessage(
        conversationRef: String,
        principal: ConnectPrincipal,
        index: Int,
        body: String,
        acceptedAt: Instant,
    ) {
        val senderTag = principal.actorType.name.lowercase()
        assertIs<DurableTextRepositoryResult.Committed>(
            messageRepository.persist(
                DurableTextWriteRequest(
                    principal = principal,
                    command =
                        SendTextMessageCommand(
                            conversationRef = conversationRef,
                            senderSubjectRef = principal.subjectRef,
                            identity =
                                ClientMessageIdentity(
                                    clientMessageRef = "$conversationRef-$senderTag-client-$index",
                                    idempotencyKey = "$conversationRef-$senderTag-idempotency-$index",
                                ),
                            body = TextMessageBody(body),
                        ),
                    serverMessageRef = "$conversationRef-$senderTag-server-$index",
                    acceptedAtServer = acceptedAt,
                ),
            ),
        )
    }

    private fun loadPage(
        principal: ConnectPrincipal,
        conversationRef: String,
        pageSize: Int = LoadDurableMessageHistoryRequest.DEFAULT_PAGE_SIZE,
        cursor: DurableMessageHistoryCursor? = null,
    ): DurableMessageHistoryPage =
        assertIs<DurableMessageHistoryResult.Loaded>(
            historyRepository.load(
                LoadDurableMessageHistoryRequest(
                    principal = principal,
                    conversationRef = conversationRef,
                    pageSize = pageSize,
                    cursor = cursor,
                ),
            ),
        ).page

    private fun conversationState(conversationRef: String): List<Long> =
        adminDataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT last_message_sequence, version FROM connect.conversations WHERE conversation_ref = ?",
            ).use { statement ->
                statement.setString(1, conversationRef)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    listOf(resultSet.getLong("last_message_sequence"), resultSet.getLong("version"))
                }
            }
        }

    private fun scalarLong(sql: String): Long =
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
        }

    private fun applicationConfig(): PostgresDatabaseConfig =
        PostgresDatabaseConfig(
            jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
            user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
            password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
            maximumPoolSize = 16,
        )

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")

    private fun resetDatabase() {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE connect.business_client_conversation_keys, connect.message_identities, connect.messages, connect.conversation_participants, connect.conversations CASCADE",
                )
            }
        }
    }

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-12T00:00:00Z")

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
