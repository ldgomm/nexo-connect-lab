package com.premierdarkcoffee.nexo.connect.lab.infrastructure.persistence.postgres

import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolution
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolver
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.ProtectedPushToken
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.ProtectedPushTokenCodec
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.PushTokenProtectionContext
import javax.sql.DataSource

class PostgresPushDeliveryTokenResolver(
    private val dataSource: DataSource,
    private val tokenCodec: ProtectedPushTokenCodec,
) : PushDeliveryTokenResolver {
    override fun <T> withActiveToken(
        intent: NotificationOutboxIntent,
        action: (PushTokenSecret) -> T,
    ): PushDeliveryTokenResolution<T> = dataSource.connection.use { connection ->
        connection.isReadOnly = true
        connection.prepareStatement(
            """
            SELECT token_fingerprint, token_ciphertext, token_nonce, token_key_version
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
            statement.executeQuery().use resultSetUse@{ resultSet ->
                if (!resultSet.next()) return@resultSetUse PushDeliveryTokenResolution.NotFoundOrDenied

                val ciphertext = resultSet.getBytes("token_ciphertext")
                val nonce = resultSet.getBytes("token_nonce")
                try {
                    ProtectedPushToken(
                        fingerprint = resultSet.getString("token_fingerprint"),
                        keyVersion = resultSet.getInt("token_key_version"),
                        nonce = nonce,
                        ciphertext = ciphertext,
                    ).use { protectedToken ->
                        val revealed = tokenCodec.revealForDelivery(protectedToken, intent.protectionContext())
                        try {
                            PushTokenSecret.fromBytes(revealed).use { token ->
                                PushDeliveryTokenResolution.Resolved(action(token))
                            }
                        } finally {
                            revealed.fill(0)
                        }
                    }
                } finally {
                    ciphertext.fill(0)
                    nonce.fill(0)
                }
            }
        }
    }

    private fun NotificationOutboxIntent.protectionContext(): PushTokenProtectionContext = PushTokenProtectionContext(
        platformScopeRef = platformScopeRef,
        organizationScopeRef = organizationScopeRef,
        businessScopeRef = businessScopeRef,
        subjectRef = recipientSubjectRef,
        actorType = recipientActorType,
        application = application,
        provider = provider,
        environment = environment,
    )
}
