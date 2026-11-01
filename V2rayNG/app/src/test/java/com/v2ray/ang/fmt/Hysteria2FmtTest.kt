package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class Hysteria2FmtTest {

    @Test
    fun hysteria2WithoutPortUses443() {
        val profile = Hysteria2Fmt.parse("hysteria2://test@example.com/")

        assertEquals("example.com", profile.server)
        assertEquals("443", profile.serverPort)
    }

    @Test
    fun hy2WithoutPortUses443() {
        val profile = Hysteria2Fmt.parse("hy2://test@example.com/")

        assertEquals("example.com", profile.server)
        assertEquals("443", profile.serverPort)
    }

    @Test
    fun uriWithoutCredentialsUses443() {
        val profile = Hysteria2Fmt.parse("hysteria2://example.com/")

        assertEquals("example.com", profile.server)
        assertEquals("443", profile.serverPort)
    }

    @Test
    fun omittedPortPreservesTlsParameters() {
        val profile = Hysteria2Fmt.parse(
            "hy2://test@example.com/?sni=example.org&insecure=0&pinSHA256=test-pin#example"
        )

        assertEquals("test", profile.password)
        assertEquals("example", profile.remarks)
        assertEquals(AppConfig.TLS, profile.security)
        assertEquals("example.org", profile.sni)
        assertEquals(false, profile.insecure)
        assertEquals("test-pin", profile.pinnedCA256)
        assertEquals("443", profile.serverPort)
    }

    @Test
    fun ipv6WithoutPortUses443() {
        val profile = Hysteria2Fmt.parse("hysteria2://test@[2001:db8::1]/")

        assertEquals("2001:db8::1", profile.server)
        assertEquals("443", profile.serverPort)
    }

    @Test
    fun explicitPortsArePreserved() {
        for (scheme in listOf("hysteria2", "hy2")) {
            // Port range validation is separate from choosing a missing-port default.
            for (port in listOf(0, 443, 8443, 65535, 65536)) {
                val profile = Hysteria2Fmt.parse("$scheme://test@example.com:$port/")

                assertEquals("example.com", profile.server)
                assertEquals(port.toString(), profile.serverPort)
            }
        }
    }

    @Test
    fun nonnumericPortDoesNotAcquireAHost() {
        val profile = Hysteria2Fmt.parse("hysteria2://test@example.com:invalid/")

        assertEquals("", profile.server)
    }

    @Test
    fun defaultPortSurvivesUriRoundTrip() {
        val profile = Hysteria2Fmt.parse("hy2://test@example.com/?sni=example.org")
        val exported = "hysteria2://${Hysteria2Fmt.toUri(profile)}"
        val reparsed = Hysteria2Fmt.parse(exported)

        assertEquals(443, URI(exported).port)
        assertEquals("443", reparsed.serverPort)
        assertEquals(profile.server, reparsed.server)
        assertEquals(profile.password, reparsed.password)
        assertEquals(profile.sni, reparsed.sni)
    }

    // PattNG: the ECH and finalMask share-link parameters.

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
        assertTrue(uri.contains("ech="), "uri should carry ech: $uri")
        assertTrue(uri.contains("echOutbound="), "uri should carry echOutbound: $uri")

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

    @Test
    fun test_toUriAndParse_roundTripPreservesFinalMask() {
        // PattNG: parse reads fm as the other links do, so toUri writes it as well.
        val json = """{"udp": [{"type": "salamander", "settings": {"password": "fm-pass"}}]}"""
        val config = createConfig().apply { finalMask = json }

        val uri = Hysteria2Fmt.toUri(config)
        assertTrue(uri.contains("fm="), "uri should carry fm: $uri")

        assertEquals(json, Hysteria2Fmt.parse("hysteria2://$uri").finalMask)
        assertFalse(Hysteria2Fmt.toUri(createConfig()).contains("fm="))
    }
}
