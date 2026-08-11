package com.premierdarkcoffee.nexo.infrastructure.config

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.util.*

enum class ConnectLabEnvironment {
    LOCAL,
    TEST,
    CI,
    STAGING,
    PRODUCTION,
    ;

    companion object {
        fun parse(value: String): ConnectLabEnvironment =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unsupported nexoConnectLab.environment")
    }
}

enum class ConnectLabIdentityMode {
    SYNTHETIC,
    NEXO_GATEWAY,
    ;

    companion object {
        fun parse(value: String): ConnectLabIdentityMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unsupported nexoConnectLab.identityMode")
    }
}

data class ConnectLabConfig(
    val serviceName: String,
    val environment: ConnectLabEnvironment,
    val httpPort: Int,
    val composeProject: String,
    val databaseName: String,
    val redisNamespace: String,
    val mediaBucket: String,
    val identityMode: ConnectLabIdentityMode,
    val nexoIntegrationEnabled: Boolean,
    val callsEnabled: Boolean,
    val e2eeClaim: Boolean,
    val nexoDbDirectAccess: Boolean,
)

object ConnectLabConfigLoader {
    fun load(source: ApplicationConfig): ConnectLabConfig {
        fun required(path: String): String =
            source.propertyOrNull(path)?.getString()?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing required configuration: $path")

        fun strictBoolean(path: String): Boolean =
            required(path).toBooleanStrictOrNull()
                ?: error("Configuration must be true or false: $path")

        val config =
            ConnectLabConfig(
                serviceName = required("nexoConnectLab.serviceName"),
                environment = ConnectLabEnvironment.parse(required("nexoConnectLab.environment")),
                httpPort =
                    required("nexoConnectLab.httpPort").toIntOrNull()
                        ?: error("Configuration must be an integer: nexoConnectLab.httpPort"),
                composeProject = required("nexoConnectLab.composeProject"),
                databaseName = required("nexoConnectLab.databaseName"),
                redisNamespace = required("nexoConnectLab.redisNamespace"),
                mediaBucket = required("nexoConnectLab.mediaBucket"),
                identityMode = ConnectLabIdentityMode.parse(required("nexoConnectLab.identityMode")),
                nexoIntegrationEnabled = strictBoolean("nexoConnectLab.nexoIntegrationEnabled"),
                callsEnabled = strictBoolean("nexoConnectLab.callsEnabled"),
                e2eeClaim = strictBoolean("nexoConnectLab.e2eeClaim"),
                nexoDbDirectAccess = strictBoolean("nexoConnectLab.nexoDbDirectAccess"),
            )

        require(config.serviceName == "nexo-connect-lab") {
            "nexoConnectLab.serviceName must preserve the isolated service identity"
        }
        require(config.httpPort == 8282) {
            "nexoConnectLab.httpPort must use the reserved Connect Lab port"
        }
        require(config.composeProject == "nexo-connect-lab") {
            "nexoConnectLab.composeProject must remain isolated"
        }
        require(config.databaseName == "nexo_connect_lab") {
            "nexoConnectLab.databaseName must remain isolated"
        }
        require(config.redisNamespace == "nexo-connect-lab") {
            "nexoConnectLab.redisNamespace must remain isolated"
        }
        require(config.mediaBucket == "nexo-connect-lab-media") {
            "nexoConnectLab.mediaBucket must remain isolated and private"
        }
        require(config.identityMode == ConnectLabIdentityMode.SYNTHETIC) {
            "Only synthetic identity is allowed during CONNECT.0"
        }
        require(!config.nexoIntegrationEnabled) {
            "Nexo integration is forbidden during CONNECT.0"
        }
        require(!config.callsEnabled) {
            "Voice and video calls are forbidden during CONNECT.0"
        }
        require(!config.e2eeClaim) {
            "End-to-end encryption must not be claimed"
        }
        require(!config.nexoDbDirectAccess) {
            "Direct access to Nexo databases is forbidden"
        }

        return config
    }
}

private val ConnectLabConfigKey = AttributeKey<ConnectLabConfig>("NexoConnectLabTypedConfig")

val Application.connectLabConfig: ConnectLabConfig
    get() = attributes[ConnectLabConfigKey]

fun Application.configureTypedConfiguration() {
    attributes.put(ConnectLabConfigKey, ConnectLabConfigLoader.load(environment.config))
}
