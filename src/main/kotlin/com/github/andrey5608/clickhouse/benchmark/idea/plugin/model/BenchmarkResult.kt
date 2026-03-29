package com.github.andrey5608.clickhouse.benchmark.idea.plugin.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class IterationStats(
    val elapsedMs: Double,
    val rowsRead: Long,
    val bytesRead: Long
)

data class BenchmarkResult(
    val query: String,
    val connectionName: String,
    val iterations: List<IterationStats>,
    val warmupCount: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    // Statistical summaries over all measured iterations
    val minMs: Double   get() = iterations.minOf { it.elapsedMs }
    val maxMs: Double   get() = iterations.maxOf { it.elapsedMs }
    val avgMs: Double   get() = iterations.map { it.elapsedMs }.average()
    val p50Ms: Double   get() = percentile(50.0)
    val p95Ms: Double   get() = percentile(95.0)
    val p99Ms: Double   get() = percentile(99.0)

    // Server-side read stats are stable across iterations for the same query
    val rowsRead: Long  get() = iterations.firstOrNull()?.rowsRead ?: 0L
    val bytesRead: Long get() = iterations.firstOrNull()?.bytesRead ?: 0L

    val queryPreview: String
        get() = query.trimIndent().replace('\n', ' ').let {
            if (it.length > 80) it.take(77) + "…" else it
        }

    val formattedTimestamp: String
        get() = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))

    private fun percentile(p: Double): Double {
        if (iterations.isEmpty()) return 0.0
        val sorted = iterations.map { it.elapsedMs }.sorted()
        val idx = ((p / 100.0) * (sorted.size - 1))
            .toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
