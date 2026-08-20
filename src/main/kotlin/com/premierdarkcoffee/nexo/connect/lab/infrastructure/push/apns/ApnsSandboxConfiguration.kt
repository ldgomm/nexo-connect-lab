package com.premierdarkcoffee.nexo.connect.lab.infrastructure.push.apns

import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushApplication
import java.nio.file.Path

class ApnsSandboxConfiguration(
    val teamId: String,
    val keyId: String,
    internal val privateKeyPath: Path,
    topics: Map<PushApplication, String>,
) {
    private val topics = topics.toMap()

    init {
        require(teamId.matches(APPLE_IDENTIFIER_PATTERN)) {
            "APNs team ID must contain ten uppercase letters or digits"
        }
        require(keyId.matches(APPLE_IDENTIFIER_PATTERN)) {
            "APNs key ID must contain ten uppercase letters or digits"
        }
        require(privateKeyPath.toString().isNotBlank()) { "APNs private key path must not be blank" }
        require(PushApplication.NEXO_CLIENT_IOS in this.topics)
        require(PushApplication.NEXO_BUSINESS_IOS in this.topics)
        require(this.topics.values.all { it.matches(BUNDLE_TOPIC_PATTERN) }) {
            "APNs topics must be bounded bundle identifiers"
        }
    }

    fun topicFor(application: PushApplication): String? = topics[application]

    override fun toString(): String = "ApnsSandboxConfiguration(teamId=<redacted>, keyId=<redacted>, " +
        "privateKeyPath=<redacted>, applications=${topics.keys.sortedBy(PushApplication::name)})"

    companion object {
        const val SANDBOX_HOST = "api.sandbox.push.apple.com"
        private val APPLE_IDENTIFIER_PATTERN = Regex("[A-Z0-9]{10}")
        private val BUNDLE_TOPIC_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,254}")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ApnsSandboxConfiguration {
            fun required(name: String): String = environment[name]?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing required environment variable: $name")

            return ApnsSandboxConfiguration(
                teamId = required("CONNECT_LAB_APNS_TEAM_ID"),
                keyId = required("CONNECT_LAB_APNS_KEY_ID"),
                privateKeyPath = Path.of(required("CONNECT_LAB_APNS_PRIVATE_KEY_PATH")),
                topics = mapOf(
                    PushApplication.NEXO_CLIENT_IOS to required("CONNECT_LAB_APNS_CLIENT_TOPIC"),
                    PushApplication.NEXO_BUSINESS_IOS to required("CONNECT_LAB_APNS_BUSINESS_TOPIC"),
                ),
            )
        }
    }
}
