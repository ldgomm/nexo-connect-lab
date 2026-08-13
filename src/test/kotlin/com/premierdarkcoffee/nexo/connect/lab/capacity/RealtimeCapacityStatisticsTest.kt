package com.premierdarkcoffee.nexo.connect.lab.capacity

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal data class RealtimeCapacityPercentiles(val p50Micros: Long, val p95Micros: Long, val p99Micros: Long)

internal object RealtimeCapacityStatistics {
    fun fromNanos(samples: List<Long>): RealtimeCapacityPercentiles {
        require(samples.isNotEmpty()) { "capacity samples must not be empty" }
        require(samples.all { it >= 0 }) { "capacity samples must not be negative" }
        val orderedMicros = samples.map { it / NANOS_PER_MICRO }.sorted()
        return RealtimeCapacityPercentiles(
            p50Micros = nearestRank(orderedMicros, 50),
            p95Micros = nearestRank(orderedMicros, 95),
            p99Micros = nearestRank(orderedMicros, 99),
        )
    }

    private fun nearestRank(ordered: List<Long>, percentile: Int): Long {
        val index = ceil(percentile.toDouble() / 100 * ordered.size).toInt().coerceIn(1, ordered.size) - 1
        return ordered[index]
    }

    private const val NANOS_PER_MICRO = 1_000L
}

class RealtimeCapacityStatisticsTest {
    @Test
    fun `uses deterministic nearest-rank percentiles`() {
        val samples = (1L..20L).map { it * 1_000 }

        assertEquals(
            RealtimeCapacityPercentiles(p50Micros = 10, p95Micros = 19, p99Micros = 20),
            RealtimeCapacityStatistics.fromNanos(samples),
        )
    }

    @Test
    fun `rejects absent or invalid measurements`() {
        assertFailsWith<IllegalArgumentException> { RealtimeCapacityStatistics.fromNanos(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RealtimeCapacityStatistics.fromNanos(listOf(-1)) }
    }
}
