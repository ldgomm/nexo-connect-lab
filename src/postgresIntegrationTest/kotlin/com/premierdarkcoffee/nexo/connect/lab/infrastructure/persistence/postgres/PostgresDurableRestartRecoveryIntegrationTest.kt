package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListConversationsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableMessageHistoryRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.CreateBusinessClientConversationCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryCursor
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostgresDurableRestartRecoveryIntegrationTest {
    @Test
    fun `recovers durable messaging state after pool and PostgreSQL process recreation`() {
        when (recoveryPhase()) {
            RecoveryPhase.SEED -> withDataSources { admin, app ->
                resetDatabase(admin)
                seedDurableState(admin, app)
            }

            RecoveryPhase.VERIFY -> withDataSources { admin, app ->
                verifyAndContinueDurableState(admin, app)
            }

            RecoveryPhase.SELF_CONTAINED -> {
                withDataSources { admin, app ->
                    resetDatabase(admin)
                    seedDurableState(admin, app)
                }
                withDataSources { admin, app ->
                    verifyAndContinueDurableState(admin, app)
                }
            }
        }
    }

    private fun seedDurableState(
        admin: HikariDataSource,
        app: HikariDataSource,
    ) {
        val conversations = PostgresConversationRepository(app)
        val messages = PostgresDurableTextRepository(app)
        val history = PostgresDurableMessageHistoryRepository(app)

        assertIs<ConversationCreationResult.Created>(conversations.create(createConversationRequest()))
        assertEquals(1L, committedSequence(messages, messageRequest(1, businessPrincipal)))
        assertEquals(2L, committedSequence(messages, messageRequest(2, clientPrincipal)))
        assertEquals(3L, committedSequence(messages, messageRequest(3, businessPrincipal)))

        assertIs<OpenConversationResult.Opened>(
            conversations.open(OpenConversationRequest(clientPrincipal, CONVERSATION_REF)),
        )
        val firstPage = loadHistory(history, pageSize = 2)
        assertEquals(listOf(3L, 2L), firstPage.items.map { it.sequence.value })
        assertEquals(2L, firstPage.nextCursor?.beforeSequence?.value)
        assertEquals(listOf(CONVERSATION_REF), listedConversationRefs(conversations))
        assertDurableCounts(admin, expectedMessages = 3L)
    }

    private fun verifyAndContinueDurableState(
        admin: HikariDataSource,
        app: HikariDataSource,
    ) {
        val conversations = PostgresConversationRepository(app)
        val messages = PostgresDurableTextRepository(app)
        val history = PostgresDurableMessageHistoryRepository(app)

        val existing =
            assertIs<ConversationCreationResult.Existing>(
                conversations.create(createConversationRequest(conversationRef = "b7-retry-must-not-rebind")),
            )
        assertEquals(CONVERSATION_REF, existing.conversation.scope.conversationRef)
        assertIs<OpenConversationResult.Opened>(
            conversations.open(OpenConversationRequest(businessPrincipal, CONVERSATION_REF)),
        )
        assertIs<OpenConversationResult.Opened>(
            conversations.open(OpenConversationRequest(clientPrincipal, CONVERSATION_REF)),
        )
        assertEquals(listOf(CONVERSATION_REF), listedConversationRefs(conversations))

        val firstPageBeforeAppend = loadHistory(history, pageSize = 2)
        assertEquals(listOf(3L, 2L), firstPageBeforeAppend.items.map { it.sequence.value })
        val durableCursor = checkNotNull(firstPageBeforeAppend.nextCursor)
        val stateBeforeReplay = durableState(admin)

        val replay =
            assertIs<DurableTextRepositoryResult.ReplayExisting>(
                messages.persist(messageRequest(1, businessPrincipal, serverMessageRef = "b7-retry-server-ignored")),
            )
        assertEquals("b7-server-1", replay.serverMessageRef)
        assertEquals(1L, replay.sequence.value)
        assertEquals(stateBeforeReplay, durableState(admin))

        assertEquals(4L, committedSequence(messages, messageRequest(4, clientPrincipal)))
        assertEquals(listOf(1L), loadHistory(history, cursor = durableCursor).items.map { it.sequence.value })
        assertEquals(listOf(4L, 3L, 2L, 1L), loadHistory(history).items.map { it.sequence.value })

        val opened =
            assertIs<OpenConversationResult.Opened>(
                conversations.open(OpenConversationRequest(businessPrincipal, CONVERSATION_REF)),
            )
        assertEquals(4L, opened.conversation.lastMessageSequence.value)
        val listing =
            assertIs<ConversationListingResult.Listed>(
                conversations.listForParticipant(ListConversationsRequest(businessPrincipal)),
            ).page
        assertEquals(4L, listing.items.single().conversation.lastMessageSequence.value)
        assertEquals(BASE_TIME.plusSeconds(40), listing.items.single().lastActivityAt)
        assertDurableCounts(admin, expectedMessages = 4L)
    }

    private fun createConversationRequest(
        conversationRef: String = CONVERSATION_REF,
    ): CreateBusinessClientConversationRequest =
        CreateBusinessClientConversationRequest(
            principal = businessPrincipal,
            command =
                CreateBusinessClientConversationCommand(
                    conversationRef = conversationRef,
                    clientSubjectRef = clientPrincipal.subjectRef,
                    requestedAt = BASE_TIME,
                ),
        )

    private fun messageRequest(
        index: Int,
        principal: ConnectPrincipal,
        serverMessageRef: String = "b7-server-$index",
    ): DurableTextWriteRequest {
        val sender = principal.actorType.name.lowercase()
        return DurableTextWriteRequest(
            principal = principal,
            command =
                SendTextMessageCommand(
                    conversationRef = CONVERSATION_REF,
                    senderSubjectRef = principal.subjectRef,
                    identity =
                        ClientMessageIdentity(
                            clientMessageRef = "b7-$sender-client-$index",
                            idempotencyKey = "b7-$sender-idempotency-$index",
                        ),
                    body = TextMessageBody("B7 durable body $index"),
                ),
            serverMessageRef = serverMessageRef,
            acceptedAtServer = BASE_TIME.plusSeconds(index * 10L),
        )
    }

    private fun committedSequence(
        repository: PostgresDurableTextRepository,
        request: DurableTextWriteRequest,
    ): Long = assertIs<DurableTextRepositoryResult.Committed>(repository.persist(request)).sequence.value

    private fun loadHistory(
        repository: PostgresDurableMessageHistoryRepository,
        pageSize: Int = LoadDurableMessageHistoryRequest.DEFAULT_PAGE_SIZE,
        cursor: DurableMessageHistoryCursor? = null,
    ) =
        assertIs<DurableMessageHistoryResult.Loaded>(
            repository.load(
                LoadDurableMessageHistoryRequest(
                    principal = clientPrincipal,
                    conversationRef = CONVERSATION_REF,
                    pageSize = pageSize,
                    cursor = cursor,
                ),
            ),
        ).page

    private fun listedConversationRefs(repository: PostgresConversationRepository): List<String> =
        assertIs<ConversationListingResult.Listed>(
            repository.listForParticipant(ListConversationsRequest(businessPrincipal)),
        ).page.items.map { it.conversationRef }

    private fun durableState(admin: HikariDataSource): List<Any> =
        admin.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT last_message_sequence, version, last_activity_at,
                       (SELECT count(*) FROM connect.messages WHERE conversation_ref = ?),
                       (SELECT count(*) FROM connect.message_identities WHERE conversation_ref = ?)
                FROM connect.conversations
                WHERE conversation_ref = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, CONVERSATION_REF)
                statement.setString(2, CONVERSATION_REF)
                statement.setString(3, CONVERSATION_REF)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    listOf(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getTimestamp(3).toInstant(),
                        resultSet.getLong(4),
                        resultSet.getLong(5),
                    )
                }
            }
        }

    private fun assertDurableCounts(
        admin: HikariDataSource,
        expectedMessages: Long,
    ) {
        assertEquals(
            listOf(1L, 2L, 1L, expectedMessages, expectedMessages),
            admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT
                            (SELECT count(*) FROM connect.conversations WHERE conversation_ref = '$CONVERSATION_REF'),
                            (SELECT count(*) FROM connect.conversation_participants WHERE conversation_ref = '$CONVERSATION_REF'),
                            (SELECT count(*) FROM connect.business_client_conversation_keys WHERE conversation_ref = '$CONVERSATION_REF'),
                            (SELECT count(*) FROM connect.messages WHERE conversation_ref = '$CONVERSATION_REF'),
                            (SELECT count(*) FROM connect.message_identities WHERE conversation_ref = '$CONVERSATION_REF')
                        """.trimIndent(),
                    ).use { resultSet ->
                        check(resultSet.next())
                        (1..5).map(resultSet::getLong)
                    }
                }
            },
        )
    }

    private fun resetDatabase(admin: HikariDataSource) {
        admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE connect.business_client_conversation_keys, connect.message_identities, connect.messages, connect.conversation_participants, connect.conversations CASCADE",
                )
            }
        }
    }

    private fun withDataSources(block: (HikariDataSource, HikariDataSource) -> Unit) {
        PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment()).use { admin ->
            PostgresDataSourceFactory.create(applicationConfig()).use { app ->
                block(admin, app)
            }
        }
    }

    private fun applicationConfig(): PostgresDatabaseConfig =
        PostgresDatabaseConfig(
            jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
            user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
            password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
            maximumPoolSize = 8,
        )

    private fun recoveryPhase(): RecoveryPhase =
        when (System.getenv("CONNECT_LAB_B7_RECOVERY_PHASE")?.uppercase()) {
            null, "", "SELF_CONTAINED" -> RecoveryPhase.SELF_CONTAINED
            "SEED" -> RecoveryPhase.SEED
            "VERIFY" -> RecoveryPhase.VERIFY
            else -> error("CONNECT_LAB_B7_RECOVERY_PHASE is invalid")
        }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Missing required environment variable: $name")

    private enum class RecoveryPhase {
        SELF_CONTAINED,
        SEED,
        VERIFY,
    }

    companion object {
        private const val CONVERSATION_REF = "b7-conversation"
        private val BASE_TIME: Instant = Instant.parse("2026-08-12T01:00:00Z")

        private val businessPrincipal =
            ConnectPrincipal(
                subjectRef = "b7-business-subject",
                actorType = ConnectActorType.BUSINESS,
                platformScopeRef = "b7-platform",
                organizationScopeRef = "b7-organization",
                businessScopeRef = "b7-business",
            )

        private val clientPrincipal =
            ConnectPrincipal(
                subjectRef = "b7-client-subject",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "b7-platform",
            )
    }
}
