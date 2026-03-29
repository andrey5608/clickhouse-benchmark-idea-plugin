package com.github.andrey5608.clickhousebenchmarkideaplugin.services

import com.github.andrey5608.clickhousebenchmarkideaplugin.model.BenchmarkResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.APP)
class BenchmarkResultsService {

    private val results: CopyOnWriteArrayList<BenchmarkResult> = CopyOnWriteArrayList()
    private val listeners: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    fun getResults(): List<BenchmarkResult> = results.toList()

    fun addResult(result: BenchmarkResult) {
        results.add(result)
        notifyListeners()
    }

    fun clearResults() {
        results.clear()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    companion object {
        fun getInstance(): BenchmarkResultsService = service()
    }
}
