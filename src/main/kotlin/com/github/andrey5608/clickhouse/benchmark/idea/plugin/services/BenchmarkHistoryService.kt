package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.model.BenchmarkResult
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.model.IterationStats
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@State(
    name = "BenchmarkHistory",
    storages = [Storage("clickhouse-benchmark-history.xml")]
)
@Service(Service.Level.PROJECT)
class BenchmarkHistoryService : PersistentStateComponent<BenchmarkHistoryService.State> {

    /** Serialization-friendly bean for a single benchmark iteration. */
    class IterationEntry {
        var elapsedMs: Double = 0.0
        var rowsRead: Long = 0L
        var bytesRead: Long = 0L

        fun toStats() = IterationStats(elapsedMs, rowsRead, bytesRead)

        companion object {
            fun from(s: IterationStats) = IterationEntry().apply {
                elapsedMs = s.elapsedMs
                rowsRead = s.rowsRead
                bytesRead = s.bytesRead
            }
        }
    }

    /** Serialization-friendly bean for one benchmark run (N iterations). */
    class HistoryEntry {
        var query: String = ""
        var connectionName: String = ""
        var warmupCount: Int = 0
        var timestamp: Long = 0L
        var iterations: MutableList<IterationEntry> = mutableListOf()

        fun toResult() = BenchmarkResult(
            query = query,
            connectionName = connectionName,
            iterations = iterations.map { it.toStats() },
            warmupCount = warmupCount,
            timestamp = timestamp
        )

        companion object {
            fun from(r: BenchmarkResult) = HistoryEntry().apply {
                query = r.query
                connectionName = r.connectionName
                warmupCount = r.warmupCount
                timestamp = r.timestamp
                iterations = r.iterations.map { IterationEntry.from(it) }.toMutableList()
            }
        }
    }

    class State {
        var entries: MutableList<HistoryEntry> = mutableListOf()
    }

    private var myState = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }

    fun getResults(): List<BenchmarkResult> = myState.entries.map { it.toResult() }

    fun addResult(result: BenchmarkResult) {
        myState.entries.add(HistoryEntry.from(result))
        notifyListeners()
    }

    fun clearHistory() {
        myState.entries.clear()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = listeners.forEach { it() }

    companion object {
        fun getInstance(project: Project): BenchmarkHistoryService = project.service()
    }
}
