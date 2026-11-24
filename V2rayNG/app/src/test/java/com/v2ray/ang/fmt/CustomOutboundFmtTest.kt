package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for CustomOutboundFmt: a custom outbound is stored as written, and goes into the configuration as written,
 * with what the app changed on its outbound.
 */
class CustomOutboundFmtTest {

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** [outbound] as the configuration has it before its custom outbound goes in, see EchOutbound.serialize. */
    private fun built(outbound: OutboundBean): JsonObject = json(JsonUtil.toJsonPretty(outbound)!!)

    @Test
    fun aCustomOutboundIsStoredAsWrittenTaggedAsTheProxy() {
        val (profile, raw) = CustomOutboundFmt.parse(VNEXT)
        assertEquals(EConfigType.CUSTOM_OUTBOUND, profile.configType)
        assertEquals("vless-vnext", profile.remarks)
        // Every key as written, vnext too, which the outbound model has no field for: without it the core refuses the
        // outbound, and the configuration with it.
        assertEquals(json(VNEXT).apply { addProperty("tag", AppConfig.TAG_PROXY) }, json(raw))
        // The tag first, where the outbound model writes it.
        assertEquals("tag", json(raw).keySet().first())
    }

    @Test
    fun aCustomOutboundTheAppLeavesAsItIsGoesInAsWritten() {
        val (_, raw) = CustomOutboundFmt.parse(FRAGMENT)
        val outbound = CustomOutboundFmt.toOutbound(raw)!!
        assertEquals(raw, outbound.customOutbound)
        // The outbound model leaves out domainStrategy, fragment, noises and three sockopt keys, and adds the tcp
        // network and happyEyeballs' tryDelayMs and maxConcurrentTry, which turn happy eyeballs on.
        assertNotEquals(json(raw), built(outbound))
        assertEquals(json(raw), CustomOutboundFmt.asWritten(raw, built(outbound)))
    }

    @Test
    fun whatTheAppChangesOnACustomOutboundGoesInWithWhatIsWritten() {
        val (_, raw) = CustomOutboundFmt.parse(XHTTP)
        val outbound = CustomOutboundFmt.toOutbound(raw)!!
        // As CoreConfigManager changes the first hop of a chain, and a latency test every outbound.
        outbound.tag = "proxy-chain-1"
        outbound.ensureSockopt().dialerProxy = "next-hop"
        outbound.targetStrategy = null
        val expected = json(raw).apply {
            addProperty("tag", "proxy-chain-1")
            remove("targetStrategy")
            getAsJsonObject("streamSettings").getAsJsonObject("sockopt").addProperty("dialerProxy", "next-hop")
        }
        assertEquals(expected, CustomOutboundFmt.asWritten(raw, built(outbound)))
    }

    @Test
    fun aCustomOutboundStoredBeforeGoesInAsBefore() {
        // What CustomOutboundFmt.parse stored before: the outbound as the outbound model reads and writes it, tagged as
        // the proxy.
        val before = JsonUtil.fromJson(XHTTP, OutboundBean::class.java)!!.apply { tag = AppConfig.TAG_PROXY }
        val raw = JsonUtil.toJsonPretty(before)!!
        val outbound = CustomOutboundFmt.toOutbound(raw)!!
        outbound.tag = "proxy-chain-1"
        outbound.ensureSockopt().dialerProxy = "next-hop"
        // As the configuration had it before: the outbound as the outbound model writes it.
        assertEquals(built(outbound), CustomOutboundFmt.asWritten(raw, built(outbound)))
    }

    private companion object {
        const val VNEXT = """
{
  "tag": "vless-vnext",
  "protocol": "vless",
  "settings": {
    "vnext": [{
      "address": "example.com",
      "port": 443,
      "users": [{"id": "27848739-7e62-4138-9fd3-098a63964b6b", "encryption": "none", "flow": "xtls-rprx-vision"}]
    }]
  },
  "streamSettings": {"network": "tcp", "security": "tls", "tlsSettings": {"serverName": "example.com", "fingerprint": "chrome"}}
}"""

        const val FRAGMENT = """
{
  "tag": "fragment",
  "protocol": "freedom",
  "settings": {
    "domainStrategy": "UseIPv4",
    "fragment": {"packets": "tlshello", "length": "100-200", "interval": "10-20"},
    "noises": [{"type": "rand", "packet": "10-20", "delay": "10-16"}]
  },
  "streamSettings": {
    "sockopt": {"tcpKeepAliveInterval": 15, "tcpKeepAliveCount": 3, "tcpHandshakeTimeout": 8000, "happyEyeballs": {"interleave": 2}}
  }
}"""

        const val XHTTP = """
{
  "tag": "vless-xhttp",
  "protocol": "vless",
  "settings": {"address": "example.com", "port": 443, "id": "27848739-7e62-4138-9fd3-098a63964b6b", "encryption": "none"},
  "streamSettings": {
    "network": "xhttp",
    "security": "tls",
    "tlsSettings": {"serverName": "example.com", "fingerprint": "chrome", "alpn": ["h2"], "tlsHandshakeTimeout": 8000},
    "xhttpSettings": {"path": "/x", "mode": "auto", "xPaddingBytes": "100-1000", "xmux": {"maxConcurrency": "16-32"}},
    "sockopt": {"tcpKeepAliveInterval": 15, "fallback": {"failedDialFallbackDialerProxy": "direct"}}
  },
  "targetStrategy": "ForceIPv4"
}"""
    }
}
