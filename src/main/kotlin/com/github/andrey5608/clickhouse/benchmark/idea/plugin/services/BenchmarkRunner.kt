package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.model.BenchmarkResult
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.model.IterationStats
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * JDBC-based benchmark engine using the ClickHouse native TCP protocol (port 9000).
 *
 * Depends on com.clickhouse:clickhouse-jdbc (thin jar) which transitively pulls
 * clickhouse-client. That module contains a pure-JVM TCP socket client — no HTTP,
 * no Apache HttpClient, no native binaries. Works on Windows, macOS, Linux.
 *
 * Native TCP is activated by passing protocol=TCP in the JDBC connection Properties.
 * SSL/TLS is configured through the ssl* properties in [SslConfig] when enabled.
 *
 * The driver is loaded via [javaClass.classLoader] (the plugin classloader) and used
 * directly instead of going through [java.sql.DriverManager]. This avoids the thread
 * context classloader issues that arise in IntelliJ's plugin sandbox environment where
 * DriverManager may not see drivers registered by non-system classloaders.
 *
 * Each benchmark run:
 *   1. Opens a single JDBC connection (amortises handshake outside the measurement window).
 *   2. Executes [warmup] iterations and discards their timings.
 *   3. Executes [iterations] measured iterations and records per-iteration [IterationStats].
 */
@State(
    name = "BenchmarkRunner",
    storages = [Storage("clickhouse-benchmark.xml")]
)
@Service(Service.Level.APP)
class BenchmarkRunner : PersistentStateComponent<BenchmarkRunner.State> {

    data class State(
        var host: String = "localhost",
        var port: Int = 9000,
        var database: String = "default",
        var user: String = "default",
        var iterations: Int = 10,
        var warmup: Int = 3,
        // SSL — flat fields for clean XML serialisation
        var sslEnabled: Boolean = false,
        var sslMode: String = "strict",
        var sslAuth: String = "",
        var sslRootCertPath: String = "",
        var sslClientCertPath: String = "",
        var sslClientKeyPath: String = "",
        var sslKeystorePath: String = "",
        var sslTruststorePath: String = ""
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun defaultConnection() = ConnectionConfig(
        host     = myState.host,
        port     = myState.port,
        database = myState.database,
        user     = myState.user,
        password = getPassword(),
        ssl      = SslConfig(
            enabled            = myState.sslEnabled,
            mode               = myState.sslMode,
            auth               = myState.sslAuth,
            rootCertPath       = myState.sslRootCertPath,
            clientCertPath     = myState.sslClientCertPath,
            clientKeyPath      = myState.sslClientKeyPath,
            keystorePath       = myState.sslKeystorePath,
            keystorePassword   = getSslKeystorePassword(),
            truststorePath     = myState.sslTruststorePath,
            truststorePassword = getSslTruststorePassword()
        )
    )

    fun run(
        query: String,
        conn: ConnectionConfig = defaultConnection(),
        connectionName: String = "${conn.host}:${conn.port}",
        iterations: Int = myState.iterations,
        warmup: Int = myState.warmup
    ): BenchmarkResult {
        thisLogger().info(
            "BenchmarkRunner.run: conn=${conn.host}:${conn.port}/${conn.database} " +
            "ssl=${conn.ssl.enabled} warmup=$warmup iterations=$iterations"
        )

        openConnection(conn).use { jdbc ->
            thisLogger().info("BenchmarkRunner: connection opened, starting warmup ($warmup runs)")
            repeat(warmup) { i ->
                executeOnce(jdbc, query).also {
                    thisLogger().info("warmup[$i]: ${it.elapsedMs} ms")
                }
            }

            thisLogger().info("BenchmarkRunner: warmup done, starting measurement ($iterations runs)")
            val stats = List(iterations) { i ->
                executeOnce(jdbc, query).also {
                    thisLogger().info("iter[$i]: ${it.elapsedMs} ms rows=${it.rowsRead}")
                }
            }

            val result = BenchmarkResult(
                query          = query,
                connectionName = connectionName,
                iterations     = stats,
                warmupCount    = warmup
            )
            thisLogger().info(
                "BenchmarkRunner.run done: min=${result.minMs} avg=${result.avgMs} " +
                "p95=${result.p95Ms} p99=${result.p99Ms} max=${result.maxMs} ms"
            )
            return result
        }
    }

    fun testConnection(conn: ConnectionConfig = defaultConnection()) {
        val ssl = conn.ssl
        thisLogger().info(
            "Test Connection: url=${conn.jdbcUrlSafe()} " +
            "ssl.enabled=${ssl.enabled}" +
            if (ssl.enabled) " ssl.mode=${ssl.mode} ssl.auth=${ssl.auth} " +
                "rootCert=${ssl.rootCertPath} clientCert=${ssl.clientCertPath} clientKey=${ssl.clientKeyPath} " +
                "keystore=${ssl.keystorePath} truststore=${ssl.truststorePath}"
            else ""
        )
        val start = System.currentTimeMillis()
        try {
            openConnection(conn).close()
            thisLogger().info("Test Connection: SUCCESS (${System.currentTimeMillis() - start} ms)")
        } catch (e: Exception) {
            thisLogger().warn("Test Connection: FAILURE (${System.currentTimeMillis() - start} ms) — ${e.message}", e)
            throw e
        }
    }

    // ------------------------------------------------------------------ //

    /**
     * Opens a JDBC connection by instantiating [com.clickhouse.jdbc.ClickHouseDriver]
     * directly through the plugin classloader.
     *
     * Using [java.sql.DriverManager.getConnection] is unreliable in IntelliJ plugins:
     * DriverManager performs a classloader-ancestry check that fails when the driver was
     * loaded by a child (plugin) classloader instead of the system classloader.
     * Bypassing DriverManager and calling [Driver.connect] directly avoids this.
     */
    private fun openConnection(conn: ConnectionConfig): Connection {
        val props = Properties().apply {
            setProperty("user", conn.user)
            setProperty("password", conn.password)
            // Disable ClickHouse-level LZ4 data compression.
            // Without this, the driver requests LZ4-compressed row data from the server and wraps
            // the response in Lz4InputStream. Over HTTPS (port 8443 / ClickHouse Cloud) the TLS
            // layer already handles transport security; if data-level compression is also active the
            // driver mis-reads the HTTP response body as an LZ4 block, hitting the
            // "Magic is not correct — expect [-126] but got [37]" IOException.
            setProperty("compress", "0")
            applySsl(conn.ssl)
        }

        val url = conn.jdbcUrl()
        thisLogger().info("BenchmarkRunner.openConnection: url=${conn.jdbcUrlSafe()} ssl=${conn.ssl.enabled}")

        val driverClass = try {
            Class.forName("com.clickhouse.jdbc.ClickHouseDriver", true, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            thisLogger().error(
                "ClickHouse JDBC driver not found in plugin classloader. " +
                "Plugin must be installed from the distribution zip, not a bare jar.", e
            )
            throw RuntimeException(
                "ClickHouse JDBC driver not found. " +
                "Please reinstall the plugin using Help > Install Plugin from Disk and select the .zip file.",
                e
            )
        }

        @Suppress("UNCHECKED_CAST")
        val driver = driverClass.getDeclaredConstructor().newInstance() as Driver
        return driver.connect(url, props)
            ?: throw SQLException("ClickHouseDriver.connect returned null for $url — driver may not support this URL")
    }

    /**
     * Maps [SslConfig] to the JDBC property keys understood by clickhouse-jdbc / clickhouse-client.
     * Properties are only set when the corresponding value is non-empty so the driver
     * keeps its own defaults for anything not explicitly configured.
     */
    private fun Properties.applySsl(ssl: SslConfig) {
        if (!ssl.enabled) return

        setProperty("ssl", "true")
        setProperty("sslmode", ssl.mode)
        thisLogger().info("SSL enabled: mode=${ssl.mode} auth=${ssl.auth.ifEmpty { "(none)" }}")

        if (ssl.auth.isNotEmpty())            setProperty("sslauth",                  ssl.auth)
        if (ssl.rootCertPath.isNotEmpty())    setProperty("sslrootcert",              ssl.rootCertPath)
        if (ssl.clientCertPath.isNotEmpty())  setProperty("sslcert",                  ssl.clientCertPath)
        if (ssl.clientKeyPath.isNotEmpty())   setProperty("sslkey",                   ssl.clientKeyPath)
        if (ssl.keystorePath.isNotEmpty()) {
            setProperty("ssl_keystore_path",     ssl.keystorePath)
            setProperty("ssl_keystore_password", ssl.keystorePassword)
        }
        if (ssl.truststorePath.isNotEmpty()) {
            setProperty("ssl_truststore_path",     ssl.truststorePath)
            setProperty("ssl_truststore_password", ssl.truststorePassword)
        }
    }

    private fun executeOnce(jdbc: Connection, query: String): IterationStats {
        val t0 = System.nanoTime()
        var rowsRead = 0L

        jdbc.createStatement().use { stmt ->
            if (stmt.execute(query)) {
                stmt.resultSet.use { rs -> while (rs.next()) rowsRead++ }
            } else {
                rowsRead = stmt.updateCount.coerceAtLeast(0).toLong()
            }
        }

        return IterationStats(
            elapsedMs = (System.nanoTime() - t0) / 1_000_000.0,
            rowsRead  = rowsRead,
            bytesRead = 0L
        )
    }

    // --- PasswordSafe helpers ---

    fun getPassword(): String = readCredential(CRED_KEY_CONNECTION)
    fun getSslKeystorePassword(): String = readCredential(CRED_KEY_KEYSTORE)
    fun getSslTruststorePassword(): String = readCredential(CRED_KEY_TRUSTSTORE)

    fun savePasswords(password: String, keystorePassword: String, truststorePassword: String) {
        writeCredential(CRED_KEY_CONNECTION, password)
        writeCredential(CRED_KEY_KEYSTORE, keystorePassword)
        writeCredential(CRED_KEY_TRUSTSTORE, truststorePassword)
    }

    private fun readCredential(key: String): String {
        val attrs = CredentialAttributes(generateServiceName(PLUGIN_SERVICE, key))
        return PasswordSafe.instance.get(attrs)?.getPasswordAsString() ?: ""
    }

    private fun writeCredential(key: String, password: String) {
        val attrs = CredentialAttributes(generateServiceName(PLUGIN_SERVICE, key))
        PasswordSafe.instance.set(attrs, Credentials("", password))
    }

    companion object {
        private const val PLUGIN_SERVICE  = "com.github.andrey5608.clickhouse.benchmark.idea.plugin"
        private const val CRED_KEY_CONNECTION  = "connection"
        private const val CRED_KEY_KEYSTORE    = "sslKeystore"
        private const val CRED_KEY_TRUSTSTORE  = "sslTruststore"

        fun getInstance(): BenchmarkRunner = service()
    }
}
