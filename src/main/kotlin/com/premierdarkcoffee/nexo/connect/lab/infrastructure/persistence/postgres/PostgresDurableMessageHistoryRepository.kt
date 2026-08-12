package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableMessageHistoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableMessageHistoryRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryEntry
import com.premierdarkcoffee.nexo.connect.lab.domain.message.DurableMessageHistoryPage
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class PostgresDurableMessageHistoryRepository(
    private val dataSource: DataSource,
) : DurableMessageHistoryRepository {
    override fun load(request: LoadDurableMessageHistoryRequest): DurableMessageHistoryResult {
        if (request.principal.actorType !in HISTORY_ACTOR_TYPES) {
            return DurableMessageHistoryResult.NotFoundOrDenied
        }

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true

            try {
                val result =
                    if (isVisibleParticipant(connection, request)) {
                        DurableMessageHistoryResult.Loaded(loadPage(connection, request))
                    } else {
                        DurableMessageHistoryResult.NotFoundOrDenied
                    }
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollbackPreservingHistoryFailure(failure)
                throw failure
            }
        }
    }

    private fun isVisibleParticipant(
        connection: Connection,
        request: LoadDurableMessageHistoryRequest,
    ): Boolean {
        val principal = request.principal
        val businessScopePredicate =
            if (principal.actorType == ConnectActorType.BUSINESS) {
                "AND conversation.organization_scope_ref = ? AND conversation.business_scope_ref = ?"
            } else {
                ""
            }
        val sql =
            """
            SELECT 1
            FROM connect.conversations AS conversation
            INNER JOIN connect.conversation_participants AS viewer
                    ON viewer.conversation_ref = conversation.conversation_ref
                   AND viewer.subject_ref = ?
                   AND viewer.actor_type = ?
            WHERE conversation.conversation_ref = ?
              AND conversation.platform_scope_ref = ?
              $businessScopePredicate
            """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setString(parameter++, principal.subjectRef)
            statement.setString(parameter++, principal.actorType.name)
            statement.setString(parameter++, request.conversationRef)
            statement.setString(parameter++, principal.platformScopeRef)
            if (principal.actorType == ConnectActorType.BUSINESS) {
                statement.setString(parameter++, checkNotNull(principal.organizationScopeRef))
                statement.setString(parameter, checkNotNull(principal.businessScopeRef))
            }
            statement.executeQuery().use(ResultSet::next)
        }
    }

    private fun loadPage(
        connection: Connection,
        request: LoadDurableMessageHistoryRequest,
    ): DurableMessageHistoryPage {
        val cursorPredicate = if (request.cursor != null) "AND message.sequence < ?" else ""
        val sql =
            """
            SELECT message.server_message_ref, message.sequence,
                   message.sender_subject_ref, message.sender_actor_type,
                   message.body, message.accepted_at_server
            FROM connect.messages AS message
            WHERE message.conversation_ref = ?
              $cursorPredicate
            ORDER BY message.sequence DESC
            LIMIT ?
            """.trimIndent()

        val records =
            connection.prepareStatement(sql).use { statement ->
                var parameter = 1
                statement.setString(parameter++, request.conversationRef)
                request.cursor?.let { cursor ->
                    statement.setLong(parameter++, cursor.beforeSequence.value)
                }
                statement.setInt(parameter, request.pageSize + 1)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toHistoryEntry())
                    }
                }
            }

        val hasMore = records.size > request.pageSize
        val items = records.take(request.pageSize)
        return DurableMessageHistoryPage(
            items = items,
            nextCursor = if (hasMore) items.last().cursor() else null,
        )
    }

    private fun ResultSet.toHistoryEntry(): DurableMessageHistoryEntry =
        DurableMessageHistoryEntry(
            serverMessageRef = getString("server_message_ref"),
            sequence = ConversationSequence(getLong("sequence")),
            senderSubjectRef = getString("sender_subject_ref"),
            senderActorType = ConnectActorType.valueOf(getString("sender_actor_type")),
            body = TextMessageBody(getString("body")),
            acceptedAtServer = getTimestamp("accepted_at_server").toInstant(),
        )

    private companion object {
        val HISTORY_ACTOR_TYPES = setOf(ConnectActorType.BUSINESS, ConnectActorType.CLIENT)
    }
}

private fun Connection.rollbackPreservingHistoryFailure(failure: Throwable) {
    try {
        rollback()
    } catch (rollbackFailure: SQLException) {
        failure.addSuppressed(rollbackFailure)
    }
}
