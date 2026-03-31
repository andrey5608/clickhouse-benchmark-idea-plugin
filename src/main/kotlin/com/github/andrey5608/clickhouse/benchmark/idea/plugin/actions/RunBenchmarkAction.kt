package com.github.andrey5608.clickhouse.benchmark.idea.plugin.actions

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.BenchmarkHistoryService
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.BenchmarkRunner
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.DataSourceProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer

class RunBenchmarkAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val query = e.getData(CommonDataKeys.EDITOR)
            ?.selectionModel?.selectedText?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                Messages.showWarningDialog(project, "No SQL selected.", "ClickHouse Benchmark")
                return
            }

        val provider = service<DataSourceProvider>()
        val useIde = provider.supportsIdeDatasources() && BenchmarkRunner.getInstance().state.useIdeDataSource
        val connections = if (useIde) provider.getConnections(project) else emptyList()
        when {
            connections.isEmpty() -> runWithFallback(project, query)
            connections.size == 1 -> runBenchmark(project, query, connections.first())
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(connections)
                .setTitle("Select ClickHouse DataSource")
                .setRenderer(SimpleListCellRenderer.create { label, value, _ -> label.text = value?.name ?: "" })
                .setMovable(false)
                .setItemChosenCallback { runBenchmark(project, query, it) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    private fun runWithFallback(project: com.intellij.openapi.project.Project, query: String) {
        val conn = BenchmarkRunner.getInstance().defaultConnection()
        runBenchmark(
            project, query,
            DataSourceProvider.NamedConnection("${conn.host}:${conn.port}", conn)
        )
    }

    private fun runBenchmark(
        project: com.intellij.openapi.project.Project,
        query: String,
        connection: DataSourceProvider.NamedConnection
    ) {
        val runner = BenchmarkRunner.getInstance()
        val history = BenchmarkHistoryService.getInstance(project)

        val dialog = BenchmarkRunDialog(
            defaultWarmup = runner.state.warmup,
            defaultIterations = runner.state.iterations,
        )
        if (!dialog.showAndGet()) return   // user cancelled

        val iterations = dialog.iterations
        val warmup = dialog.warmup

        object : Task.Backgroundable(
            project,
            "CH Benchmark › ${connection.name} (warmup=$warmup, n=$iterations)…",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "Connecting…"

                val result = runner.run(
                    query = query,
                    conn = connection.config,
                    connectionName = connection.name,
                    iterations = iterations,
                    warmup = warmup,
                    onProgress = { done, total ->
                        val phase: String
                        val totalEffective: Int
                        val doneEffective: Int
                        if (done <= warmup) {
                            phase = "Warmup"
                            totalEffective = warmup
                            doneEffective = done
                        }
                        else {
                            phase = "Benchmarking"
                            totalEffective = (total - warmup).coerceAtLeast(0)
                            doneEffective = (done - warmup).coerceAtLeast(0)
                        }
                        indicator.text = "$phase… ($doneEffective / $totalEffective)"
                        indicator.fraction = done.toDouble() / total
                    }
                )

                indicator.fraction = 1.0
                history.addResult(result)
            }

            override fun onSuccess() {
                ToolWindowManager.getInstance(project)
                    .getToolWindow("ClickHouse Benchmark")
                    ?.show()
            }

            override fun onThrowable(error: Throwable) {
                thisLogger().error("CH Benchmark failed on '${connection.name}'", error)
                Messages.showErrorDialog(
                    project,
                    error.message ?: error.toString(),
                    "CH Benchmark — Error"
                )
            }
        }.queue()
    }
}
