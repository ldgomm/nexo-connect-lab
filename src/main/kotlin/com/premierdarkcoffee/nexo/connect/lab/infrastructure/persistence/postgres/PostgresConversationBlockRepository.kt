package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ApplyConversationBlockRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationBlockMutationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationBlockRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokeConversationBlockRequest
import com.premierdarkcoffee.nexo.connect.lab.application.safety.ConversationBlockAuthorizationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.safety.ConversationBlockLookupPort
import com.premierdarkcoffee.nexo.connect.lab.application.safety.ConversationBlockLookupResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationBlock
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationBlockDirection
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationBlockStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyAuditAction
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScope
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScopeType
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.requireSafetyReference
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresConversationBlockRepository(
    private val dataSource: DataSource,
    private val blockRefSupplier: () -> String = { UUID.randomUUID().toString() },
    private val auditRefSupplier: () -> String = { UUID.randomUUID().toString() },
) : ConversationBlockRepository,
    ConversationBlockLookupPort {
    override fun apply(request: ApplyConversationBlockRequest): ConversationBlockMutationResult =
        serializableTransaction { connection ->
            val target = findOwnedDirection(
                connection = connection,
                principal = request.principal,
                conversationRef = request.conversationRef,
                targetSubjectRef = request.blockedSubjectRef,
                targetActorType = request.blockedActorType,
            ) ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied
            val existing = findForUpdate(connection, target.direction, request.conversationRef)

            when {
                existing == null && request.expectedVersion == 0L -> {
                    val blockRef = blockRefSupplier().also { requireSafetyReference(it, "blockRef") }
                    val inserted = insertBlock(connection, blockRef, target, request.now)
                        ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied
                    appendAudit(connection, inserted, ConversationSafetyAuditAction.APPLIED, request.now)
                    ConversationBlockMutationResult.Updated(inserted, created = true, changed = true)
                }

                existing != null && existing.version == request.expectedVersion &&
                    existing.status == ConversationBlockStatus.ACTIVE ->
                    ConversationBlockMutationResult.Updated(existing, created = false, changed = false)

                existing != null && existing.version == request.expectedVersion &&
                    existing.status == ConversationBlockStatus.REVOKED -> {
                    val updated = updateStatus(
                        connection = connection,
                        blockRef = existing.blockRef,
                        expectedVersion = existing.version,
                        status = ConversationBlockStatus.ACTIVE,
                        now = request.now,
                    ) ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied
                    appendAudit(connection, updated, ConversationSafetyAuditAction.APPLIED, request.now)
                    ConversationBlockMutationResult.Updated(updated, created = false, changed = true)
                }

                else -> ConversationBlockMutationResult.NotFoundOrDenied
            }
        }

    override fun revoke(request: RevokeConversationBlockRequest): ConversationBlockMutationResult =
        serializableTransaction { connection ->
            val target = findOwnedDirection(
                connection = connection,
                principal = request.principal,
                conversationRef = request.conversationRef,
                targetSubjectRef = request.blockedSubjectRef,
                targetActorType = request.blockedActorType,
            ) ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied
            val existing = findForUpdate(connection, target.direction, request.conversationRef)
                ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied

            when {
                existing.version != request.expectedVersion ->
                    ConversationBlockMutationResult.NotFoundOrDenied

                existing.status == ConversationBlockStatus.REVOKED ->
                    ConversationBlockMutationResult.Updated(existing, created = false, changed = false)

                else -> {
                    val updated = updateStatus(
                        connection = connection,
                        blockRef = existing.blockRef,
                        expectedVersion = existing.version,
                        status = ConversationBlockStatus.REVOKED,
                        now = request.now,
                    ) ?: return@serializableTransaction ConversationBlockMutationResult.NotFoundOrDenied
                    appendAudit(connection, updated, ConversationSafetyAuditAction.REVOKED, request.now)
                    ConversationBlockMutationResult.Updated(updated, created = false, changed = true)
                }
            }
        }

    override fun lookup(request: ConversationBlockAuthorizationRequest): ConversationBlockLookupResult = try {
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.prepareStatement(
                """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM connect.conversations AS conversation
                        JOIN connect.conversation_participants AS first_participant
                          ON first_participant.conversation_ref = conversation.conversation_ref
                         AND first_participant.subject_ref = ?
                         AND first_participant.actor_type = ?
                         AND first_participant.status = 'ACTIVE'
                        JOIN connect.conversation_participants AS second_participant
                          ON second_participant.conversation_ref = conversation.conversation_ref
                         AND second_participant.subject_ref = ?
                         AND second_participant.actor_type = ?
                         AND second_participant.status = 'ACTIVE'
                        WHERE conversation.conversation_ref = ?
                          AND conversation.platform_scope_ref = ?
                          AND conversation.organization_scope_ref = ?
                          AND conversation.business_scope_ref = ?
                          AND conversation.status = 'ACTIVE'
                    ) AS eligible,
                    EXISTS (
                        SELECT 1
                        FROM connect.conversation_blocks AS relationship_block
                        WHERE relationship_block.scope_type = 'CONVERSATION'
                          AND relationship_block.conversation_ref = ?
                          AND relationship_block.platform_scope_ref = ?
                          AND relationship_block.organization_scope_ref = ?
                          AND relationship_block.business_scope_ref = ?
                          AND relationship_block.status = 'ACTIVE'
                          AND (
                                (
                                    relationship_block.blocker_subject_ref = ?
                                    AND relationship_block.blocker_actor_type = ?
                                    AND relationship_block.blocked_subject_ref = ?
                                    AND relationship_block.blocked_actor_type = ?
                                )
                                OR (
                                    relationship_block.blocker_subject_ref = ?
                                    AND relationship_block.blocker_actor_type = ?
                                    AND relationship_block.blocked_subject_ref = ?
                                    AND relationship_block.blocked_actor_type = ?
                                )
                          )
                    ) AS blocked
                """.trimIndent(),
            ).use { statement ->
                bindAuthorizationParticipants(statement, request)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    when {
                        !resultSet.getBoolean("eligible") -> ConversationBlockLookupResult.NotFoundOrDenied
                        resultSet.getBoolean("blocked") -> ConversationBlockLookupResult.ActiveBlock
                        else -> ConversationBlockLookupResult.Clear
                    }
                }
            }
        }
    } catch (_: SQLException) {
        ConversationBlockLookupResult.Unavailable
    }

    private fun findOwnedDirection(
        connection: Connection,
        principal: ConnectPrincipal,
        conversationRef: String,
        targetSubjectRef: String,
        targetActorType: ConnectActorType,
    ): OwnedBlockTarget? = connection.prepareStatement(
        """
        SELECT conversation.platform_scope_ref,
               conversation.organization_scope_ref,
               conversation.business_scope_ref
        FROM connect.conversations AS conversation
        JOIN connect.conversation_participants AS blocker
          ON blocker.conversation_ref = conversation.conversation_ref
         AND blocker.subject_ref = ?
         AND blocker.actor_type = ?
         AND blocker.status = 'ACTIVE'
        JOIN connect.conversation_participants AS blocked
          ON blocked.conversation_ref = conversation.conversation_ref
         AND blocked.subject_ref = ?
         AND blocked.actor_type = ?
         AND blocked.status = 'ACTIVE'
        WHERE conversation.conversation_ref = ?
          AND conversation.platform_scope_ref = ?
          AND conversation.status = 'ACTIVE'
          AND (
                ? = 'CLIENT'
                OR (
                    conversation.organization_scope_ref IS NOT DISTINCT FROM ?
                    AND conversation.business_scope_ref IS NOT DISTINCT FROM ?
                )
          )
        FOR SHARE OF conversation, blocker, blocked
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, principal.subjectRef)
        statement.setString(2, principal.actorType.name)
        statement.setString(3, targetSubjectRef)
        statement.setString(4, targetActorType.name)
        statement.setString(5, conversationRef)
        statement.setString(6, principal.platformScopeRef)
        statement.setString(7, principal.actorType.name)
        statement.setString(8, principal.organizationScopeRef)
        statement.setString(9, principal.businessScopeRef)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                null
            } else {
                OwnedBlockTarget(
                    scope = ConversationSafetyScope(
                        type = ConversationSafetyScopeType.CONVERSATION,
                        conversationRef = conversationRef,
                        platformScopeRef = resultSet.getString("platform_scope_ref"),
                        organizationScopeRef = resultSet.getString("organization_scope_ref"),
                        businessScopeRef = resultSet.getString("business_scope_ref"),
                    ),
                    direction = ConversationBlockDirection(
                        blocker = ConversationSafetyParticipant(principal.subjectRef, principal.actorType),
                        blocked = ConversationSafetyParticipant(targetSubjectRef, targetActorType),
                    ),
                )
            }
        }
    }

    private fun findForUpdate(
        connection: Connection,
        direction: ConversationBlockDirection,
        conversationRef: String,
    ): ConversationBlock? = connection.prepareStatement(
        """
        SELECT block_ref, scope_type, conversation_ref, platform_scope_ref,
               organization_scope_ref, business_scope_ref,
               blocker_subject_ref, blocker_actor_type,
               blocked_subject_ref, blocked_actor_type,
               status, created_at, revoked_at, updated_at, version
        FROM connect.conversation_blocks
        WHERE conversation_ref = ?
          AND blocker_subject_ref = ?
          AND blocker_actor_type = ?
          AND blocked_subject_ref = ?
          AND blocked_actor_type = ?
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, conversationRef)
        statement.setString(2, direction.blocker.subjectRef)
        statement.setString(3, direction.blocker.actorType.name)
        statement.setString(4, direction.blocked.subjectRef)
        statement.setString(5, direction.blocked.actorType.name)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toBlock() else null
        }
    }

    private fun insertBlock(
        connection: Connection,
        blockRef: String,
        target: OwnedBlockTarget,
        now: Instant,
    ): ConversationBlock? = connection.prepareStatement(
        """
        INSERT INTO connect.conversation_blocks (
            block_ref, scope_type, conversation_ref, platform_scope_ref,
            organization_scope_ref, business_scope_ref,
            blocker_subject_ref, blocker_actor_type,
            blocked_subject_ref, blocked_actor_type,
            status, created_at, revoked_at, updated_at, version
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NULL, ?, 1)
        ON CONFLICT DO NOTHING
        RETURNING block_ref, scope_type, conversation_ref, platform_scope_ref,
                  organization_scope_ref, business_scope_ref,
                  blocker_subject_ref, blocker_actor_type,
                  blocked_subject_ref, blocked_actor_type,
                  status, created_at, revoked_at, updated_at, version
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, blockRef)
        statement.setString(2, target.scope.type.name)
        statement.setString(3, target.scope.conversationRef)
        statement.setString(4, target.scope.platformScopeRef)
        statement.setString(5, target.scope.organizationScopeRef)
        statement.setString(6, target.scope.businessScopeRef)
        statement.setString(7, target.direction.blocker.subjectRef)
        statement.setString(8, target.direction.blocker.actorType.name)
        statement.setString(9, target.direction.blocked.subjectRef)
        statement.setString(10, target.direction.blocked.actorType.name)
        statement.setTimestamp(11, Timestamp.from(now))
        statement.setTimestamp(12, Timestamp.from(now))
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toBlock() else null
        }
    }

    private fun updateStatus(
        connection: Connection,
        blockRef: String,
        expectedVersion: Long,
        status: ConversationBlockStatus,
        now: Instant,
    ): ConversationBlock? = connection.prepareStatement(
        """
        UPDATE connect.conversation_blocks
        SET status = ?,
            revoked_at = CASE
                WHEN ? = 'REVOKED' THEN CAST(? AS TIMESTAMPTZ)
                ELSE NULL::TIMESTAMPTZ
            END,
            updated_at = ?,
            version = version + 1
        WHERE block_ref = ?
          AND version = ?
        RETURNING block_ref, scope_type, conversation_ref, platform_scope_ref,
                  organization_scope_ref, business_scope_ref,
                  blocker_subject_ref, blocker_actor_type,
                  blocked_subject_ref, blocked_actor_type,
                  status, created_at, revoked_at, updated_at, version
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, status.name)
        statement.setString(2, status.name)
        statement.setTimestamp(3, Timestamp.from(now))
        statement.setTimestamp(4, Timestamp.from(now))
        statement.setString(5, blockRef)
        statement.setLong(6, expectedVersion)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toBlock() else null
        }
    }

    private fun appendAudit(
        connection: Connection,
        block: ConversationBlock,
        action: ConversationSafetyAuditAction,
        occurredAt: Instant,
    ) {
        val auditRef = auditRefSupplier().also { requireSafetyReference(it, "auditRef") }
        connection.prepareStatement(
            """
            INSERT INTO connect.conversation_block_audit_events (
                audit_ref, block_ref, scope_type, conversation_ref,
                platform_scope_ref, organization_scope_ref, business_scope_ref,
                blocker_subject_ref, blocker_actor_type,
                blocked_subject_ref, blocked_actor_type,
                action, resulting_version, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, auditRef)
            statement.setString(2, block.blockRef)
            statement.setString(3, block.scope.type.name)
            statement.setString(4, block.scope.conversationRef)
            statement.setString(5, block.scope.platformScopeRef)
            statement.setString(6, block.scope.organizationScopeRef)
            statement.setString(7, block.scope.businessScopeRef)
            statement.setString(8, block.direction.blocker.subjectRef)
            statement.setString(9, block.direction.blocker.actorType.name)
            statement.setString(10, block.direction.blocked.subjectRef)
            statement.setString(11, block.direction.blocked.actorType.name)
            statement.setString(12, action.name)
            statement.setLong(13, block.version)
            statement.setTimestamp(14, Timestamp.from(occurredAt))
            check(statement.executeUpdate() == 1) { "Conversation block audit insert was not durable" }
        }
    }

    private fun bindAuthorizationParticipants(
        statement: PreparedStatement,
        request: ConversationBlockAuthorizationRequest,
    ) {
        statement.setString(1, request.first.subjectRef)
        statement.setString(2, request.first.actorType.name)
        statement.setString(3, request.second.subjectRef)
        statement.setString(4, request.second.actorType.name)
        bindScope(statement, request.scope, startAt = 5)
        bindScope(statement, request.scope, startAt = 9)
        statement.setString(13, request.first.subjectRef)
        statement.setString(14, request.first.actorType.name)
        statement.setString(15, request.second.subjectRef)
        statement.setString(16, request.second.actorType.name)
        statement.setString(17, request.second.subjectRef)
        statement.setString(18, request.second.actorType.name)
        statement.setString(19, request.first.subjectRef)
        statement.setString(20, request.first.actorType.name)
    }

    private fun bindScope(statement: PreparedStatement, scope: ConversationSafetyScope, startAt: Int) {
        statement.setString(startAt, scope.conversationRef)
        statement.setString(startAt + 1, scope.platformScopeRef)
        statement.setString(startAt + 2, scope.organizationScopeRef)
        statement.setString(startAt + 3, scope.businessScopeRef)
    }

    private fun ResultSet.toBlock(): ConversationBlock = ConversationBlock(
        blockRef = getString("block_ref"),
        scope = ConversationSafetyScope(
            type = ConversationSafetyScopeType.valueOf(getString("scope_type")),
            conversationRef = getString("conversation_ref"),
            platformScopeRef = getString("platform_scope_ref"),
            organizationScopeRef = getString("organization_scope_ref"),
            businessScopeRef = getString("business_scope_ref"),
        ),
        direction = ConversationBlockDirection(
            blocker = ConversationSafetyParticipant(
                subjectRef = getString("blocker_subject_ref"),
                actorType = ConnectActorType.valueOf(getString("blocker_actor_type")),
            ),
            blocked = ConversationSafetyParticipant(
                subjectRef = getString("blocked_subject_ref"),
                actorType = ConnectActorType.valueOf(getString("blocked_actor_type")),
            ),
        ),
        status = ConversationBlockStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        version = getLong("version"),
    )

    private fun <T> serializableTransaction(block: (Connection) -> T): T {
        var finalFailure: SQLException? = null
        repeat(MAX_TRANSACTION_ATTEMPTS) { attempt ->
            try {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
                    try {
                        val result = block(connection)
                        connection.commit()
                        return result
                    } catch (failure: Throwable) {
                        try {
                            connection.rollback()
                        } catch (rollbackFailure: SQLException) {
                            failure.addSuppressed(rollbackFailure)
                        }
                        throw failure
                    }
                }
            } catch (failure: SQLException) {
                finalFailure = failure
                if (failure.sqlState !in RETRYABLE_SQL_STATES || attempt == MAX_TRANSACTION_ATTEMPTS - 1) {
                    throw failure
                }
            }
        }
        throw checkNotNull(finalFailure)
    }

    private data class OwnedBlockTarget(val scope: ConversationSafetyScope, val direction: ConversationBlockDirection)

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 3
        val RETRYABLE_SQL_STATES = setOf("40001", "40P01")
    }
}
