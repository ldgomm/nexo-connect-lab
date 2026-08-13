package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptAdvance
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptCursorRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursor
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

class PostgresDurableReceiptCursorRepository(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : DurableReceiptCursorRepository {
    override fun advance(
        request: AdvanceDurableReceiptCursorRequest,
    ): AdvanceDurableReceiptCursorResult {
        if (request.principal.actorType !in RECEIPT_ACTOR_TYPES) {
            return AdvanceDurableReceiptCursorResult.NotFoundOrDenied
        }

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
            try {
                val lastMessageSequence =
                    lockAuthorizedConversation(connection, request.principal, request.conversationRef)
                        ?: return@use AdvanceDurableReceiptCursorResult.NotFoundOrDenied.also {
                            connection.commit()
                        }
                if (request.sequence > lastMessageSequence) {
                    connection.commit()
                    return@use AdvanceDurableReceiptCursorResult.InvalidSequence
                }

                val before =
                    loadCursor(
                        connection = connection,
                        conversationRef = request.conversationRef,
                        principal = request.principal,
                    )
                val recorded = upsertCursor(connection, request)
                connection.commit()
                AdvanceDurableReceiptCursorResult.Recorded(
                    cursor = recorded,
                    advanced = before != recorded,
                )
            } catch (failure: Throwable) {
                connection.rollbackPreservingReceiptFailure(failure)
                throw failure
            }
        }
    }

    override fun load(
        request: LoadDurableReceiptCursorsRequest,
    ): LoadDurableReceiptCursorsResult {
        if (request.principal.actorType !in RECEIPT_ACTOR_TYPES) {
            return LoadDurableReceiptCursorsResult.NotFoundOrDenied
        }

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            try {
                val result =
                    if (isAuthorizedConversation(connection, request.principal, request.conversationRef)) {
                        LoadDurableReceiptCursorsResult.Loaded(
                            loadConversationCursors(connection, request.conversationRef),
                        )
                    } else {
                        LoadDurableReceiptCursorsResult.NotFoundOrDenied
                    }
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollbackPreservingReceiptFailure(failure)
                throw failure
            }
        }
    }

    private fun lockAuthorizedConversation(
        connection: Connection,
        principal: ConnectPrincipal,
        conversationRef: String,
    ): Long? {
        val businessScopePredicate =
            if (principal.actorType == ConnectActorType.BUSINESS) {
                "AND conversation.organization_scope_ref = ? AND conversation.business_scope_ref = ?"
            } else {
                ""
            }
        val sql =
            """
            SELECT conversation.last_message_sequence
            FROM connect.conversations AS conversation
            INNER JOIN connect.conversation_participants AS participant
                    ON participant.conversation_ref = conversation.conversation_ref
                   AND participant.subject_ref = ?
                   AND participant.actor_type = ?
                   AND participant.status = 'ACTIVE'
            WHERE conversation.conversation_ref = ?
              AND conversation.platform_scope_ref = ?
              AND conversation.status = 'ACTIVE'
              $businessScopePredicate
            FOR UPDATE OF conversation
            """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            var parameter = bindAuthorization(statement, principal, conversationRef)
            if (principal.actorType == ConnectActorType.BUSINESS) {
                statement.setString(parameter++, checkNotNull(principal.organizationScopeRef))
                statement.setString(parameter, checkNotNull(principal.businessScopeRef))
            }
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getLong("last_message_sequence") else null
            }
        }
    }

    private fun isAuthorizedConversation(
        connection: Connection,
        principal: ConnectPrincipal,
        conversationRef: String,
    ): Boolean {
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
            INNER JOIN connect.conversation_participants AS participant
                    ON participant.conversation_ref = conversation.conversation_ref
                   AND participant.subject_ref = ?
                   AND participant.actor_type = ?
                   AND participant.status = 'ACTIVE'
            WHERE conversation.conversation_ref = ?
              AND conversation.platform_scope_ref = ?
              AND conversation.status = 'ACTIVE'
              $businessScopePredicate
            """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            var parameter = bindAuthorization(statement, principal, conversationRef)
            if (principal.actorType == ConnectActorType.BUSINESS) {
                statement.setString(parameter++, checkNotNull(principal.organizationScopeRef))
                statement.setString(parameter, checkNotNull(principal.businessScopeRef))
            }
            statement.executeQuery().use(ResultSet::next)
        }
    }

    private fun bindAuthorization(
        statement: java.sql.PreparedStatement,
        principal: ConnectPrincipal,
        conversationRef: String,
    ): Int {
        var parameter = 1
        statement.setString(parameter++, principal.subjectRef)
        statement.setString(parameter++, principal.actorType.name)
        statement.setString(parameter++, conversationRef)
        statement.setString(parameter++, principal.platformScopeRef)
        return parameter
    }

    private fun loadCursor(
        connection: Connection,
        conversationRef: String,
        principal: ConnectPrincipal,
    ): DurableReceiptCursor? =
        connection.prepareStatement(
            """
            SELECT conversation_ref, subject_ref, actor_type,
                   highest_delivered_sequence, highest_read_sequence,
                   delivered_at, read_at, updated_at, version
            FROM connect.conversation_receipt_cursors
            WHERE conversation_ref = ? AND subject_ref = ? AND actor_type = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.setString(2, principal.subjectRef)
            statement.setString(3, principal.actorType.name)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toReceiptCursor() else null
            }
        }

    private fun upsertCursor(
        connection: Connection,
        request: AdvanceDurableReceiptCursorRequest,
    ): DurableReceiptCursor {
        val now = clock.instant()
        val requestedRead = if (request.advance == DurableReceiptAdvance.READ) request.sequence else 0L
        val sql =
            """
            INSERT INTO connect.conversation_receipt_cursors (
                conversation_ref, subject_ref, actor_type,
                highest_delivered_sequence, highest_read_sequence,
                delivered_at, read_at, updated_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (conversation_ref, subject_ref, actor_type) DO UPDATE SET
                highest_delivered_sequence = GREATEST(
                    connect.conversation_receipt_cursors.highest_delivered_sequence,
                    EXCLUDED.highest_delivered_sequence
                ),
                highest_read_sequence = GREATEST(
                    connect.conversation_receipt_cursors.highest_read_sequence,
                    EXCLUDED.highest_read_sequence
                ),
                delivered_at = CASE
                    WHEN EXCLUDED.highest_delivered_sequence >
                         connect.conversation_receipt_cursors.highest_delivered_sequence
                    THEN EXCLUDED.delivered_at
                    ELSE connect.conversation_receipt_cursors.delivered_at
                END,
                read_at = CASE
                    WHEN EXCLUDED.highest_read_sequence >
                         connect.conversation_receipt_cursors.highest_read_sequence
                    THEN EXCLUDED.read_at
                    ELSE connect.conversation_receipt_cursors.read_at
                END,
                updated_at = CASE
                    WHEN EXCLUDED.highest_delivered_sequence >
                         connect.conversation_receipt_cursors.highest_delivered_sequence
                      OR EXCLUDED.highest_read_sequence >
                         connect.conversation_receipt_cursors.highest_read_sequence
                    THEN EXCLUDED.updated_at
                    ELSE connect.conversation_receipt_cursors.updated_at
                END,
                version = CASE
                    WHEN EXCLUDED.highest_delivered_sequence >
                         connect.conversation_receipt_cursors.highest_delivered_sequence
                      OR EXCLUDED.highest_read_sequence >
                         connect.conversation_receipt_cursors.highest_read_sequence
                    THEN connect.conversation_receipt_cursors.version + 1
                    ELSE connect.conversation_receipt_cursors.version
                END
            RETURNING conversation_ref, subject_ref, actor_type,
                      highest_delivered_sequence, highest_read_sequence,
                      delivered_at, read_at, updated_at, version
            """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, request.conversationRef)
            statement.setString(2, request.principal.subjectRef)
            statement.setString(3, request.principal.actorType.name)
            statement.setLong(4, request.sequence)
            statement.setLong(5, requestedRead)
            statement.setTimestamp(6, Timestamp.from(now))
            if (requestedRead > 0) {
                statement.setTimestamp(7, Timestamp.from(now))
            } else {
                statement.setTimestamp(7, null)
            }
            statement.setTimestamp(8, Timestamp.from(now))
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Durable receipt upsert returned no row" }
                resultSet.toReceiptCursor()
            }
        }
    }

    private fun loadConversationCursors(
        connection: Connection,
        conversationRef: String,
    ): List<DurableReceiptCursor> =
        connection.prepareStatement(
            """
            SELECT conversation_ref, subject_ref, actor_type,
                   highest_delivered_sequence, highest_read_sequence,
                   delivered_at, read_at, updated_at, version
            FROM connect.conversation_receipt_cursors
            WHERE conversation_ref = ?
            ORDER BY actor_type, subject_ref
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.toReceiptCursor())
                }
            }
        }

    private fun ResultSet.toReceiptCursor(): DurableReceiptCursor =
        DurableReceiptCursor(
            conversationRef = getString("conversation_ref"),
            subjectRef = getString("subject_ref"),
            actorType = ConnectActorType.valueOf(getString("actor_type")),
            highestDeliveredSequence = getLong("highest_delivered_sequence"),
            highestReadSequence = getLong("highest_read_sequence"),
            deliveredAt = getTimestamp("delivered_at")?.toInstant(),
            readAt = getTimestamp("read_at")?.toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            version = getLong("version"),
        )

    private companion object {
        val RECEIPT_ACTOR_TYPES = setOf(ConnectActorType.BUSINESS, ConnectActorType.CLIENT)
    }
}

private fun Connection.rollbackPreservingReceiptFailure(failure: Throwable) {
    try {
        rollback()
    } catch (rollbackFailure: SQLException) {
        failure.addSuppressed(rollbackFailure)
    }
}
