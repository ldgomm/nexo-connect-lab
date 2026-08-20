package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationDeliveryDiagnostic
import com.premierdarkcoffee.nexo.connect.lab.application.push.NotificationProviderDeliveryResult
import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationFailureCode
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

enum class ApnsPushType(val headerValue: String, val priority: Int) {
    BACKGROUND("background", 5),
    ALERT("alert", 10),
}

class ApnsSandboxRequest internal constructor(
    deviceToken: CharArray,
    authorization: CharArray,
    val topic: String,
    val expirationEpochSecond: Long,
    val pushType: ApnsPushType,
    payload: ByteArray,
) : AutoCloseable {
    private var deviceTokenMaterial: CharArray? = deviceToken.copyOf()
    private var authorizationMaterial: CharArray? = authorization.copyOf()
    private var payloadMaterial: ByteArray? = payload.copyOf()

    init {
        require(deviceToken.size in MIN_DEVICE_TOKEN_CHARS..MAX_DEVICE_TOKEN_CHARS)
        require(deviceToken.all { character -> character.isHexDigit() }) {
            "APNs device token must be hexadecimal"
        }
        require(authorization.isNotEmpty() && authorization.size <= MAX_AUTHORIZATION_CHARS)
        require(topic.matches(TOPIC_PATTERN))
        require(expirationEpochSecond > 0)
        require(payload.isNotEmpty() && payload.size <= MAX_APNS_PAYLOAD_BYTES)
    }

    internal fun <T> withMaterial(action: (CharArray, CharArray, ByteArray) -> T): T {
        val deviceToken = checkNotNull(deviceTokenMaterial) { "APNs request is closed" }.copyOf()
        val authorization = checkNotNull(authorizationMaterial) { "APNs request is closed" }.copyOf()
        val payload = checkNotNull(payloadMaterial) { "APNs request is closed" }.copyOf()
        return try {
            action(deviceToken, authorization, payload)
        } finally {
            deviceToken.fill('\u0000')
            authorization.fill('\u0000')
            payload.fill(0)
        }
    }

    override fun close() {
        deviceTokenMaterial?.fill('\u0000')
        authorizationMaterial?.fill('\u0000')
        payloadMaterial?.fill(0)
        deviceTokenMaterial = null
        authorizationMaterial = null
        payloadMaterial = null
    }

    override fun toString(): String = "ApnsSandboxRequest(deviceToken=<redacted>, authorization=<redacted>, " +
        "topic=$topic, pushType=${pushType.headerValue}, priority=${pushType.priority}, " +
        "expirationEpochSecond=$expirationEpochSecond, " +
        "payloadBytes=${payloadMaterial?.size ?: 0})"

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val MIN_DEVICE_TOKEN_CHARS = 16
        const val MAX_DEVICE_TOKEN_CHARS = 4096
        const val MAX_AUTHORIZATION_CHARS = 4096
        const val MAX_APNS_PAYLOAD_BYTES = 4096
        val TOPIC_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,254}")
    }
}

class ApnsSandboxResponse internal constructor(val statusCode: Int, body: ByteArray) : AutoCloseable {
    private var bodyMaterial: ByteArray? = body.copyOf()

    init {
        require(statusCode in 100..599)
        require(body.size <= MAX_RESPONSE_BODY_BYTES)
    }

    internal fun <T> withBody(action: (ByteArray) -> T): T {
        val copy = checkNotNull(bodyMaterial) { "APNs response is closed" }.copyOf()
        return try {
            action(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun close() {
        bodyMaterial?.fill(0)
        bodyMaterial = null
    }

    override fun toString(): String = "ApnsSandboxResponse(statusCode=$statusCode, body=<redacted>)"

    companion object {
        const val MAX_RESPONSE_BODY_BYTES = 8 * 1024
    }
}

sealed interface ApnsSandboxTransportResult {
    data class Response(val response: ApnsSandboxResponse) : ApnsSandboxTransportResult

    data object Timeout : ApnsSandboxTransportResult

    data object Unavailable : ApnsSandboxTransportResult
}

fun interface ApnsSandboxTransport {
    fun send(request: ApnsSandboxRequest): ApnsSandboxTransportResult
}

class JavaNetApnsSandboxTransport(
    connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(10),
    private val client: HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .build(),
) : ApnsSandboxTransport {
    init {
        require(!connectTimeout.isZero && !connectTimeout.isNegative)
        require(!requestTimeout.isZero && !requestTimeout.isNegative)
        require(connectTimeout <= MAX_TIMEOUT && requestTimeout <= MAX_TIMEOUT)
    }

    override fun send(request: ApnsSandboxRequest): ApnsSandboxTransportResult =
        request.withMaterial { deviceToken, authorization, payload ->
            try {
                val uri = URI(
                    "https",
                    null,
                    ApnsSandboxConfiguration.SANDBOX_HOST,
                    -1,
                    "/3/device/${String(deviceToken)}",
                    null,
                    null,
                )
                val httpRequest =
                    HttpRequest.newBuilder(uri)
                        .version(HttpClient.Version.HTTP_2)
                        .timeout(requestTimeout)
                        .header("authorization", "bearer ${String(authorization)}")
                        .header("apns-topic", request.topic)
                        .header("apns-push-type", request.pushType.headerValue)
                        .header("apns-priority", request.pushType.priority.toString())
                        .header("apns-expiration", request.expirationEpochSecond.toString())
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build()
                val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                if (
                    response.version() != HttpClient.Version.HTTP_2 ||
                    response.body().size > ApnsSandboxResponse.MAX_RESPONSE_BODY_BYTES
                ) {
                    response.body().fill(0)
                    ApnsSandboxTransportResult.Unavailable
                } else {
                    val body = response.body()
                    try {
                        ApnsSandboxTransportResult.Response(
                            ApnsSandboxResponse(statusCode = response.statusCode(), body = body),
                        )
                    } finally {
                        body.fill(0)
                    }
                }
            } catch (_: HttpTimeoutException) {
                ApnsSandboxTransportResult.Timeout
            } catch (_: IOException) {
                ApnsSandboxTransportResult.Unavailable
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ApnsSandboxTransportResult.Unavailable
            }
        }

    override fun toString(): String = "JavaNetApnsSandboxTransport(host=${ApnsSandboxConfiguration.SANDBOX_HOST}, " +
        "httpVersion=HTTP_2, credentials=<none>)"

    private companion object {
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

enum class ApnsResponseCode {
    ACCEPTED,
    INVALID_DEVICE_TOKEN,
    UNREGISTERED,
    RATE_LIMITED,
    EXPIRED_PROVIDER_TOKEN,
    AUTHENTICATION_REJECTED,
    REQUEST_REJECTED,
    PROVIDER_UNAVAILABLE,
    TRANSPORT_TIMEOUT,
    TRANSPORT_UNAVAILABLE,
}

data class ClassifiedApnsResponse(val code: ApnsResponseCode, val delivery: NotificationProviderDeliveryResult)

object ApnsResponseClassifier {
    private val REASON_PATTERN = Regex("\\\"reason\\\"\\s*:\\s*\\\"([A-Za-z][A-Za-z0-9]{0,63})\\\"")

    fun classify(result: ApnsSandboxTransportResult): ClassifiedApnsResponse = when (result) {
        ApnsSandboxTransportResult.Timeout -> retryable(
            code = ApnsResponseCode.TRANSPORT_TIMEOUT,
            errorCode = NotificationFailureCode.PROVIDER_TIMEOUT,
            diagnostic = NotificationDeliveryDiagnostic.PROVIDER_TIMEOUT,
        )

        ApnsSandboxTransportResult.Unavailable -> retryable(
            code = ApnsResponseCode.TRANSPORT_UNAVAILABLE,
            errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
            diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
        )

        is ApnsSandboxTransportResult.Response -> result.response.use { response ->
            classifyResponse(response.statusCode, response.reasonOrNull())
        }
    }

    private fun classifyResponse(statusCode: Int, reason: String?): ClassifiedApnsResponse = when {
        statusCode == 200 -> ClassifiedApnsResponse(
            code = ApnsResponseCode.ACCEPTED,
            delivery = NotificationProviderDeliveryResult.Delivered(),
        )

        statusCode == 410 || reason == "Unregistered" -> permanent(
            code = ApnsResponseCode.UNREGISTERED,
            errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
            diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
        )

        reason == "BadDeviceToken" || reason == "DeviceTokenNotForTopic" -> permanent(
            code = ApnsResponseCode.INVALID_DEVICE_TOKEN,
            errorCode = NotificationFailureCode.REGISTRATION_REVOKED,
            diagnostic = NotificationDeliveryDiagnostic.INVALID_REGISTRATION,
        )

        statusCode == 429 -> retryable(
            code = ApnsResponseCode.RATE_LIMITED,
            errorCode = NotificationFailureCode.PROVIDER_RATE_LIMITED,
            diagnostic = NotificationDeliveryDiagnostic.RATE_LIMITED,
        )

        statusCode == 403 && reason == "ExpiredProviderToken" -> retryable(
            code = ApnsResponseCode.EXPIRED_PROVIDER_TOKEN,
            errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
            diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
        )

        statusCode == 400 && reason == "IdleTimeout" -> retryable(
            code = ApnsResponseCode.PROVIDER_UNAVAILABLE,
            errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
            diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
        )

        statusCode in 500..599 -> retryable(
            code = ApnsResponseCode.PROVIDER_UNAVAILABLE,
            errorCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
            diagnostic = NotificationDeliveryDiagnostic.PROVIDER_UNAVAILABLE,
        )

        statusCode == 403 -> permanent(
            code = ApnsResponseCode.AUTHENTICATION_REJECTED,
            errorCode = NotificationFailureCode.PROVIDER_REJECTED,
            diagnostic = NotificationDeliveryDiagnostic.AUTHENTICATION_REJECTED,
        )

        else -> permanent(
            code = ApnsResponseCode.REQUEST_REJECTED,
            errorCode = NotificationFailureCode.PROVIDER_REJECTED,
            diagnostic = NotificationDeliveryDiagnostic.REQUEST_REJECTED,
        )
    }

    private fun ApnsSandboxResponse.reasonOrNull(): String? = withBody { bytes ->
        if (bytes.isEmpty()) return@withBody null
        REASON_PATTERN.find(String(bytes, StandardCharsets.UTF_8))?.groupValues?.get(1)
    }

    private fun retryable(
        code: ApnsResponseCode,
        errorCode: NotificationFailureCode,
        diagnostic: NotificationDeliveryDiagnostic,
    ): ClassifiedApnsResponse = ClassifiedApnsResponse(
        code = code,
        delivery = NotificationProviderDeliveryResult.RetryableFailure(errorCode, diagnostic),
    )

    private fun permanent(
        code: ApnsResponseCode,
        errorCode: NotificationFailureCode,
        diagnostic: NotificationDeliveryDiagnostic,
    ): ClassifiedApnsResponse = ClassifiedApnsResponse(
        code = code,
        delivery = NotificationProviderDeliveryResult.PermanentFailure(errorCode, diagnostic),
    )
}
