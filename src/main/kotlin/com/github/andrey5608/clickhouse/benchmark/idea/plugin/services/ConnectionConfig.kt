package com.github.andrey5608.clickhouse.benchmark.idea.plugin.services

data class SslConfig(
    val enabled: Boolean = false,
    val mode: String = "strict",             // none | strict | certificate
    val auth: String = "",                   // sslauth — e.g. "certificate"
    val rootCertPath: String = "",           // CA certificate (PEM)
    val clientCertPath: String = "",         // client certificate (PEM)
    val clientKeyPath: String = "",          // client private key (PEM)
    val keystorePath: String = "",           // Java keystore (.jks / .p12)
    val keystorePassword: String = "",
    val truststorePath: String = "",         // Java truststore (.jks / .p12)
    val truststorePassword: String = ""
)

data class ConnectionConfig(
    val host: String = "localhost",
    val port: Int = 9000,
    val database: String = "default",
    val user: String = "default",
    val password: String = "",
    val ssl: SslConfig = SslConfig()
) {
    fun jdbcUrl(): String {
        val params = buildList {
            add("user=$user")
            add("password=$password")
            if (ssl.enabled) add("ssl=true")
        }
        return "jdbc:clickhouse://$host:$port/$database?${params.joinToString("&")}"
    }

    /** Same as [jdbcUrl] but with the password replaced by `*******` — safe to write to logs. */
    fun jdbcUrlSafe(): String {
        val params = buildList {
            add("user=$user")
            add("password=*******")
            if (ssl.enabled) add("ssl=true")
        }
        return "jdbc:clickhouse://$host:$port/$database?${params.joinToString("&")}"
    }
}
