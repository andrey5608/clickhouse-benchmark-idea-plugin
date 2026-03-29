package com.github.andrey5608.clickhouse.benchmark.idea.plugin.toolWindow

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.model.BenchmarkResult
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.BenchmarkHistoryService
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.BenchmarkRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
        val panel = BenchmarkPanel(project)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, "", false)
        )
    }
}

private class BenchmarkPanel(project: Project) : JPanel(BorderLayout()) {

    private val history = BenchmarkHistoryService.getInstance(project)
    private val tableModel = BenchmarkTableModel()
    private val table = JBTable(tableModel)
    private val emptyLabel = JLabel(
        "No results — select SQL and press Ctrl+Shift+B",
        SwingConstants.CENTER
    )

    private val refreshListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater { refresh() }
    }

    init {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Test Connection", "Verify ClickHouse connection settings", AllIcons.Debugger.Db_verified_breakpoint) {
                override fun actionPerformed(e: AnActionEvent) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            BenchmarkRunner.getInstance().testConnection()
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, "Connection successful!", "Test Connection")
                            }
                        } catch (ex: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(project, ex.message ?: "Unknown error", "Test Connection Failed")
                            }
                        }
                    }
                }
            })
            add(object : AnAction("Clear History", "Remove all results", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = history.clearHistory()
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClickHouseBenchmarkToolbar", group, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        table.setShowGrid(true)
        table.autoResizeMode = JBTable.AUTO_RESIZE_OFF
        Column.entries.forEach { col ->
            table.columnModel.getColumn(col.ordinal).preferredWidth = col.width
        }

        add(JBScrollPane(table), BorderLayout.CENTER)
        history.addListener(refreshListener)
        refresh()
    }

    private fun refresh() {
        tableModel.updateResults(history.getResults())
        remove(emptyLabel)
        if (tableModel.rowCount == 0) add(emptyLabel, BorderLayout.SOUTH)
        revalidate()
        repaint()
    }

    override fun removeNotify() {
        super.removeNotify()
        history.removeListener(refreshListener)
    }
}

private enum class Column(val label: String, val width: Int) {
    RUN       ("#",          40),
    CONNECTION("Host",      120),
    QUERY     ("Query",              300),
    ITERS     ("Repeated (times)",  40),
    MIN       ("Min (ms)",          80),
    AVG       ("Avg (ms)",     80),
    MEDIAN    ("p50 (ms)",     80),
    P95       ("p95 (ms)",     80),
    P99       ("p99 (ms)",     80),
    MAX       ("Max (ms)",     80),
    ROWS      ("Rows Read", 100),
    TIME      ("Time",       70),
}

private class BenchmarkTableModel : AbstractTableModel() {

    private var data: List<BenchmarkResult> = emptyList()

    fun updateResults(results: List<BenchmarkResult>) {
        data = results
        fireTableDataChanged()
    }

    override fun getRowCount() = data.size
    override fun getColumnCount() = Column.entries.size
    override fun getColumnName(col: Int) = Column.entries[col].label

    override fun getValueAt(row: Int, col: Int): Any {
        val r = data[row]
        return when (Column.entries[col]) {
            Column.RUN        -> row + 1
            Column.CONNECTION -> r.connectionName
            Column.QUERY      -> r.queryPreview
            Column.ITERS      -> r.iterations.size
            Column.MIN        -> "%.2f".format(r.minMs)
            Column.AVG        -> "%.2f".format(r.avgMs)
            Column.MEDIAN     -> "%.2f".format(r.p50Ms)
            Column.P95        -> "%.2f".format(r.p95Ms)
            Column.P99        -> "%.2f".format(r.p99Ms)
            Column.MAX        -> "%.2f".format(r.maxMs)
            Column.ROWS       -> r.rowsRead
            Column.TIME       -> r.formattedTimestamp
        }
    }

    override fun getColumnClass(col: Int) = when (Column.entries[col]) {
        Column.RUN, Column.ITERS -> Int::class.javaObjectType
        Column.ROWS              -> Long::class.javaObjectType
        else                     -> String::class.java
    }

    override fun isCellEditable(row: Int, col: Int) = false
}
