package com.github.andrey5608.clickhousebenchmarkideaplugin.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BenchmarkResult(
    val query: String,
    val elapsedMs: Long,
    val rowsRead: Long,
    val bytesRead: Long,
    val memoryUsageBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedTimestamp: String
        get() = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))

    val queryPreview: String
        get() = query.trimIndent().replace('\n', ' ').let {
            if (it.length > 80) it.take(77) + "…" else it
        }
}
