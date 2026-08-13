package com.premierdarkcoffee.nexo.connect.lab.capacity

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ClientRealtimeFrameType
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrame
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.ServerRealtimeFrameType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeSingleInstanceCapacityRuntimeTest {
    @Test
    fun `measures bounded capacity without durable loss`() = runBlocking {
        val websocketEndpoint = System.getenv(RUNTIME_URL_ENV) ?: return@runBlocking
        val httpEndpoint = requiredEnvironment(RUNTIME_HTTP_URL_ENV).trimEnd('/')
        val businessToken = requiredEnvironment(BUSINESS_TOKEN_ENV)
        val conversationRef = requiredEnvironment(CONVERSATION_REF_ENV)
        val reportPath = Path.of(requiredEnvironment(REPORT_PATH_ENV)).toAbsolutePath().normalize()
        val json = Json { ignoreUnknownKeys = false }
        val client = HttpClient(CIO) { install(WebSockets) }
        val runtime = Runtime.getRuntime()
        val memoryBefore = usedMemoryBytes(runtime)

        try {
            runConnectionTier(
                client = client,
                websocketEndpoint = websocketEndpoint,
                businessToken = businessToken,
                conversationRef = conversationRef,
                json = json,
                tierName = "warmup",
                connections = WARMUP_CONNECTIONS,
                slowConnections = 0,
                slowDelayMillis = 0,
            )
            val baselineSamples =
                runConnectionTier(
                    client = client,
                    websocketEndpoint = websocketEndpoint,
                    businessToken = businessToken,
                    conversationRef = conversationRef,
                    json = json,
                    tierName = "baseline",
                    connections = BASELINE_CONNECTIONS,
                    slowConnections = 0,
                    slowDelayMillis = 0,
                )
            val pressureSamples =
                runConnectionTier(
                    client = client,
                    websocketEndpoint = websocketEndpoint,
                    businessToken = businessToken,
                    conversationRef = conversationRef,
                    json = json,
                    tierName = "pressure",
                    connections = PRESSURE_CONNECTIONS,
                    slowConnections = PRESSURE_SLOW_CONNECTIONS,
                    slowDelayMillis = SLOW_READER_DELAY_MILLIS,
                )
            val sendSamples =
                measureDurableLiveDelivery(
                    client = client,
                    websocketEndpoint = websocketEndpoint,
                    httpEndpoint = httpEndpoint,
                    businessToken = businessToken,
                    conversationRef = conversationRef,
                    json = json,
                )
            val catchUpSamples =
                measureDurableCatchUp(
                    client = client,
                    websocketEndpoint = websocketEndpoint,
                    businessToken = businessToken,
                    conversationRef = conversationRef,
                    json = json,
                )

            val baseline = RealtimeCapacityStatistics.fromNanos(baselineSamples)
            val pressure = RealtimeCapacityStatistics.fromNanos(pressureSamples)
            val send = RealtimeCapacityStatistics.fromNanos(sendSamples)
            val catchUp = RealtimeCapacityStatistics.fromNanos(catchUpSamples)
            assertTrue(
                pressure.p95Micros >= CONTROLLED_DEGRADATION_MINIMUM_MICROS,
                "controlled slow-reader tier did not produce the measured degradation point",
            )

            writeReport(
                reportPath = reportPath,
                baseline = baseline,
                pressure = pressure,
                send = send,
                catchUp = catchUp,
                memoryBefore = memoryBefore,
                memoryAfter = usedMemoryBytes(runtime),
            )
        } finally {
            client.close()
        }
    }

    private suspend fun runConnectionTier(
        client: HttpClient,
        websocketEndpoint: String,
        businessToken: String,
        conversationRef: String,
        json: Json,
        tierName: String,
        connections: Int,
        slowConnections: Int,
        slowDelayMillis: Long,
    ): List<Long> = coroutineScope {
        val ready = Channel<Long>(connections)
        val release = CompletableDeferred<Unit>()
        val jobs =
            (0 until connections).map { index ->
                async {
                    client.webSocket(
                        request = {
                            url(websocketEndpoint)
                            bearerAuth(businessToken)
                        },
                    ) {
                        val startedAt = System.nanoTime()
                        assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(json).type)
                        sendSubscription(
                            json = json,
                            conversationRef = conversationRef,
                            correlationId = "$tierName-$index",
                            afterSequence = 0,
                        )
                        if (index < slowConnections) delay(slowDelayMillis)
                        assertEquals(
                            ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED,
                            receiveServerFrame(json).type,
                        )
                        val synced = receiveServerFrame(json)
                        assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, synced.type)
                        assertEquals(0, synced.replayedMessageCount)
                        ready.send(System.nanoTime() - startedAt)
                        release.await()
                    }
                }
            }

        val samples =
            try {
                withTimeout(TIER_TIMEOUT_MILLIS) {
                    List(connections) { ready.receive() }
                }
            } finally {
                release.complete(Unit)
            }
        jobs.awaitAll()
        samples
    }

    private suspend fun measureDurableLiveDelivery(
        client: HttpClient,
        websocketEndpoint: String,
        httpEndpoint: String,
        businessToken: String,
        conversationRef: String,
        json: Json,
    ): List<Long> {
        val samples = mutableListOf<Long>()
        client.webSocket(
            request = {
                url(websocketEndpoint)
                bearerAuth(businessToken)
            },
        ) {
            assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(json).type)
            sendSubscription(json, conversationRef, "durable-live", afterSequence = 0)
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, receiveServerFrame(json).type)
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, receiveServerFrame(json).type)

            repeat(DURABLE_MESSAGE_COUNT) { offset ->
                val sequence = offset + 1
                val startedAt = System.nanoTime()
                val response =
                    client.post("$httpEndpoint/v1/conversations/$conversationRef/messages") {
                        bearerAuth(businessToken)
                        setBody(
                            """{"clientMessageRef":"connect-10-client-$sequence","idempotencyKey":"connect-10-key-$sequence","body":"capacity-message-$sequence"}""",
                        )
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                response.bodyAsText()
                val live = receiveServerFrame(json)
                assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, live.type)
                assertEquals(sequence.toLong(), live.message?.sequence)
                samples += System.nanoTime() - startedAt
            }
        }
        return samples
    }

    private suspend fun measureDurableCatchUp(
        client: HttpClient,
        websocketEndpoint: String,
        businessToken: String,
        conversationRef: String,
        json: Json,
    ): List<Long> {
        val samples = mutableListOf<Long>()
        client.webSocket(
            request = {
                url(websocketEndpoint)
                bearerAuth(businessToken)
            },
        ) {
            assertEquals(ServerRealtimeFrameType.AUTH_OK, receiveServerFrame(json).type)
            sendSubscription(json, conversationRef, "durable-catch-up", afterSequence = 0)
            val subscribed = receiveServerFrame(json)
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED, subscribed.type)
            assertEquals(DURABLE_MESSAGE_COUNT.toLong(), subscribed.lastMessageSequence)

            repeat(DURABLE_MESSAGE_COUNT) { offset ->
                val startedAt = System.nanoTime()
                val replayed = receiveServerFrame(json)
                samples += System.nanoTime() - startedAt
                assertEquals(ServerRealtimeFrameType.MESSAGE_CREATED, replayed.type)
                assertEquals((offset + 1).toLong(), replayed.message?.sequence)
            }
            val synced = receiveServerFrame(json)
            assertEquals(ServerRealtimeFrameType.CONVERSATION_SYNCED, synced.type)
            assertEquals(DURABLE_MESSAGE_COUNT, synced.replayedMessageCount)
            assertEquals(DURABLE_MESSAGE_COUNT.toLong(), synced.lastMessageSequence)
        }
        return samples
    }

    private suspend fun DefaultClientWebSocketSession.sendSubscription(
        json: Json,
        conversationRef: String,
        correlationId: String,
        afterSequence: Long,
    ) {
        send(
            Frame.Text(
                json.encodeToString(
                    ClientRealtimeFrame(
                        protocolMajor = 1,
                        type = ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION,
                        eventId = "connect-10-event-$correlationId",
                        correlationId = "connect-10-$correlationId",
                        conversationRef = conversationRef,
                        afterSequence = afterSequence,
                    ),
                ),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveServerFrame(json: Json): ServerRealtimeFrame =
        withTimeout(FRAME_TIMEOUT_MILLIS) {
            val raw = (incoming.receive() as Frame.Text).readText()
            json.decodeFromString(raw)
        }

    private fun writeReport(
        reportPath: Path,
        baseline: RealtimeCapacityPercentiles,
        pressure: RealtimeCapacityPercentiles,
        send: RealtimeCapacityPercentiles,
        catchUp: RealtimeCapacityPercentiles,
        memoryBefore: Long,
        memoryAfter: Long,
    ) {
        val lines =
            listOf(
                "report.schema.version=1",
                "programme=NEXO_CONNECT_LAB",
                "phase=CONNECT.10",
                "scope=SINGLE_APPLICATION_INSTANCE",
                "claim=BOUNDED_LOCAL_BASELINE_NOT_PRODUCTION_CAPACITY",
                "baseline.connections=$BASELINE_CONNECTIONS",
                "baseline.connection.p50.micros=${baseline.p50Micros}",
                "baseline.connection.p95.micros=${baseline.p95Micros}",
                "baseline.connection.p99.micros=${baseline.p99Micros}",
                "pressure.connections=$PRESSURE_CONNECTIONS",
                "pressure.slow_reader.connections=$PRESSURE_SLOW_CONNECTIONS",
                "pressure.slow_reader.delay.millis=$SLOW_READER_DELAY_MILLIS",
                "pressure.connection.p50.micros=${pressure.p50Micros}",
                "pressure.connection.p95.micros=${pressure.p95Micros}",
                "pressure.connection.p99.micros=${pressure.p99Micros}",
                "degradation.point.connections=$PRESSURE_CONNECTIONS",
                "degradation.controlled=true",
                "durable.messages.expected=$DURABLE_MESSAGE_COUNT",
                "durable.messages.live_observed=$DURABLE_MESSAGE_COUNT",
                "durable.messages.catch_up_observed=$DURABLE_MESSAGE_COUNT",
                "durable.loss=0",
                "send_live.p50.micros=${send.p50Micros}",
                "send_live.p95.micros=${send.p95Micros}",
                "send_live.p99.micros=${send.p99Micros}",
                "catch_up.p50.micros=${catchUp.p50Micros}",
                "catch_up.p95.micros=${catchUp.p95Micros}",
                "catch_up.p99.micros=${catchUp.p99Micros}",
                "harness.jvm.memory.before.bytes=$memoryBefore",
                "harness.jvm.memory.after.bytes=$memoryAfter",
            )
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun usedMemoryBytes(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

    companion object {
        private const val RUNTIME_URL_ENV = "CONNECT_LAB_CONNECT_10_RUNTIME_URL"
        private const val RUNTIME_HTTP_URL_ENV = "CONNECT_LAB_CONNECT_10_RUNTIME_HTTP_URL"
        private const val BUSINESS_TOKEN_ENV = "CONNECT_LAB_CONNECT_10_BUSINESS_TOKEN"
        private const val CONVERSATION_REF_ENV = "CONNECT_LAB_CONNECT_10_CONVERSATION_REF"
        private const val REPORT_PATH_ENV = "CONNECT_LAB_CONNECT_10_REPORT_PATH"
        private const val WARMUP_CONNECTIONS = 2
        private const val BASELINE_CONNECTIONS = 4
        private const val PRESSURE_CONNECTIONS = 16
        private const val PRESSURE_SLOW_CONNECTIONS = 4
        private const val SLOW_READER_DELAY_MILLIS = 750L
        private const val DURABLE_MESSAGE_COUNT = 12
        private const val CONTROLLED_DEGRADATION_MINIMUM_MICROS = 700_000L
        private const val FRAME_TIMEOUT_MILLIS = 10_000L
        private const val TIER_TIMEOUT_MILLIS = 30_000L
    }
}
