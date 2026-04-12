package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.sql.psi.SqlPsiFacade

/**
 * Ultimate-only. Loaded via clickhouse-benchmark-database.xml when com.intellij.database is present.
 * This is the ONLY file that imports com.intellij.database.*.
 */
@Service(Service.Level.APP)
class DatabasePluginDataSourceProvider : DataSourceProvider {

    override fun supportsIdeDatasources() = true

    override fun getConnections(project: Project): List<DataSourceProvider.NamedConnection> =
        LocalDataSourceManager.getInstance(project)
            .dataSources
            .filter { it.isClickHouse() }
            .map { it.toNamedConnection() }

    /**
     * Returns the single ClickHouse connection that the given SQL file is attached to, or null if
     * the file has no datasource, multiple datasources, or no ClickHouse datasource.
     *
     * This is used to skip the connection-chooser popup when the user runs a benchmark from a
     * Query Console that is already bound to a specific connection.
     */
    override fun getContextConnection(project: Project, psiFile: PsiFile): DataSourceProvider.NamedConnection? {
        val dataSources = SqlPsiFacade.getInstance(project).getDataSources(psiFile)
        val clickHouseSources = dataSources
            .mapNotNull { it.delegate as? LocalDataSource }
            .filter { it.isClickHouse() }
        thisLogger().info(
            "getContextConnection: file='${psiFile.name}' " +
                    "total_sources=${dataSources.size} clickhouse_sources=${clickHouseSources.size}"
        )
        return if (clickHouseSources.size == 1) clickHouseSources.first().toNamedConnection() else null
    }

    private fun LocalDataSource.isClickHouse(): Boolean {
        val url = url ?: return false
        return url.contains("clickhouse", ignoreCase = true)
                || driverClass?.contains("clickhouse", ignoreCase = true) == true
    }

    private fun LocalDataSource.toNamedConnection(): DataSourceProvider.NamedConnection {
        val allProps = resolveAllJdbcProps()
        // If the datasource has no SSL keys at all, fall back to the plugin's SSL settings so that
        // users who configure ssl/sslmode in Settings > Tools > ClickHouse Benchmark don't have
        // to duplicate that configuration in every IDE datasource's Advanced tab.
        val ssl = if (allProps.keys.any { it in SSL_PROP_KEYS }) {
            buildSslConfig(allProps)
        } else {
            service<BenchmarkRunner>().defaultConnection().ssl
        }
        return DataSourceProvider.NamedConnection(
            name = name,
            config = ConnectionConfig(
                host = extractHost(url ?: ""),
                port = extractPort(url ?: ""),
                database = extractDatabase(url ?: ""),
                user = username,
                password = resolvePassword(),
                ssl = ssl
            )
        )
    }

    /**
     * Resolves the JDBC password for this datasource using a two-step fallback:
     *
     *  1. IntelliJ's PasswordSafe — where DataGrip/Database Tools stores the password that the
     *     user entered in the IDE's datasource dialog ("General" tab → Password field).
     *     Uses [generateServiceName] with the "DataGrip" subsystem and the datasource's unique ID,
     *     which is the key format used by IntelliJ's database plugin.
     *  2. Plugin settings password (Settings > Tools > ClickHouse Benchmark) — fallback for the
     *     case where the IDE credential store returns nothing (different storage type, blank
     *     password, or key format mismatch).
     *
     * A warning is logged when no password is found so the user can configure it.
     */
    private fun LocalDataSource.resolvePassword(): String {
        // Step 1: try IntelliJ's PasswordSafe (where the IDE stores the datasource password).
        val attrs = CredentialAttributes(generateServiceName("DataGrip", uniqueId), username)
        val idePassword = PasswordSafe.instance.get(attrs)?.getPasswordAsString()
        if (!idePassword.isNullOrEmpty()) {
            thisLogger().info("resolvePassword '${name}': using IDE credential store (DataGrip)")
            return idePassword
        }

        // Step 2: fall back to the password configured in plugin settings.
        val pluginPassword = service<BenchmarkRunner>().getPassword()
        if (pluginPassword.isNotEmpty()) {
            thisLogger().info("resolvePassword '${name}': using plugin settings password")
            return pluginPassword
        }

        thisLogger().warn(
            "resolvePassword '${name}': no password found. " +
                    "Set it in Settings > Tools > ClickHouse Benchmark, or re-enter it in the IDE datasource dialog."
        )
        return ""
    }

    /**
     * Collects SSL (and other) JDBC properties from four sources, in increasing priority:
     *
     *  1. URL query string  — e.g. jdbc:clickhouse://host:9000/db?ssl=true&sslmode=strict
     *  2. IDE SSL config panel (DataSourceSslConfiguration via getSslCfg()) — the "SSL" tab
     *     in the DataSource dialog; translated to JDBC property names understood by clickhouse-jdbc.
     *  3. Advanced tab JDBC connection properties (LocalDataSource.getConnectionProperties()) —
     *     the key=value JDBC properties set by the user in the "Advanced" tab of the datasource
     *     dialog; these are passed directly to the JDBC driver and are NOT part of the URL.
     *  4. IntelliJ-internal additional properties (LocalDataSource.getAdditionalPropertiesMap) —
     *     highest priority so any explicit overrides win. Note: in practice these contain
     *     IntelliJ cloud/Kubernetes metadata (e.g. com.intellij.clouds.*) and rarely carry SSL
     *     settings, but are kept for completeness.
     */
    private fun LocalDataSource.resolveAllJdbcProps(): Map<String, String> {
        val fromUrl = parseQueryParams(url ?: "")
        val fromSslCfg = sslCfgToJdbcProps()
        // JDBC connection properties set in the "Advanced" tab of the datasource dialog.
        // getConnectionProperties() returns java.util.Properties (never null).
        val fromConnProps: Map<String, String> = connectionProperties
            .entries.associate { (k, v) -> k.toString() to v.toString() }
        val fromAdvanced: Map<String, String> = LocalDataSource.getAdditionalPropertiesMap(this)
        val merged = fromUrl + fromSslCfg + fromConnProps + fromAdvanced
        thisLogger().info(
            "resolveAllJdbcProps '${name}': " +
                    "url_params=${fromUrl.keys} ssl_cfg=${fromSslCfg.keys} " +
                    "conn_props=${fromConnProps.keys} advanced_params=${fromAdvanced.keys}"
        )
        return merged
    }

    /**
     * Translates the IDE's DataSourceSslConfiguration (the "SSL" tab) to JDBC property keys
     * understood by clickhouse-jdbc. Returns an empty map when SSL is not configured via that tab.
     */
    private fun LocalDataSource.sslCfgToJdbcProps(): Map<String, String> {
        val cfg = sslCfg?.takeIf { !it.isEmpty } ?: return emptyMap()
        val props = mutableMapOf<String, String>()
        props["ssl"] = cfg.myEnabled.toString()
        if (cfg.myMode != null) {
            // JdbcSettings.SslMode (REQUIRE / VERIFY_CA / VERIFY_FULL) → clickhouse-jdbc sslmode
            props["sslmode"] = "strict"
        }
        if (!cfg.myCaCertPath.isNullOrEmpty()) props["sslrootcert"] = cfg.myCaCertPath
        if (!cfg.myClientCertPath.isNullOrEmpty()) props["sslcert"] = cfg.myClientCertPath
        if (!cfg.myClientKeyPath.isNullOrEmpty()) props["sslkey"] = cfg.myClientKeyPath
        return props
    }

    private fun buildSslConfig(props: Map<String, String>): SslConfig {
        fun get(vararg keys: String) =
            keys.firstNotNullOfOrNull { props[it]?.takeIf(String::isNotEmpty) } ?: ""

        val enabled = get("ssl").equals("true", ignoreCase = true)
        val mode = get("sslmode")

        return SslConfig(
            enabled = enabled || (mode.isNotEmpty() && mode != "none"),
            mode = mode.ifEmpty { if (enabled) "strict" else "none" },
            auth = get("sslauth"),
            rootCertPath = get("sslrootcert", "ssl_root_certificate"),
            clientCertPath = get("sslcert", "ssl_client_certificate"),
            clientKeyPath = get("sslkey", "ssl_client_key"),
            keystorePath = get("ssl_keystore_path"),
            keystorePassword = get("ssl_keystore_password"),
            truststorePath = get("ssl_truststore_path"),
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

    companion object {
        /** All JDBC property keys that carry SSL configuration for clickhouse-jdbc. */
        private val SSL_PROP_KEYS = setOf(
            "ssl", "sslmode", "sslauth",
            "sslrootcert", "ssl_root_certificate",
            "sslcert", "ssl_client_certificate",
            "sslkey", "ssl_client_key",
            "ssl_keystore_path", "ssl_keystore_password",
            "ssl_truststore_path", "ssl_truststore_password"
        )
    }
}
