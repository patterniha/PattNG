package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for CoreOutboundBuilder.applyDialMode: dialMode must land in
 * streamSettings.sockopt without discarding the other sockopt options.
 */
class CoreOutboundBuilderTest {

    private fun createProfile(mode: String?): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply { dialMode = mode }

    @Test
    fun test_applyDialMode_setsSockoptDialMode() {
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean())

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        assertEquals("code-1", outbound.streamSettings?.sockopt?.dialMode)
        assertEquals(AppConfig.DEFAULT_NETWORK, outbound.streamSettings?.network)
    }

    @Test
    fun test_applyDialMode_keepsExistingSockoptOptions() {
        val outbound = OutboundBean(
            protocol = "vless",
            streamSettings = OutboundBean.StreamSettingsBean(
                sockopt = OutboundBean.StreamSettingsBean.SockoptBean(
                    dialerProxy = "hop-1",
                    domainStrategy = "UseIP"
                )
            )
        )

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        val sockopt = outbound.streamSettings?.sockopt
        assertEquals("code-1", sockopt?.dialMode)
        assertEquals("hop-1", sockopt?.dialerProxy)
        assertEquals("UseIP", sockopt?.domainStrategy)
    }

    @Test
    fun test_applyDialMode_ignoresBlankDialMode() {
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean())

        CoreOutboundBuilder.applyDialMode(outbound, createProfile(" "))
        CoreOutboundBuilder.applyDialMode(outbound, createProfile(null))

        assertNull(outbound.streamSettings?.sockopt)
    }

    @Test
    fun test_applyDialMode_wireguardWithoutStreamSettings_getsSockoptWithoutNetwork() {
        // wireguard outbounds are created without streamSettings; Xray still dials their
        // endpoint through the system dialer, so a sockopt-only streamSettings is added
        val outbound = OutboundBean(protocol = "wireguard")

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        assertNotNull(outbound.streamSettings)
        assertNull(outbound.streamSettings?.network)
        assertEquals("code-1", outbound.streamSettings?.sockopt?.dialMode)
    }

    /** A profile whose sni and finalMask keep populateTlsSettings away from Utils and MMKV. */
    private fun echProfile(security: String, echOutbound: String): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            this.security = security
            sni = "example.com"
            finalMask = "{}"
            echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query"
            this.echOutbound = echOutbound
        }

    @Test
    fun test_populateTlsSettings_attachesTheEchOutboundForTls() {
        val streamSettings = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, """{"tag": "ech-out", "protocol": "freedom"}"""), null)

        assertEquals("ech-out", streamSettings.tlsSettings?.echOutbound?.get("tag")?.asString)
    }

    @Test
    fun test_populateTlsSettings_attachesNoEchOutboundForRealityOrAnInvalidOne() {
        val reality = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(reality, echProfile(AppConfig.REALITY, """{"tag": "ech-out"}"""), null)
        assertNotNull(reality.realitySettings)
        assertNull(reality.realitySettings?.echOutbound)

        val invalid = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(invalid, echProfile(AppConfig.TLS, """{"tag": "proxy"}"""), null)
        assertNotNull(invalid.tlsSettings)
        assertNull(invalid.tlsSettings?.echOutbound)
    }

    @Test
    fun test_applyTargetStrategy_setsOutboundTargetStrategy() {
        val outbound = OutboundBean(protocol = "vless")

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = "ForceIPv6v4" })

        assertEquals("ForceIPv6v4", outbound.targetStrategy)
    }

    @Test
    fun test_applyTargetStrategy_leavesTheDefaultOut() {
        val outbound = OutboundBean(protocol = "vless", targetStrategy = "UseIP")

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS })
        assertNull(outbound.targetStrategy)

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = " " })
        assertNull(outbound.targetStrategy)

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS))
        assertNull(outbound.targetStrategy)
    }
}
