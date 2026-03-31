package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Fallback implementation used on IDEA Community (where com.intellij.database is absent).
 * Returns no connections — RunBenchmarkAction will use BenchmarkRunner's fallback settings instead.
 */
@Service(Service.Level.APP)
class DefaultDataSourceProvider : DataSourceProvider {
    override fun getConnections(project: Project): List<DataSourceProvider.NamedConnection> = emptyList()
}
