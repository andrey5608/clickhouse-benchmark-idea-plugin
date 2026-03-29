package com.github.andrey5608.clickhousebenchmarkideaplugin.actions

import com.github.andrey5608.clickhousebenchmarkideaplugin.services.BenchmarkHistoryService
import com.github.andrey5608.clickhousebenchmarkideaplugin.services.BenchmarkRunner
import com.github.andrey5608.clickhousebenchmarkideaplugin.services.DataSourceProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager

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

        val connections = service<DataSourceProvider>().getConnections(project)
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
        val iterations = runner.state.iterations
        val warmup = runner.state.warmup

        object : Task.Backgroundable(
            project,
            "CH Benchmark › ${connection.name} (warmup=$warmup, n=$iterations)…",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val total = warmup + iterations
                var done = 0

                // We can't hook into individual iterations from outside BenchmarkRunner,
                // so show progress based on a single blocking run.
                indicator.text = "Warming up ($warmup runs)…"
                indicator.fraction = 0.0

                val result = runner.run(
                    query = query,
                    conn = connection.config,
                    connectionName = connection.name
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
                Messages.showErrorDialog(
                    project,
                    error.message ?: error.toString(),
                    "CH Benchmark — Error"
                )
            }
        }.queue()
    }
}
