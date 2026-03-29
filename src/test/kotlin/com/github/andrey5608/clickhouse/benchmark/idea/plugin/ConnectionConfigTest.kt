package com.github.andrey5608.clickhouse.benchmark.idea.plugin

import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.ConnectionConfig
import com.github.andrey5608.clickhouse.benchmark.idea.plugin.services.SslConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {

    @Test
    fun `jdbcUrl includes ssl=true when SSL is enabled`() {
        val config = ConnectionConfig(
            host     = "test.eu-central-1.aws.clickhouse.cloud",
            port     = 8443,
            database = "default",
            user     = "default",
            password = "secret",
            ssl      = SslConfig(enabled = true)
        )

        val url = config.jdbcUrl()

        assertEquals(
            "jdbc:clickhouse://test.eu-central-1.aws.clickhouse.cloud:8443/default" +
            "?user=default&password=secret&ssl=true",
            url
        )
        assertTrue("URL must contain ssl=true", url.contains("ssl=true"))
    }

    @Test
    fun `jdbcUrl does not include ssl param when SSL is disabled`() {
        val config = ConnectionConfig(
            host     = "localhost",
            port     = 9000,
            database = "default",
            user     = "default",
            password = "",
            ssl      = SslConfig(enabled = false)
        )

        val url = config.jdbcUrl()

        assertEquals(
            "jdbc:clickhouse://localhost:9000/default?user=default&password=",
            url
        )
        assertFalse("URL must not contain ssl param when SSL is disabled", url.contains("ssl="))
    }

    @Test
    fun `jdbcUrlSafe masks the password`() {
        val config = ConnectionConfig(
            host     = "dcdccrd07q.eu-central-1.aws.clickhouse.cloud",
            port     = 8443,
            database = "default",
            user     = "default",
            password = "supersecret",
            ssl      = SslConfig(enabled = true)
        )

        val url = config.jdbcUrlSafe()

        assertEquals(
            "jdbc:clickhouse://dcdccrd07q.eu-central-1.aws.clickhouse.cloud:8443/default" +
            "?user=default&password=*******&ssl=true",
            url
        )
        assertFalse("Safe URL must not contain the real password", url.contains("supersecret"))
    }
}
