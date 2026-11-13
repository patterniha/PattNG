package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

        assertTrue(uri.contains("dialMode=code-1"), "uri should carry dialMode: $uri")
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
        assertTrue(uri.contains("echOutbound="), "uri should carry echOutbound: $uri")

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

    // PattNG: "cp" carries curvePreferences as the profile holds them, the comma percent-encoded, and "esr=1"
    // enableSessionResumption when it is on; neither is written when unset.
    @Test
    fun test_toUriAndParse_carryCurvePreferencesAndSessionResumption() {
        val config = createConfig(null).apply {
            security = "tls"
            fingerPrint = "unsafe"
            curvePreferences = "X25519MLKEM768,X25519"
            enableSessionResumption = true
        }

        val uri = VlessFmt.toUri(config)
        assertTrue(uri.contains("cp=X25519MLKEM768%2CX25519"), uri)
        assertTrue(uri.contains("esr=1"), uri)
        val reparsed = VlessFmt.parse("vless://$uri")

        assertNotNull(reparsed)
        assertEquals("X25519MLKEM768,X25519", reparsed?.curvePreferences)
        assertEquals(true, reparsed?.enableSessionResumption)

        config.curvePreferences = " "
        config.enableSessionResumption = false
        val plain = VlessFmt.toUri(config)
        val keys = plain.substringAfter('?').substringBefore('#').split('&').map { it.substringBefore('=') }
        assertFalse("cp" in keys, plain)
        assertFalse("esr" in keys, plain)
        val reparsedPlain = VlessFmt.parse("vless://$plain")
        assertNull(reparsedPlain?.curvePreferences)
        assertEquals(false, reparsedPlain?.enableSessionResumption)
    }

    // PattNG: tcpUserTimeout, a sockopt option like dialMode, carried where the outbound connects over TCP
    // (TcpUserTimeoutRule), and read back as a whole number above 0.
    @Test
    fun test_toUriAndParse_carryTcpUserTimeoutWhereItCounts() {
        val config = createConfig("code-1").apply { tcpUserTimeout = 10000 }

        val uri = VlessFmt.toUri(config)
        assertTrue(uri.contains("tut=10000"), uri)
        val reparsed = VlessFmt.parse("vless://$uri")
        assertEquals(10000, reparsed?.tcpUserTimeout)
        assertEquals("code-1", reparsed?.dialMode)

        // Over mKCP the connection is UDP: the profile keeps its value, no link carries it.
        config.network = "kcp"
        val kcpUri = VlessFmt.toUri(config)
        assertFalse("tut" in kcpUri.substringAfter('?').substringBefore('#').split('&').map { it.substringBefore('=') }, kcpUri)

        assertNull(VlessFmt.parse("vless://uuid@example.com:443?encryption=none&type=tcp#Plain")?.tcpUserTimeout)
        for (none in listOf("0", "-1", "2s", "")) {
            val parsed = VlessFmt.parse("vless://uuid@example.com:443?encryption=none&type=tcp&tut=$none#None")
            assertNotNull(parsed, none)
            assertNull(parsed?.tcpUserTimeout, none)
        }
    }
}
