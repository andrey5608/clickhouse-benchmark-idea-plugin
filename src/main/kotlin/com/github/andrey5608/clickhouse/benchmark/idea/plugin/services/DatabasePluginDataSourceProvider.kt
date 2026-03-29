package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Ultimate-only. Loaded via clickhouse-benchmark-database.xml when com.intellij.database is present.
 * This is the ONLY file that imports com.intellij.database.*.
 */
@Service(Service.Level.APP)
class DatabasePluginDataSourceProvider : DataSourceProvider {

    override fun getConnections(project: Project): List<DataSourceProvider.NamedConnection> =
        LocalDataSourceManager.getInstance(project)
            .dataSources
            .filter { it.isClickHouse() }
            .map { it.toNamedConnection() }

    private fun LocalDataSource.isClickHouse(): Boolean {
        val url = url ?: return false
        return url.contains("clickhouse", ignoreCase = true)
            || driverClass?.contains("clickhouse", ignoreCase = true) == true
    }

    private fun LocalDataSource.toNamedConnection(): DataSourceProvider.NamedConnection {
        val allProps = resolveAllJdbcProps()
        return DataSourceProvider.NamedConnection(
            name   = name,
            config = ConnectionConfig(
                host     = extractHost(url ?: ""),
                port     = extractPort(url ?: ""),
                database = extractDatabase(url ?: ""),
                user     = username,
                password = "",    // stored in IDE credential store; user can set it in plugin settings
                ssl      = buildSslConfig(allProps)
            )
        )
    }

    /**
     * Collects SSL (and other) JDBC properties from two sources, in increasing priority:
     *
     *  1. URL query string  — e.g. jdbc:clickhouse://host:9000/db?ssl=true&sslmode=strict
     *  2. additionalJdbcProperties — the "Advanced" tab in the DataSource dialog stores
     *     extra key=value pairs here (ssl, sslmode, sslauth, ssl_keystore_path, …).
     *     Accessed via reflection so a change in IntelliJ internals causes a warning,
     *     not a plugin crash.
     */
    private fun LocalDataSource.resolveAllJdbcProps(): Map<String, String> {
        val fromUrl = parseQueryParams(url ?: "")

        val fromAdvanced: Map<String, String> = runCatching {
            @Suppress("UNCHECKED_CAST")
            (javaClass.getMethod("getAdditionalJdbcProperties").invoke(this) as? Map<*, *>)
                ?.entries
                ?.associate { (k, v) -> k.toString() to v.toString() }
                ?: emptyMap()
        }.onFailure {
            thisLogger().debug("additionalJdbcProperties not available for '${name}': ${it.message}")
        }.getOrDefault(emptyMap())

        return fromUrl + fromAdvanced   // additionalJdbcProperties wins on conflict
    }

    private fun buildSslConfig(props: Map<String, String>): SslConfig {
        fun get(vararg keys: String) =
            keys.firstNotNullOfOrNull { props[it]?.takeIf(String::isNotEmpty) } ?: ""

        val enabled = get("ssl").equals("true", ignoreCase = true)
        val mode    = get("sslmode")

        return SslConfig(
            enabled            = enabled || (mode.isNotEmpty() && mode != "none"),
            mode               = mode.ifEmpty { if (enabled) "strict" else "none" },
            auth               = get("sslauth"),
            rootCertPath       = get("sslrootcert",          "ssl_root_certificate"),
            clientCertPath     = get("sslcert",              "ssl_client_certificate"),
            clientKeyPath      = get("sslkey",               "ssl_client_key"),
            keystorePath       = get("ssl_keystore_path"),
            keystorePassword   = get("ssl_keystore_password"),
            truststorePath     = get("ssl_truststore_path"),
            truststorePassword = get("ssl_truststore_password")
        )
    }

    // ------------------------------------------------------------------ //
    // URL helpers — used only for UI labels; actual JDBC connection uses jdbcUrl()

    private fun parseQueryParams(url: String): Map<String, String> {
        val qs = url.substringAfter("?", "").takeIf(String::isNotEmpty) ?: return emptyMap()
        return qs.split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
    }

    private fun extractHost(url: String): String =
        Regex("""://([^/:?]+)""").find(url)?.groupValues?.get(1) ?: "localhost"

    private fun extractPort(url: String): Int =
        Regex("""://[^/:]+:(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 9000

    private fun extractDatabase(url: String): String =
        Regex("""://[^/]+/([^?]+)""").find(url)?.groupValues?.get(1)?.ifEmpty { "default" } ?: "default"
}
