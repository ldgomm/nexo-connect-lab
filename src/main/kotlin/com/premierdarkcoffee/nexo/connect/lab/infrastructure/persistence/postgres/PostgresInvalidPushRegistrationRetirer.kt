package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.push.InvalidPushRegistrationRetirementResult
import com.premierdarkcoffee.nexo.connect.lab.application.push.InvalidPushRegistrationRetirer
import com.premierdarkcoffee.nexo.connect.lab.application.push.RetireInvalidPushRegistrationRequest
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

class PostgresInvalidPushRegistrationRetirer(private val dataSource: DataSource) : InvalidPushRegistrationRetirer {
    override fun retire(request: RetireInvalidPushRegistrationRequest): InvalidPushRegistrationRetirementResult =
        transaction { connection ->
            val intent = request.intent
            val current =
                connection.prepareStatement(
                    """
                SELECT status, token_version
                FROM connect.push_device_registrations
                WHERE registration_ref = ?
                  AND platform_scope_ref = ?
                  AND organization_scope_ref IS NOT DISTINCT FROM ?
                  AND business_scope_ref IS NOT DISTINCT FROM ?
                  AND subject_ref = ?
                  AND actor_type = ?
                  AND application = ?
                  AND provider = ?
                  AND environment = ?
                FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, intent.registrationRef)
                    statement.setString(2, intent.platformScopeRef)
                    statement.setString(3, intent.organizationScopeRef)
                    statement.setString(4, intent.businessScopeRef)
                    statement.setString(5, intent.recipientSubjectRef)
                    statement.setString(6, intent.recipientActorType.name)
                    statement.setString(7, intent.application.name)
                    statement.setString(8, intent.provider.name)
                    statement.setString(9, intent.environment.name)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return@transaction InvalidPushRegistrationRetirementResult.NotFoundOrDenied
                        }
                        CurrentRegistration(
                            status = resultSet.getString("status"),
                            tokenVersion = resultSet.getLong("token_version"),
                        )
                    }
                }

            if (current.status != "ACTIVE") {
                return@transaction InvalidPushRegistrationRetirementResult.NotFoundOrDenied
            }
            if (current.tokenVersion != request.expectedTokenVersion) {
                return@transaction InvalidPushRegistrationRetirementResult.TokenRotated
            }

            val retired =
                connection.prepareStatement(
                    """
                UPDATE connect.push_device_registrations
                SET status = 'REVOKED',
                    token_fingerprint = NULL,
                    token_ciphertext = NULL,
                    token_nonce = NULL,
                    token_key_version = NULL,
                    revoked_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE registration_ref = ?
                  AND platform_scope_ref = ?
                  AND organization_scope_ref IS NOT DISTINCT FROM ?
                  AND business_scope_ref IS NOT DISTINCT FROM ?
                  AND subject_ref = ?
                  AND actor_type = ?
                  AND application = ?
                  AND provider = ?
                  AND environment = ?
                  AND status = 'ACTIVE'
                  AND token_version = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(request.now))
                    statement.setTimestamp(2, Timestamp.from(request.now))
                    statement.setString(3, intent.registrationRef)
                    statement.setString(4, intent.platformScopeRef)
                    statement.setString(5, intent.organizationScopeRef)
                    statement.setString(6, intent.businessScopeRef)
                    statement.setString(7, intent.recipientSubjectRef)
                    statement.setString(8, intent.recipientActorType.name)
                    statement.setString(9, intent.application.name)
                    statement.setString(10, intent.provider.name)
                    statement.setString(11, intent.environment.name)
                    statement.setLong(12, request.expectedTokenVersion)
                    statement.executeUpdate()
                }

            if (retired == 1) {
                InvalidPushRegistrationRetirementResult.Retired
            } else {
                InvalidPushRegistrationRetirementResult.NotFoundOrDenied
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

    private data class CurrentRegistration(val status: String, val tokenVersion: Long)
}
