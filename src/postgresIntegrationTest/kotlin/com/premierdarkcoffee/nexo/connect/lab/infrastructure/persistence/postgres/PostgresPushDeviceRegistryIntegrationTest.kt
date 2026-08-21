package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.ListActivePushDevicesRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RegisterPushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RegisterPushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokePushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RevokePushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RotatePushDeviceRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.RotatePushDeviceResult
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolution
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushDeviceRegistrationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.ProtectedPushTokenCodec
import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresPushDeviceRegistryIntegrationTest {
    private lateinit var adminDataSource: HikariDataSource
    private lateinit var appDataSource: HikariDataSource
    private lateinit var codec: ProtectedPushTokenCodec
    private lateinit var registry: PostgresPushDeviceRegistry

    @BeforeTest
    fun setUp() {
        adminDataSource = PostgresDataSourceFactory.create(PostgresDatabaseConfig.fromEnvironment())
        appDataSource = PostgresDataSourceFactory.create(applicationConfig())
        codec =
            ProtectedPushTokenCodec(
                activeKeyVersion = 3,
                encryptionKeys = mapOf(3 to ByteArray(32) { index -> (index + 1).toByte() }),
                fingerprintKey = ByteArray(32) { index -> (index + 65).toByte() },
            )
        val sequence = AtomicInteger()
        registry =
            PostgresPushDeviceRegistry(
                dataSource = appDataSource,
                tokenCodec = codec,
                clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                registrationRefSupplier = { "push-registration-${sequence.incrementAndGet()}" },
            )
        executeAdmin(
            "TRUNCATE connect.notification_mute_audit_events, connect.push_notification_preferences, " +
                "connect.notification_outbox, " +
                "connect.push_device_registrations",
        )
    }

    @AfterTest
    fun tearDown() {
        codec.close()
        appDataSource.close()
        adminDataSource.close()
    }

    @Test
    fun `registers idempotently and lists metadata without token disclosure`() {
        val first = register(CLIENT_ONE, DEVICE_ONE, TOKEN_ONE)
        val replay = register(CLIENT_ONE, DEVICE_ONE, TOKEN_ONE)

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.registration, replay.registration)
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.push_device_registrations"))

        val listed = registry.listActive(listRequest(CLIENT_ONE)).registrations
        assertEquals(listOf(first.registration), listed)
        assertFalse(first.registration.toString().contains(TOKEN_ONE))
        assertFalse(first.registration.toString().contains(DEVICE_ONE))

        val protectedMaterial = scalarBytes("SELECT token_ciphertext FROM connect.push_device_registrations")
        assertFalse(protectedMaterial.contentEquals(TOKEN_ONE.toByteArray()))
        assertEquals(12, scalarLong("SELECT octet_length(token_nonce) FROM connect.push_device_registrations"))
        assertEquals(64, scalarLong("SELECT length(token_fingerprint) FROM connect.push_device_registrations"))
    }

    @Test
    fun `denies guessed references and token crossover with a uniform result`() {
        val registered = register(CLIENT_ONE, DEVICE_ONE, TOKEN_ONE).registration

        token(TOKEN_TWO).use { secret ->
            assertSame(
                RotatePushDeviceResult.NotFoundOrDenied,
                registry.rotate(rotateRequest(CLIENT_TWO, registered.registrationRef, registered.version, secret)),
            )
        }
        assertSame(
            RevokePushDeviceResult.NotFoundOrDenied,
            registry.revoke(revokeRequest(CLIENT_TWO, registered.registrationRef, registered.version)),
        )

        token(TOKEN_ONE).use { duplicateToken ->
            assertSame(
                RegisterPushDeviceResult.NotFoundOrDenied,
                registry.register(registerRequest(CLIENT_TWO, "device-two", duplicateToken)),
            )
        }
        assertTrue(registry.listActive(listRequest(CLIENT_TWO)).registrations.isEmpty())
        assertEquals(1, scalarLong("SELECT count(*) FROM connect.push_device_registrations"))
    }

    @Test
    fun `rotates with version fencing and revokes through cryptographic erasure`() {
        val registered = register(CLIENT_ONE, DEVICE_ONE, TOKEN_ONE).registration

        val rotated =
            token(TOKEN_TWO).use { secret ->
                assertIs<RotatePushDeviceResult.Rotated>(
                    registry.rotate(rotateRequest(CLIENT_ONE, registered.registrationRef, registered.version, secret)),
                )
            }
        assertTrue(rotated.changed)
        assertEquals(2, rotated.registration.tokenVersion)
        assertEquals(2, rotated.registration.version)

        token(TOKEN_THREE).use { secret ->
            assertSame(
                RotatePushDeviceResult.NotFoundOrDenied,
                registry.rotate(rotateRequest(CLIENT_ONE, registered.registrationRef, registered.version, secret)),
            )
        }

        val revoked =
            assertIs<RevokePushDeviceResult.Revoked>(
                registry.revoke(
                    revokeRequest(
                        CLIENT_ONE,
                        rotated.registration.registrationRef,
                        rotated.registration.version,
                    ),
                ),
            ).registration
        assertEquals(PushDeviceRegistrationStatus.REVOKED, revoked.status)
        assertEquals(3, revoked.version)
        assertTrue(registry.listActive(listRequest(CLIENT_ONE)).registrations.isEmpty())
        assertEquals(
            0,
            scalarLong(
                """
                SELECT count(*) FROM connect.push_device_registrations
                WHERE token_fingerprint IS NOT NULL
                   OR token_ciphertext IS NOT NULL
                   OR token_nonce IS NOT NULL
                   OR token_key_version IS NOT NULL
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `delivery token resolver decrypts only the exact active owner scoped registration`() {
        val registered = register(CLIENT_ONE, DEVICE_ONE, TOKEN_ONE).registration
        val resolver = PostgresPushDeliveryTokenResolver(appDataSource, codec)
        val intent = deliveryIntent(registered.registrationRef)

        val callbackCount = AtomicInteger()
        val resolved = resolver.withActiveToken(intent) { _, tokenVersion ->
            callbackCount.incrementAndGet()
            assertEquals(1, tokenVersion)
            "delivery-token-resolved"
        }
        assertEquals(
            "delivery-token-resolved",
            assertIs<PushDeliveryTokenResolution.Resolved<String>>(resolved).value,
        )
        assertEquals(1, callbackCount.get())

        assertSame(
            PushDeliveryTokenResolution.NotFoundOrDenied,
            resolver.withActiveToken(intent.copy(platformScopeRef = "platform-2")) { _, _ -> "must-not-run" },
        )

        assertIs<RevokePushDeviceResult.Revoked>(
            registry.revoke(revokeRequest(CLIENT_ONE, registered.registrationRef, registered.version)),
        )
        assertSame(
            PushDeliveryTokenResolution.NotFoundOrDenied,
            resolver.withActiveToken(intent) { _, _ -> "must-not-run" },
        )
    }

    private fun register(
        principal: ConnectPrincipal,
        deviceRef: String,
        tokenValue: String,
    ): RegisterPushDeviceResult.Registered = token(tokenValue).use { secret ->
        assertIs<RegisterPushDeviceResult.Registered>(
            registry.register(registerRequest(principal, deviceRef, secret)),
        )
    }

    private fun registerRequest(
        principal: ConnectPrincipal,
        deviceRef: String,
        secret: PushTokenSecret,
    ): RegisterPushDeviceRequest = RegisterPushDeviceRequest(
        principal = principal,
        deviceRef = deviceRef,
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
        token = secret,
    )

    private fun rotateRequest(
        principal: ConnectPrincipal,
        registrationRef: String,
        expectedVersion: Long,
        secret: PushTokenSecret,
    ): RotatePushDeviceRequest = RotatePushDeviceRequest(
        principal = principal,
        registrationRef = registrationRef,
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
        expectedVersion = expectedVersion,
        token = secret,
    )

    private fun revokeRequest(
        principal: ConnectPrincipal,
        registrationRef: String,
        expectedVersion: Long,
    ): RevokePushDeviceRequest = RevokePushDeviceRequest(
        principal = principal,
        registrationRef = registrationRef,
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
        expectedVersion = expectedVersion,
    )

    private fun listRequest(principal: ConnectPrincipal): ListActivePushDevicesRequest = ListActivePushDevicesRequest(
        principal = principal,
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
    )

    private fun token(value: String): PushTokenSecret = PushTokenSecret.fromBytes(value.toByteArray())

    private fun deliveryIntent(registrationRef: String): NotificationOutboxIntent = NotificationOutboxIntent(
        intentRef = "notification-intent-1",
        platformScopeRef = CLIENT_ONE.platformScopeRef,
        organizationScopeRef = null,
        businessScopeRef = null,
        conversationRef = "conversation-1",
        serverMessageRef = "server-message-1",
        recipientSubjectRef = CLIENT_ONE.subjectRef,
        recipientActorType = CLIENT_ONE.actorType,
        registrationRef = registrationRef,
        application = PushApplication.NEXO_CLIENT_IOS,
        provider = PushProvider.APNS,
        environment = PushEnvironment.SANDBOX,
        type = NotificationType.MESSAGE_CREATED,
        status = NotificationOutboxStatus.CLAIMED,
        attemptCount = 1,
        maxAttempts = 4,
        nextAttemptAt = BASE_TIME,
        leaseOwner = "connect-apns-worker-1",
        leaseExpiresAt = BASE_TIME.plusSeconds(30),
        lastErrorCode = null,
        deliveredAt = null,
        deadLetteredAt = null,
        createdAt = BASE_TIME,
        updatedAt = BASE_TIME,
        version = 1,
    )

    private fun applicationConfig(): PostgresDatabaseConfig = PostgresDatabaseConfig(
        jdbcUrl = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_JDBC_URL"),
        user = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_USER"),
        password = requiredEnvironment("CONNECT_LAB_B4_POSTGRES_APP_PASSWORD"),
        maximumPoolSize = 8,
    )

    private fun executeAdmin(sql: String) {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun scalarLong(sql: String): Long = adminDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
    }

    private fun scalarBytes(sql: String): ByteArray = adminDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                check(resultSet.next())
                resultSet.getBytes(1)
            }
        }
    }

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

    companion object {
        private val BASE_TIME = Instant.parse("2026-08-14T18:00:00Z")
        private const val DEVICE_ONE = "private-client-device-one"
        private const val TOKEN_ONE = "push-token-one-0123456789"
        private const val TOKEN_TWO = "push-token-two-0123456789"
        private const val TOKEN_THREE = "push-token-three-0123456789"

        private val CLIENT_ONE =
            ConnectPrincipal(
                subjectRef = "client-subject-1",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "platform-1",
            )
        private val CLIENT_TWO =
            ConnectPrincipal(
                subjectRef = "client-subject-2",
                actorType = ConnectActorType.CLIENT,
                platformScopeRef = "platform-2",
            )
    }
}
