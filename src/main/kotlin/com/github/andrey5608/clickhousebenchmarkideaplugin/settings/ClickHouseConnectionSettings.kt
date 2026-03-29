package com.github.andrey5608.clickhousebenchmarkideaplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(
    name = "ClickHouseConnectionSettings",
    storages = [Storage("clickhouse-benchmark.xml")]
)
@Service(Service.Level.APP)
class ClickHouseConnectionSettings : PersistentStateComponent<ClickHouseConnectionSettings.State> {

    data class State(
        var host: String = "localhost",
        var port: Int = 8123,
        var database: String = "default",
        var user: String = "default",
        var password: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun jdbcUrl(): String = "jdbc:ch://${myState.host}:${myState.port}/${myState.database}"

    companion object {
        fun getInstance(): ClickHouseConnectionSettings = service()
    }
}
