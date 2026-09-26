package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for EchOutbound: the checks made when a profile is saved, the tags the ECH outbounds get
 * in a configuration, and how they are appended to it.
 */
class EchOutboundTest {

    private fun profile(echOutbound: String?, echConfigList: String? = ECH_CONFIG_LIST): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            this.echConfigList = echConfigList
            this.echOutbound = echOutbound
        }

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** A TLS proxy outbound as CoreOutboundBuilder builds it, carrying [echOutbound]. */
    private fun tlsOutbound(echOutbound: String?): OutboundBean =
        OutboundBean(
            protocol = "vless",
            streamSettings = OutboundBean.StreamSettingsBean(
                security = "tls",
                tlsSettings = OutboundBean.StreamSettingsBean.TlsSettingsBean(
                    echConfigList = ECH_CONFIG_LIST,
                    echOutbound = echOutbound?.let { json(it) },
                ),
            ),
        )

    private fun dialerProxyOf(outbound: OutboundBean): String? =
        outbound.streamSettings?.tlsSettings?.echSockopt?.dialerProxy

    /** A generated configuration with the proxy and direct outbounds and, optionally, one tagged [extraTag]. */
    private fun config(extraTag: String? = null): String {
        val extra = extraTag?.let { """, {"tag": "$it", "protocol": "dns"}""" }.orEmpty()
        return """{"outbounds": [{"tag": "proxy", "protocol": "vless"}, {"tag": "direct", "protocol": "freedom"}$extra]}"""
    }

    private fun outboundsOf(result: EchOutbound.AppendResult): JsonArray =
        JsonParser.parseString((result as EchOutbound.AppendResult.Done).content).asJsonObject.getAsJsonArray("outbounds")

    @Test
    fun validate_acceptsAnEmptyOrValidEchOutbound() {
        assertNull(EchOutbound.validate(profile(null)))
        assertNull(EchOutbound.validate(profile("  ")))
        assertNull(EchOutbound.validate(profile("""{"tag": "ech-out", "protocol": "freedom"}""")))
        assertNull(EchOutbound.validate(profile("""{"tag": "ech-proxy"}""")))
        assertNull(EchOutbound.validate(profile("""{"tag": "Proxy"}""")))
    }

    @Test
    fun validate_rejectsWhatIsNotAJsonObject() {
        for (json in listOf("freedom", "[{\"tag\": \"ech-out\"}]", "{\"tag\": \"ech-out\"", "{\"tag\": \"ech-out\"} x")) {
            assertEquals(json, EchOutbound.Error.INVALID_JSON, EchOutbound.validate(profile(json)))
        }
    }

    @Test
    fun validate_needsAnEchConfigList() {
        assertEquals(
            EchOutbound.Error.NEEDS_ECH_CONFIG_LIST,
            EchOutbound.validate(profile("""{"tag": "ech-out"}""", echConfigList = "")),
        )
    }

    @Test
    fun validate_rejectsAMissingOrTakenTag() {
        val jsons = listOf(
            """{"protocol": "freedom"}""",
            """{"tag": ""}""",
            """{"tag": " "}""",
            """{"tag": 1}""",
            """{"tag": "direct"}""",
            """{"tag": "block"}""",
            """{"tag": "proxy"}""",
            """{"tag": "proxy-ech"}""",
        )
        for (json in jsons) {
            assertEquals(json, EchOutbound.Error.INVALID_TAG, EchOutbound.validate(profile(json)))
        }
    }

    @Test
    fun link_sharesAnEchOutboundAndNumbersADifferentOneUnderTheSameTag() {
        val shared = """{"tag": "ech", "protocol": "freedom"}"""
        val outbounds = listOf(
            tlsOutbound(shared),
            tlsOutbound("""{"tag": "ech", "protocol": "blackhole"}"""),
            tlsOutbound(shared),
            tlsOutbound(null),
        )

        val echOutbounds = EchOutbound.link(outbounds)

        assertEquals(listOf("ech", "ech-2", "ech", null), outbounds.map { dialerProxyOf(it) })
        assertEquals(listOf("ech", "ech-2"), echOutbounds.map { EchOutbound.tagOf(it) })
        assertEquals("blackhole", echOutbounds[1].get("protocol").asString)
    }

    @Test
    fun link_numbersTheCopyThatIsAppendedAndNotTheProfilesOwnJson() {
        val second = tlsOutbound("""{"tag": "ech", "protocol": "blackhole"}""")

        EchOutbound.link(listOf(tlsOutbound("""{"tag": "ech", "protocol": "freedom"}"""), second))

        assertEquals("ech", EchOutbound.tagOf(second.streamSettings!!.tlsSettings!!.echOutbound!!))
    }

    @Test
    fun link_leavesTheAttachedEchOutboundOutOfTheSerializedOutbound() {
        val outbound = tlsOutbound("""{"tag": "ech", "protocol": "freedom"}""")
        EchOutbound.link(listOf(outbound))

        val tlsSettings = json(JsonUtil.toJsonPretty(outbound)!!).getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")

        assertNull(tlsSettings.get("echOutbound"))
        assertEquals("ech", tlsSettings.getAsJsonObject("echSockopt").get("dialerProxy").asString)
    }

    @Test
    fun appendTo_addsTheEchOutboundsLastAsWritten() {
        // tcpKeepAliveIdle is not in any bean: the outbound has to reach the configuration as written.
        val echOutbound = json("""{"tag": "ech-out", "protocol": "freedom", "streamSettings": {"sockopt": {"tcpKeepAliveIdle": 100}}}""")

        val outbounds = outboundsOf(EchOutbound.appendTo(config(), listOf(echOutbound)))

        assertEquals(3, outbounds.size())
        assertEquals(echOutbound, outbounds.last())
    }

    @Test
    fun appendTo_leavesAConfigurationWithoutEchOutboundsAlone() {
        val content = config()

        assertEquals(EchOutbound.AppendResult.Done(content), EchOutbound.appendTo(content, emptyList()))
    }

    @Test
    fun appendTo_reportsATagThatTheConfigurationAlreadyHas() {
        val result = EchOutbound.appendTo(config(extraTag = "dns-out"), listOf(json("""{"tag": "dns-out", "protocol": "freedom"}""")))

        assertEquals(EchOutbound.AppendResult.TagConflict("dns-out"), result)
    }

    @Test
    fun linkAndAppend_keepEachEchOutboundUnderItsOwnTag() {
        val outbounds = listOf(
            tlsOutbound("""{"tag": "ech", "protocol": "freedom"}"""),
            tlsOutbound("""{"tag": "ech", "protocol": "blackhole"}"""),
        )

        val appended = outboundsOf(EchOutbound.appendTo(config(), EchOutbound.link(outbounds)))

        assertEquals(listOf("proxy", "direct", "ech", "ech-2"), appended.map { it.asJsonObject.get("tag").asString })
    }

    private companion object {
        const val ECH_CONFIG_LIST = "cloudflare-ech.com+https://1.1.1.1/dns-query"
    }
}
