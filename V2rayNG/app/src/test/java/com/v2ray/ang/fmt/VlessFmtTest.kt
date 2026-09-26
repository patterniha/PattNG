package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VlessFmt, covering the dialMode share-link parameter.
 */
class VlessFmtTest {

    private fun createConfig(mode: String?): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Dial Mode"
            server = "example.com"
            serverPort = "443"
            password = "uuid"
            method = "none"
            network = "tcp"
            dialMode = mode
        }

    @Test
    fun test_toUri_includesDialModeQueryParameter() {
        val uri = VlessFmt.toUri(createConfig("code-1"))

        assertTrue("uri should carry dialMode: $uri", uri.contains("dialMode=code-1"))
    }

    @Test
    fun test_toUri_omitsDialModeWhenBlank() {
        assertFalse(VlessFmt.toUri(createConfig("")).contains("dialMode"))
        assertFalse(VlessFmt.toUri(createConfig(null)).contains("dialMode"))
    }

    @Test
    fun test_parse_readsDialModeQueryParameter() {
        val result = VlessFmt.parse("vless://uuid@example.com:443?encryption=none&type=tcp&dialMode=code-1#Dial%20Mode")

        assertNotNull(result)
        assertEquals("code-1", result?.dialMode)
        assertEquals("example.com", result?.server)
    }

    @Test
    fun test_parse_leavesDialModeNullWhenAbsent() {
        val result = VlessFmt.parse("vless://uuid@example.com:443?encryption=none&type=tcp#Plain")

        assertNotNull(result)
        assertNull(result?.dialMode)
    }

    @Test
    fun test_toUriAndParse_roundTripPreservesEchOutbound() {
        val json = """{"tag": "ech-out", "protocol": "freedom"}"""
        val config = createConfig(null).apply {
            echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query"
            echOutbound = json
        }

        val uri = VlessFmt.toUri(config)
        assertTrue("uri should carry echOutbound: $uri", uri.contains("echOutbound="))

        val reparsed = VlessFmt.parse("vless://$uri")

        assertNotNull(reparsed)
        assertEquals(json, reparsed?.echOutbound)
        assertEquals(config.echConfigList, reparsed?.echConfigList)
    }

    @Test
    fun test_echOutboundIsLeftOutWhenAbsent() {
        val result = VlessFmt.parse("vless://uuid@example.com:443?encryption=none&type=tcp#Plain")

        assertNotNull(result)
        assertNull(result?.echOutbound)
        assertFalse(VlessFmt.toUri(createConfig(null)).contains("echOutbound"))
    }

    @Test
    fun test_parseAndToUri_roundTripPreservesDialMode() {
        val regenerated = VlessFmt.toUri(createConfig("code-1"))

        val reparsed = VlessFmt.parse("vless://$regenerated")

        assertNotNull(reparsed)
        assertEquals("code-1", reparsed?.dialMode)
    }
}
