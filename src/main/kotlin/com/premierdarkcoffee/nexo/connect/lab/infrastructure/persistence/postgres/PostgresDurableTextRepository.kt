package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextAuthorizationDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.DurableTextMessageAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageAcceptanceDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageIdempotencyEvaluator
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextRepositoryResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteRequest
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushNotificationPolicy
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
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPolicyDecision
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPreferenceSnapshot
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationPresentation
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.HexFormat
import javax.sql.DataSource

class PostgresDurableTextRepository(
    private val dataSource: DataSource,
    private val authorizer: DurableTextMessageAuthorizer = DurableTextMessageAuthorizer(),
    private val idempotencyEvaluator: MessageIdempotencyEvaluator = MessageIdempotencyEvaluator(),
    private val notificationPolicy: PushNotificationPolicy = PushNotificationPolicy(),
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
        val postWriteConversation =
            allocateNextSequence(
                connection = connection,
                conversation = lockedConversation,
                acceptedAtServer = request.acceptedAtServer,
            )
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
        insertNotificationOutboxIntents(
            connection = connection,
            conversation = postWriteConversation,
            message = message,
        )

        return DurableTextRepositoryResult.Committed(
            serverMessageRef = message.serverMessageRef,
            sequence = message.sequence,
        )
    }

    private fun lockConversation(connection: Connection, conversationRef: String): ConversationPersistenceRecord? =
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
    ): ConversationParticipantPersistenceRecord? = connection.prepareStatement(
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
    ): List<ConversationParticipantPersistenceRecord> = connection.prepareStatement(
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
    ): DurableTextAuthorizationContext = DurableTextAuthorizationContext(
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

    private fun lockMessageIdentities(connection: Connection, request: DurableTextWriteRequest) {
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
    ): MessageIdempotencyRecord? = findIdentity(
        connection = connection,
        whereColumn = "idempotency_key",
        platformScopeRef = request.principal.platformScopeRef,
        senderSubjectRef = request.command.senderSubjectRef,
        identityValue = request.command.identity.idempotencyKey,
    )

    private fun findIdentityByClientMessageRef(
        connection: Connection,
        request: DurableTextWriteRequest,
    ): MessageIdempotencyRecord? = findIdentity(
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
        acceptedAtServer: java.time.Instant,
    ): ConversationPersistenceRecord = connection.prepareStatement(
        """
            UPDATE connect.conversations
            SET last_message_sequence = last_message_sequence + 1,
                last_activity_at = GREATEST(last_activity_at, ?),
                version = version + 1
            WHERE conversation_ref = ?
            RETURNING last_message_sequence, version
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, java.sql.Timestamp.from(acceptedAtServer))
        statement.setString(2, conversation.conversationRef)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Locked conversation disappeared during sequence allocation" }
            conversation.copy(
                lastMessageSequence = ConversationSequence(resultSet.getLong("last_message_sequence")),
                version = resultSet.getLong("version"),
            )
        }
    }

    private fun insertMessage(connection: Connection, message: TextMessagePersistenceRecord) {
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

    private fun insertIdentity(connection: Connection, identity: MessageIdentityPersistenceRecord) {
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

    private fun insertNotificationOutboxIntents(
        connection: Connection,
        conversation: ConversationPersistenceRecord,
        message: TextMessagePersistenceRecord,
    ) {
        val targets = loadActiveNotificationTargets(connection, conversation, message.senderSubjectRef)
            .mapNotNull { target ->
                when (val decision = notificationPolicy.decide(target.preference)) {
                    NotificationPolicyDecision.SuppressedMuted -> null
                    is NotificationPolicyDecision.Deliver -> PreparedNotificationTarget(target, decision.presentation)
                }
            }
        if (targets.isEmpty()) return

        connection.prepareStatement(
            """
            INSERT INTO connect.notification_outbox (
                intent_ref, platform_scope_ref, organization_scope_ref, business_scope_ref,
                conversation_ref, server_message_ref, recipient_subject_ref,
                recipient_actor_type, registration_ref, application, provider, environment,
                notification_type, presentation_mode, badge_mode,
                status, attempt_count, max_attempts, next_attempt_at,
                lease_owner, lease_expires_at, last_error_code, delivered_at,
                dead_lettered_at, created_at, updated_at, version
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'MESSAGE_CREATED', ?, ?, 'PENDING', 0, ?, ?,
                NULL, NULL, NULL, NULL, NULL, ?, ?, 0
            )
            """.trimIndent(),
        ).use { statement ->
            targets.forEach { target ->
                statement.setString(1, notificationIntentRef(message.serverMessageRef, target.target.registrationRef))
                statement.setString(2, conversation.platformScopeRef)
                statement.setString(3, target.target.organizationScopeRef)
                statement.setString(4, target.target.businessScopeRef)
                statement.setString(5, conversation.conversationRef)
                statement.setString(6, message.serverMessageRef)
                statement.setString(7, target.target.subjectRef)
                statement.setString(8, target.target.actorType)
                statement.setString(9, target.target.registrationRef)
                statement.setString(10, target.target.application)
                statement.setString(11, target.target.provider)
                statement.setString(12, target.target.environment)
                statement.setString(13, target.presentation.mode.name)
                statement.setString(14, target.presentation.badgeMode.name)
                statement.setInt(15, DEFAULT_NOTIFICATION_MAX_ATTEMPTS)
                statement.setTimestamp(16, java.sql.Timestamp.from(message.acceptedAtServer))
                statement.setTimestamp(17, java.sql.Timestamp.from(message.acceptedAtServer))
                statement.setTimestamp(18, java.sql.Timestamp.from(message.acceptedAtServer))
                statement.addBatch()
            }
            val results = statement.executeBatch()
            check(results.size == targets.size && results.all { it == 1 || it == java.sql.Statement.SUCCESS_NO_INFO }) {
                "Notification outbox insert did not cover every active recipient device"
            }
        }
    }

    private fun loadActiveNotificationTargets(
        connection: Connection,
        conversation: ConversationPersistenceRecord,
        senderSubjectRef: String,
    ): List<ActiveNotificationTarget> = connection.prepareStatement(
        """
            SELECT registration.registration_ref,
                   registration.organization_scope_ref,
                   registration.business_scope_ref,
                   registration.subject_ref,
                   registration.actor_type,
                   registration.application,
                   registration.provider,
                   registration.environment,
                   COALESCE(preference.muted, FALSE) AS preference_muted,
                   COALESCE(preference.lock_screen_privacy, 'GENERIC') AS lock_screen_privacy,
                   COALESCE(preference.badge_mode, 'SET_ONE') AS preference_badge_mode,
                   COALESCE(preference.quiet_mode, 'OFF') AS preference_quiet_mode
            FROM connect.push_device_registrations AS registration
            JOIN connect.conversation_participants AS participant
              ON participant.conversation_ref = ?
             AND participant.subject_ref = registration.subject_ref
             AND participant.actor_type = registration.actor_type
            LEFT JOIN connect.push_notification_preferences AS preference
              ON preference.conversation_ref = participant.conversation_ref
             AND preference.registration_ref = registration.registration_ref
             AND preference.platform_scope_ref = registration.platform_scope_ref
             AND preference.subject_ref = registration.subject_ref
             AND preference.actor_type = registration.actor_type
            WHERE registration.platform_scope_ref = ?
              AND registration.status = 'ACTIVE'
              AND participant.status = 'ACTIVE'
              AND registration.subject_ref <> ?
              AND (
                    (registration.actor_type = 'BUSINESS'
                        AND registration.organization_scope_ref = ?
                        AND registration.business_scope_ref = ?)
                    OR (registration.actor_type = 'CLIENT'
                        AND registration.organization_scope_ref IS NULL
                        AND registration.business_scope_ref IS NULL)
              )
            ORDER BY registration.subject_ref, registration.registration_ref
            FOR SHARE OF registration, participant
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, conversation.conversationRef)
        statement.setString(2, conversation.platformScopeRef)
        statement.setString(3, senderSubjectRef)
        statement.setString(4, conversation.organizationScopeRef)
        statement.setString(5, conversation.businessScopeRef)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        ActiveNotificationTarget(
                            registrationRef = resultSet.getString("registration_ref"),
                            organizationScopeRef = resultSet.getString("organization_scope_ref"),
                            businessScopeRef = resultSet.getString("business_scope_ref"),
                            subjectRef = resultSet.getString("subject_ref"),
                            actorType = resultSet.getString("actor_type"),
                            application = resultSet.getString("application"),
                            provider = resultSet.getString("provider"),
                            environment = resultSet.getString("environment"),
                            preference = NotificationPreferenceSnapshot(
                                muted = resultSet.getBoolean("preference_muted"),
                                lockScreenPrivacy = NotificationLockScreenPrivacy.valueOf(
                                    resultSet.getString("lock_screen_privacy"),
                                ),
                                badgeMode = NotificationBadgeMode.valueOf(
                                    resultSet.getString("preference_badge_mode"),
                                ),
                                quietMode = NotificationQuietMode.valueOf(
                                    resultSet.getString("preference_quiet_mode"),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun notificationIntentRef(serverMessageRef: String, registrationRef: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                "$serverMessageRef\u0000$registrationRef\u0000MESSAGE_CREATED".toByteArray(StandardCharsets.UTF_8),
            )
        return "notification-${HexFormat.of().formatHex(digest)}"
    }

    private data class ActiveNotificationTarget(
        val registrationRef: String,
        val organizationScopeRef: String?,
        val businessScopeRef: String?,
        val subjectRef: String,
        val actorType: String,
        val application: String,
        val provider: String,
        val environment: String,
        val preference: NotificationPreferenceSnapshot,
    )

    private data class PreparedNotificationTarget(
        val target: ActiveNotificationTarget,
        val presentation: NotificationPresentation,
    )

    private fun ResultSet.toConversationRecord(): ConversationPersistenceRecord = ConversationPersistenceRecord(
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

    private fun ResultSet.toIdempotencyRecord(): MessageIdempotencyRecord = MessageIdempotencyRecord(
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

    private fun deniedScopeOrMembership(): DurableTextRepositoryResult.Denied = DurableTextRepositoryResult.Denied(
        DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP,
    )

    private companion object {
        const val DEFAULT_NOTIFICATION_MAX_ATTEMPTS = 5
    }
}
