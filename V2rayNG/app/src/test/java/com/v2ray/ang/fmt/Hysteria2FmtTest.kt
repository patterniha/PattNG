package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Hysteria2Fmt, covering the ECH share-link parameters.
 */
class Hysteria2FmtTest {

    private fun createConfig(): ProfileItem =
        ProfileItem.create(EConfigType.HYSTERIA2).apply {
            remarks = "Hysteria2"
            server = "example.com"
            serverPort = "443"
            password = "secret"
            security = "tls"
            sni = "example.com"
        }

    @Test
    fun test_toUriAndParse_roundTripPreservesEch() {
        val json = """{"tag": "ech-out", "protocol": "freedom"}"""
        val config = createConfig().apply {
            echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query"
            echOutbound = json
        }

        val uri = Hysteria2Fmt.toUri(config)
        assertTrue("uri should carry ech: $uri", uri.contains("ech="))
        assertTrue("uri should carry echOutbound: $uri", uri.contains("echOutbound="))

        val reparsed = Hysteria2Fmt.parse("hysteria2://$uri")

        assertEquals(config.echConfigList, reparsed.echConfigList)
        assertEquals(json, reparsed.echOutbound)
    }

    @Test
    fun test_toUri_omitsEchWhenBlank() {
        val uri = Hysteria2Fmt.toUri(createConfig())

        assertFalse(uri.contains("ech="))
        assertFalse(uri.contains("echOutbound="))
    }
}
