package com.github.andrey5608.clickhousebenchmarkideaplugin.settings

import com.github.andrey5608.clickhousebenchmarkideaplugin.services.ClickHouseService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class ClickHouseSettingsConfigurable : Configurable {

    private val settings = ClickHouseConnectionSettings.getInstance()
    private val state get() = settings.state

    // UI fields — kept as refs so we can read password (bindText on JBPasswordField won't work with char[])
    private val passwordField = JBPasswordField()

    private val panel by lazy {
        panel {
            group("Connection") {
                row("Host:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.host }, { state.host = it })
                }
                row("Port:") {
                    intTextField(1..65535)
                        .columns(COLUMNS_SHORT)
                        .bindIntText({ state.port }, { state.port = it })
                }
                row("Database:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.database }, { state.database = it })
                }
                row("Username:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.user }, { state.user = it })
                }
                row("Password:") {
                    cell(passwordField)
                        .columns(COLUMNS_MEDIUM)
                }
            }
            row {
                button("Test Connection") {
                    applyPasswordToState()
                    val result = runCatching { ClickHouseService.testConnection(settings) }
                    val msg = result.fold(
                        onSuccess = { "Connection successful" },
                        onFailure = { "Connection failed: ${it.message}" }
                    )
                    com.intellij.openapi.ui.Messages.showInfoMessage(msg, "ClickHouse Benchmark")
                }
            }
        }
    }

    override fun getDisplayName(): String = "ClickHouse Benchmark"

    override fun createComponent(): JComponent {
        passwordField.text = state.password
        return panel
    }

    override fun isModified(): Boolean {
        if (panel.isModified()) return true
        return passwordField.password.concatToString() != state.password
    }

    override fun apply() {
        panel.apply()
        applyPasswordToState()
    }

    override fun reset() {
        panel.reset()
        passwordField.text = state.password
    }

    private fun applyPasswordToState() {
        state.password = passwordField.password.concatToString()
    }
}
