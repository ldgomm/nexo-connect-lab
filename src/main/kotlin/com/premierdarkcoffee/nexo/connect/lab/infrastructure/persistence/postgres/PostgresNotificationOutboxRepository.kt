package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ClaimNotificationOutboxRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DeadLetterNotificationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.MarkNotificationDeliveredRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxClaimBatch
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.NotificationOutboxRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RecordNotificationFailureRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPresentationMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

class PostgresNotificationOutboxRepository(private val dataSource: DataSource) : NotificationOutboxRepository {
    override fun claim(request: ClaimNotificationOutboxRequest): NotificationOutboxClaimBatch =
        transaction { connection ->
            deadLetterExhaustedExpiredLeases(connection, request)
            val leaseExpiresAt = request.now.plus(request.leaseDuration)
            val intents =
                connection.prepareStatement(
                    """
                    WITH candidates AS (
                        SELECT intent_ref
                        FROM connect.notification_outbox
                        WHERE (
                            (status IN ('PENDING', 'RETRY_PENDING') AND next_attempt_at <= ?)
                            OR (status = 'CLAIMED' AND lease_expires_at <= ?)
                        )
                          AND attempt_count < max_attempts
                        ORDER BY next_attempt_at, created_at, intent_ref
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                    )
                    UPDATE connect.notification_outbox AS notification
                    SET status = 'CLAIMED',
                        attempt_count = notification.attempt_count + 1,
                        lease_owner = ?,
                        lease_expires_at = ?,
                        updated_at = ?,
                        version = notification.version + 1
                    FROM candidates
                    WHERE notification.intent_ref = candidates.intent_ref
                    RETURNING notification.*
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(request.now))
                    statement.setTimestamp(2, Timestamp.from(request.now))
                    statement.setInt(3, request.limit)
                    statement.setString(4, request.leaseOwner)
                    statement.setTimestamp(5, Timestamp.from(leaseExpiresAt))
                    statement.setTimestamp(6, Timestamp.from(request.now))
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.toNotificationOutboxIntent())
                        }.sortedWith(
                            compareBy(
                                NotificationOutboxIntent::nextAttemptAt,
                                NotificationOutboxIntent::createdAt,
                                NotificationOutboxIntent::intentRef,
                            ),
                        )
                    }
                }
            NotificationOutboxClaimBatch(intents)
        }

    override fun markDelivered(request: MarkNotificationDeliveredRequest): NotificationOutboxMutationResult =
        transaction { connection ->
            updateClaimed(
                connection = connection,
                intentRef = request.intentRef,
                leaseOwner = request.leaseOwner,
                expectedVersion = request.expectedVersion,
                now = request.now,
                sql =
                """
                UPDATE connect.notification_outbox
                SET status = 'DELIVERED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    delivered_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE intent_ref = ?
                  AND status = 'CLAIMED'
                  AND lease_owner = ?
                  AND lease_expires_at > ?
                  AND updated_at <= ?
                  AND version = ?
                RETURNING *
                """.trimIndent(),
            ) { statement ->
                statement.setTimestamp(1, Timestamp.from(request.now))
                statement.setTimestamp(2, Timestamp.from(request.now))
                statement.setString(3, request.intentRef)
                statement.setString(4, request.leaseOwner)
                statement.setTimestamp(5, Timestamp.from(request.now))
                statement.setTimestamp(6, Timestamp.from(request.now))
                statement.setLong(7, request.expectedVersion)
            }
        }

    override fun recordFailure(request: RecordNotificationFailureRequest): NotificationOutboxMutationResult =
        transaction { connection ->
            updateClaimed(
                connection = connection,
                intentRef = request.intentRef,
                leaseOwner = request.leaseOwner,
                expectedVersion = request.expectedVersion,
                now = request.now,
                sql =
                """
                UPDATE connect.notification_outbox
                SET status = CASE
                        WHEN attempt_count >= max_attempts THEN 'DEAD_LETTER'
                        ELSE 'RETRY_PENDING'
                    END,
                    next_attempt_at = CASE
                        WHEN attempt_count >= max_attempts THEN next_attempt_at
                        ELSE ?
                    END,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = ?,
                    dead_lettered_at = CASE
                        WHEN attempt_count >= max_attempts THEN CAST(? AS TIMESTAMPTZ)
                        ELSE NULL
                    END,
                    updated_at = ?,
                    version = version + 1
                WHERE intent_ref = ?
                  AND status = 'CLAIMED'
                  AND lease_owner = ?
                  AND lease_expires_at > ?
                  AND updated_at <= ?
                  AND version = ?
                RETURNING *
                """.trimIndent(),
            ) { statement ->
                statement.setTimestamp(1, Timestamp.from(request.retryAt))
                statement.setString(2, request.errorCode.name)
                statement.setTimestamp(3, Timestamp.from(request.now))
                statement.setTimestamp(4, Timestamp.from(request.now))
                statement.setString(5, request.intentRef)
                statement.setString(6, request.leaseOwner)
                statement.setTimestamp(7, Timestamp.from(request.now))
                statement.setTimestamp(8, Timestamp.from(request.now))
                statement.setLong(9, request.expectedVersion)
            }
        }

    override fun deadLetter(request: DeadLetterNotificationRequest): NotificationOutboxMutationResult =
        transaction { connection ->
            updateClaimed(
                connection = connection,
                intentRef = request.intentRef,
                leaseOwner = request.leaseOwner,
                expectedVersion = request.expectedVersion,
                now = request.now,
                sql =
                """
                UPDATE connect.notification_outbox
                SET status = 'DEAD_LETTER',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = ?,
                    dead_lettered_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE intent_ref = ?
                  AND status = 'CLAIMED'
                  AND lease_owner = ?
                  AND lease_expires_at > ?
                  AND updated_at <= ?
                  AND version = ?
                RETURNING *
                """.trimIndent(),
            ) { statement ->
                statement.setString(1, request.errorCode.name)
                statement.setTimestamp(2, Timestamp.from(request.now))
                statement.setTimestamp(3, Timestamp.from(request.now))
                statement.setString(4, request.intentRef)
                statement.setString(5, request.leaseOwner)
                statement.setTimestamp(6, Timestamp.from(request.now))
                statement.setTimestamp(7, Timestamp.from(request.now))
                statement.setLong(8, request.expectedVersion)
            }
        }

    private fun deadLetterExhaustedExpiredLeases(connection: Connection, request: ClaimNotificationOutboxRequest) {
        connection.prepareStatement(
            """
            WITH exhausted AS (
                SELECT intent_ref
                FROM connect.notification_outbox
                WHERE status = 'CLAIMED'
                  AND lease_expires_at <= ?
                  AND attempt_count >= max_attempts
                ORDER BY lease_expires_at, created_at, intent_ref
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE connect.notification_outbox AS notification
            SET status = 'DEAD_LETTER',
                lease_owner = NULL,
                lease_expires_at = NULL,
                last_error_code = COALESCE(last_error_code, 'LEASE_EXPIRED_MAX_ATTEMPTS'),
                dead_lettered_at = ?,
                updated_at = ?,
                version = notification.version + 1
            FROM exhausted
            WHERE notification.intent_ref = exhausted.intent_ref
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(request.now))
            statement.setInt(2, request.limit)
            statement.setTimestamp(3, Timestamp.from(request.now))
            statement.setTimestamp(4, Timestamp.from(request.now))
            statement.executeUpdate()
        }
    }

    private fun updateClaimed(
        connection: Connection,
        intentRef: String,
        leaseOwner: String,
        expectedVersion: Long,
        now: java.time.Instant,
        sql: String,
        binder: (java.sql.PreparedStatement) -> Unit,
    ): NotificationOutboxMutationResult {
        require(intentRef.isNotBlank())
        require(leaseOwner.isNotBlank())
        require(expectedVersion >= 1)
        require(now != java.time.Instant.MIN)

        return connection.prepareStatement(sql).use { statement ->
            binder(statement)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    NotificationOutboxMutationResult.Updated(resultSet.toNotificationOutboxIntent())
                } else {
                    NotificationOutboxMutationResult.NotFoundOrDenied
                }
            }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun ResultSet.toNotificationOutboxIntent(): NotificationOutboxIntent = NotificationOutboxIntent(
        intentRef = getString("intent_ref"),
        platformScopeRef = getString("platform_scope_ref"),
        organizationScopeRef = getString("organization_scope_ref"),
        businessScopeRef = getString("business_scope_ref"),
        conversationRef = getString("conversation_ref"),
        serverMessageRef = getString("server_message_ref"),
        recipientSubjectRef = getString("recipient_subject_ref"),
        recipientActorType = ConnectActorType.valueOf(getString("recipient_actor_type")),
        registrationRef = getString("registration_ref"),
        application = PushApplication.valueOf(getString("application")),
        provider = PushProvider.valueOf(getString("provider")),
        environment = PushEnvironment.valueOf(getString("environment")),
        type = NotificationType.valueOf(getString("notification_type")),
        status = NotificationOutboxStatus.valueOf(getString("status")),
        attemptCount = getInt("attempt_count"),
        maxAttempts = getInt("max_attempts"),
        nextAttemptAt = getTimestamp("next_attempt_at").toInstant(),
        leaseOwner = getString("lease_owner"),
        leaseExpiresAt = getTimestamp("lease_expires_at")?.toInstant(),
        lastErrorCode = getString("last_error_code")?.let(NotificationFailureCode::valueOf),
        deliveredAt = getTimestamp("delivered_at")?.toInstant(),
        deadLetteredAt = getTimestamp("dead_lettered_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        version = getLong("version"),
        presentationMode = NotificationPresentationMode.valueOf(getString("presentation_mode")),
        badgeMode = NotificationBadgeMode.valueOf(getString("badge_mode")),
    )
}
