package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RealtimeConnectionRegistration(val registrationRef: String, val deviceRef: String, val sessionRef: String)

internal data class RegisteredRealtimeConnection(
    val registration: RealtimeConnectionRegistration,
    val principal: ConnectPrincipal,
    val subscribedConversationRefs: MutableSet<String>,
    val messageSink: MessageCreatedEventSink,
    val receiptSink: ReceiptCursorEventSink,
    val lastTouchedNanos: AtomicLong,
)

class OpaqueRealtimeRouteRefFactory(private val secureRandom: SecureRandom = SecureRandom()) {
    fun connectionRef(): String = opaqueRef("connection")

    fun deviceRef(): String = opaqueRef("device")

    fun sessionRef(): String = opaqueRef("session")

    private fun opaqueRef(kind: String): String {
        val entropy = ByteArray(ENTROPY_BYTES)
        secureRandom.nextBytes(entropy)
        return "${kind}_${Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)}"
    }

    private companion object {
        const val ENTROPY_BYTES = 24
    }
}

class EphemeralRealtimeConnectionRegistry(
    ttl: Duration = DEFAULT_TTL,
    private val maximumConnections: Int = DEFAULT_MAXIMUM_CONNECTIONS,
    private val registrationRefFactory: () -> String = OpaqueRealtimeRouteRefFactory()::connectionRef,
    private val deviceRefFactory: () -> String = OpaqueRealtimeRouteRefFactory()::deviceRef,
    private val sessionRefFactory: () -> String = OpaqueRealtimeRouteRefFactory()::sessionRef,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val ttlNanos = ttl.toNanos()
    private val connections = ConcurrentHashMap<String, RegisteredRealtimeConnection>()

    init {
        require(!ttl.isZero && !ttl.isNegative && ttl <= MAXIMUM_TTL) {
            "Realtime connection registry TTL must be positive and bounded"
        }
        require(maximumConnections in 1..MAX_MAXIMUM_CONNECTIONS) {
            "Realtime connection registry capacity must be bounded"
        }
    }

    fun register(
        principal: ConnectPrincipal,
        messageSink: MessageCreatedEventSink,
        receiptSink: ReceiptCursorEventSink,
    ): RealtimeConnectionRegistration {
        purgeExpired()
        check(connections.size < maximumConnections) { "Realtime connection registry capacity reached" }

        repeat(MAX_REFERENCE_ATTEMPTS) {
            val registration =
                RealtimeConnectionRegistration(
                    registrationRef = registrationRefFactory(),
                    deviceRef = deviceRefFactory(),
                    sessionRef = sessionRefFactory(),
                )
            requireValidOpaqueRefs(registration)
            val connection =
                RegisteredRealtimeConnection(
                    registration = registration,
                    principal = principal,
                    subscribedConversationRefs = ConcurrentHashMap.newKeySet(),
                    messageSink = messageSink,
                    receiptSink = receiptSink,
                    lastTouchedNanos = AtomicLong(monotonicNanos()),
                )
            if (connections.putIfAbsent(registration.registrationRef, connection) == null) {
                return registration
            }
        }
        error("Realtime registration reference collision")
    }

    fun subscribe(registration: RealtimeConnectionRegistration, conversationRef: String) {
        val connection = requireActive(registration)
        connection.subscribedConversationRefs += conversationRef
        connection.lastTouchedNanos.set(monotonicNanos())
    }

    fun touch(registration: RealtimeConnectionRegistration): Boolean {
        val connection = connections[registration.registrationRef] ?: return false
        if (!sameRegistration(connection.registration, registration) || removeIfExpired(connection)) return false
        connection.lastTouchedNanos.set(monotonicNanos())
        return true
    }

    fun unregister(registration: RealtimeConnectionRegistration) {
        connections.computeIfPresent(registration.registrationRef) { _, connection ->
            if (sameRegistration(connection.registration, registration)) null else connection
        }
    }

    internal fun candidates(
        conversationRef: String,
        excludedRegistration: RealtimeConnectionRegistration? = null,
    ): List<RegisteredRealtimeConnection> {
        purgeExpired()
        return connections.values.filter { connection ->
            (
                excludedRegistration == null ||
                    !sameRegistration(connection.registration, excludedRegistration)
                ) &&
                conversationRef in connection.subscribedConversationRefs
        }
    }

    fun activeConnectionCount(): Int {
        purgeExpired()
        return connections.size
    }

    fun activeConnectionCount(principal: ConnectPrincipal): Int {
        purgeExpired()
        return connections.values.count { it.principal == principal }
    }

    private fun requireActive(registration: RealtimeConnectionRegistration): RegisteredRealtimeConnection {
        val connection = checkNotNull(connections[registration.registrationRef]) {
            "Realtime connection is not registered"
        }
        check(sameRegistration(connection.registration, registration) && !removeIfExpired(connection)) {
            "Realtime connection is not registered"
        }
        return connection
    }

    private fun purgeExpired() {
        connections.values.forEach(::removeIfExpired)
    }

    private fun removeIfExpired(connection: RegisteredRealtimeConnection): Boolean {
        val expired = monotonicNanos() - connection.lastTouchedNanos.get() >= ttlNanos
        if (expired) {
            connections.remove(connection.registration.registrationRef, connection)
        }
        return expired
    }

    private fun requireValidOpaqueRefs(registration: RealtimeConnectionRegistration) {
        listOf(
            "connection" to registration.registrationRef,
            "device" to registration.deviceRef,
            "session" to registration.sessionRef,
        ).forEach { (kind, value) ->
            require(value.matches(Regex("${kind}_[A-Za-z0-9_-]{32}"))) {
                "Realtime $kind reference must be opaque and bounded"
            }
        }
    }

    private fun sameRegistration(
        first: RealtimeConnectionRegistration,
        second: RealtimeConnectionRegistration,
    ): Boolean = first.registrationRef == second.registrationRef &&
        first.deviceRef == second.deviceRef &&
        first.sessionRef == second.sessionRef

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofSeconds(90)
        val REFRESH_INTERVAL: Duration = Duration.ofSeconds(30)
        private val MAXIMUM_TTL: Duration = Duration.ofHours(1)
        private const val DEFAULT_MAXIMUM_CONNECTIONS = 10_000
        private const val MAX_MAXIMUM_CONNECTIONS = 100_000
        private const val MAX_REFERENCE_ATTEMPTS = 8
    }
}
