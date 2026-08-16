package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ActivePushDevices
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListActivePushDevicesRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.PushDeviceRegistry
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RegisterPushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RegisterPushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokePushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokePushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RotatePushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RotatePushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushDeviceRegistration
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushDeviceRegistrationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.ProtectedPushTokenCodec
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.PushTokenProtectionContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

class PostgresPushDeviceRegistry(
    private val dataSource: DataSource,
    private val tokenCodec: ProtectedPushTokenCodec,
    private val clock: Clock = Clock.systemUTC(),
    private val registrationRefSupplier: () -> String = { UUID.randomUUID().toString() },
) : PushDeviceRegistry {
    override fun register(request: RegisterPushDeviceRequest): RegisterPushDeviceResult =
        mapUniqueViolation(RegisterPushDeviceResult.NotFoundOrDenied) {
            serializableTransaction { connection ->
                val context = request.protectionContext()
                val deviceFingerprint = tokenCodec.deviceFingerprint(request.deviceRef, context)

                tokenCodec.protect(request.token, context).use { protectedToken ->
                    val existing =
                        findActiveByDevice(
                            connection = connection,
                            request = request,
                            deviceFingerprint = deviceFingerprint,
                        )
                    if (existing != null) {
                        return@serializableTransaction if (existing.tokenFingerprint == protectedToken.fingerprint) {
                            RegisterPushDeviceResult.Registered(
                                registration = existing.registration,
                                created = false,
                            )
                        } else {
                            RegisterPushDeviceResult.NotFoundOrDenied
                        }
                    }

                    val now = clock.instant()
                    val registration =
                        insertRegistration(
                            connection = connection,
                            request = request,
                            registrationRef = registrationRefSupplier(),
                            deviceFingerprint = deviceFingerprint,
                            tokenFingerprint = protectedToken.fingerprint,
                            tokenCiphertext = protectedToken.ciphertextCopy(),
                            tokenNonce = protectedToken.nonceCopy(),
                            tokenKeyVersion = protectedToken.keyVersion,
                            now = now,
                        )
                    RegisterPushDeviceResult.Registered(registration = registration, created = true)
                }
            }
        }

    override fun rotate(request: RotatePushDeviceRequest): RotatePushDeviceResult =
        mapUniqueViolation(RotatePushDeviceResult.NotFoundOrDenied) {
            serializableTransaction { connection ->
                val existing = findActiveByRegistration(connection, request)
                    ?: return@serializableTransaction RotatePushDeviceResult.NotFoundOrDenied
                if (existing.registration.version != request.expectedVersion) {
                    return@serializableTransaction RotatePushDeviceResult.NotFoundOrDenied
                }

                val context = request.protectionContext()
                tokenCodec.protect(request.token, context).use { protectedToken ->
                    if (existing.tokenFingerprint == protectedToken.fingerprint) {
                        return@serializableTransaction RotatePushDeviceResult.Rotated(
                            registration = existing.registration,
                            changed = false,
                        )
                    }

                    val registration =
                        updateToken(
                            connection = connection,
                            request = request,
                            tokenFingerprint = protectedToken.fingerprint,
                            tokenCiphertext = protectedToken.ciphertextCopy(),
                            tokenNonce = protectedToken.nonceCopy(),
                            tokenKeyVersion = protectedToken.keyVersion,
                            now = clock.instant(),
                        ) ?: return@serializableTransaction RotatePushDeviceResult.NotFoundOrDenied

                    RotatePushDeviceResult.Rotated(registration = registration, changed = true)
                }
            }
        }

    override fun revoke(request: RevokePushDeviceRequest): RevokePushDeviceResult =
        serializableTransaction { connection ->
            val registration = revokeRegistration(connection, request, clock.instant())
                ?: return@serializableTransaction RevokePushDeviceResult.NotFoundOrDenied
            RevokePushDeviceResult.Revoked(registration)
        }

    override fun listActive(request: ListActivePushDevicesRequest): ActivePushDevices =
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.prepareStatement(
                """
                SELECT registration_ref, application, provider, environment, status,
                       token_version, created_at, rotated_at, revoked_at, updated_at, version
                FROM connect.push_device_registrations
                WHERE platform_scope_ref = ?
                  AND organization_scope_ref IS NOT DISTINCT FROM ?
                  AND business_scope_ref IS NOT DISTINCT FROM ?
                  AND subject_ref = ?
                  AND actor_type = ?
                  AND application = ?
                  AND provider = ?
                  AND environment = ?
                  AND status = 'ACTIVE'
                ORDER BY updated_at DESC, registration_ref
                """.trimIndent(),
            ).use { statement ->
                bindOwner(statement, request.principal, startAt = 1)
                statement.setString(6, request.application.name)
                statement.setString(7, request.provider.name)
                statement.setString(8, request.environment.name)
                statement.executeQuery().use { resultSet ->
                    ActivePushDevices(
                        buildList {
                            while (resultSet.next()) add(resultSet.toRegistration())
                        },
                    )
                }
            }
        }

    private fun findActiveByDevice(
        connection: Connection,
        request: RegisterPushDeviceRequest,
        deviceFingerprint: String,
    ): StoredRegistration? = connection.prepareStatement(
        """
            SELECT registration_ref, application, provider, environment, status,
                   token_fingerprint, token_version, created_at, rotated_at,
                   revoked_at, updated_at, version
            FROM connect.push_device_registrations
            WHERE platform_scope_ref = ?
              AND organization_scope_ref IS NOT DISTINCT FROM ?
              AND business_scope_ref IS NOT DISTINCT FROM ?
              AND subject_ref = ?
              AND actor_type = ?
              AND application = ?
              AND provider = ?
              AND environment = ?
              AND device_fingerprint = ?
              AND status = 'ACTIVE'
            FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        bindOwner(statement, request.principal, startAt = 1)
        statement.setString(6, request.application.name)
        statement.setString(7, request.provider.name)
        statement.setString(8, request.environment.name)
        statement.setString(9, deviceFingerprint)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toStoredRegistration() else null
        }
    }

    private fun findActiveByRegistration(
        connection: Connection,
        request: RotatePushDeviceRequest,
    ): StoredRegistration? = connection.prepareStatement(
        """
            SELECT registration_ref, application, provider, environment, status,
                   token_fingerprint, token_version, created_at, rotated_at,
                   revoked_at, updated_at, version
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
              AND status = 'ACTIVE'
            FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, request.registrationRef)
        bindOwner(statement, request.principal, startAt = 2)
        statement.setString(7, request.application.name)
        statement.setString(8, request.provider.name)
        statement.setString(9, request.environment.name)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toStoredRegistration() else null
        }
    }

    private fun insertRegistration(
        connection: Connection,
        request: RegisterPushDeviceRequest,
        registrationRef: String,
        deviceFingerprint: String,
        tokenFingerprint: String,
        tokenCiphertext: ByteArray,
        tokenNonce: ByteArray,
        tokenKeyVersion: Int,
        now: java.time.Instant,
    ): PushDeviceRegistration = try {
        connection.prepareStatement(
            """
                INSERT INTO connect.push_device_registrations (
                    registration_ref, platform_scope_ref, organization_scope_ref,
                    business_scope_ref, subject_ref, actor_type, application,
                    provider, environment, device_fingerprint, token_fingerprint,
                    token_ciphertext, token_nonce, token_key_version, token_version,
                    status, created_at, rotated_at, revoked_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1,
                          'ACTIVE', ?, NULL, NULL, ?, 1)
                RETURNING registration_ref, application, provider, environment, status,
                          token_version, created_at, rotated_at, revoked_at, updated_at, version
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, registrationRef)
            bindOwner(statement, request.principal, startAt = 2)
            statement.setString(7, request.application.name)
            statement.setString(8, request.provider.name)
            statement.setString(9, request.environment.name)
            statement.setString(10, deviceFingerprint)
            statement.setString(11, tokenFingerprint)
            statement.setBytes(12, tokenCiphertext)
            statement.setBytes(13, tokenNonce)
            statement.setInt(14, tokenKeyVersion)
            statement.setTimestamp(15, Timestamp.from(now))
            statement.setTimestamp(16, Timestamp.from(now))
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Push device registration insert returned no row" }
                resultSet.toRegistration()
            }
        }
    } finally {
        tokenCiphertext.fill(0)
        tokenNonce.fill(0)
    }

    private fun updateToken(
        connection: Connection,
        request: RotatePushDeviceRequest,
        tokenFingerprint: String,
        tokenCiphertext: ByteArray,
        tokenNonce: ByteArray,
        tokenKeyVersion: Int,
        now: java.time.Instant,
    ): PushDeviceRegistration? = try {
        connection.prepareStatement(
            """
                UPDATE connect.push_device_registrations
                SET token_fingerprint = ?,
                    token_ciphertext = ?,
                    token_nonce = ?,
                    token_key_version = ?,
                    token_version = token_version + 1,
                    rotated_at = ?,
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
                  AND version = ?
                RETURNING registration_ref, application, provider, environment, status,
                          token_version, created_at, rotated_at, revoked_at, updated_at, version
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tokenFingerprint)
            statement.setBytes(2, tokenCiphertext)
            statement.setBytes(3, tokenNonce)
            statement.setInt(4, tokenKeyVersion)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setTimestamp(6, Timestamp.from(now))
            statement.setString(7, request.registrationRef)
            bindOwner(statement, request.principal, startAt = 8)
            statement.setString(13, request.application.name)
            statement.setString(14, request.provider.name)
            statement.setString(15, request.environment.name)
            statement.setLong(16, request.expectedVersion)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toRegistration() else null
            }
        }
    } finally {
        tokenCiphertext.fill(0)
        tokenNonce.fill(0)
    }

    private fun revokeRegistration(
        connection: Connection,
        request: RevokePushDeviceRequest,
        now: java.time.Instant,
    ): PushDeviceRegistration? = connection.prepareStatement(
        """
            UPDATE connect.push_device_registrations
            SET token_fingerprint = NULL,
                token_ciphertext = NULL,
                token_nonce = NULL,
                token_key_version = NULL,
                status = 'REVOKED',
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
              AND version = ?
            RETURNING registration_ref, application, provider, environment, status,
                      token_version, created_at, rotated_at, revoked_at, updated_at, version
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setString(3, request.registrationRef)
        bindOwner(statement, request.principal, startAt = 4)
        statement.setString(9, request.application.name)
        statement.setString(10, request.provider.name)
        statement.setString(11, request.environment.name)
        statement.setLong(12, request.expectedVersion)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toRegistration() else null
        }
    }

    private fun bindOwner(statement: PreparedStatement, principal: ConnectPrincipal, startAt: Int) {
        statement.setString(startAt, principal.platformScopeRef)
        statement.setString(startAt + 1, principal.organizationScopeRef)
        statement.setString(startAt + 2, principal.businessScopeRef)
        statement.setString(startAt + 3, principal.subjectRef)
        statement.setString(startAt + 4, principal.actorType.name)
    }

    private fun RegisterPushDeviceRequest.protectionContext(): PushTokenProtectionContext =
        principal.protectionContext(application, provider, environment)

    private fun RotatePushDeviceRequest.protectionContext(): PushTokenProtectionContext =
        principal.protectionContext(application, provider, environment)

    private fun ConnectPrincipal.protectionContext(
        application: PushApplication,
        provider: PushProvider,
        environment: PushEnvironment,
    ): PushTokenProtectionContext = PushTokenProtectionContext(
        platformScopeRef = platformScopeRef,
        organizationScopeRef = organizationScopeRef,
        businessScopeRef = businessScopeRef,
        subjectRef = subjectRef,
        actorType = actorType,
        application = application,
        provider = provider,
        environment = environment,
    )

    private fun ResultSet.toStoredRegistration(): StoredRegistration = StoredRegistration(
        registration = toRegistration(),
        tokenFingerprint = checkNotNull(getString("token_fingerprint")),
    )

    private fun ResultSet.toRegistration(): PushDeviceRegistration = PushDeviceRegistration(
        registrationRef = getString("registration_ref"),
        application = PushApplication.valueOf(getString("application")),
        provider = PushProvider.valueOf(getString("provider")),
        environment = PushEnvironment.valueOf(getString("environment")),
        status = PushDeviceRegistrationStatus.valueOf(getString("status")),
        tokenVersion = getLong("token_version"),
        createdAt = getTimestamp("created_at").toInstant(),
        rotatedAt = getTimestamp("rotated_at")?.toInstant(),
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

    private fun <T> mapUniqueViolation(denied: T, block: () -> T): T = try {
        block()
    } catch (failure: SQLException) {
        if (failure.sqlState == UNIQUE_VIOLATION_SQL_STATE) denied else throw failure
    }

    private data class StoredRegistration(val registration: PushDeviceRegistration, val tokenFingerprint: String)

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 3
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        val RETRYABLE_SQL_STATES = setOf("40001", "40P01")
    }
}
