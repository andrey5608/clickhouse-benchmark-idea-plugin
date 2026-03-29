package com.github.andrey5608.clickhousebenchmarkideaplugin.actions

import com.github.andrey5608.clickhousebenchmarkideaplugin.services.BenchmarkResultsService
import com.github.andrey5608.clickhousebenchmarkideaplugin.services.ClickHouseService
import com.github.andrey5608.clickhousebenchmarkideaplugin.settings.ClickHouseConnectionSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class RunBenchmarkAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = editor != null && hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText?.trim()

        if (selectedText.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "No SQL selected. Please select a query in the editor.",
                "ClickHouse Benchmark"
            )
            return
        }

        val settings = ClickHouseConnectionSettings.getInstance().state
        if (settings.host.isBlank()) {
            Messages.showWarningDialog(
                project,
                "ClickHouse connection not configured.\nGo to Settings > Tools > ClickHouse Benchmark.",
                "ClickHouse Benchmark"
            )
            return
        }

        val chService = project.service<ClickHouseService>()
        val resultsService = BenchmarkResultsService.getInstance()

        object : Task.Backgroundable(project, "Running ClickHouse Benchmark…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing query…"
                indicator.isIndeterminate = true
                val result = chService.runBenchmark(selectedText)
                resultsService.addResult(result)
            }

            override fun onSuccess() {
                // Reveal the tool window so the user sees the result
                ToolWindowManager.getInstance(project)
                    .getToolWindow("ClickHouse Benchmark")
                    ?.show()
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(
                    project,
                    "Benchmark error: ${error.message}",
                    "ClickHouse Benchmark"
                )
            }
        }.queue()
    }
}
