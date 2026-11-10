package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for CoreOutboundBuilder.applyDialMode: dialMode must land in
 * streamSettings.sockopt without discarding the other sockopt options.
 */
class CoreOutboundBuilderTest {

    private fun createProfile(mode: String?): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply { dialMode = mode }

    @Test
    fun aMuxConcurrencyFieldThatHoldsNoNumberFallsBackToTheDefault() {
        assertEquals(16, CoreOutboundBuilder.muxConcurrency("16", 8))
        assertEquals(16, CoreOutboundBuilder.muxConcurrency(" 16 ", 8))
        assertEquals(-1, CoreOutboundBuilder.muxConcurrency("-1", 8))
        // Blank, whitespace, words, a decimal and an overflow left the outbound unbuilt before.
        for (text in listOf(null, "", "  ", "eight", "1.5", "99999999999")) {
            assertEquals(8, CoreOutboundBuilder.muxConcurrency(text, 8), text.toString())
        }
    }

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

    /** A profile whose sni keeps populateTlsSettings away from Utils. */
    private fun echProfile(security: String, echOutbound: String): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            this.security = security
            sni = "example.com"
            echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query"
            this.echOutbound = echOutbound
        }

    @Test
    fun test_populateTlsSettings_addsNoFinalMaskOfItsOwn() {
        // Fragmenting is the profile's own finalMask; no global setting adds one to a TLS or REALITY outbound.
        for (security in listOf(AppConfig.TLS, AppConfig.REALITY)) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(security, ""), null)

            assertNull(streamSettings.finalmask, security)
        }
    }

    @Test
    fun test_populateTlsSettings_attachesTheEchOutboundForTlsAsWritten() {
        // EchOutbound.serialize checks it, so an invalid one reaches it too and fails the configuration there.
        for (echOutbound in listOf("""{"tag": "ech-out", "protocol": "freedom"}""", """{"tag": "proxy"}""")) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, echOutbound), null)

            assertEquals(echOutbound, streamSettings.tlsSettings?.echOutbound)
        }
    }

    @Test
    fun test_populateTlsSettings_attachesNoEchOutboundForRealityOrABlankOne() {
        val reality = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(reality, echProfile(AppConfig.REALITY, """{"tag": "ech-out"}"""), null)
        assertNotNull(reality.realitySettings)
        assertNull(reality.realitySettings?.echOutbound)

        val blank = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(blank, echProfile(AppConfig.TLS, " "), null)
        assertNotNull(blank.tlsSettings)
        assertNull(blank.tlsSettings?.echOutbound)
    }

    @Test
    fun test_populateTlsSettings_offersTheAlpnAsWrittenCommaSeparated() {
        // TlsSettingsCheck reads alpn with alpnProtocols as well, to refuse what WebSocket and HTTPUpgrade cannot use.
        val offered = mapOf(
            " h2 , http/1.1," to listOf("h2", "http/1.1"),
            "http/1.1" to listOf("http/1.1"),
            "h3,,h2" to listOf("h3", "h2"),
            // Only a comma parts two; Xray is offered what is between as one name.
            "h2 http/1.1" to listOf("h2 http/1.1"),
            " , " to null,
            "" to null,
            null to null,
        )
        for ((alpn, protocols) in offered) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, " ").apply { this.alpn = alpn }, null)

            assertEquals(protocols, streamSettings.tlsSettings?.alpn, "$alpn")
            assertEquals(protocols.orEmpty(), CoreOutboundBuilder.alpnProtocols(alpn), "$alpn")
        }
    }

    @Test
    fun test_populateTlsSettings_offersTheCurvePreferencesAsAListAndSessionResumptionOnlyWhenOn() {
        // PattNG: curvePreferences split on ',' and trimmed as alpn's are, none written for none; enableSessionResumption
        // left out while it is off.
        val offered = mapOf(
            "X25519MLKEM768,X25519" to listOf("X25519MLKEM768", "X25519"),
            " X25519 ," to listOf("X25519"),
            " , " to null,
            "" to null,
            null to null,
        )
        for ((curves, names) in offered) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, " ").apply { curvePreferences = curves }, null)

            assertEquals(names, streamSettings.tlsSettings?.curvePreferences, "$curves")
            assertEquals(names, CoreOutboundBuilder.curvePreferenceNames(curves), "$curves")
        }
        for ((resumption, written) in listOf(true to true, false to null, null to null)) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, " ").apply { enableSessionResumption = resumption }, null)

            assertEquals(written, streamSettings.tlsSettings?.enableSessionResumption, "$resumption")
        }

        // Written, they follow cipherSuites, as in the editor and the share links.
        val streamSettings = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(
            streamSettings,
            echProfile(AppConfig.TLS, " ").apply {
                fingerPrint = "unsafe"
                cipherSuites = "TLS_AES_128_GCM_SHA256"
                curvePreferences = "X25519MLKEM768,X25519"
                enableSessionResumption = true
            },
            null,
        )
        val json = JsonUtil.toJson(streamSettings.tlsSettings)
        val cipherSuites = json.indexOf("\"cipherSuites\"")
        val curvePreferences = json.indexOf("\"curvePreferences\"")
        val sessionResumption = json.indexOf("\"enableSessionResumption\"")
        assertTrue(cipherSuites in 0 until curvePreferences && curvePreferences < sessionResumption, json)
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

    @Test
    fun test_applyTargetStrategy_anAetherProfileThroughWarpDefaultsToForceIPv4v6() {
        // Its core looks names up inside the tunnel with no cache, so Xray's DNS looks them up for it first.
        val outbound = OutboundBean(protocol = "socks")
        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.AETHER))
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, outbound.targetStrategy)
        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.AETHER).apply { targetStrategy = " " })
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, outbound.targetStrategy)
        // AsIs chosen for one stays AsIs, so its outbound carries none; any other choice is kept.
        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.AETHER).apply { targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS })
        assertNull(outbound.targetStrategy)
        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.AETHER).apply { targetStrategy = "UseIPv6" })
        assertEquals("UseIPv6", outbound.targetStrategy)
        // Every other type passes names on as they are, WireGuard as well, whose tunnel looks them up with the profile's
        // own DNS and keeps the answers; a choice is kept there too.
        for (type in EConfigType.entries.filter { it != EConfigType.AETHER }) {
            val other = OutboundBean(protocol = "vless", targetStrategy = "UseIP")
            CoreOutboundBuilder.applyTargetStrategy(other, ProfileItem.create(type))
            assertNull(other.targetStrategy, type.name)
        }
        val wireguard = OutboundBean(protocol = "wireguard")
        CoreOutboundBuilder.applyTargetStrategy(wireguard, ProfileItem.create(EConfigType.WIREGUARD).apply { targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6 })
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, wireguard.targetStrategy)
    }

    @Test
    fun test_defaultTargetStrategy_anAetherProfileWhoseTrafficLeavesThroughTorOrPsiphonPassesNamesOn() {
        fun aether(tor: AetherTor = AetherTor.OFF, psiphon: AetherPsiphon = AetherPsiphon.OFF, command: String? = null) =
            ProfileItem.create(EConfigType.AETHER).apply {
                aetherTor = tor.type
                aetherPsiphon = psiphon.type
                aetherCommand = command
            }
        fun default(profile: ProfileItem) = CoreOutboundBuilder.defaultTargetStrategy(profile)
        val force = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        val asIs = AppConfig.TARGET_STRATEGY_AS_IS

        // Inside the tunnel, or alone, Tor and Psiphon carry the traffic last, and look names up at their exit.
        assertEquals(asIs, default(aether(tor = AetherTor.CHAIN)))
        assertEquals(asIs, default(aether(tor = AetherTor.ONLY)))
        assertEquals(asIs, default(aether(psiphon = AetherPsiphon.CHAIN)))
        assertEquals(asIs, default(aether(psiphon = AetherPsiphon.ONLY)))
        assertEquals(asIs, default(aether(tor = AetherTor.REVERSE, psiphon = AetherPsiphon.CHAIN)))
        // Around the tunnel, they leave the traffic to WARP.
        assertEquals(force, default(aether()))
        assertEquals(force, default(aether(tor = AetherTor.REVERSE)))
        assertEquals(force, default(aether(psiphon = AetherPsiphon.REVERSE)))
        assertEquals(force, default(aether(tor = AetherTor.REVERSE, psiphon = AetherPsiphon.REVERSE)))

        // A command of its own runs in place of the settings, so it says where the traffic leaves.
        assertEquals(asIs, default(aether(command = "aether --masque --tor")))
        assertEquals(asIs, default(aether(command = "aether --psiphon-only --psiphon-bind 127.0.0.1:10819")))
        assertEquals(force, default(aether(tor = AetherTor.CHAIN, command = "aether --masque --tor-reverse")))
        // One the app cannot read does not run, and the settings do.
        assertEquals(asIs, default(aether(tor = AetherTor.CHAIN, command = "aether")))
        assertEquals(force, default(aether(command = "aether --tor --tor-bind 127.0.0.1:")))
    }

    @Test
    fun test_applyChainTargetStrategies_aHopCarryingTheHopBeforeItPassesNamesOnUnlessItsProfileSaysOtherwise() {
        fun hop(type: EConfigType, tag: String, strategy: String? = null): Pair<ProfileItem, OutboundBean> {
            val profile = ProfileItem.create(type).apply { targetStrategy = strategy }
            return profile to OutboundBean(tag = tag, protocol = "socks").also { CoreOutboundBuilder.applyTargetStrategy(it, profile) }
        }

        // Aether first carries your traffic and keeps its default; the hop after it is its exit-node, which passes every
        // name on whatever its profile says; a later hop keeps what its profile says.
        val throughAether = listOf(
            hop(EConfigType.AETHER, AppConfig.TAG_PROXY),
            hop(EConfigType.WIREGUARD, AppConfig.TAG_EXIT_NODE, AppConfig.TARGET_STRATEGY_FORCE_IPV4V6),
            hop(EConfigType.VLESS, "proxy-chain-2", "UseIPv4"),
        )
        CoreOutboundBuilder.applyChainTargetStrategies(throughAether)
        assertEquals(listOf(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, null, "UseIPv4"), throughAether.map { it.second.targetStrategy })

        // Aether after the first carries the connection of the hop before it, and hands its server name to its core.
        val behindAether = listOf(
            hop(EConfigType.VLESS, AppConfig.TAG_PROXY),
            hop(EConfigType.AETHER, "proxy-chain-1"),
        )
        CoreOutboundBuilder.applyChainTargetStrategies(behindAether)
        assertEquals(listOf<String?>(null, null), behindAether.map { it.second.targetStrategy })
        val chosen = listOf(hop(EConfigType.VLESS, AppConfig.TAG_PROXY), hop(EConfigType.AETHER, "proxy-chain-1", "UseIPv4v6"))
        CoreOutboundBuilder.applyChainTargetStrategies(chosen)
        assertEquals("UseIPv4v6", chosen[1].second.targetStrategy)
    }

    @Test
    fun test_toOutboundAetherExit_carriesTheFinalMaskAndDialModeOfTheAetherProfile() {
        val plain = CoreOutboundBuilder.toOutboundAetherExit(AetherExit.PLAIN)!!
        assertEquals(AppConfig.TAG_EXIT_NODE, plain.tag)
        assertEquals("freedom", plain.protocol)
        assertNull(plain.mux)
        assertNull(plain.streamSettings)

        val mask = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello"}}]}"""
        val exit = CoreOutboundBuilder.toOutboundAetherExit(AetherExit(finalMask = mask, dialMode = "code-1"))!!
        assertEquals(JsonUtil.parseString(mask), exit.streamSettings?.finalmask)
        assertEquals("code-1", exit.streamSettings?.sockopt?.dialMode)
        // A freedom outbound has no transport to name.
        assertNull(exit.streamSettings?.network)

        val dialOnly = CoreOutboundBuilder.toOutboundAetherExit(AetherExit(dialMode = "code-1"))!!
        assertEquals("code-1", dialOnly.streamSettings?.sockopt?.dialMode)
        assertNull(dialOnly.streamSettings?.finalmask)
        assertNull(dialOnly.streamSettings?.network)
    }

    @Test
    fun test_toOutboundAetherExit_takesTheOutboundOfItsNodeChangedInItsTagAndPassingEveryNameOn() {
        val node = OutboundBean(
            tag = AppConfig.TAG_PROXY,
            protocol = "vless",
            streamSettings = OutboundBean.StreamSettingsBean(network = "ws"),
            targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6,
        )
        val nodes = mapOf("germany" to ExitNodeOutbound.Built(node), "twice" to ExitNodeOutbound.SameName)
        val exit = CoreOutboundBuilder.toOutboundAetherExit(AetherExit(node = "germany")) { nodes[it] ?: ExitNodeOutbound.NotFound }!!
        assertEquals(AppConfig.TAG_EXIT_NODE, exit.tag)
        assertEquals("vless", exit.protocol)
        assertEquals("ws", exit.streamSettings?.network)
        assertNull(exit.streamSettings?.sockopt)
        // What the core sends itself is not looked up by Xray, whose DNS would reach out through the core.
        assertNull(exit.targetStrategy)
        // A name no profile has any more, or several have, or one that gives no outbound, gives no exit-node, rather than freedom.
        assertNull(CoreOutboundBuilder.toOutboundAetherExit(AetherExit(node = "gone")) { nodes[it] ?: ExitNodeOutbound.NotFound })
        assertNull(CoreOutboundBuilder.toOutboundAetherExit(AetherExit(node = "twice")) { nodes[it] ?: ExitNodeOutbound.NotFound })
        assertNull(CoreOutboundBuilder.toOutboundAetherExit(AetherExit(node = "germany")) { ExitNodeOutbound.NoOutbound })
        // Without one, the lookup is not asked.
        assertEquals("freedom", CoreOutboundBuilder.toOutboundAetherExit(AetherExit.PLAIN) { error("no node to look up") }?.protocol)
    }

    @Test
    fun test_nodeOf_digestsTheProfileAsStoredThoughBuildingItsOutboundWritesIntoIt() {
        val stored = ProfileItem.create(EConfigType.HYSTERIA2).apply { remarks = "hy"; server = "203.0.113.9"; serverPort = "443"; network = "tcp" }

        // As building a Hysteria2 outbound does, the build writes its network and alpn into the profile it builds from.
        val node = CoreOutboundBuilder.nodeOf(stored.copy()) { profile ->
            profile.network = "hysteria"
            profile.alpn = "h3"
            OutboundBean(protocol = "hysteria")
        }

        // A test digests the profile as it reads it, unbuilt.
        assertEquals(AetherExit.contentOf(stored), (node as ExitNodeOutbound.Built).content)
        assertEquals(ExitNodeOutbound.NoOutbound, CoreOutboundBuilder.nodeOf(stored.copy()) { null })
    }

    @Test
    fun test_nodeOutboundOf_refusesANodeWhoseEchOutboundCannotGoBesideIt() {
        fun withEch(echOutbound: String?, echConfigList: String? = "cloudflare-ech.com+https://1.1.1.1/dns-query") = OutboundBean(
            tag = AppConfig.TAG_PROXY,
            protocol = "vless",
            streamSettings = OutboundBean.StreamSettingsBean(
                security = AppConfig.TLS,
                tlsSettings = OutboundBean.StreamSettingsBean.TlsSettingsBean(echConfigList = echConfigList, echOutbound = echOutbound),
            ),
        )
        assertEquals(ExitNodeOutbound.NoOutbound, CoreOutboundBuilder.nodeOutboundOf(null))
        val plain = OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "trojan")
        assertEquals(ExitNodeOutbound.Built(plain), CoreOutboundBuilder.nodeOutboundOf(plain))
        val echOk = withEch("""{"tag": "ech", "protocol": "freedom"}""")
        assertEquals(ExitNodeOutbound.Built(echOk), CoreOutboundBuilder.nodeOutboundOf(echOk))
        // The exit-node's own tag, which its configuration would refuse as a conflict, and an ECH outbound no
        // configuration takes, which a profile imported unchecked can carry.
        assertEquals(ExitNodeOutbound.EchUnusable, CoreOutboundBuilder.nodeOutboundOf(withEch("""{"tag": "exit-node", "protocol": "freedom"}""")))
        assertEquals(ExitNodeOutbound.EchUnusable, CoreOutboundBuilder.nodeOutboundOf(withEch("freedom")))
        assertEquals(ExitNodeOutbound.EchUnusable, CoreOutboundBuilder.nodeOutboundOf(withEch("""{"tag": "ech", "protocol": "freedom"}""", echConfigList = null)))
    }

    @Test
    fun aWireGuardOutboundGetsOnlyAddressesAsRemoteDnsEachItsOwnEntry() {
        val none = emptyList<String>()
        // Blank: the default servers, one entry each, the IPv4 ones alone when IPv6 is off.
        assertEquals(
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001") to none,
            CoreOutboundBuilder.wireguardRemoteDns(null, ipv6Enabled = true),
        )
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to none, CoreOutboundBuilder.wireguardRemoteDns(" , ", ipv6Enabled = false))
        // "local", which the core takes no more, a host name and an address with a port are left out.
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to listOf("local"), CoreOutboundBuilder.wireguardRemoteDns("local", ipv6Enabled = false))
        assertEquals(
            listOf("8.8.8.8") to listOf("dns.google", "1.1.1.1:53"),
            CoreOutboundBuilder.wireguardRemoteDns("dns.google, 8.8.8.8, 1.1.1.1:53", ipv6Enabled = true),
        )
        // Only IPv6 servers while IPv6 is off: the default IPv4 ones.
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to none, CoreOutboundBuilder.wireguardRemoteDns("2001:4860:4860::8888", ipv6Enabled = false))
        // An IPv6 address loses its brackets; with a port it is left out.
        assertEquals(
            listOf("2001:4860:4860::8888", "9.9.9.9") to listOf("[2001:4860:4860::8844]:53"),
            CoreOutboundBuilder.wireguardRemoteDns("[2001:4860:4860::8888], 9.9.9.9, [2001:4860:4860::8844]:53", ipv6Enabled = true),
        )
        // What the core refuses although it looks like an address is left out; what it takes is kept.
        assertEquals(
            listOf("::ffff:1.1.1.1", "64:ff9b::8.8.8.8") to listOf("8.8.8.08", "1:2:3:4:5:6:7::8", "[[::1]]"),
            CoreOutboundBuilder.wireguardRemoteDns("8.8.8.08, ::ffff:1.1.1.1, 1:2:3:4:5:6:7::8, [[::1]], 64:ff9b::8.8.8.8", ipv6Enabled = true),
        )
    }

    @Test
    fun anAddressIsWhatGosNetipParseAddrTakes() {
        listOf(
            "1.1.1.1", "0.0.0.0", "255.255.255.255", "::", "::1", "1::", "2001:db8::68", "2001:DB8:0:0:0:0:0:1",
            "1:2:3:4:5:6:7:8", "::ffff:1.1.1.1", "64:ff9b::8.8.8.8", "::1.2.3.4", "1:2:3:4:5:6:1.2.3.4", "fe80::1%eth0",
        ).forEach { assertTrue(CoreOutboundBuilder.isNetipAddress(it), it) }
        listOf(
            "", "local", "dns.google", "8.8.8.08", "010.0.0.1", "00.1.1.1", "256.1.1.1", "1.2.3", "1.2.3.4.5", "1..2.3",
            "1.2.3.4%eth0", "1.1.1.1:53", "1:2:3:4:5:6:7::8", "2001:db8:0:0:0:0:0::1", "1:::2", ":1::2", "1::2::3", "1:2",
            "[::1]", "12345::1", "::1%", "%eth0", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:1.2.3.4:5", "::1.2.3", "1:2:3:4:5:1.2.3.4",
            "::g", "1:2:3:4:5:6:7:8::", "１.1.1.1",
        ).forEach { assertFalse(CoreOutboundBuilder.isNetipAddress(it), it) }
    }

    /** A socks or http profile on an address, so its outbound is built without reading the settings store. */
    private fun maskedOutbound(type: EConfigType, finalMask: String?): OutboundBean? {
        val profile = ProfileItem.create(type).apply {
            server = "1.2.3.4"
            serverPort = "1080"
            this.finalMask = finalMask
        }
        return if (type == EConfigType.SOCKS) CoreOutboundBuilder.toOutboundSocks(profile) else CoreOutboundBuilder.toOutboundHttp(profile)
    }

    @Test
    fun aSocksOrHttpOutboundCarriesTheFinalMaskOfItsProfile() {
        val mask = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "length": "100-200"}}]}"""
        for (type in listOf(EConfigType.SOCKS, EConfigType.HTTP)) {
            val outbound = maskedOutbound(type, mask)
            assertEquals(JsonUtil.parseString(mask), outbound?.streamSettings?.finalmask, type.name)
            // The mask goes on the stream settings the outbound is created with; no transport comes in.
            assertEquals(AppConfig.DEFAULT_NETWORK, outbound?.streamSettings?.network, type.name)
            // No mask, or a blank one, sets none: a blank one is no JSON, so it is not handed on to be warned of.
            for (none in listOf(null, "", "  ")) {
                assertNull(maskedOutbound(type, none)?.streamSettings?.finalmask, "$type $none")
            }
        }
    }
}
