package com.github.andrey5608.clickhousebenchmarkideaplugin.services

import com.intellij.openapi.project.Project

interface DataSourceProvider {

    data class NamedConnection(val name: String, val config: ConnectionConfig)

    /** Returns ClickHouse connections from the IDE's Database tool window, or empty list. */
    fun getConnections(project: Project): List<NamedConnection>
}
