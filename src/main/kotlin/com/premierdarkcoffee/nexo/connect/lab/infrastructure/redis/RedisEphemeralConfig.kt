package com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis

class RedisEphemeralConfig(
    val host: String,
    val port: Int,
    val user: String,
    internal val password: String,
    val keyNamespace: String,
    val channelNamespace: String,
    val database: Int,
    val connectTimeoutMillis: Long,
    val commandTimeoutMillis: Long,
    val reconnectMinDelayMillis: Long,
    val reconnectMaxDelayMillis: Long,
    val requestQueueSize: Int,
) {
    init {
        require(host.isNotBlank() && host.none(Char::isWhitespace) && '\u0000' !in host) {
            "CONNECT_LAB_REDIS_HOST must be a non-blank host"
        }
        require(port in 1..65_535) { "CONNECT_LAB_REDIS_PORT must be between 1 and 65535" }
        require(user == DEDICATED_APP_USER) {
            "CONNECT_LAB_REDIS_APP_USER must be the dedicated Connect Lab application identity"
        }
        require(password.length >= 32 && '\u0000' !in password) {
            "CONNECT_LAB_REDIS_APP_PASSWORD must contain at least 32 non-NUL characters"
        }
        require(keyNamespace == ISOLATED_KEY_NAMESPACE) {
            "CONNECT_LAB_REDIS_NAMESPACE must remain isolated"
        }
        require(channelNamespace == ISOLATED_CHANNEL_NAMESPACE) {
            "CONNECT_LAB_REDIS_CHANNEL_NAMESPACE must preserve the frozen channel major"
        }
        require(database == 0) { "CONNECT_LAB_REDIS_DATABASE must remain zero" }
        require(connectTimeoutMillis in 100..10_000) {
            "CONNECT_LAB_REDIS_CONNECT_TIMEOUT_MILLIS must be between 100 and 10000"
        }
        require(commandTimeoutMillis in 100..5_000) {
            "CONNECT_LAB_REDIS_COMMAND_TIMEOUT_MILLIS must be between 100 and 5000"
        }
        require(reconnectMinDelayMillis in 25..5_000) {
            "CONNECT_LAB_REDIS_RECONNECT_MIN_DELAY_MILLIS must be between 25 and 5000"
        }
        require(reconnectMaxDelayMillis in reconnectMinDelayMillis..30_000) {
            "CONNECT_LAB_REDIS_RECONNECT_MAX_DELAY_MILLIS must be at least the minimum and at most 30000"
        }
        require(requestQueueSize in 1..4_096) {
            "CONNECT_LAB_REDIS_REQUEST_QUEUE_SIZE must be between 1 and 4096"
        }
    }

    override fun toString(): String = "RedisEphemeralConfig(" +
        "host=$host, port=$port, user=$user, password=<redacted>, " +
        "keyNamespace=$keyNamespace, channelNamespace=$channelNamespace, database=$database, " +
        "connectTimeoutMillis=$connectTimeoutMillis, commandTimeoutMillis=$commandTimeoutMillis, " +
        "reconnectMinDelayMillis=$reconnectMinDelayMillis, " +
        "reconnectMaxDelayMillis=$reconnectMaxDelayMillis, requestQueueSize=$requestQueueSize)"

    companion object {
        const val DEDICATED_APP_USER = "nexo_connect_lab_app"
        const val ISOLATED_KEY_NAMESPACE = "nexo-connect-lab"
        const val ISOLATED_CHANNEL_NAMESPACE = "nexo.connect.realtime.v1"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RedisEphemeralConfig {
            fun required(name: String): String = environment[name]?.takeIf(String::isNotBlank)
                ?: error("Missing required environment variable: $name")

            fun integer(name: String): Int = required(name).toIntOrNull() ?: error("$name must be an integer")

            fun long(name: String): Long = required(name).toLongOrNull() ?: error("$name must be an integer")

            return RedisEphemeralConfig(
                host = required("CONNECT_LAB_REDIS_HOST"),
                port = integer("CONNECT_LAB_REDIS_PORT"),
                user = required("CONNECT_LAB_REDIS_APP_USER"),
                password = required("CONNECT_LAB_REDIS_APP_PASSWORD"),
                keyNamespace = required("CONNECT_LAB_REDIS_NAMESPACE"),
                channelNamespace = required("CONNECT_LAB_REDIS_CHANNEL_NAMESPACE"),
                database = integer("CONNECT_LAB_REDIS_DATABASE"),
                connectTimeoutMillis = long("CONNECT_LAB_REDIS_CONNECT_TIMEOUT_MILLIS"),
                commandTimeoutMillis = long("CONNECT_LAB_REDIS_COMMAND_TIMEOUT_MILLIS"),
                reconnectMinDelayMillis = long("CONNECT_LAB_REDIS_RECONNECT_MIN_DELAY_MILLIS"),
                reconnectMaxDelayMillis = long("CONNECT_LAB_REDIS_RECONNECT_MAX_DELAY_MILLIS"),
                requestQueueSize = integer("CONNECT_LAB_REDIS_REQUEST_QUEUE_SIZE"),
            )
        }
    }
}
