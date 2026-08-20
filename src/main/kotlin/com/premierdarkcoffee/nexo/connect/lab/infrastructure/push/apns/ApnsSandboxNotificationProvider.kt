package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryDiagnostic
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProvider
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProviderDeliveryResult
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolution
import com.premierdarkcoffee.nexo.connect.lab.application.push.PushDeliveryTokenResolver
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationType
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushEnvironment
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushProvider
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration

class ApnsSandboxNotificationProvider(
    private val configuration: ApnsSandboxConfiguration,
    private val tokenResolver: PushDeliveryTokenResolver,
    private val authorizationSource: ApnsAuthorizationSource,
    private val transport: ApnsSandboxTransport = JavaNetApnsSandboxTransport(),
    private val clock: Clock = Clock.systemUTC(),
) : NotificationProvider {
    override fun deliver(intent: NotificationOutboxIntent): NotificationProviderDeliveryResult {
        if (intent.provider != PushProvider.APNS) return rejected(NotificationDeliveryDiagnostic.REQUEST_REJECTED)
        if (intent.environment != PushEnvironment.SANDBOX) {
            return rejected(NotificationDeliveryDiagnostic.UNSUPPORTED_ENVIRONMENT)
        }
        val topic = configuration.topicFor(intent.application)
            ?: return rejected(NotificationDeliveryDiagnostic.REQUEST_REJECTED)

        return when (
            val resolution = tokenResolver.withActiveToken(intent) { token ->
                token.withBytes { tokenBytes ->
                    val deviceToken = tokenBytes.toApnsDeviceToken()
                    val payload = minimalBackgroundPayload(intent)
                    try {
                        authorizationSource.authorization().use { authorization ->
                            authorization.withChars { authorizationChars ->
                                ApnsSandboxRequest(
                                    deviceToken = deviceToken,
                                    authorization = authorizationChars,
                                    topic = topic,
                                    expirationEpochSecond = clock.instant().plus(PUSH_EXPIRATION).epochSecond,
                                    payload = payload,
                                ).use { request ->
                                    val classified = ApnsResponseClassifier.classify(transport.send(request))
                                    if (classified.code == ApnsResponseCode.EXPIRED_PROVIDER_TOKEN) {
                                        authorizationSource.invalidate()
                                    }
                                    classified.delivery
                                }
                            }
                        }
                    } finally {
                        deviceToken.fill('\u0000')
                        payload.fill(0)
                    }
                }
            }
        ) {
            PushDeliveryTokenResolution.NotFoundOrDenied ->
                NotificationProviderDeliveryResult.PermanentFailure(
                    errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
                    diagnostic = NotificationDeliveryDiagnostic.REGISTRATION_NOT_ACTIVE,
                )

            is PushDeliveryTokenResolution.Resolved -> resolution.value
        }
    }

    override fun toString(): String = "ApnsSandboxNotificationProvider(configuration=$configuration, " +
        "tokenResolver=<redacted>, authorizationSource=<redacted>, transport=<redacted>)"

    private fun minimalBackgroundPayload(intent: NotificationOutboxIntent): ByteArray {
        require(intent.type == NotificationType.MESSAGE_CREATED)
        val json =
            "{\"aps\":{\"content-available\":1},\"nexo\":{" +
                "\"v\":1," +
                "\"type\":\"MESSAGE_CREATED\"," +
                "\"conversationRef\":${intent.conversationRef.toJsonString()}," +
                "\"serverMessageRef\":${intent.serverMessageRef.toJsonString()}" +
                "}}"
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_APNS_PAYLOAD_BYTES) { "APNs background payload exceeds the provider limit" }
        return bytes
    }

    private fun String.toJsonString(): String = buildString(length + 2) {
        append('"')
        this@toJsonString.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun ByteArray.toApnsDeviceToken(): CharArray {
        val isTextHex = isNotEmpty() && all { byte -> byte.toInt().toChar().isHexDigit() }
        if (isTextHex) return CharArray(size) { index -> this[index].toInt().toChar() }

        val hex = "0123456789abcdef"
        return CharArray(size * 2) { index ->
            val byte = this[index / 2].toInt() and 0xff
            if (index % 2 == 0) hex[byte ushr 4] else hex[byte and 0x0f]
        }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun rejected(diagnostic: NotificationDeliveryDiagnostic): NotificationProviderDeliveryResult =
        NotificationProviderDeliveryResult.PermanentFailure(
            errorCode = NotificationFailureCode.PROVIDER_REJECTED,
            diagnostic = diagnostic,
        )

    private companion object {
        const val MAX_APNS_PAYLOAD_BYTES = 4096
        val PUSH_EXPIRATION: Duration = Duration.ofHours(24)
    }
}
