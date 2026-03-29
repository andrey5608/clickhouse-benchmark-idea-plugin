package com.github.andrey5608.clickhousebenchmarkideaplugin.services

import com.github.andrey5608.clickhousebenchmarkideaplugin.model.BenchmarkResult
import com.github.andrey5608.clickhousebenchmarkideaplugin.model.IterationStats
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.sql.Connection
import java.sql.DriverManager
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
        var password: String = "",
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
        var sslKeystorePassword: String = "",
        var sslTruststorePath: String = "",
        var sslTruststorePassword: String = ""
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun defaultConnection() = ConnectionConfig(
        host     = myState.host,
        port     = myState.port,
        database = myState.database,
        user     = myState.user,
        password = myState.password,
        ssl      = SslConfig(
            enabled            = myState.sslEnabled,
            mode               = myState.sslMode,
            auth               = myState.sslAuth,
            rootCertPath       = myState.sslRootCertPath,
            clientCertPath     = myState.sslClientCertPath,
            clientKeyPath      = myState.sslClientKeyPath,
            keystorePath       = myState.sslKeystorePath,
            keystorePassword   = myState.sslKeystorePassword,
            truststorePath     = myState.sslTruststorePath,
            truststorePassword = myState.sslTruststorePassword
        )
    )

    fun run(
        query: String,
        conn: ConnectionConfig = defaultConnection(),
        connectionName: String = "${conn.host}:${conn.port}",
        iterations: Int = myState.iterations,
        warmup: Int = myState.warmup
    ): BenchmarkResult {
        ensureDriver()
        thisLogger().info(
            "BenchmarkRunner: warmup=$warmup iterations=$iterations " +
            "conn=${conn.host}:${conn.port} ssl=${conn.ssl.enabled}"
        )

        openConnection(conn).use { jdbc ->
            repeat(warmup) { executeOnce(jdbc, query) }
            val stats = List(iterations) { executeOnce(jdbc, query) }
            return BenchmarkResult(
                query          = query,
                connectionName = connectionName,
                iterations     = stats,
                warmupCount    = warmup
            )
        }
    }

    fun testConnection(conn: ConnectionConfig = defaultConnection()) {
        ensureDriver()
        openConnection(conn).close()
    }

    // ------------------------------------------------------------------ //

    private fun openConnection(conn: ConnectionConfig): Connection {
        val props = Properties().apply {
            setProperty("user", conn.user)
            setProperty("password", conn.password)
            // Force native TCP binary protocol (ClickHouseProtocol.TCP)
            setProperty("protocol", "TCP")
            applySsl(conn.ssl)
        }
        return DriverManager.getConnection(conn.jdbcUrl(), props)
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

    private fun ensureDriver() {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
    }

    companion object {
        fun getInstance(): BenchmarkRunner = service()
    }
}
