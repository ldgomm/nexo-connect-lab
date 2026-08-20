package com.premierdarkcoffee.nexo.connect.lab.infrastructure.config

import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabConfigLoader
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabEnvironment
import com.premierdarkcoffee.nexo.connect.lab.infrastructure.config.ConnectLabIdentityMode
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectLabConfigTest {
    @Test
    fun `loads the isolated Connect zero baseline`() {
        val config = ConnectLabConfigLoader.load(validConfig())

        assertEquals("nexo-connect-lab", config.serviceName)
        assertEquals(ConnectLabEnvironment.LOCAL, config.environment)
        assertEquals(8282, config.httpPort)
        assertEquals("nexo-connect-lab", config.composeProject)
        assertEquals("nexo_connect_lab", config.databaseName)
        assertEquals("nexo-connect-lab", config.redisNamespace)
        assertEquals("nexo-connect-lab-media", config.mediaBucket)
        assertEquals(ConnectLabIdentityMode.SYNTHETIC, config.identityMode)
        assertFalse(config.nexoIntegrationEnabled)
        assertFalse(config.callsEnabled)
        assertFalse(config.e2eeClaim)
        assertFalse(config.nexoDbDirectAccess)
        assertTrue(config.databaseLifecycleEnabled)
        assertFalse(config.notificationDeliveryEnabled)
    }

    @Test
    fun `rejects unknown runtime environment`() {
        val source = validConfig("nexoConnectLab.environment" to "mystery")

        assertFailsWith<IllegalStateException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects future Nexo gateway identity during Connect zero`() {
        val source = validConfig("nexoConnectLab.identityMode" to "nexo_gateway")

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects Nexo integration during Connect zero`() {
        val source = validConfig("nexoConnectLab.nexoIntegrationEnabled" to "true")

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects calls during Connect zero`() {
        val source = validConfig("nexoConnectLab.callsEnabled" to "true")

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects an end to end encryption claim`() {
        val source = validConfig("nexoConnectLab.e2eeClaim" to "true")

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects direct Nexo database access`() {
        val source = validConfig("nexoConnectLab.nexoDbDirectAccess" to "true")

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects malformed booleans instead of guessing`() {
        val source = validConfig("nexoConnectLab.callsEnabled" to "yes")

        assertFailsWith<IllegalStateException> { ConnectLabConfigLoader.load(source) }
    }

    @Test
    fun `rejects notification delivery without the PostgreSQL lifecycle`() {
        val source =
            validConfig(
                "nexoConnectLab.databaseLifecycleEnabled" to "false",
                "nexoConnectLab.notificationDeliveryEnabled" to "true",
            )

        assertFailsWith<IllegalArgumentException> { ConnectLabConfigLoader.load(source) }
    }

    private fun validConfig(vararg overrides: Pair<String, String>): MapApplicationConfig {
        val values =
            linkedMapOf(
                "nexoConnectLab.serviceName" to "nexo-connect-lab",
                "nexoConnectLab.environment" to "local",
                "nexoConnectLab.httpPort" to "8282",
                "nexoConnectLab.composeProject" to "nexo-connect-lab",
                "nexoConnectLab.databaseName" to "nexo_connect_lab",
                "nexoConnectLab.redisNamespace" to "nexo-connect-lab",
                "nexoConnectLab.mediaBucket" to "nexo-connect-lab-media",
                "nexoConnectLab.identityMode" to "synthetic",
                "nexoConnectLab.nexoIntegrationEnabled" to "false",
                "nexoConnectLab.callsEnabled" to "false",
                "nexoConnectLab.e2eeClaim" to "false",
                "nexoConnectLab.nexoDbDirectAccess" to "false",
                "nexoConnectLab.databaseLifecycleEnabled" to "true",
                "nexoConnectLab.notificationDeliveryEnabled" to "false",
            )
        values.putAll(overrides)
        return MapApplicationConfig(*values.map { it.toPair() }.toTypedArray())
    }
}
