package com.github.andrey5608.clickhouse.benchmark.idea.plugin.actions

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * A lightweight dialog shown before each benchmark run so that warmup and
 * iteration counts can be adjusted without opening the global settings page.
 * Values are pre-populated from the global defaults stored in [BenchmarkRunner.State].
 */
class BenchmarkRunDialog(
    defaultWarmup: Int,
    defaultIterations: Int,
) : DialogWrapper(false) {

    var warmup: Int = defaultWarmup
    var iterations: Int = defaultIterations

    init {
        title = "Run ClickHouse Benchmark"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Warmup iterations:") {
            intTextField(0..10_000)
                .columns(COLUMNS_SHORT)
                .bindIntText({ warmup }, { warmup = it })
                .comment("Runs discarded before measurement starts")
        }
        row("Measured iterations:") {
            intTextField(1..100_000)
                .columns(COLUMNS_SHORT)
                .bindIntText({ iterations }, { iterations = it })
                .comment("Used to compute min / avg / p95 / p99")
        }
    }
}
