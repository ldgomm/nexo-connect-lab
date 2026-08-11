package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextMessageAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageAcceptanceDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageIdempotencyEvaluator
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantCommandState
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableTextAuthorizationContext
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessageIdempotencyRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessagePayloadFingerprint
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.ConversationParticipantPersistenceRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.ConversationPersistenceRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.DurableTextPersistenceBundle
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.MessageIdentityPersistenceRecord
import com.premierdarkcoffee.nexo.connect.lab.domain.persistence.TextMessagePersistenceRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class PostgresDurableTextRepository(
    private val dataSource: DataSource,
    private val authorizer: DurableTextMessageAuthorizer = DurableTextMessageAuthorizer(),
    private val idempotencyEvaluator: MessageIdempotencyEvaluator = MessageIdempotencyEvaluator(),
) : DurableTextRepository {
    override fun persist(request: DurableTextWriteRequest): DurableTextRepositoryResult =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED

            try {
                val result = persistWithinTransaction(connection, request)

                when (result) {
                    is DurableTextRepositoryResult.Committed,
                    is DurableTextRepositoryResult.ReplayExisting,
                    -> connection.commit()

                    is DurableTextRepositoryResult.Conflict,
                    is DurableTextRepositoryResult.Denied,
                    -> connection.rollback()
                }

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

    private fun persistWithinTransaction(
        connection: Connection,
        request: DurableTextWriteRequest,
    ): DurableTextRepositoryResult {
        val conversation =
            lockConversation(connection, request.command.conversationRef)
                ?: return deniedScopeOrMembership()

        val sender =
            lockSenderParticipant(
                connection = connection,
                conversationRef = conversation.conversationRef,
                senderSubjectRef = request.command.senderSubjectRef,
            ) ?: return deniedScopeOrMembership()

        val allParticipants = loadParticipants(connection, conversation.conversationRef)
        val authorizationContext = authorizationContext(conversation, allParticipants)
        val authorizationDecision =
            authorizer.decide(
                principal = request.principal,
                command = request.command,
                context = authorizationContext,
            )

        if (authorizationDecision != DurableTextAuthorizationDecision.ALLOW) {
            return DurableTextRepositoryResult.Denied(authorizationDecision)
        }

        require(sender.subjectRef == request.principal.subjectRef) {
            "Locked sender must match the authorized principal"
        }
        require(sender.actorType == request.principal.actorType) {
            "Locked sender actor type must match the authorized principal"
        }

        lockMessageIdentities(connection, request)

        val existingByIdempotencyKey =
            findIdentityByIdempotencyKey(connection, request)
        val existingByClientMessageRef =
            findIdentityByClientMessageRef(connection, request)

        return when (
            val decision =
                idempotencyEvaluator.decide(
                    command = request.command,
                    existingByIdempotencyKey = existingByIdempotencyKey,
                    existingByClientMessageRef = existingByClientMessageRef,
                )
        ) {
            MessageAcceptanceDecision.AcceptNew ->
                persistNewMessage(
                    connection = connection,
                    request = request,
                    lockedConversation = conversation,
                    lockedSender = sender,
                )

            is MessageAcceptanceDecision.ReplayExisting ->
                DurableTextRepositoryResult.ReplayExisting(
                    serverMessageRef = decision.serverMessageRef,
                    sequence = decision.sequence,
                )

            is MessageAcceptanceDecision.Conflict ->
                DurableTextRepositoryResult.Conflict(decision.reason)
        }
    }

    private fun persistNewMessage(
        connection: Connection,
        request: DurableTextWriteRequest,
        lockedConversation: ConversationPersistenceRecord,
        lockedSender: ConversationParticipantPersistenceRecord,
    ): DurableTextRepositoryResult.Committed {
        val postWriteConversation = allocateNextSequence(connection, lockedConversation)
        val message =
            TextMessagePersistenceRecord(
                serverMessageRef = request.serverMessageRef,
                conversationRef = postWriteConversation.conversationRef,
                sequence = postWriteConversation.lastMessageSequence,
                senderSubjectRef = request.command.senderSubjectRef,
                senderActorType = request.principal.actorType,
                body = request.command.body,
                acceptedAtServer = request.acceptedAtServer,
            )
        val identity =
            MessageIdentityPersistenceRecord(
                platformScopeRef = postWriteConversation.platformScopeRef,
                conversationRef = postWriteConversation.conversationRef,
                senderSubjectRef = request.command.senderSubjectRef,
                identity = request.command.identity,
                payloadFingerprint = request.command.payloadFingerprint,
                serverMessageRef = request.serverMessageRef,
                sequence = postWriteConversation.lastMessageSequence,
            )

        DurableTextPersistenceBundle(
            conversation = postWriteConversation,
            senderParticipant = lockedSender,
            message = message,
            identityBinding = identity,
        )

        insertMessage(connection, message)
        insertIdentity(connection, identity)

        return DurableTextRepositoryResult.Committed(
            serverMessageRef = message.serverMessageRef,
            sequence = message.sequence,
        )
    }

    private fun lockConversation(
        connection: Connection,
        conversationRef: String,
    ): ConversationPersistenceRecord? =
        connection.prepareStatement(
            """
            SELECT conversation_ref, conversation_type, platform_scope_ref,
                   organization_scope_ref, business_scope_ref, status,
                   created_at, last_message_sequence, version, schema_version
            FROM connect.conversations
            WHERE conversation_ref = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toConversationRecord() else null
            }
        }

    private fun lockSenderParticipant(
        connection: Connection,
        conversationRef: String,
        senderSubjectRef: String,
    ): ConversationParticipantPersistenceRecord? =
        connection.prepareStatement(
            """
            SELECT conversation_ref, subject_ref, actor_type, status,
                   capabilities, joined_at, left_at
            FROM connect.conversation_participants
            WHERE conversation_ref = ? AND subject_ref = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.setString(2, senderSubjectRef)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toParticipantRecord() else null
            }
        }

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
            ORDER BY subject_ref
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toParticipantRecord())
                    }
                }
            }
        }

    private fun authorizationContext(
        conversation: ConversationPersistenceRecord,
        participants: List<ConversationParticipantPersistenceRecord>,
    ): DurableTextAuthorizationContext =
        DurableTextAuthorizationContext(
            scope =
                ConversationAccessScope(
                    conversationRef = conversation.conversationRef,
                    type = conversation.type,
                    platformScopeRef = conversation.platformScopeRef,
                    organizationScopeRef = conversation.organizationScopeRef,
                    businessScopeRef = conversation.businessScopeRef,
                    participants =
                        participants.mapTo(linkedSetOf()) { participant ->
                            ConversationParticipant(
                                subjectRef = participant.subjectRef,
                                actorType = participant.actorType,
                            )
                        },
                ),
            conversationStatus = conversation.status,
            participantStates =
                participants.mapTo(linkedSetOf()) { participant ->
                    ConversationParticipantCommandState(
                        subjectRef = participant.subjectRef,
                        actorType = participant.actorType,
                        status = participant.status,
                        capabilities = participant.capabilities,
                    )
                },
        )

    private fun lockMessageIdentities(
        connection: Connection,
        request: DurableTextWriteRequest,
    ) {
        val lockKeys =
            listOf(
                "client-message-ref|${request.principal.platformScopeRef}|${request.command.senderSubjectRef}|${request.command.identity.clientMessageRef}",
                "idempotency-key|${request.principal.platformScopeRef}|${request.command.senderSubjectRef}|${request.command.identity.idempotencyKey}",
            ).sorted()

        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        ).use { statement ->
            lockKeys.forEach { lockKey ->
                statement.setString(1, lockKey)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "PostgreSQL advisory identity lock was not acquired" }
                }
            }
        }
    }

    private fun findIdentityByIdempotencyKey(
        connection: Connection,
        request: DurableTextWriteRequest,
    ): MessageIdempotencyRecord? =
        findIdentity(
            connection = connection,
            whereColumn = "idempotency_key",
            platformScopeRef = request.principal.platformScopeRef,
            senderSubjectRef = request.command.senderSubjectRef,
            identityValue = request.command.identity.idempotencyKey,
        )

    private fun findIdentityByClientMessageRef(
        connection: Connection,
        request: DurableTextWriteRequest,
    ): MessageIdempotencyRecord? =
        findIdentity(
            connection = connection,
            whereColumn = "client_message_ref",
            platformScopeRef = request.principal.platformScopeRef,
            senderSubjectRef = request.command.senderSubjectRef,
            identityValue = request.command.identity.clientMessageRef,
        )

    private fun findIdentity(
        connection: Connection,
        whereColumn: String,
        platformScopeRef: String,
        senderSubjectRef: String,
        identityValue: String,
    ): MessageIdempotencyRecord? {
        require(whereColumn == "idempotency_key" || whereColumn == "client_message_ref")

        return connection.prepareStatement(
            """
            SELECT conversation_ref, sender_subject_ref, idempotency_key,
                   client_message_ref, payload_fingerprint,
                   server_message_ref, sequence
            FROM connect.message_identities
            WHERE platform_scope_ref = ?
              AND sender_subject_ref = ?
              AND $whereColumn = ?
            FOR SHARE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, platformScopeRef)
            statement.setString(2, senderSubjectRef)
            statement.setString(3, identityValue)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toIdempotencyRecord() else null
            }
        }
    }

    private fun allocateNextSequence(
        connection: Connection,
        conversation: ConversationPersistenceRecord,
    ): ConversationPersistenceRecord =
        connection.prepareStatement(
            """
            UPDATE connect.conversations
            SET last_message_sequence = last_message_sequence + 1,
                version = version + 1
            WHERE conversation_ref = ?
            RETURNING last_message_sequence, version
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversation.conversationRef)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Locked conversation disappeared during sequence allocation" }
                conversation.copy(
                    lastMessageSequence = ConversationSequence(resultSet.getLong("last_message_sequence")),
                    version = resultSet.getLong("version"),
                )
            }
        }

    private fun insertMessage(
        connection: Connection,
        message: TextMessagePersistenceRecord,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO connect.messages (
                server_message_ref, conversation_ref, sequence,
                sender_subject_ref, sender_actor_type, message_type,
                status, body, payload_fingerprint, accepted_at_server, schema_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, message.serverMessageRef)
            statement.setString(2, message.conversationRef)
            statement.setLong(3, message.sequence.value)
            statement.setString(4, message.senderSubjectRef)
            statement.setString(5, message.senderActorType.name)
            statement.setString(6, message.type.name)
            statement.setString(7, message.status.name)
            statement.setString(8, message.body.value)
            statement.setString(9, message.payloadFingerprint.value)
            statement.setTimestamp(10, java.sql.Timestamp.from(message.acceptedAtServer))
            statement.setInt(11, message.schemaVersion)
            check(statement.executeUpdate() == 1) { "Text message insert did not affect exactly one row" }
        }
    }

    private fun insertIdentity(
        connection: Connection,
        identity: MessageIdentityPersistenceRecord,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO connect.message_identities (
                server_message_ref, platform_scope_ref, conversation_ref,
                sender_subject_ref, idempotency_key, client_message_ref,
                payload_fingerprint, sequence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, identity.serverMessageRef)
            statement.setString(2, identity.platformScopeRef)
            statement.setString(3, identity.conversationRef)
            statement.setString(4, identity.senderSubjectRef)
            statement.setString(5, identity.identity.idempotencyKey)
            statement.setString(6, identity.identity.clientMessageRef)
            statement.setString(7, identity.payloadFingerprint.value)
            statement.setLong(8, identity.sequence.value)
            check(statement.executeUpdate() == 1) { "Message identity insert did not affect exactly one row" }
        }
    }

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

    private fun ResultSet.toIdempotencyRecord(): MessageIdempotencyRecord =
        MessageIdempotencyRecord(
            conversationRef = getString("conversation_ref"),
            senderSubjectRef = getString("sender_subject_ref"),
            identity =
                ClientMessageIdentity(
                    idempotencyKey = getString("idempotency_key"),
                    clientMessageRef = getString("client_message_ref"),
                ),
            payloadFingerprint =
                MessagePayloadFingerprint.fromPersistedValue(getString("payload_fingerprint")),
            serverMessageRef = getString("server_message_ref"),
            sequence = ConversationSequence(getLong("sequence")),
        )

    private fun deniedScopeOrMembership(): DurableTextRepositoryResult.Denied =
        DurableTextRepositoryResult.Denied(
            DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
        )
}
