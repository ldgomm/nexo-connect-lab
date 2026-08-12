package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationAccessDecision
import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationParticipantAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationConflictReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationDenialReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationCreationResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingDenialReason
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationListingResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ConversationRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.CreateBusinessClientConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListConversationsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.OpenConversationResult
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantCommandState
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationListEntry
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationListPage
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableConversationSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.BusinessClientConversationKeyPersistenceRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.BusinessClientConversationPersistenceBundle
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.ConversationParticipantPersistenceRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.ConversationPersistenceRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PostgresConversationRepository(
    private val dataSource: DataSource,
    private val participantAuthorizer: ConversationParticipantAuthorizer = ConversationParticipantAuthorizer(),
) : ConversationRepository {
    override fun create(request: CreateBusinessClientConversationRequest): ConversationCreationResult {
        if (!request.principal.isScopedBusinessCreator()) {
            return ConversationCreationResult.Denied(
                ConversationCreationDenialReason.CREATOR_NOT_SCOPED_BUSINESS,
            )
        }

        val organizationScopeRef = checkNotNull(request.principal.organizationScopeRef)
        val businessScopeRef = checkNotNull(request.principal.businessScopeRef)
        val directKey =
            BusinessClientConversationKeyPersistenceRecord(
                platformScopeRef = request.principal.platformScopeRef,
                organizationScopeRef = organizationScopeRef,
                businessScopeRef = businessScopeRef,
                businessSubjectRef = request.principal.subjectRef,
                clientSubjectRef = request.command.clientSubjectRef,
                conversationRef = request.command.conversationRef,
            )

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED

            try {
                val result =
                    createWithinTransaction(
                        connection = connection,
                        directKey = directKey,
                        requestedAt = request.command.requestedAt,
                    )
                when (result) {
                    is ConversationCreationResult.Created,
                    is ConversationCreationResult.Existing,
                    -> connection.commit()

                    is ConversationCreationResult.Conflict,
                    is ConversationCreationResult.Denied,
                    -> connection.rollback()
                }
                result
            } catch (failure: Throwable) {
                connection.rollbackPreserving(failure)
                throw failure
            }
        }
    }

    override fun open(request: OpenConversationRequest): OpenConversationResult =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true

            try {
                val snapshot = loadSnapshot(connection, request.conversationRef)
                val result =
                    if (
                        snapshot != null &&
                        participantAuthorizer.decide(request.principal, snapshot.scope) == ConversationAccessDecision.ALLOW
                    ) {
                        OpenConversationResult.Opened(snapshot)
                    } else {
                        OpenConversationResult.NotFoundOrDenied
                    }
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollbackPreserving(failure)
                throw failure
            }
        }

    override fun listForParticipant(request: ListConversationsRequest): ConversationListingResult {
        if (request.principal.actorType !in LISTABLE_ACTOR_TYPES) {
            return ConversationListingResult.Denied(
                ConversationListingDenialReason.PRINCIPAL_TYPE_NOT_SUPPORTED,
            )
        }

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true

            try {
                val page = listWithinTransaction(connection, request)
                connection.commit()
                ConversationListingResult.Listed(page)
            } catch (failure: Throwable) {
                connection.rollbackPreserving(failure)
                throw failure
            }
        }
    }

    private fun listWithinTransaction(
        connection: Connection,
        request: ListConversationsRequest,
    ): DurableConversationListPage {
        val listedRecords = loadListedConversationRecords(connection, request)
        val hasMore = listedRecords.size > request.pageSize
        val visibleRecords = listedRecords.take(request.pageSize)
        val participantsByConversation =
            loadParticipantsByConversationRefs(
                connection = connection,
                conversationRefs = visibleRecords.map { it.conversation.conversationRef },
            )
        val items =
            visibleRecords.map { listed ->
                val participants =
                    checkNotNull(participantsByConversation[listed.conversation.conversationRef]) {
                        "Listed conversation has no durable participants"
                    }
                DurableConversationListEntry(
                    conversation = listed.conversation.toSnapshot(participants),
                    lastActivityAt = listed.lastActivityAt,
                )
            }

        return DurableConversationListPage(
            items = items,
            nextCursor = if (hasMore) items.last().cursor() else null,
        )
    }

    private fun loadListedConversationRecords(
        connection: Connection,
        request: ListConversationsRequest,
    ): List<ListedConversationRecord> {
        val principal = request.principal
        val businessScopePredicate =
            if (principal.actorType == ConnectActorType.BUSINESS) {
                "AND conversation.organization_scope_ref = ? AND conversation.business_scope_ref = ?"
            } else {
                ""
            }
        val cursorPredicate =
            if (request.cursor != null) {
                """
                AND (
                    conversation.last_activity_at < ?
                    OR (
                        conversation.last_activity_at = ?
                        AND conversation.conversation_ref COLLATE "C" < ? COLLATE "C"
                    )
                )
                """.trimIndent()
            } else {
                ""
            }

        val sql =
            """
            SELECT conversation.conversation_ref, conversation.conversation_type,
                   conversation.platform_scope_ref, conversation.organization_scope_ref,
                   conversation.business_scope_ref, conversation.status,
                   conversation.created_at, conversation.last_activity_at,
                   conversation.last_message_sequence, conversation.version,
                   conversation.schema_version
            FROM connect.conversations AS conversation
            INNER JOIN connect.conversation_participants AS viewer
                    ON viewer.conversation_ref = conversation.conversation_ref
                   AND viewer.subject_ref = ?
                   AND viewer.actor_type = ?
            WHERE conversation.platform_scope_ref = ?
              $businessScopePredicate
              $cursorPredicate
            ORDER BY conversation.last_activity_at DESC,
                     conversation.conversation_ref COLLATE "C" DESC
            LIMIT ?
            """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setString(parameter++, principal.subjectRef)
            statement.setString(parameter++, principal.actorType.name)
            statement.setString(parameter++, principal.platformScopeRef)
            if (principal.actorType == ConnectActorType.BUSINESS) {
                statement.setString(parameter++, checkNotNull(principal.organizationScopeRef))
                statement.setString(parameter++, checkNotNull(principal.businessScopeRef))
            }
            request.cursor?.let { cursor ->
                statement.setTimestamp(parameter++, Timestamp.from(cursor.lastActivityAt))
                statement.setTimestamp(parameter++, Timestamp.from(cursor.lastActivityAt))
                statement.setString(parameter++, cursor.conversationRef)
            }
            statement.setInt(parameter, request.pageSize + 1)

            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            ListedConversationRecord(
                                conversation = resultSet.toConversationRecord(),
                                lastActivityAt = resultSet.getTimestamp("last_activity_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun createWithinTransaction(
        connection: Connection,
        directKey: BusinessClientConversationKeyPersistenceRecord,
        requestedAt: Instant,
    ): ConversationCreationResult {
        lockCreationIdentities(connection, directKey)

        val existingByPair = findDirectKeyByPair(connection, directKey)
        if (existingByPair != null) {
            return ConversationCreationResult.Existing(loadRequiredSnapshot(connection, existingByPair.conversationRef))
        }

        if (
            findDirectKeyByConversationRef(connection, directKey.conversationRef) != null ||
            conversationExists(connection, directKey.conversationRef)
        ) {
            return ConversationCreationResult.Conflict(
                ConversationCreationConflictReason.CONVERSATION_REF_ALREADY_BOUND,
            )
        }

        val conversation =
            ConversationPersistenceRecord(
                conversationRef = directKey.conversationRef,
                type = ConversationType.BUSINESS_CLIENT,
                platformScopeRef = directKey.platformScopeRef,
                organizationScopeRef = directKey.organizationScopeRef,
                businessScopeRef = directKey.businessScopeRef,
                status = ConversationStatus.ACTIVE,
                createdAt = requestedAt,
                lastMessageSequence = ConversationSequence.INITIAL,
                version = 0,
            )
        val businessParticipant =
            newParticipant(
                directKey = directKey,
                subjectRef = directKey.businessSubjectRef,
                actorType = ConnectActorType.BUSINESS,
                joinedAt = conversation.createdAt,
            )
        val clientParticipant =
            newParticipant(
                directKey = directKey,
                subjectRef = directKey.clientSubjectRef,
                actorType = ConnectActorType.CLIENT,
                joinedAt = conversation.createdAt,
            )

        BusinessClientConversationPersistenceBundle(
            conversation = conversation,
            businessParticipant = businessParticipant,
            clientParticipant = clientParticipant,
            directKey = directKey,
        )

        insertConversation(connection, conversation)
        insertParticipant(connection, businessParticipant)
        insertParticipant(connection, clientParticipant)
        insertDirectKey(connection, directKey)

        return ConversationCreationResult.Created(loadRequiredSnapshot(connection, conversation.conversationRef))
    }

    private fun lockCreationIdentities(
        connection: Connection,
        directKey: BusinessClientConversationKeyPersistenceRecord,
    ) {
        val lockKeys =
            listOf(
                "conversation-pair|${directKey.platformScopeRef}|${directKey.organizationScopeRef}|${directKey.businessScopeRef}|${directKey.businessSubjectRef}|${directKey.clientSubjectRef}",
                "conversation-ref|${directKey.conversationRef}",
            ).sorted()

        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            lockKeys.forEach { lockKey ->
                statement.setString(1, lockKey)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "PostgreSQL advisory conversation lock was not acquired" }
                }
            }
        }
    }

    private fun findDirectKeyByPair(
        connection: Connection,
        directKey: BusinessClientConversationKeyPersistenceRecord,
    ): BusinessClientConversationKeyPersistenceRecord? =
        connection.prepareStatement(
            """
            SELECT platform_scope_ref, organization_scope_ref, business_scope_ref,
                   business_subject_ref, client_subject_ref, conversation_ref
            FROM connect.business_client_conversation_keys
            WHERE platform_scope_ref = ?
              AND organization_scope_ref = ?
              AND business_scope_ref = ?
              AND business_subject_ref = ?
              AND client_subject_ref = ?
            FOR SHARE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, directKey.platformScopeRef)
            statement.setString(2, directKey.organizationScopeRef)
            statement.setString(3, directKey.businessScopeRef)
            statement.setString(4, directKey.businessSubjectRef)
            statement.setString(5, directKey.clientSubjectRef)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toDirectKeyRecord() else null
            }
        }

    private fun findDirectKeyByConversationRef(
        connection: Connection,
        conversationRef: String,
    ): BusinessClientConversationKeyPersistenceRecord? =
        connection.prepareStatement(
            """
            SELECT platform_scope_ref, organization_scope_ref, business_scope_ref,
                   business_subject_ref, client_subject_ref, conversation_ref
            FROM connect.business_client_conversation_keys
            WHERE conversation_ref = ?
            FOR SHARE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toDirectKeyRecord() else null
            }
        }

    private fun conversationExists(
        connection: Connection,
        conversationRef: String,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM connect.conversations WHERE conversation_ref = ? FOR SHARE",
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    private fun insertConversation(
        connection: Connection,
        conversation: ConversationPersistenceRecord,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO connect.conversations (
                conversation_ref, conversation_type, platform_scope_ref,
                organization_scope_ref, business_scope_ref, status,
                created_at, last_activity_at, last_message_sequence, version, schema_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversation.conversationRef)
            statement.setString(2, conversation.type.name)
            statement.setString(3, conversation.platformScopeRef)
            statement.setString(4, conversation.organizationScopeRef)
            statement.setString(5, conversation.businessScopeRef)
            statement.setString(6, conversation.status.name)
            statement.setTimestamp(7, Timestamp.from(conversation.createdAt))
            statement.setTimestamp(8, Timestamp.from(conversation.createdAt))
            statement.setLong(9, conversation.lastMessageSequence.value)
            statement.setLong(10, conversation.version)
            statement.setInt(11, conversation.schemaVersion)
            check(statement.executeUpdate() == 1) { "Conversation insert did not affect exactly one row" }
        }
    }

    private fun insertParticipant(
        connection: Connection,
        participant: ConversationParticipantPersistenceRecord,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO connect.conversation_participants (
                conversation_ref, subject_ref, actor_type, status,
                capabilities, joined_at, left_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, participant.conversationRef)
            statement.setString(2, participant.subjectRef)
            statement.setString(3, participant.actorType.name)
            statement.setString(4, participant.status.name)
            statement.setArray(
                5,
                connection.createArrayOf("text", participant.capabilities.map { it.name }.sorted().toTypedArray()),
            )
            statement.setTimestamp(6, Timestamp.from(participant.joinedAt))
            statement.setTimestamp(7, participant.leftAt?.let(Timestamp::from))
            check(statement.executeUpdate() == 1) { "Participant insert did not affect exactly one row" }
        }
    }

    private fun insertDirectKey(
        connection: Connection,
        directKey: BusinessClientConversationKeyPersistenceRecord,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO connect.business_client_conversation_keys (
                platform_scope_ref, organization_scope_ref, business_scope_ref,
                business_subject_ref, business_actor_type,
                client_subject_ref, client_actor_type,
                conversation_ref
            ) VALUES (?, ?, ?, ?, 'BUSINESS', ?, 'CLIENT', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, directKey.platformScopeRef)
            statement.setString(2, directKey.organizationScopeRef)
            statement.setString(3, directKey.businessScopeRef)
            statement.setString(4, directKey.businessSubjectRef)
            statement.setString(5, directKey.clientSubjectRef)
            statement.setString(6, directKey.conversationRef)
            check(statement.executeUpdate() == 1) { "Direct conversation key insert did not affect exactly one row" }
        }
    }

    private fun loadRequiredSnapshot(
        connection: Connection,
        conversationRef: String,
    ): DurableConversationSnapshot =
        checkNotNull(loadSnapshot(connection, conversationRef)) {
            "Durable conversation disappeared inside its transaction"
        }

    private fun loadSnapshot(
        connection: Connection,
        conversationRef: String,
    ): DurableConversationSnapshot? {
        val conversation =
            connection.prepareStatement(
                """
                SELECT conversation_ref, conversation_type, platform_scope_ref,
                       organization_scope_ref, business_scope_ref, status,
                       created_at, last_message_sequence, version, schema_version
                FROM connect.conversations
                WHERE conversation_ref = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, conversationRef)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toConversationRecord() else null
                }
            } ?: return null

        return conversation.toSnapshot(loadParticipants(connection, conversationRef))
    }

    private fun ConversationPersistenceRecord.toSnapshot(
        participants: List<ConversationParticipantPersistenceRecord>,
    ): DurableConversationSnapshot =
        DurableConversationSnapshot(
            scope =
                ConversationAccessScope(
                    conversationRef = conversationRef,
                    type = type,
                    platformScopeRef = platformScopeRef,
                    organizationScopeRef = organizationScopeRef,
                    businessScopeRef = businessScopeRef,
                    participants =
                        participants.mapTo(linkedSetOf()) {
                            ConversationParticipant(it.subjectRef, it.actorType)
                        },
                ),
            status = status,
            participantStates =
                participants.mapTo(linkedSetOf()) {
                    ConversationParticipantCommandState(
                        subjectRef = it.subjectRef,
                        actorType = it.actorType,
                        status = it.status,
                        capabilities = it.capabilities,
                    )
                },
            createdAt = createdAt,
            lastMessageSequence = lastMessageSequence,
        )

    private fun loadParticipants(
        connection: Connection,
        conversationRef: String,
    ): List<ConversationParticipantPersistenceRecord> =
        connection.prepareStatement(
            """
            SELECT conversation_ref, subject_ref, actor_type, status,
                   capabilities, joined_at, left_at
            FROM connect.conversation_participants
            WHERE conversation_ref = ?
            ORDER BY actor_type, subject_ref
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.toParticipantRecord())
                }
            }
        }

    private fun loadParticipantsByConversationRefs(
        connection: Connection,
        conversationRefs: List<String>,
    ): Map<String, List<ConversationParticipantPersistenceRecord>> {
        if (conversationRefs.isEmpty()) return emptyMap()

        val sqlArray = connection.createArrayOf("text", conversationRefs.toTypedArray())
        return try {
            connection.prepareStatement(
                """
                SELECT conversation_ref, subject_ref, actor_type, status,
                       capabilities, joined_at, left_at
                FROM connect.conversation_participants
                WHERE conversation_ref = ANY (?)
                ORDER BY conversation_ref, actor_type, subject_ref
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(1, sqlArray)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toParticipantRecord())
                    }.groupBy(ConversationParticipantPersistenceRecord::conversationRef)
                }
            }
        } finally {
            sqlArray.free()
        }
    }

    private fun newParticipant(
        directKey: BusinessClientConversationKeyPersistenceRecord,
        subjectRef: String,
        actorType: ConnectActorType,
        joinedAt: Instant,
    ): ConversationParticipantPersistenceRecord =
        ConversationParticipantPersistenceRecord(
            conversationRef = directKey.conversationRef,
            subjectRef = subjectRef,
            actorType = actorType,
            status = ConversationParticipantStatus.ACTIVE,
            capabilities = setOf(ConversationCapability.SEND_TEXT),
            joinedAt = joinedAt,
        )

    private fun ResultSet.toDirectKeyRecord(): BusinessClientConversationKeyPersistenceRecord =
        BusinessClientConversationKeyPersistenceRecord(
            platformScopeRef = getString("platform_scope_ref"),
            organizationScopeRef = getString("organization_scope_ref"),
            businessScopeRef = getString("business_scope_ref"),
            businessSubjectRef = getString("business_subject_ref"),
            clientSubjectRef = getString("client_subject_ref"),
            conversationRef = getString("conversation_ref"),
        )

    private fun ResultSet.toConversationRecord(): ConversationPersistenceRecord =
        ConversationPersistenceRecord(
            conversationRef = getString("conversation_ref"),
            type = ConversationType.valueOf(getString("conversation_type")),
            platformScopeRef = getString("platform_scope_ref"),
            organizationScopeRef = getString("organization_scope_ref"),
            businessScopeRef = getString("business_scope_ref"),
            status = ConversationStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant(),
            lastMessageSequence = ConversationSequence(getLong("last_message_sequence")),
            version = getLong("version"),
            schemaVersion = getInt("schema_version"),
        )

    private fun ResultSet.toParticipantRecord(): ConversationParticipantPersistenceRecord =
        ConversationParticipantPersistenceRecord(
            conversationRef = getString("conversation_ref"),
            subjectRef = getString("subject_ref"),
            actorType = ConnectActorType.valueOf(getString("actor_type")),
            status = ConversationParticipantStatus.valueOf(getString("status")),
            capabilities = readCapabilities(),
            joinedAt = getTimestamp("joined_at").toInstant(),
            leftAt = getTimestamp("left_at")?.toInstant(),
        )

    private fun ResultSet.readCapabilities(): Set<ConversationCapability> {
        val sqlArray = getArray("capabilities")
        val values =
            try {
                (sqlArray.array as Array<*>).map { it.toString() }
            } finally {
                sqlArray.free()
            }
        return values.mapTo(linkedSetOf(), ConversationCapability::valueOf)
    }

    private fun ConnectPrincipal.isScopedBusinessCreator(): Boolean =
        actorType == ConnectActorType.BUSINESS &&
            organizationScopeRef != null &&
            businessScopeRef != null

    private fun Connection.rollbackPreserving(failure: Throwable) {
        try {
            rollback()
        } catch (rollbackFailure: SQLException) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private data class ListedConversationRecord(
        val conversation: ConversationPersistenceRecord,
        val lastActivityAt: Instant,
    )

    private companion object {
        val LISTABLE_ACTOR_TYPES = setOf(ConnectActorType.BUSINESS, ConnectActorType.CLIENT)
    }
}
