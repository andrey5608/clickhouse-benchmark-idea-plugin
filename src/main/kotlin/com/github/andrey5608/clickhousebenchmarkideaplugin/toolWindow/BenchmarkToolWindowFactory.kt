package com.github.andrey5608.clickhousebenchmarkideaplugin.toolWindow

import com.github.andrey5608.clickhousebenchmarkideaplugin.model.BenchmarkResult
import com.github.andrey5608.clickhousebenchmarkideaplugin.services.BenchmarkResultsService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

class BenchmarkToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BenchmarkPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class BenchmarkPanel : JPanel(BorderLayout()) {

    private val resultsService = BenchmarkResultsService.getInstance()
    private val tableModel = BenchmarkTableModel()
    private val table = JBTable(tableModel)
    private val emptyLabel = JLabel("No benchmark results yet. Select SQL and use 'Run as ClickHouse Benchmark'.", SwingConstants.CENTER)

    private val refreshListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater { refresh() }
    }

    init {
        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Clear Results", "Remove all benchmark results", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    resultsService.clearResults()
                }
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClickHouseBenchmarkToolbar", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // Table setup
        table.setShowGrid(true)
        table.columnModel.getColumn(COLUMN_QUERY).preferredWidth = 300
        table.columnModel.getColumn(COLUMN_ELAPSED).preferredWidth = 100
        table.columnModel.getColumn(COLUMN_ROWS).preferredWidth = 100
        table.columnModel.getColumn(COLUMN_BYTES).preferredWidth = 100
        table.columnModel.getColumn(COLUMN_MEMORY).preferredWidth = 120
        table.columnModel.getColumn(COLUMN_TIME).preferredWidth = 80
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN

        add(JBScrollPane(table), BorderLayout.CENTER)

        // Register listener and initial state
        resultsService.addListener(refreshListener)
        refresh()
    }

    private fun refresh() {
        tableModel.updateResults(resultsService.getResults())
        remove(emptyLabel)
        if (tableModel.rowCount == 0) {
            add(emptyLabel, BorderLayout.SOUTH)
        }
        revalidate()
        repaint()
    }

    // Clean up listener when the panel is disposed (parent component hierarchy removed)
    override fun removeNotify() {
        super.removeNotify()
        resultsService.removeListener(refreshListener)
    }

    companion object {
        private const val COLUMN_RUN = 0
        private const val COLUMN_QUERY = 1
        private const val COLUMN_ELAPSED = 2
        private const val COLUMN_ROWS = 3
        private const val COLUMN_BYTES = 4
        private const val COLUMN_MEMORY = 5
        private const val COLUMN_TIME = 6
    }
}

private class BenchmarkTableModel : AbstractTableModel() {

    private val columns = arrayOf("#", "Query", "Elapsed (ms)", "Rows Read", "Bytes Read", "Memory (bytes)", "Time")
    private var data: List<BenchmarkResult> = emptyList()

    fun updateResults(results: List<BenchmarkResult>) {
        data = results
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = data.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(col: Int): String = columns[col]

    override fun getValueAt(row: Int, col: Int): Any {
        val r = data[row]
        return when (col) {
            0 -> (row + 1)
            1 -> r.queryPreview
            2 -> r.elapsedMs
            3 -> r.rowsRead
            4 -> r.bytesRead
            5 -> r.memoryUsageBytes
            6 -> r.formattedTimestamp
            else -> ""
        }
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        0 -> Int::class.javaObjectType
        2, 3, 4, 5 -> Long::class.javaObjectType
        else -> String::class.java
    }

    override fun isCellEditable(row: Int, col: Int): Boolean = false
}
