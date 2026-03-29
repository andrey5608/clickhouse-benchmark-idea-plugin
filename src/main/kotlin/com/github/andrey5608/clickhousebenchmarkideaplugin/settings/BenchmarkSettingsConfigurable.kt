package com.github.andrey5608.clickhousebenchmarkideaplugin.settings

import com.github.andrey5608.clickhousebenchmarkideaplugin.services.BenchmarkRunner
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
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

    private lateinit var sslEnabledCell: Cell<JBCheckBox>

    private val settingsPanel by lazy {
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
                        .comment("Native TCP port — default 9000")
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
                    cell(passwordField).columns(COLUMNS_MEDIUM)
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
                    comboBox(listOf("none", "strict", "certificate"))
                        .bindItem({ state.sslMode }, { state.sslMode = it ?: "strict" })
                        .comment("strict — verify server cert; certificate — mutual TLS")
                }.enabledIf(sslEnabledCell.selected)
                row("SSL Auth:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslAuth }, { state.sslAuth = it })
                        .comment("sslauth value, e.g. \"certificate\"")
                }.enabledIf(sslEnabledCell.selected)

                separator()
                row { label("PEM certificates") }

                row("Root certificate (CA):") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslRootCertPath }, { state.sslRootCertPath = it })
                        .comment("sslrootcert — path to CA .crt / .pem file")
                }.enabledIf(sslEnabledCell.selected)
                row("Client certificate:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslClientCertPath }, { state.sslClientCertPath = it })
                        .comment("sslcert — path to client .crt / .pem file")
                }.enabledIf(sslEnabledCell.selected)
                row("Client key:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslClientKeyPath }, { state.sslClientKeyPath = it })
                        .comment("sslkey — path to client private key file")
                }.enabledIf(sslEnabledCell.selected)

                separator()
                row { label("Java Keystore / Truststore") }

                row("Keystore path:") {
                    textField()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.sslKeystorePath }, { state.sslKeystorePath = it })
                        .comment("ssl_keystore_path — .jks or .p12 file")
                }.enabledIf(sslEnabledCell.selected)
                row("Keystore password:") {
                    cell(keystorePasswordField).columns(COLUMNS_MEDIUM)
                }.enabledIf(sslEnabledCell.selected)
                row("Truststore path:") {
                    textField()
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
        passwordField.text           = state.password
        keystorePasswordField.text   = state.sslKeystorePassword
        truststorePasswordField.text = state.sslTruststorePassword
        return settingsPanel
    }

    override fun isModified(): Boolean =
        settingsPanel.isModified()
            || passwordField.password.concatToString()           != state.password
            || keystorePasswordField.password.concatToString()   != state.sslKeystorePassword
            || truststorePasswordField.password.concatToString() != state.sslTruststorePassword

    override fun apply() {
        settingsPanel.apply()
        flushPasswordFields()
    }

    override fun reset() {
        settingsPanel.reset()
        passwordField.text           = state.password
        keystorePasswordField.text   = state.sslKeystorePassword
        truststorePasswordField.text = state.sslTruststorePassword
    }

    // Called from the "Test Connection" button. Must be a class-level method (not a DSL lambda)
    // so that settingsPanel.apply() resolves to the DialogPanel member, not Kotlin stdlib apply{}.
    private fun onTestConnectionClicked() {
        // Flush form bindings to state BEFORE reading defaultConnection().
        // Without this, bindText/bindIntText setters never run and state retains
        // last-persisted values (default: localhost:9000).
        settingsPanel.apply()
        flushPasswordFields()
        val config = runner.defaultConnection()
        thisLogger().debug(
            "Test Connection: host=${config.host} port=${config.port} " +
            "db=${config.database} user=${config.user} ssl=${config.ssl.enabled}"
        )
        val msg = runCatching { runner.testConnection(config) }
            .fold(
                onSuccess = { "Connection successful" },
                onFailure = { "Connection failed:\n${it.message}" }
            )
        Messages.showInfoMessage(msg, "ClickHouse Benchmark")
    }

    private fun flushPasswordFields() {
        state.password              = passwordField.password.concatToString()
        state.sslKeystorePassword   = keystorePasswordField.password.concatToString()
        state.sslTruststorePassword = truststorePasswordField.password.concatToString()
    }
}
