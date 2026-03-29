package com.github.andrey5608.clickhouse.benchmark.idea.plugin.settings

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.BenchmarkRunner
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.ConnectionConfig
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.SslConfig
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import javax.swing.JComponent

class BenchmarkSettingsConfigurable : Configurable {

    private val runner = BenchmarkRunner.getInstance()
    private val state get() = runner.state

    // Sensitive fields handled outside the panel binding (JBPasswordField masks input
    // and returns char[] — bindText is not appropriate for these)
    private val passwordField          = JBPasswordField()
    private val keystorePasswordField  = JBPasswordField()
    private val truststorePasswordField = JBPasswordField()

    private lateinit var savePasswordCell: Cell<JBCheckBox>
    private lateinit var sslEnabledCell: Cell<JBCheckBox>

    // Cell references for connection fields — used by currentConnectionConfig() so that
    // "Test Connection" reads live form values without calling settingsPanel.apply().
    private lateinit var hostCell: Cell<JBTextField>
    private lateinit var portCell: Cell<JBTextField>
    private lateinit var databaseCell: Cell<JBTextField>
    private lateinit var userCell: Cell<JBTextField>
    private lateinit var sslModeCell: Cell<ComboBox<String>>
    private lateinit var sslAuthCell: Cell<JBTextField>
    private lateinit var sslRootCertCell: Cell<JBTextField>
    private lateinit var sslClientCertCell: Cell<JBTextField>
    private lateinit var sslClientKeyCell: Cell<JBTextField>
    private lateinit var sslKeystorePathCell: Cell<JBTextField>
    private lateinit var sslTruststorePathCell: Cell<JBTextField>

    private val settingsPanel by lazy {
        panel {
            group("Connection") {
                row("Host:") {
                    hostCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.host }, { state.host = it })
                }
                row("Port:") {
                    portCell = intTextField(1..65535)
                        .columns(COLUMNS_SHORT)
                        .bindIntText({ state.port }, { state.port = it })
                        .comment("Native TCP port — default 9000")
                }
                row("Database:") {
                    databaseCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.database }, { state.database = it })
                }
                row("Username:") {
                    userCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.user }, { state.user = it })
                }
                row("Password:") {
                    cell(passwordField).columns(COLUMNS_MEDIUM)
                }
                row {
                    savePasswordCell = checkBox("Save password")
                        .bindSelected({ state.savePassword }, { state.savePassword = it })
                }
                row("Socket timeout (s):") {
                    intTextField(1..3600)
                        .columns(COLUMNS_SHORT)
                        .bindIntText({ state.socketTimeoutSeconds }, { state.socketTimeoutSeconds = it })
                        .comment("Read timeout per query in seconds (default 300 = 5 min)")
                }
                row {
                    button("Test Connection") { onTestConnectionClicked() }
                }
            }

            collapsibleGroup("SSL / TLS") {
                row {
                    sslEnabledCell = checkBox("Enable SSL / TLS")
                        .bindSelected({ state.sslEnabled }, { state.sslEnabled = it })
                }
                row("Mode:") {
                    sslModeCell = comboBox(listOf("none", "strict", "certificate"))
                        .bindItem({ state.sslMode }, { state.sslMode = it ?: "strict" })
                        .comment("strict — verify server cert; certificate — mutual TLS")
                }.enabledIf(sslEnabledCell.selected)
                row("SSL Auth:") {
                    sslAuthCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslAuth }, { state.sslAuth = it })
                        .comment("sslauth value, e.g. \"certificate\"")
                }.enabledIf(sslEnabledCell.selected)

                separator()
                row { label("PEM certificates") }

                row("Root certificate (CA):") {
                    sslRootCertCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslRootCertPath }, { state.sslRootCertPath = it })
                        .comment("sslrootcert — path to CA .crt / .pem file")
                }.enabledIf(sslEnabledCell.selected)
                row("Client certificate:") {
                    sslClientCertCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslClientCertPath }, { state.sslClientCertPath = it })
                        .comment("sslcert — path to client .crt / .pem file")
                }.enabledIf(sslEnabledCell.selected)
                row("Client key:") {
                    sslClientKeyCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslClientKeyPath }, { state.sslClientKeyPath = it })
                        .comment("sslkey — path to client private key file")
                }.enabledIf(sslEnabledCell.selected)

                separator()
                row { label("Java Keystore / Truststore") }

                row("Keystore path:") {
                    sslKeystorePathCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslKeystorePath }, { state.sslKeystorePath = it })
                        .comment("ssl_keystore_path — .jks or .p12 file")
                }.enabledIf(sslEnabledCell.selected)
                row("Keystore password:") {
                    cell(keystorePasswordField).columns(COLUMNS_MEDIUM)
                }.enabledIf(sslEnabledCell.selected)
                row("Truststore path:") {
                    sslTruststorePathCell = textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslTruststorePath }, { state.sslTruststorePath = it })
                        .comment("ssl_truststore_path — .jks or .p12 file")
                }.enabledIf(sslEnabledCell.selected)
                row("Truststore password:") {
                    cell(truststorePasswordField).columns(COLUMNS_MEDIUM)
                }.enabledIf(sslEnabledCell.selected)
            }

            group("Benchmark Defaults") {
                row("Warmup iterations:") {
                    intTextField(0..1000)
                        .columns(COLUMNS_SHORT)
                        .bindIntText({ state.warmup }, { state.warmup = it })
                        .comment("Runs discarded before measurement starts")
                }
                row("Measured iterations:") {
                    intTextField(1..10000)
                        .columns(COLUMNS_SHORT)
                        .bindIntText({ state.iterations }, { state.iterations = it })
                        .comment("Used to compute min / avg / p95 / p99")
                }
            }
        }
    }

    override fun getDisplayName(): String = "ClickHouse Benchmark"

    override fun createComponent(): JComponent {
        passwordField.text           = runner.getPassword()
        keystorePasswordField.text   = runner.getSslKeystorePassword()
        truststorePasswordField.text = runner.getSslTruststorePassword()
        return settingsPanel
    }

    override fun isModified(): Boolean =
        settingsPanel.isModified()
            || passwordField.password.concatToString()           != runner.getPassword()
            || keystorePasswordField.password.concatToString()   != runner.getSslKeystorePassword()
            || truststorePasswordField.password.concatToString() != runner.getSslTruststorePassword()

    override fun apply() {
        settingsPanel.apply()
        flushPasswordFields()
    }

    override fun reset() {
        settingsPanel.reset()
        passwordField.text           = runner.getPassword()
        keystorePasswordField.text   = runner.getSslKeystorePassword()
        truststorePasswordField.text = runner.getSslTruststorePassword()
    }

    // Called from the "Test Connection" button. Must be a class-level method (not a DSL lambda)
    // so that settingsPanel.apply() resolves to the DialogPanel member, not Kotlin stdlib apply{}.
    private fun onTestConnectionClicked() {
        val config = currentConnectionConfig()
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing ClickHouse connection…", false) {
            private var resultMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                resultMsg = runCatching { runner.testConnection(config) }
                    .fold(
                        onSuccess = { "Connection successful" },
                        onFailure = { "Connection failed:\n${it.message}" }
                    )
            }

            override fun onSuccess() {
                Messages.showInfoMessage(resultMsg, "ClickHouse Benchmark")
            }
        })
    }

    /** Builds a [ConnectionConfig] directly from live form-field values without mutating state. */
    private fun currentConnectionConfig(): ConnectionConfig {
        val ssl = SslConfig(
            enabled        = sslEnabledCell.component.isSelected,
            mode           = sslModeCell.component.selectedItem as? String ?: "strict",
            auth           = sslAuthCell.component.text,
            rootCertPath   = sslRootCertCell.component.text,
            clientCertPath = sslClientCertCell.component.text,
            clientKeyPath  = sslClientKeyCell.component.text,
            keystorePath   = sslKeystorePathCell.component.text,
            keystorePassword = keystorePasswordField.password.concatToString(),
            truststorePath   = sslTruststorePathCell.component.text,
            truststorePassword = truststorePasswordField.password.concatToString(),
        )
        return ConnectionConfig(
            host     = hostCell.component.text,
            port     = portCell.component.text.toIntOrNull() ?: 9000,
            database = databaseCell.component.text,
            user     = userCell.component.text,
            password = passwordField.password.concatToString(),
            ssl      = ssl,
        )
    }

    private fun flushPasswordFields() {
        runner.savePasswords(
            save               = savePasswordCell.component.isSelected,
            password           = passwordField.password.concatToString(),
            keystorePassword   = keystorePasswordField.password.concatToString(),
            truststorePassword = truststorePasswordField.password.concatToString()
        )
    }
}
