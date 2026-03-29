package com.github.andrey5608.clickhousebenchmarkideaplugin.services

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
    fun jdbcUrl() = "jdbc:clickhouse://$host:$port/$database"
}
