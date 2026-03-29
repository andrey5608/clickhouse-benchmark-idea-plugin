package com.github.andrey5608.clickhousebenchmarkideaplugin.services

import com.github.andrey5608.clickhousebenchmarkideaplugin.model.BenchmarkResult
import com.github.andrey5608.clickhousebenchmarkideaplugin.settings.ClickHouseConnectionSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.sql.DriverManager
import java.util.UUID

@Service(Service.Level.PROJECT)
class ClickHouseService {

    fun runBenchmark(query: String): BenchmarkResult {
        // Ensure the ClickHouse JDBC driver is registered
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver")

        val settings = ClickHouseConnectionSettings.getInstance()
        val state = settings.state
        val queryId = UUID.randomUUID().toString()

        DriverManager.getConnection(settings.jdbcUrl(), state.user, state.password).use { conn ->
            // Tag the query with a unique id so we can look it up in system.query_log
            conn.createStatement().use { it.execute("SET query_id='$queryId'") }

            val startNs = System.nanoTime()
            var rowsRead = 0L

            conn.createStatement().use { stmt ->
                val hasResultSet = stmt.execute(query)
                if (hasResultSet) {
                    stmt.resultSet.use { rs ->
                        while (rs.next()) rowsRead++
                    }
                } else {
                    rowsRead = stmt.updateCount.coerceAtLeast(0).toLong()
                }
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            // Flush logs and query system.query_log for server-side metrics
            val (bytesRead, memoryUsage) = fetchServerStats(conn, queryId)

            return BenchmarkResult(
                query = query,
                elapsedMs = elapsedMs,
                rowsRead = rowsRead,
                bytesRead = bytesRead,
                memoryUsageBytes = memoryUsage
            )
        }
    }

    private fun fetchServerStats(conn: java.sql.Connection, queryId: String): Pair<Long, Long> {
        return try {
            conn.createStatement().use { it.execute("SYSTEM FLUSH LOGS") }
            conn.prepareStatement(
                """
                SELECT read_bytes, memory_usage
                FROM system.query_log
                WHERE query_id = ?
                  AND type = 'QueryFinish'
                ORDER BY event_time DESC
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, queryId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Could not fetch server stats for query_id=$queryId: ${e.message}")
            0L to 0L
        }
    }

    companion object {
        /** Opens a test connection and closes it immediately. Throws on failure. */
        fun testConnection(settings: ClickHouseConnectionSettings) {
            Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
            val state = settings.state
            DriverManager.getConnection(settings.jdbcUrl(), state.user, state.password).close()
        }
    }
}
