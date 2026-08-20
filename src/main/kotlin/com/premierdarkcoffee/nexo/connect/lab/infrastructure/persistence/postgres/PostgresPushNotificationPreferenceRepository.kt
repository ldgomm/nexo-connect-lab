package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.GetPushNotificationPreferenceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.GetPushNotificationPreferenceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PushNotificationPreferenceRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PutPushNotificationPreferenceResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationBadgeMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationLockScreenPrivacy
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationQuietMode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushNotificationPreference
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

class PostgresPushNotificationPreferenceRepository(private val dataSource: DataSource) :
    PushNotificationPreferenceRepository {
    override fun put(request: PutPushNotificationPreferenceRequest): PutPushNotificationPreferenceResult =
        serializableTransaction { connection ->
            val owner = findOwnedTarget(connection, request.principal, request.conversationRef, request.registrationRef)
                ?: return@serializableTransaction PutPushNotificationPreferenceResult.NotFoundOrDenied
            val existing = findPreferenceForUpdate(connection, request.conversationRef, request.registrationRef)

            when {
                existing == null && request.expectedVersion == 0L -> {
                    val inserted = insertPreference(connection, request, owner)
                        ?: return@serializableTransaction PutPushNotificationPreferenceResult.NotFoundOrDenied
                    PutPushNotificationPreferenceResult.Updated(inserted, created = true)
                }

                existing != null && existing.version == request.expectedVersion -> {
                    val updated = updatePreference(connection, request)
                        ?: return@serializableTransaction PutPushNotificationPreferenceResult.NotFoundOrDenied
                    PutPushNotificationPreferenceResult.Updated(updated, created = false)
                }

                else -> PutPushNotificationPreferenceResult.NotFoundOrDenied
            }
        }

    override fun get(request: GetPushNotificationPreferenceRequest): GetPushNotificationPreferenceResult =
        serializableTransaction { connection ->
            findOwnedTarget(connection, request.principal, request.conversationRef, request.registrationRef)
                ?: return@serializableTransaction GetPushNotificationPreferenceResult.NotFoundOrDenied
            val preference = findPreference(connection, request.conversationRef, request.registrationRef)
                ?: return@serializableTransaction GetPushNotificationPreferenceResult.NotFoundOrDenied
            GetPushNotificationPreferenceResult.Found(preference)
        }

    private fun findOwnedTarget(
        connection: Connection,
        principal: ConnectPrincipal,
        conversationRef: String,
        registrationRef: String,
    ): OwnedPreferenceTarget? = connection.prepareStatement(
        """
        SELECT registration.platform_scope_ref,
               registration.subject_ref,
               registration.actor_type
        FROM connect.push_device_registrations AS registration
        JOIN connect.conversation_participants AS participant
          ON participant.conversation_ref = ?
         AND participant.subject_ref = registration.subject_ref
         AND participant.actor_type = registration.actor_type
         AND participant.status = 'ACTIVE'
        JOIN connect.conversations AS conversation
          ON conversation.conversation_ref = participant.conversation_ref
         AND conversation.platform_scope_ref = registration.platform_scope_ref
         AND conversation.status = 'ACTIVE'
        WHERE registration.registration_ref = ?
          AND registration.platform_scope_ref = ?
          AND registration.organization_scope_ref IS NOT DISTINCT FROM ?
          AND registration.business_scope_ref IS NOT DISTINCT FROM ?
          AND registration.subject_ref = ?
          AND registration.actor_type = ?
          AND registration.status = 'ACTIVE'
          AND (
                registration.actor_type = 'CLIENT'
                OR (
                    conversation.organization_scope_ref IS NOT DISTINCT FROM registration.organization_scope_ref
                    AND conversation.business_scope_ref IS NOT DISTINCT FROM registration.business_scope_ref
                )
          )
        FOR SHARE OF registration, participant, conversation
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, conversationRef)
        statement.setString(2, registrationRef)
        bindPrincipal(statement, principal, startAt = 3)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) {
                OwnedPreferenceTarget(
                    platformScopeRef = resultSet.getString("platform_scope_ref"),
                    subjectRef = resultSet.getString("subject_ref"),
                    actorType = resultSet.getString("actor_type"),
                )
            } else {
                null
            }
        }
    }

    private fun findPreferenceForUpdate(
        connection: Connection,
        conversationRef: String,
        registrationRef: String,
    ): PushNotificationPreference? = findPreference(connection, conversationRef, registrationRef, forUpdate = true)

    private fun findPreference(
        connection: Connection,
        conversationRef: String,
        registrationRef: String,
        forUpdate: Boolean = false,
    ): PushNotificationPreference? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT conversation_ref, registration_ref, muted, lock_screen_privacy,
                   badge_mode, quiet_mode, updated_at, version
            FROM connect.push_notification_preferences
            WHERE conversation_ref = ? AND registration_ref = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversationRef)
            statement.setString(2, registrationRef)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toPreference() else null
            }
        }
    }

    private fun insertPreference(
        connection: Connection,
        request: PutPushNotificationPreferenceRequest,
        owner: OwnedPreferenceTarget,
    ): PushNotificationPreference? = connection.prepareStatement(
        """
        INSERT INTO connect.push_notification_preferences (
            conversation_ref, registration_ref, platform_scope_ref,
            subject_ref, actor_type, muted, lock_screen_privacy,
            badge_mode, quiet_mode, updated_at, version
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
        ON CONFLICT (conversation_ref, registration_ref) DO NOTHING
        RETURNING conversation_ref, registration_ref, muted, lock_screen_privacy,
                  badge_mode, quiet_mode, updated_at, version
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, request.conversationRef)
        statement.setString(2, request.registrationRef)
        statement.setString(3, owner.platformScopeRef)
        statement.setString(4, owner.subjectRef)
        statement.setString(5, owner.actorType)
        bindPreference(statement, request, startAt = 6)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPreference() else null
        }
    }

    private fun updatePreference(
        connection: Connection,
        request: PutPushNotificationPreferenceRequest,
    ): PushNotificationPreference? = connection.prepareStatement(
        """
        UPDATE connect.push_notification_preferences
        SET muted = ?,
            lock_screen_privacy = ?,
            badge_mode = ?,
            quiet_mode = ?,
            updated_at = ?,
            version = version + 1
        WHERE conversation_ref = ?
          AND registration_ref = ?
          AND version = ?
        RETURNING conversation_ref, registration_ref, muted, lock_screen_privacy,
                  badge_mode, quiet_mode, updated_at, version
        """.trimIndent(),
    ).use { statement ->
        bindPreference(statement, request, startAt = 1)
        statement.setString(6, request.conversationRef)
        statement.setString(7, request.registrationRef)
        statement.setLong(8, request.expectedVersion)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPreference() else null
        }
    }

    private fun bindPreference(
        statement: PreparedStatement,
        request: PutPushNotificationPreferenceRequest,
        startAt: Int,
    ) {
        statement.setBoolean(startAt, request.muted)
        statement.setString(startAt + 1, request.lockScreenPrivacy.name)
        statement.setString(startAt + 2, request.badgeMode.name)
        statement.setString(startAt + 3, request.quietMode.name)
        statement.setTimestamp(startAt + 4, Timestamp.from(request.now))
    }

    private fun bindPrincipal(statement: PreparedStatement, principal: ConnectPrincipal, startAt: Int) {
        statement.setString(startAt, principal.platformScopeRef)
        statement.setString(startAt + 1, principal.organizationScopeRef)
        statement.setString(startAt + 2, principal.businessScopeRef)
        statement.setString(startAt + 3, principal.subjectRef)
        statement.setString(startAt + 4, principal.actorType.name)
    }

    private fun ResultSet.toPreference(): PushNotificationPreference = PushNotificationPreference(
        conversationRef = getString("conversation_ref"),
        registrationRef = getString("registration_ref"),
        muted = getBoolean("muted"),
        lockScreenPrivacy = NotificationLockScreenPrivacy.valueOf(getString("lock_screen_privacy")),
        badgeMode = NotificationBadgeMode.valueOf(getString("badge_mode")),
        quietMode = NotificationQuietMode.valueOf(getString("quiet_mode")),
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

    private data class OwnedPreferenceTarget(
        val platformScopeRef: String,
        val subjectRef: String,
        val actorType: String,
    )

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 3
        val RETRYABLE_SQL_STATES = setOf("40001", "40P01")
    }
}
