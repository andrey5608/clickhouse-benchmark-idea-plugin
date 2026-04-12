package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.intellij.openapi.project.Project

interface DataSourceProvider {

    data class NamedConnection(val name: String, val config: ConnectionConfig)

    /** Returns ClickHouse connections from the IDE's Database tool window, or empty list. */
    fun getConnections(project: Project): List<NamedConnection>

    /** True only in Ultimate where com.intellij.database is available. */
    fun supportsIdeDatasources(): Boolean = false

    /**
     * Tries to resolve the "active" datasource from the SQL file that is currently open in the
     * editor (e.g. a Query Console that is bound to a specific connection).
     *
     * Returns a [NamedConnection] when the file is attached to exactly one ClickHouse datasource
     * so the caller can skip the connection-chooser popup.
     * Returns null in any other case — the caller should fall back to normal selection logic.
     */
    fun getContextConnection(project: Project, psiFile: com.intellij.psi.PsiFile): NamedConnection? = null
}
