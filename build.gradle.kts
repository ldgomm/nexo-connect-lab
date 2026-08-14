plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(ktorLibs.plugins.ktor)
}

group = "com.premierdarkcoffee.nexo"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

spotless {
    ratchetFrom("558d702bd5e7729721cde71d0e3080513798dcdd")

    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }

    kotlinGradle {
        target("*.gradle.kts", "gradle/**/*.gradle.kts")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

dependencies {
    implementation(libs.flyway.core)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.websockets)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.hikari)
    implementation(libs.lettuce.core)
    implementation(libs.logback.classic)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.cio)
    testImplementation(ktorLibs.client.websockets)
    testImplementation(ktorLibs.server.testHost)
}

val postgresIntegrationTest = sourceSets.create("postgresIntegrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[postgresIntegrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[postgresIntegrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("postgresIntegrationTest") {
    description = "Runs the PostgreSQL repository integration contract."
    group = "verification"
    testClassesDirs = postgresIntegrationTest.output.classesDirs
    classpath = postgresIntegrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("semanticAcceptanceTest") {
    description = "Runs formatting-independent protocol, security, and runtime contracts."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.SemanticAcceptanceGateTest")
        includeTestsMatching("*.RealtimeProtocolTest")
        includeTestsMatching("*.RealtimeRoutingProtocolTest")
        includeTestsMatching("*.DurableTextAuthorizationContractTest")
        includeTestsMatching("*.ConversationSubscriptionAuthorizerTest")
        includeTestsMatching("*.RealtimeTransportHardeningTest")
        includeTestsMatching("*.AuthenticatedRealtimeRoutesTest")
        includeTestsMatching("*.AuthenticatedRealtimeRuntimeTest")
        includeTestsMatching("*.AuthenticatedRealtimeCatchUpRuntimeTest")
        includeTestsMatching("*.AuthenticatedRealtimeReceiptRuntimeTest")
        includeTestsMatching("*.ReadinessRoutesTest")
        includeTestsMatching("*.RedisEphemeralConfigTest")
        includeTestsMatching("*.RedisEphemeralRuntimeTest")
        includeTestsMatching("*.RealtimeFanoutEnvelopeTest")
        includeTestsMatching("*.MultiInstanceRealtimeFanoutTest")
        includeTestsMatching("*.RedisRealtimeFanoutConfigTest")
        includeTestsMatching("*.EphemeralRealtimeConnectionRegistryTest")
        includeTestsMatching("*.RedisLossRecoveryContractTest")
        includeTestsMatching("*.PresenceLeaseContractTest")
        includeTestsMatching("*.RedisPresenceLeaseStoreTest")
        includeTestsMatching("*.AuthenticatedRealtimePresenceLeaseTest")
        includeTestsMatching("*.TypingSignalProtocolTest")
        includeTestsMatching("*.TypingSignalRateLimiterTest")
        includeTestsMatching("*.RedisTypingLeaseStoreTest")
        includeTestsMatching("*.AuthorizedTypingSignalFanoutTest")
        includeTestsMatching("*.MultiInstanceTypingSignalFanoutTest")
        includeTestsMatching("*.AuthenticatedRealtimeTypingSignalTest")
        includeTestsMatching("*.PrivacyAwarePresenceAggregatorTest")
        includeTestsMatching("*.RedisEphemeralSignalResilienceIntegrationTest")
    }
}

tasks.register<Test>("multiInstanceRealtimeFanoutTest") {
    description = "Verifies versioned, authorised multi-instance message and receipt fan-out."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.RealtimeFanoutEnvelopeTest")
        includeTestsMatching("*.MultiInstanceRealtimeFanoutTest")
        includeTestsMatching("*.RedisRealtimeFanoutConfigTest")
    }
}

tasks.register<Test>("multiDeviceRealtimeRoutingTest") {
    description = "Verifies opaque, TTL-bound multi-device realtime routing."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.EphemeralRealtimeConnectionRegistryTest")
        includeTestsMatching("*.AuthorizedConversationEventHubTest")
        includeTestsMatching("*.MultiInstanceRealtimeFanoutTest")
        includeTestsMatching("*.RealtimeProtocolTest")
        includeTestsMatching("*.RealtimeRoutingProtocolTest")
    }
}

tasks.register<Test>("redisLossRecoveryTest") {
    description = "Proves durable message and receipt repair across Redis flush, loss, partition, and rejoin."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.RedisLossRecoveryContractTest")
        includeTestsMatching("*.DurableConversationCatchUpTest")
        includeTestsMatching("*.DurableReceiptCursorTest")
        includeTestsMatching("*.RedisEphemeralRuntimeTest")
    }
}

tasks.register<Test>("redisEphemeralBoundaryTest") {
    description = "Verifies the isolated, degradable Redis client lifecycle."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.ReadinessRoutesTest")
        includeTestsMatching("*.RedisEphemeralConfigTest")
        includeTestsMatching("*.RedisEphemeralRuntimeTest")
    }
}

tasks.register<Test>("presenceLeaseTest") {
    description = "Verifies bounded Redis presence lease ownership, refresh, expiry, and reconnect semantics."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.PresenceLeaseContractTest")
        includeTestsMatching("*.RedisPresenceLeaseStoreTest")
        includeTestsMatching("*.AuthenticatedRealtimePresenceLeaseTest")
    }
}

tasks.register<Test>("typingSignalTest") {
    description = "Verifies bounded, authorised, ephemeral typing leases and fan-out."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.TypingSignalProtocolTest")
        includeTestsMatching("*.TypingSignalRateLimiterTest")
        includeTestsMatching("*.RedisTypingLeaseStoreTest")
        includeTestsMatching("*.AuthorizedTypingSignalFanoutTest")
        includeTestsMatching("*.MultiInstanceTypingSignalFanoutTest")
        includeTestsMatching("*.AuthenticatedRealtimeTypingSignalTest")
    }
}

tasks.register<Test>("presenceAggregationTest") {
    description = "Verifies privacy-aware multi-device presence aggregation and topology-free projection."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.PrivacyAwarePresenceAggregatorTest")
        includeTestsMatching("*.RedisPresenceLeaseStoreTest")
        includeTestsMatching("*.RedisPresenceAggregationIntegrationTest")
    }
}

tasks.register<Test>("ephemeralSignalResilienceTest") {
    description = "Proves presence and typing resilience across Redis failure injection and reconnects."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.RedisPresenceLeaseStoreTest")
        includeTestsMatching("*.RedisTypingLeaseStoreTest")
        includeTestsMatching("*.RedisEphemeralSignalResilienceIntegrationTest")
    }
}

tasks.register<Test>("realtimeCapacityBaselineTest") {
    description = "Measures the bounded single-instance realtime capacity envelope."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*.RealtimeSingleInstanceCapacityRuntimeTest")
    }
}

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"))
}
