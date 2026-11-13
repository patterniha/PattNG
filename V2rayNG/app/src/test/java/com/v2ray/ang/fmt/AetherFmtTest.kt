package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AetherFmtTest {

    private fun link(config: ProfileItem) =
        EConfigType.AETHER.protocolScheme + AetherFmt.toUri(config)

    private fun profile(block: ProfileItem.() -> Unit) =
        ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "My Node"
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = AetherTransport.HTTP3.type
            aetherScanMode = AetherScanMode.BALANCED.type
            aetherObfuscation = AetherObfuscation.BALANCED.type
            aetherIpVersion = AetherIpVersion.V4.type
            block()
        }

    /** What [block] returns with the Aether listen port of the settings at [port]. */
    private fun <T> onListenPort(port: Int, block: () -> T): T {
        val source = AetherCoreManager.listenPortSource
        AetherCoreManager.listenPortSource = { port }
        try {
            return block()
        } finally {
            AetherCoreManager.listenPortSource = source
        }
    }

    @Test
    fun aPinnedMasqueNodeSurvivesTheRoundTrip() {
        val original = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.VERIFIED.type
            aetherObfuscation = AetherObfuscation.AGGRESSIVE.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherFragment = true
        }

        val parsed = AetherFmt.parse(link(original))

        assertNotNull(parsed)
        assertEquals(EConfigType.AETHER, parsed?.configType)
        assertEquals("My Node", parsed?.remarks)
        assertEquals("162.159.198.1", parsed?.server)
        assertEquals("443", parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h2", parsed?.aetherTransport)
        assertEquals("verified", parsed?.aetherScanMode)
        // Over HTTP/2 obfuscation does nothing, and the link leaves it out.
        assertEquals("auto", parsed?.aetherObfuscation)
        assertEquals("both", parsed?.aetherIpVersion)
        assertEquals(true, parsed?.aetherFragment)
    }

    @Test
    fun theOldNameOfTheVerifiedScanModeStillReads() {
        assertEquals(AetherScanMode.VERIFIED, AetherScanMode.fromString("stealth"))
        assertEquals(AetherScanMode.VERIFIED, AetherScanMode.fromString("verified"))
        assertEquals(AetherScanMode.BALANCED, AetherScanMode.fromString("quiet"))
        // A link written before the rename.
        val parsed = AetherFmt.parse(link(profile {}).replace("scan=balanced", "scan=stealth"))
        assertEquals("verified", parsed?.aetherScanMode)
    }

    @Test
    fun psiphonSurvivesTheRoundTripAndStaysOutOfALinkWithoutIt() {
        val chained = profile {
            aetherPsiphon = "chain"
            aetherPsiphonMode = "cdn"
            aetherPsiphonCdnIps = "1.1.1.1,1.0.0.1"
            aetherPsiphonCdnSni = "a.example,b.example"
            aetherPsiphonRegion = "DE"
            aetherPsiphonBundledList = false
            aetherPsiphonCdnSets = "cloudflare,fastly"
        }
        val parsed = AetherFmt.parse(link(chained))
        assertEquals("chain", parsed?.aetherPsiphon)
        assertEquals("cdn", parsed?.aetherPsiphonMode)
        assertEquals("1.1.1.1,1.0.0.1", parsed?.aetherPsiphonCdnIps)
        assertEquals("a.example,b.example", parsed?.aetherPsiphonCdnSni)
        assertEquals("DE", parsed?.aetherPsiphonRegion)
        assertEquals(false, parsed?.aetherPsiphonBundledList)
        assertEquals("cloudflare,fastly", parsed?.aetherPsiphonCdnSets)

        val plain = AetherFmt.toUri(profile {})
        assertFalse(plain.contains("psiphon"))
        val parsedPlain = AetherFmt.parse(link(profile {}))
        assertNull(parsedPlain?.aetherPsiphon)
        assertNull(parsedPlain?.aetherPsiphonMode)

        // Starting from the bundled list is the default and needs no word in a link.
        assertFalse(AetherFmt.toUri(profile { aetherPsiphon = "chain" }).contains("psiphon_bundled"))
        assertNull(AetherFmt.parse(link(profile { aetherPsiphon = "chain" }))?.aetherPsiphonBundledList)
    }

    @Test
    fun psiphonSettingsAreNormalizedAndClearedWhenPsiphonIsOff() {
        val chained = profile {
            aetherPsiphon = "chain"
            aetherPsiphonMode = "made-up"
            aetherPsiphonCdnIps = " 1.1.1.1, 1.0.0.1  8.8.8.8 "
            aetherPsiphonCdnSni = ""
            aetherPsiphonRegion = " de "
            aetherPsiphonBundledList = true
            aetherPsiphonCdnSets = " fastly, nowhere cloudflare fastly "
        }
        assertNull(AetherFmt.normalize(chained))
        // The sets come out in the order the core tries them, once each, strangers left out.
        assertEquals("cloudflare,fastly", chained.aetherPsiphonCdnSets)
        assertEquals("auto", chained.aetherPsiphonMode)
        assertEquals("1.1.1.1,1.0.0.1,8.8.8.8", chained.aetherPsiphonCdnIps)
        assertNull(chained.aetherPsiphonCdnSni)
        assertEquals("DE", chained.aetherPsiphonRegion)
        // Yes is the default and is stored as nothing; no stays.
        assertNull(chained.aetherPsiphonBundledList)
        val fresh = profile { aetherPsiphon = "chain"; aetherPsiphonBundledList = false }
        assertNull(AetherFmt.normalize(fresh))
        assertEquals(false, fresh.aetherPsiphonBundledList)

        val off = profile { aetherPsiphon = "off"; aetherPsiphonMode = "cdn"; aetherPsiphonRegion = "DE"; aetherPsiphonBundledList = false; aetherPsiphonCdnSets = "fastly" }
        assertNull(AetherFmt.normalize(off))
        assertNull(off.aetherPsiphon)
        assertNull(off.aetherPsiphonMode)
        assertNull(off.aetherPsiphonRegion)
        assertNull(off.aetherPsiphonBundledList)
        assertNull(off.aetherPsiphonCdnSets)
    }

    @Test
    fun psiphonAroundTheTunnelNeedsMasqueAndPsiphonInsideItNeedsThePortAfterTheListenPort() {
        assertEquals(
            AetherFmt.Problem.PSIPHON_NEEDS_MASQUE,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherPsiphon = "reverse" })
        )
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.MASQUE.type; aetherPsiphon = "reverse" }))
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.MIM.type; aetherPsiphon = "reverse" }))
        assertEquals(
            AetherFmt.Problem.PSIPHON_NEEDS_MASQUE,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherPsiphon = "reverse" })
        )
        // WireGuard over MASQUE dials MASQUE alone, with its WireGuard inside that tunnel.
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WG_OVER_MASQUE.type; aetherPsiphon = "reverse" }))
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WG_OVER_MASQUE.type; aetherPsiphon = "chain" }))
        // WireGuard inside Psiphon is fine: Psiphon carries the tunnel only the other way round.
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherPsiphon = "chain" }))

        assertEquals(AetherFmt.Problem.NEXT_PORT_TAKEN, AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10820)))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10819)))
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10821)))
        // Without Psiphon inside, the port after the listen port is nobody's business.
        assertNull(AetherFmt.normalize(profile {}, takenPorts = setOf(10820)))
    }

    @Test
    fun torSurvivesTheRoundTripAndStaysOutOfALinkWithoutIt() {
        val bridged = profile {
            aetherTor = "only"
            aetherTorBridges = "own"
            aetherTorBridgeLines = "obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0\nwebtunnel 198.51.100.25:443 7DD627 url=https://example.com/x"
        }
        val parsed = AetherFmt.parse(link(bridged))
        assertEquals("only", parsed?.aetherTor)
        assertEquals("own", parsed?.aetherTorBridges)
        assertEquals(bridged.aetherTorBridgeLines, parsed?.aetherTorBridgeLines)

        val automatic = AetherFmt.parse(link(profile { aetherTor = "chain" }))
        assertEquals("chain", automatic?.aetherTor)
        assertEquals("auto", automatic?.aetherTorBridges)
        assertNull(automatic?.aetherTorBridgeLines)

        val plain = AetherFmt.toUri(profile {})
        assertFalse(plain.contains("tor="))
        assertFalse(plain.contains("bridges"))
        assertNull(AetherFmt.parse(link(profile {}))?.aetherTor)
    }

    @Test
    fun theExitNodeSurvivesTheRoundTripUnderTheNamesOfAnOrdinaryLink() {
        val mask = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "length": "50-100"}}]}"""
        val masked = profile { finalMask = mask; dialMode = "code-1" }
        val uri = AetherFmt.toUri(masked)
        assertTrue(uri.contains("fm="))
        assertTrue(uri.contains("dialMode=code-1"))
        val parsed = AetherFmt.parse(link(masked))
        assertEquals(mask, parsed?.finalMask)
        assertEquals("code-1", parsed?.dialMode)

        val plain = AetherFmt.toUri(profile {})
        assertFalse(plain.contains("fm="))
        assertFalse(plain.contains("dialMode"))
        val bare = AetherFmt.parse(link(profile {}))
        assertNull(bare?.finalMask)
        assertNull(bare?.dialMode)
    }

    // PattNG: the exit-node's tcpUserTimeout, under the name of an ordinary link, only where the core of the link dials
    // out over TCP (TcpUserTimeoutRule): MASQUE over HTTP/2 here, not over HTTP/3.
    @Test
    fun theExitNodesTcpUserTimeoutSurvivesTheRoundTripWhereTheCoreConnectsOverTcp() {
        val overTcp = profile {
            aetherTransport = AetherTransport.HTTP2.type
            tcpUserTimeout = 10000
        }
        val uri = AetherFmt.toUri(overTcp)
        assertTrue(uri.contains("tut=10000"), uri)
        assertEquals(10000, AetherFmt.parse(link(overTcp))?.tcpUserTimeout)

        val overUdp = profile { tcpUserTimeout = 10000 }
        val udpUri = AetherFmt.toUri(overUdp)
        assertFalse("tut" in udpUri.substringAfter('?').substringBefore('#').split('&').map { it.substringBefore('=') }, udpUri)
        assertNull(AetherFmt.parse(link(overUdp))?.tcpUserTimeout)
        assertNull(AetherFmt.parse(link(profile {}))?.tcpUserTimeout)
    }

    @Test
    fun torSettingsAreNormalizedAndClearedWhenTorIsOff() {
        val own = profile {
            aetherTor = "chain"
            aetherTorBridges = "own"
            aetherTorBridgeLines = " Bridge obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0 \n# a comment\n\nbridge webtunnel 198.51.100.25:443 7DD627 url=https://example.com/x\n"
        }
        assertNull(AetherFmt.normalize(own))
        assertEquals(
            "obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0\nwebtunnel 198.51.100.25:443 7DD627 url=https://example.com/x",
            own.aetherTorBridgeLines
        )

        // Lines are kept only with the setting that uses them, and an unknown setting is the automatic one.
        val automatic = profile { aetherTor = "chain"; aetherTorBridges = "made-up"; aetherTorBridgeLines = "obfs4 192.0.2.55:38114 316E64 cert=abc" }
        assertNull(AetherFmt.normalize(automatic))
        assertEquals("auto", automatic.aetherTorBridges)
        assertNull(automatic.aetherTorBridgeLines)

        val off = profile { aetherTor = "off"; aetherTorBridges = "first"; aetherTorBridgeLines = "obfs4 192.0.2.55:38114 316E64 cert=abc" }
        assertNull(AetherFmt.normalize(off))
        assertNull(off.aetherTor)
        assertNull(off.aetherTorBridges)
        assertNull(off.aetherTorBridgeLines)

        assertEquals(
            AetherFmt.Problem.TOR_BRIDGES_MISSING,
            AetherFmt.normalize(profile { aetherTor = "only"; aetherTorBridges = "own"; aetherTorBridgeLines = "# nothing here\n" })
        )
    }

    @Test
    fun torAroundTheTunnelNeedsMasqueAndTorAndPsiphonOnlyNest() {
        assertEquals(
            AetherFmt.Problem.TOR_NEEDS_MASQUE,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherTor = "reverse" })
        )
        assertEquals(
            AetherFmt.Problem.TOR_NEEDS_MASQUE,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherTor = "reverse" })
        )
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WG_OVER_MASQUE.type; aetherTor = "reverse" }))
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.MIM.type; aetherTor = "reverse" }))
        // Inside the tunnel or alone, Tor does not care what carries WARP.
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherTor = "chain" }))
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherTor = "only" }))

        assertNull(AetherFmt.normalize(profile { aetherTor = "chain"; aetherPsiphon = "reverse" }))
        assertNull(AetherFmt.normalize(profile { aetherTor = "reverse"; aetherPsiphon = "chain" }))
        val clashes = listOf("chain" to "chain", "reverse" to "reverse", "only" to "chain", "only" to "only", "chain" to "only", "reverse" to "only")
        for ((tor, psiphon) in clashes) {
            assertEquals(
                AetherFmt.Problem.TOR_PSIPHON_CONFLICT,
                AetherFmt.normalize(profile { aetherTor = tor; aetherPsiphon = psiphon }),
                "tor=$tor psiphon=$psiphon"
            )
        }
    }

    @Test
    fun aCommandCannotListenOnTheSecondarySocksPort() {
        // The editor holds the ports of a core to the local proxy ports and the Aether secondary SOCKS port together.
        val secondary = AetherCoreManager.secondarySocksPort
        val taken = setOf(10808, 10809) + secondary
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:$secondary" }, taken)
        )
        // On the Aether listen port, the most that Tor and Psiphon take leaves it free.
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }, taken))
    }

    @Test
    fun torTakesThePortAfterTheListenPortInsideOrAroundTheTunnel() {
        assertEquals(AetherFmt.Problem.NEXT_PORT_TAKEN, AetherFmt.normalize(profile { aetherTor = "chain" }, takenPorts = setOf(10820)))
        assertEquals(AetherFmt.Problem.NEXT_PORT_TAKEN, AetherFmt.normalize(profile { aetherTor = "reverse" }, takenPorts = setOf(10820)))
        assertNull(AetherFmt.normalize(profile { aetherTor = "only" }, takenPorts = setOf(10820)))
        // Nested, Psiphon inside and Tor around, the core takes two ports after the listen port.
        assertEquals(
            AetherFmt.Problem.NEXT_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }, takenPorts = setOf(10821))
        )
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }, takenPorts = setOf(10822)))
    }

    @Test
    fun theFingerprintRidesWithMasqueAndChromesStaysOutOfALink() {
        for (fingerprint in listOf("firefox", "semi-python", "go")) {
            for (transport in AetherTransport.entries) {
                val text = link(profile { aetherTransport = transport.type; aetherFingerprint = fingerprint })
                assertTrue(text.contains("fingerprint=$fingerprint"), text)
                assertEquals(fingerprint, AetherFmt.parse(text)?.aetherFingerprint)
            }
        }
        val chrome = link(profile { aetherFingerprint = "chrome" })
        assertFalse(chrome.contains("fingerprint="))
        assertNull(AetherFmt.parse(chrome)?.aetherFingerprint)
        // WireGuard has no TLS handshake of its own to shape.
        assertFalse(link(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherFingerprint = "go" }).contains("fingerprint="))
    }

    @Test
    fun obfuscationStaysOutOfTheLinkOfMasqueOverHttp2() {
        assertTrue(link(profile {}).contains("noize=balanced"))
        assertFalse(link(profile { aetherTransport = AetherTransport.HTTP2.type }).contains("noize="))
        // Tor or Psiphon around the tunnel carry TCP alone, so the core takes HTTP/2 whatever the transport says.
        assertFalse(link(profile { aetherPsiphon = "reverse" }).contains("noize="))
        assertFalse(link(profile { aetherTor = "reverse" }).contains("noize="))
        // WireGuard keeps it whatever the transport says.
        assertTrue(link(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherTransport = AetherTransport.HTTP2.type }).contains("noize=balanced"))
    }

    @Test
    fun automaticObfuscationStaysOutOfALinkAndEveryProfileOfTheCoreRoundTrips() {
        val automatic = link(profile { aetherObfuscation = AetherObfuscation.AUTO.type })
        assertFalse(automatic.contains("noize="))
        assertEquals("auto", AetherFmt.parse(automatic)?.aetherObfuscation)
        for (named in listOf("off", "light", "firewall", "balanced", "gfw", "aggressive")) {
            assertEquals(named, AetherFmt.parse(link(profile { aetherObfuscation = named }))?.aetherObfuscation)
        }
    }

    @Test
    fun theEchResolverAndDomainSurviveTheRoundTripWhileEchIsOn() {
        val tuned = profile {
            aetherEch = true
            aetherEchDns = "https://doq.dns4all.eu/dns-query"
            aetherEchDomain = "ip.gs"
        }
        val parsed = AetherFmt.parse(link(tuned))
        assertEquals("https://doq.dns4all.eu/dns-query", parsed?.aetherEchDns)
        assertEquals("ip.gs", parsed?.aetherEchDomain)

        // Without ECH a link says nothing of them, and they are not taken from one.
        val off = link(profile { aetherEchDns = "tcp://1.1.1.1"; aetherEchDomain = "ip.gs" })
        assertFalse(off.contains("ech_dns="))
        assertFalse(off.contains("ech_domain="))
        val plain = link(profile {})
        val strayLink = plain.substringBefore('#') + "&ech_dns=tcp%3A%2F%2F1.1.1.1&ech_domain=ip.gs#" + plain.substringAfter('#')
        assertTrue(strayLink.substringBefore('#').contains("ech_dns="), strayLink)
        val stray = AetherFmt.parse(strayLink)
        assertNull(stray?.aetherEchDns)
        assertNull(stray?.aetherEchDomain)
    }

    @Test
    fun theEchResolverIsUdpOrTcpWithAnIpAddressOrAnHttpsUrl() {
        val good = listOf(
            "udp://1.0.0.1",
            "udp://1.1.1.1:5353",
            "tcp://8.8.8.8",
            "TCP://[2606:4700:4700::1111]:53",
            "tcp://[::1]",
            "udp://2606:4700::1111",
            "https://doq.dns4all.eu/dns-query",
            "https://1.1.1.1:8443/dns-query",
            // Where the connection goes and what the ClientHello names, each once and in either order.
            "https://1.1.1.1/dns-query@sni=www.microsoft.com",
            "https://doq.dns4all.eu/dns-query@address=194.0.5.3",
            "https://doq.dns4all.eu/dns-query@address=2.2.2.2@sni=google.com",
            "https://doq.dns4all.eu/dns-query@SNI=google.com@address=[2606:4700::1111]",
            "https://doq.dns4all.eu/dns-query@address=2606:4700::1111",
            "https://doq.dns4all.eu/dns-query@address=front.example.net",
        )
        for (dns in good) {
            val config = profile { aetherEch = true; aetherEchDns = " $dns " }
            assertNull(AetherFmt.normalize(config), dns)
            assertEquals(dns, config.aetherEchDns)
        }
        val bad = listOf(
            "1.1.1.1",
            "udp://dns.google",
            "tls://1.1.1.1",
            "udp://1.1.1.1:70000",
            "udp://",
            "https://",
            "https:///dns-query",
            // The core reads no bracketed IPv4 address, no space and no digit outside ASCII, as a Persian keyboard types.
            "udp://[1.1.1.1]",
            "udp://[1.1.1.1]:53",
            "udp:// 1.1.1.1",
            "udp://1.1.1.1 :53",
            "udp://1.1.1.1: 53",
            "udp://\u06f1.\u06f1.\u06f1.\u06f1",
            "udp://1.1.1.1:\u06f5\u06f3",
            "udp://\uff11.\uff11.\uff11.\uff11",
            "https://dns example/dns-query",
            "https://dns.example/dns-query?x='1'",
            // What the core refuses after a DoH URL: an empty or unusable setting, a port, an IP address as the
            // server name, a setting named twice or one it does not know.
            "https://@address=2.2.2.2",
            "https://doq.dns4all.eu/dns-query@address=",
            "https://doq.dns4all.eu/dns-query@address=2.2.2.2:443",
            "https://doq.dns4all.eu/dns-query@sni=",
            "https://doq.dns4all.eu/dns-query@sni=2.2.2.2",
            "https://doq.dns4all.eu/dns-query@sni=[::1]",
            "https://doq.dns4all.eu/dns-query@sni=google.com@sni=bing.com",
            "https://doq.dns4all.eu/dns-query@address=1.1.1.1@address=2.2.2.2",
            "https://doq.dns4all.eu/dns-query@port=443",
            "https://doq.dns4all.eu/dns-query@google.com",
        )
        for (dns in bad) {
            assertEquals(AetherFmt.Problem.INVALID_ECH_DNS, AetherFmt.normalize(profile { aetherEch = true; aetherEchDns = dns }), dns)
        }
    }

    @Test
    fun theEchDomainIsADomainName() {
        for (domain in listOf("crypto.cloudflare.com", "ip.gs", "ip.gs.", "_ech.example.com")) {
            val config = profile { aetherEch = true; aetherEchDomain = domain }
            assertNull(AetherFmt.normalize(config), domain)
            assertEquals(domain, config.aetherEchDomain)
        }
        for (domain in listOf("a..b", "with space.com", "https://ip.gs", "ip.gs/", "${"a".repeat(64)}.com", "--upstream", "-ip.gs", "ip-.gs")) {
            assertEquals(AetherFmt.Problem.INVALID_ECH_DOMAIN, AetherFmt.normalize(profile { aetherEch = true; aetherEchDomain = domain }), domain)
        }
    }

    @Test
    fun echFieldsOutOfUseNeitherBlockSavingNorLoseWhatWasWritten() {
        val masque = profile { aetherEch = true; aetherEchDns = "udp://dns.google"; aetherEchDomain = "--upstream" }
        assertEquals(AetherFmt.Problem.INVALID_ECH_DNS, AetherFmt.normalize(masque))

        val shapes = listOf<ProfileItem.() -> Unit>(
            { aetherEch = false },
            { aetherProtocol = AetherProtocol.WIREGUARD.type },
            { aetherPsiphon = "only" },
            { aetherTor = "only" },
        )
        for (shape in shapes) {
            // Kept as written, as the WARP keys page keeps its own, to be put right when ECH is next in use.
            val waiting = profile { aetherEch = true; aetherEchDns = " udp://dns.google "; aetherEchDomain = "--upstream"; shape() }
            assertNull(AetherFmt.normalize(waiting))
            assertEquals("udp://dns.google", waiting.aetherEchDns)
            assertEquals("--upstream", waiting.aetherEchDomain)
            // Out of use, neither reaches the core nor a link.
            val arguments = AetherCoreManager.buildArguments(waiting, 10819)
            assertFalse(arguments.any { it.startsWith("--ech") || it == "--upstream" }, arguments.toString())
            assertFalse(link(waiting).contains("ech"))

            val kept = profile { aetherEch = true; aetherEchDns = "tcp://8.8.8.8"; aetherEchDomain = "ip.gs"; shape() }
            assertNull(AetherFmt.normalize(kept))
            assertEquals("tcp://8.8.8.8", kept.aetherEchDns)
            assertEquals("ip.gs", kept.aetherEchDomain)
        }

        // In use, a link carries ECH, but never a value the core would refuse.
        val unchecked = link(profile { aetherEch = true; aetherEchDns = "udp://dns.google"; aetherEchDomain = "ip.gs" })
        assertTrue(unchecked.contains("ech=1"))
        assertFalse(unchecked.contains("ech_dns="))
        assertTrue(unchecked.contains("ech_domain=ip.gs"))
    }

    @Test
    fun aLinkGivesNoEchResolverOrDomainTheCoreWouldRefuse() {
        val plain = link(profile { aetherEch = true })
        val crafted = plain.substringBefore('#') + "&ech_dns=udp%3A%2F%2F%5B1.1.1.1%5D&ech_domain=--upstream#" + plain.substringAfter('#')
        assertTrue(crafted.substringBefore('#').contains("ech_domain="), crafted)
        val parsed = AetherFmt.parse(crafted)
        assertEquals(true, parsed?.aetherEch)
        assertNull(parsed?.aetherEchDns)
        assertNull(parsed?.aetherEchDomain)
    }

    @Test
    fun theDefaultEchResolverAndDomainAreLeftToTheDefaults() {
        val config = profile { aetherEch = true; aetherEchDns = AppConfig.AETHER_ECH_DNS; aetherEchDomain = " ${AppConfig.AETHER_ECH_DOMAIN} " }
        assertNull(AetherFmt.normalize(config))
        assertNull(config.aetherEchDns)
        assertNull(config.aetherEchDomain)
        // Others are kept with ECH off as well.
        val off = profile { aetherEchDns = "tcp://1.1.1.1"; aetherEchDomain = "ip.gs" }
        assertNull(AetherFmt.normalize(off))
        assertEquals("tcp://1.1.1.1", off.aetherEchDns)
        assertEquals("ip.gs", off.aetherEchDomain)
    }

    /** The query of [link], each name with its value as the link writes it. */
    private fun query(link: String): Map<String, String> =
        link.substringAfter('?').substringBefore('#').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun theMasqueServerNameIsOneTheCoreTakes() {
        // As the core takes it: a domain name, with a trailing dot or without.
        for (name in listOf("www.cloudflare.com", "consumer-masque.cloudflareclient.com", "www.cloudflare.com.", "localhost", "_sni.example.com")) {
            assertTrue(AetherFmt.isMasqueSni(name), name)
        }
        // No IPv4 address as the core's parser reads one; what it reads as none is a name like any other.
        for (name in listOf("1.1.1.1", "1.1.1.1.", "255.255.255.255", "0.0.0.0")) {
            assertFalse(AetherFmt.isMasqueSni(name), name)
        }
        for (name in listOf("1.1.1", "256.1.1.1", "01.1.1.1")) {
            assertTrue(AetherFmt.isMasqueSni(name), name)
        }
        val bad = listOf(
            "",
            ".",
            "www..cloudflare.com",
            "www.cloudflare.com..",
            "www cloudflare com",
            "www.cloudflare.com:443",
            "https://www.cloudflare.com",
            "2606:4700:4700::1111",
            "[2606:4700:4700::1111]",
            "${"a".repeat(64)}.com",
            "--upstream",
            "-www.cloudflare.com",
        )
        for (name in bad) {
            assertFalse(AetherFmt.isMasqueSni(name), name)
        }
    }

    @Test
    fun aMasqueServerNameTheCoreWouldRefuseIsRefusedOnlyWhileAMasqueTunnelTakesIt() {
        for (protocol in listOf(AetherProtocol.MASQUE, AetherProtocol.MIM, AetherProtocol.WG_OVER_MASQUE)) {
            for (name in listOf("1.1.1.1", "www..cloudflare.com", "--upstream")) {
                val config = profile { aetherProtocol = protocol.type; aetherMasqueSni = name }
                assertEquals(AetherFmt.Problem.INVALID_MASQUE_SNI, AetherFmt.normalize(config), "${protocol.type} $name")
            }
        }

        val shapes = listOf<ProfileItem.() -> Unit>(
            { aetherProtocol = AetherProtocol.WIREGUARD.type },
            { aetherProtocol = AetherProtocol.GOOL.type },
            { aetherPsiphon = "only" },
            { aetherTor = "only" },
        )
        for (shape in shapes) {
            // Kept as written, trimmed, to be put right when a MASQUE tunnel next takes it.
            val waiting = profile { aetherMasqueSni = " 1.1.1.1 "; shape() }
            assertNull(AetherFmt.normalize(waiting))
            assertEquals("1.1.1.1", waiting.aetherMasqueSni)
            // Out of use, it reaches neither the core nor a link.
            val arguments = AetherCoreManager.buildArguments(waiting, 10819)
            assertFalse("--masque-sni" in arguments, arguments.toString())
            assertNull(query(link(waiting))["sni"])
        }
    }

    @Test
    fun theDefaultMasqueServerNameIsLeftToTheDefault() {
        for (default in listOf(" ${AppConfig.AETHER_MASQUE_SNI} ", "www.cloudflare.com", " ")) {
            val config = profile { aetherMasqueSni = default }
            assertNull(AetherFmt.normalize(config), default)
            assertNull(config.aetherMasqueSni, default)
        }
        // Another is kept as written, trimmed; the core leaves out a trailing dot itself.
        val own = profile { aetherMasqueSni = " consumer-masque.cloudflareclient.com. " }
        assertNull(AetherFmt.normalize(own))
        assertEquals("consumer-masque.cloudflareclient.com.", own.aetherMasqueSni)
    }

    @Test
    fun aLinkCarriesAMasqueServerNameOfItsOwnOverMasque() {
        for (protocol in listOf(AetherProtocol.MASQUE, AetherProtocol.MIM, AetherProtocol.WG_OVER_MASQUE)) {
            val own = link(profile { aetherProtocol = protocol.type; aetherMasqueSni = "consumer-masque.cloudflareclient.com" })
            assertEquals("consumer-masque.cloudflareclient.com", query(own)["sni"], own)
            assertEquals("consumer-masque.cloudflareclient.com", AetherFmt.parse(own)?.aetherMasqueSni, own)
        }
        // The default needs no word, and a link without one leaves the profile to the default.
        for (default in listOf(null, AppConfig.AETHER_MASQUE_SNI)) {
            val plain = link(profile { aetherMasqueSni = default })
            assertNull(query(plain)["sni"], plain)
            assertNull(AetherFmt.parse(plain)?.aetherMasqueSni, plain)
        }
        // A name the core would refuse goes into no link and is taken from none, and WireGuard takes none at all.
        assertNull(query(link(profile { aetherMasqueSni = "1.1.1.1" }))["sni"])
        val crafted = listOf(
            link(profile {}) to "1.1.1.1",
            link(profile {}) to "--upstream",
            link(profile { aetherProtocol = AetherProtocol.WIREGUARD.type }) to "consumer-masque.cloudflareclient.com",
        )
        for ((plain, name) in crafted) {
            val stray = plain.substringBefore('#') + "&sni=$name#" + plain.substringAfter('#')
            assertEquals(name, query(stray)["sni"], stray)
            assertNull(AetherFmt.parse(stray)?.aetherMasqueSni, stray)
        }
    }

    @Test
    fun encryptedClientHelloTheResolversAndTheExitRuleSurviveTheRoundTrip() {
        val tuned = profile {
            aetherEch = true
            aetherDns = "1.1.1.1,10.0.0.1:5353"
            aetherExitLoc = "!IR,RU"
        }
        val parsed = AetherFmt.parse(link(tuned))
        assertEquals(true, parsed?.aetherEch)
        assertEquals("1.1.1.1,10.0.0.1:5353", parsed?.aetherDns)
        assertEquals("!IR,RU", parsed?.aetherExitLoc)

        val plain = AetherFmt.toUri(profile {})
        assertFalse(plain.contains("ech="))
        assertFalse(plain.contains("dns="))
        assertFalse(plain.contains("exit_loc="))
        // ECH rides with the MASQUE handshake alone.
        assertFalse(link(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherEch = true }).contains("ech="))
        assertTrue(link(profile { aetherProtocol = AetherProtocol.MIM.type; aetherEch = true }).contains("ech=1"))
    }

    @Test
    fun theResolversAreCheckedAndWrittenBackWithCommas() {
        val mixed = profile { aetherDns = " 1.1.1.1, [2606:4700:4700::1111]:53  8.8.8.8;10.0.0.1:5353 " }
        assertNull(AetherFmt.normalize(mixed))
        assertEquals("1.1.1.1,[2606:4700:4700::1111]:53,8.8.8.8,10.0.0.1:5353", mixed.aetherDns)

        val bare6 = profile { aetherDns = "2606:4700:4700::1111" }
        assertNull(AetherFmt.normalize(bare6))
        assertEquals("2606:4700:4700::1111", bare6.aetherDns)

        val blank = profile { aetherDns = " , " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherDns)

        assertEquals(AetherFmt.Problem.INVALID_DNS, AetherFmt.normalize(profile { aetherDns = "dns.google" }))
        assertEquals(AetherFmt.Problem.INVALID_DNS, AetherFmt.normalize(profile { aetherDns = "1.1.1.1,10.0.0.1:70000" }))
    }

    @Test
    fun theExitRuleIsCheckedAndWrittenInCapitals() {
        val refused = profile { aetherExitLoc = " ! ir, ru " }
        assertNull(AetherFmt.normalize(refused))
        assertEquals("!IR,RU", refused.aetherExitLoc)

        val allowed = profile { aetherExitLoc = "de,se" }
        assertNull(AetherFmt.normalize(allowed))
        assertEquals("DE,SE", allowed.aetherExitLoc)

        val blank = profile { aetherExitLoc = "  " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherExitLoc)

        assertEquals(AetherFmt.Problem.INVALID_EXIT_LOC, AetherFmt.normalize(profile { aetherExitLoc = "Germany" }))
        assertEquals(AetherFmt.Problem.INVALID_EXIT_LOC, AetherFmt.normalize(profile { aetherExitLoc = "DE,,SE" }))
        assertEquals(AetherFmt.Problem.INVALID_EXIT_LOC, AetherFmt.normalize(profile { aetherExitLoc = "DE!" }))
    }

    @Test
    fun theBridgePoolRidesWithTorAndIsClearedWithIt() {
        assertEquals("only", AetherFmt.parse(link(profile { aetherTor = "only"; aetherTorRelays = "only" }))?.aetherTorRelays)
        assertEquals("auto", AetherFmt.parse(link(profile { aetherTor = "chain" }))?.aetherTorRelays)
        assertFalse(link(profile { aetherTorRelays = "only" }).contains("tor_relays"))

        val normalized = profile { aetherTor = "chain"; aetherTorRelays = "made-up" }
        assertNull(AetherFmt.normalize(normalized))
        assertEquals("auto", normalized.aetherTorRelays)

        val off = profile { aetherTor = "off"; aetherTorRelays = "only" }
        assertNull(AetherFmt.normalize(off))
        assertNull(off.aetherTorRelays)
    }

    @Test
    fun aCommandWrittenInPlaceOfTheSettingsIsCheckedAndTrimmed() {
        val trimmed = profile { aetherCommand = "  aether --wg --bind 127.0.0.1:20808  " }
        assertNull(AetherFmt.normalize(trimmed))
        assertEquals("aether --wg --bind 127.0.0.1:20808", trimmed.aetherCommand)

        val blank = profile { aetherCommand = "   " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherCommand)

        assertEquals(AetherFmt.Problem.INVALID_COMMAND, AetherFmt.normalize(profile { aetherCommand = "aether" }))
        assertEquals(AetherFmt.Problem.INVALID_COMMAND, AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 20808" }))
        // The ports a command names are held to the same rule as a listen port.
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:10808" }, takenPorts = setOf(10808))
        )
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --psiphon --bind 127.0.0.1:10808 --psiphon-bind 127.0.0.1:10819" }, takenPorts = setOf(10808))
        )
    }

    @Test
    fun fragmentValuesSurviveTheRoundTrip() {
        val uri = link(profile {
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherFragmentSize = "16-32"
            aetherFragmentDelay = "5"
        })

        val parsed = AetherFmt.parse(uri)
        assertEquals("16-32", parsed?.aetherFragmentSize)
        assertEquals("5", parsed?.aetherFragmentDelay)

        val broken = AetherFmt.parse("aether://?protocol=masque&transport=h2&fragment=1&fragment_size=0&fragment_delay=x#X")
        assertNull(broken?.aetherFragmentSize)
        assertNull(broken?.aetherFragmentDelay)
    }

    @Test
    fun fragmentValuesAreCheckedOnlyWhenTheyAreUsed() {
        val used = profile {
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherFragmentSize = "32 - 16"
            aetherFragmentDelay = ""
        }
        assertNull(AetherFmt.normalize(used))
        assertEquals("16-32", used.aetherFragmentSize)
        assertNull(used.aetherFragmentDelay)

        assertEquals(
            AetherFmt.Problem.INVALID_FRAGMENT,
            AetherFmt.normalize(used.copy(aetherFragmentDelay = "5000"))
        )

        val unused = used.copy(aetherTransport = AetherTransport.HTTP3.type, aetherFragmentDelay = "5000")
        assertNull(AetherFmt.normalize(unused))
        assertNull(unused.aetherFragmentDelay)

        // Masque-in-masque fragments on the HTTP/2 carrier as MASQUE does; WireGuard never does.
        assertEquals(
            AetherFmt.Problem.INVALID_FRAGMENT,
            AetherFmt.normalize(used.copy(aetherProtocol = AetherProtocol.MIM.type, aetherFragmentDelay = "5000"))
        )
        assertNull(AetherFmt.normalize(used.copy(aetherProtocol = AetherProtocol.WIREGUARD.type, aetherFragmentDelay = "5000")))
    }

    @Test
    fun bothGoolHopsSurviveTheRoundTrip() {
        val original = profile {
            remarks = "Gool"
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:894"
        }

        val parsed = AetherFmt.parse(link(original))

        assertEquals("gool", parsed?.aetherProtocol)
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700:d0::a29f:c001]:894", parsed?.aetherWiwInner)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
    }

    @Test
    fun aNodeLeftToTheScannerCarriesNoEndpoint() {
        val uri = link(profile {})

        assertTrue(uri.startsWith("aether://?"), "uri should hold only a query: $uri")

        val parsed = AetherFmt.parse(uri)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
    }

    @Test
    fun anIpv6EndpointSurvivesTheRoundTrip() {
        val uri = link(profile {
            server = "2606:4700:d0::a29f:c001"
            serverPort = "443"
        })
        assertTrue(uri.startsWith("aether://[2606:4700:d0::a29f:c001]:443?"), "uri should bracket the address: $uri")

        val parsed = AetherFmt.parse(uri)
        assertEquals("2606:4700:d0::a29f:c001", parsed?.server)
        assertEquals("443", parsed?.serverPort)
    }

    @Test
    fun bothMimHopsAndTheTransportSurviveTheRoundTrip() {
        val original = profile {
            remarks = "Mim"
            aetherProtocol = AetherProtocol.MIM.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherWiwOuter = "162.159.192.1:443"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:443"
        }

        val parsed = AetherFmt.parse(link(original))

        assertEquals("mim", parsed?.aetherProtocol)
        assertEquals("h2", parsed?.aetherTransport)
        assertEquals(true, parsed?.aetherFragment)
        assertEquals("162.159.192.1:443", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700:d0::a29f:c001]:443", parsed?.aetherWiwInner)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
    }

    @Test
    fun theHopsOnlyRideAlongWithATwoHopProtocol() {
        val uri = link(profile {
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "188.114.96.1:894"
        })

        assertFalse(uri.contains("outer="))
        assertFalse(uri.contains("inner="))
    }

    @Test
    fun theEndpointNeverRidesAlongWithGool() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
        })

        assertTrue(uri.startsWith("aether://?"), "uri should hold only a query: $uri")
        assertNull(AetherFmt.parse("aether://162.159.198.1:443?protocol=gool#X")?.server)
    }

    @Test
    fun theTransportOnlyRidesAlongOverMasque() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
        })

        assertFalse(uri.contains("transport="))
        assertFalse(uri.contains("fragment="))
        assertEquals("wg", AetherFmt.parse(uri)?.aetherProtocol)

        assertFalse(link(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherTransport = AetherTransport.HTTP2.type }).contains("transport="))
        assertTrue(link(profile { aetherProtocol = AetherProtocol.MIM.type; aetherTransport = AetherTransport.HTTP2.type }).contains("transport=h2"))
    }

    @Test
    fun aLinkWithoutSettingsFallsBackToTheDefaults() {
        val parsed = AetherFmt.parse("aether://#Shared")

        assertEquals("Shared", parsed?.remarks)
        assertEquals("wg", parsed?.aetherProtocol)
        assertEquals("h3", parsed?.aetherTransport)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("auto", parsed?.aetherObfuscation)
        assertEquals("v4", parsed?.aetherIpVersion)
        assertEquals(false, parsed?.aetherFragment)
    }

    @Test
    fun aNamelessLinkStillGetsALabel() {
        assertEquals("Aether", AetherFmt.parse("aether://?protocol=wg")?.remarks)
    }

    @Test
    fun anUnknownSettingFallsBackInsteadOfFailing() {
        val parsed = AetherFmt.parse("aether://?protocol=quantum&scan=instant&ip=v9#X")

        assertEquals("wg", parsed?.aetherProtocol)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("v4", parsed?.aetherIpVersion)
    }

    @Test
    fun anEndpointTheCoreCannotUseIsDroppedFromALink() {
        val hostname = AetherFmt.parse("aether://engage.cloudflareclient.com:2408?protocol=wg#X")
        assertNull(hostname?.server)
        assertNull(hostname?.serverPort)

        val portless = AetherFmt.parse("aether://162.159.198.1?protocol=masque#X")
        assertNull(portless?.server)
        assertNull(portless?.serverPort)
    }

    @Test
    fun goolHopsFromALinkAreCheckedAndNormalized() {
        val parsed = AetherFmt.parse(
            "aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=%5B2606%3A4700%3A0%3A0%3A0%3A0%3A0%3A1%5D%3A894#X"
        )
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700::1]:894", parsed?.aetherWiwInner)

        val malformed = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1&inner=example.com%3A894#X")
        assertNull(malformed?.aetherWiwOuter)
        assertNull(malformed?.aetherWiwInner)

        val shared = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=162.159.192.1%3A894#X")
        assertEquals("162.159.192.1:2408", shared?.aetherWiwOuter)
        assertNull(shared?.aetherWiwInner)
    }

    @Test
    fun wireGuardOverMasqueGoesThroughALinkWithItsGatewayItsWireGuardEndpointAndItsMasqueSettings() {
        val config = profile {
            aetherProtocol = AetherProtocol.WG_OVER_MASQUE.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherFragmentSize = "8-16"
            aetherEch = true
            aetherFingerprint = "firefox"
            aetherWiwOuter = "162.159.192.1:443"
            // The same address on both hops is no problem for the core here.
            aetherWiwInner = "162.159.192.1:2408"
        }
        assertNull(AetherFmt.normalize(config))
        assertEquals("162.159.192.1:443", config.aetherWiwOuter)
        assertEquals("162.159.192.1:2408", config.aetherWiwInner)

        val text = link(config)
        assertTrue(text.contains("protocol=wg-over-masque"), text)
        val parsed = AetherFmt.parse(text)
        assertEquals(AetherProtocol.WG_OVER_MASQUE.type, parsed?.aetherProtocol)
        assertEquals("162.159.192.1:443", parsed?.aetherWiwOuter)
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwInner)
        assertEquals(AetherTransport.HTTP2.type, parsed?.aetherTransport)
        assertEquals(true, parsed?.aetherFragment)
        assertEquals("8-16", parsed?.aetherFragmentSize)
        assertEquals(true, parsed?.aetherEch)
        assertEquals("firefox", parsed?.aetherFingerprint)
        assertEquals(text, link(parsed!!))
    }

    @Test
    fun anEmptyEndpointMeansScanning() {
        val config = profile {
            server = "  "
            serverPort = "443"
            aetherWiwOuter = "162.159.192.1:2408"
        }

        assertNull(AetherFmt.normalize(config))
        assertNull(config.server)
        assertNull(config.serverPort)
        assertNull(config.aetherWiwOuter)
    }

    @Test
    fun aPinnedEndpointIsNormalizedBeforeItIsSaved() {
        val config = profile {
            server = " [2606:4700:0:0:0:0:0:1] "
            serverPort = " 0443 "
        }

        assertNull(AetherFmt.normalize(config))
        assertEquals("2606:4700::1", config.server)
        assertEquals("443", config.serverPort)
    }

    @Test
    fun aPinnedEndpointTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "engage.cloudflareclient.com"; serverPort = "2408" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "162.159.198.1"; serverPort = "" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "162.159.198.1"; serverPort = "70000" })
        )
    }

    @Test
    fun goolHopsAreNormalizedAndTheEndpointIsCleared() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
            aetherWiwOuter = " 162.159.192.1:2408 "
            aetherWiwInner = ""
        }

        assertNull(AetherFmt.normalize(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertNull(config.aetherWiwInner)
        assertNull(config.server)
        assertNull(config.serverPort)
    }

    @Test
    fun aGoolHopTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.Problem.INVALID_HOP,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwOuter = "162.159.192.1" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_HOP,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwInner = "2606:4700::1:894" })
        )
    }

    @Test
    fun mimHopsAreCheckedLikeGoolHops() {
        val config = profile {
            aetherProtocol = AetherProtocol.MIM.type
            server = "162.159.198.1"
            serverPort = "443"
            aetherWiwOuter = " 162.159.192.1:443 "
            aetherWiwInner = ""
        }

        assertNull(AetherFmt.normalize(config))
        assertEquals("162.159.192.1:443", config.aetherWiwOuter)
        assertNull(config.aetherWiwInner)
        assertNull(config.server)
        assertNull(config.serverPort)

        assertEquals(
            AetherFmt.Problem.INVALID_HOP,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.MIM.type; aetherWiwOuter = "162.159.192.1" })
        )
        assertEquals(
            AetherFmt.Problem.SHARED_HOP,
            AetherFmt.normalize(profile {
                aetherProtocol = AetherProtocol.MIM.type
                aetherWiwOuter = "162.159.192.1:443"
                aetherWiwInner = "162.159.192.1:2408"
            })
        )
    }

    @Test
    fun goolHopsMustLeaveThroughDifferentAddresses() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "162.159.192.1:894"
        }

        assertEquals(AetherFmt.Problem.SHARED_HOP, AetherFmt.normalize(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertEquals("162.159.192.1:894", config.aetherWiwInner)
    }

    @Test
    fun aLinkCarriesNoExitNodeProfile() {
        // The name is that of a profile of this device; a link elsewhere would name another there, or none.
        val noded = profile { aetherExitNode = "germany" }
        val text = link(noded)
        assertFalse(text.contains("germany"), text)
        assertEquals(link(profile { }), text)
        assertNull(AetherFmt.parse(text)?.aetherExitNode)
    }

    @Test
    fun aListenPortALinkStillNamesCountsNoMore() {
        // Links from before every core listened on the Aether listen port of the settings may name a port of their own.
        val plain = link(profile { })
        val old = plain.replace("?", "?listen=20808&")
        val parsed = AetherFmt.parse(old)
        assertNotNull(parsed)
        assertNull(parsed?.aetherListenPort)
        assertEquals(AetherCoreManager.socksPort, AetherCore.of(parsed!!).port)
        // Otherwise it reads as it did, and a profile that carries such a port gives none to its link.
        assertEquals(plain, link(parsed))
        assertFalse(link(profile { aetherListenPort = "20808" }).contains("listen="))
    }

    @Test
    fun aListenPortAProfileStillCarriesIsNeitherCheckedNorChanged() {
        for (stored in listOf("20808", "10808", "0", "socks")) {
            val legacy = profile { aetherListenPort = stored }
            assertNull(AetherFmt.normalize(legacy, setOf(10808, 10809)))
            assertEquals(stored, legacy.aetherListenPort)
        }
    }

    @Test
    fun theAetherListenPortCannotBeAPortTheLocalProxyListensOn() {
        val localProxy = setOf(10808, 10809)
        assertNull(AetherFmt.normalize(profile { }, localProxy))
        onListenPort(10808) {
            assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { }, localProxy))
        }
        onListenPort(10807) {
            assertNull(AetherFmt.normalize(profile { }, localProxy))
            // Tor inside the tunnel takes the port after it.
            assertEquals(AetherFmt.Problem.NEXT_PORT_TAKEN, AetherFmt.normalize(profile { aetherTor = "chain" }, localProxy))
        }
    }

    @Test
    fun theAetherListenPortIsTakenOnceTheLocalProxyWasMovedOntoIt() {
        val movedOntoIt = setOf(10819)
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { }, movedOntoIt))
        // A command written by hand listens where it says.
        assertNull(AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808" }, movedOntoIt))
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:10819" }, movedOntoIt)
        )
        // So does an HTTP proxy it opens.
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:10819" }, movedOntoIt)
        )
    }

    @Test
    fun theMessageOfATakenPortNamesThatPort() {
        // A profile built from its settings listens on the Aether listen port.
        assertEquals(10819, AetherFmt.takenListenPort(profile { }, setOf(10808, 10819)))
        assertEquals(20808, AetherFmt.takenListenPort(profile { }, setOf(20808), listenPort = 20808))
        // A command written by hand: the first of its ports that is taken, its HTTP proxy's too; a blank one is none.
        val command = profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:10808" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(command, setOf(10808)))
        assertEquals(10808, AetherFmt.takenListenPort(command, setOf(10808)))
        assertEquals(10819, AetherFmt.takenListenPort(profile { aetherCommand = "   " }, setOf(10819)))
        // Nothing taken: no port to name.
        assertNull(AetherFmt.takenListenPort(profile { }, setOf(10808)))
        assertNull(AetherFmt.takenListenPort(command, setOf(10809)))
        assertNull(AetherFmt.takenListenPort(command, emptySet()))
    }

    @Test
    fun theDefaultPortOfAListenerACommandLeavesOutCanBeTaken() {
        // Tor inside the tunnel is named; the core's own listener, left out, listens on its default, 1819.
        val torInside = profile { aetherCommand = "aether --wg --tor --tor-bind 127.0.0.1:10819" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(torInside, setOf(1819)))
        assertEquals(1819, AetherFmt.takenListenPort(torInside, setOf(1819)))
        assertNull(AetherFmt.normalize(torInside, setOf(1820)))
    }

    @Test
    fun aCommandWhoseListenersShareAPortIsRefused() {
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_SHARED,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:20808" })
        )
        // A command naming no listener gets one on the Aether listen port, which its HTTP proxy then shares.
        onListenPort(20808) {
            assertEquals(AetherFmt.Problem.LISTEN_PORT_SHARED, AetherFmt.normalize(profile { aetherCommand = "aether --wg --http-proxy 127.0.0.1:20808" }))
        }
        assertNull(AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:20809" }))
    }

    @Test
    fun theMessageOfASharedPortNamesIt() {
        val shared = profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:20808" }
        assertEquals(20808, AetherFmt.sharedListenPort(shared))
        // The core's own listener, left out, listens on its default, which the command writes only for Tor's HTTP proxy.
        val onDefault = profile { aetherCommand = "aether --wg --tor --tor-bind 127.0.0.1:10819 --tor-http 127.0.0.1:1819" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_SHARED, AetherFmt.normalize(onDefault))
        assertEquals(1819, AetherFmt.sharedListenPort(onDefault))
        assertNull(AetherFmt.sharedListenPort(profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:20809" }))
        assertNull(AetherFmt.sharedListenPort(profile { }))
    }

    @Test
    fun aCommandListeningWhereTheCoreCannotIsRefusedAsAStartRefusesIt() {
        // A number that is no port comes first, then an address the core does not take, then ports two listeners share.
        val noPort = profile { aetherCommand = "aether --wg --bind localhost:70000 --http-proxy 127.0.0.1:70000" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_INVALID, AetherFmt.normalize(noPort))
        assertEquals(70000, AetherFmt.invalidListenPort(noPort))
        val noAddress = profile { aetherCommand = "aether --wg --bind localhost:20808 --http-proxy 127.0.0.1:20808" }
        assertEquals(AetherFmt.Problem.LISTEN_ADDRESS_INVALID, AetherFmt.normalize(noAddress, takenPorts = setOf(20808)))
        assertEquals("--bind", AetherFmt.invalidListener(noAddress))
        assertNull(AetherFmt.invalidListenPort(noAddress))
        // A listener the app does not dial, on port 0, takes any port, which the core can bind.
        val anyPort = profile { aetherCommand = "aether --wg --bind 127.0.0.1:20808 --http-proxy 127.0.0.1:0" }
        assertNull(AetherFmt.normalize(anyPort))
        assertNull(AetherFmt.invalidListenPort(anyPort))
        assertNull(AetherFmt.invalidListener(anyPort))
        // An address the core takes, where the app does not reach the listener it dials, comes before a shared port, and
        // before a taken one.
        val unreachable = profile { aetherCommand = "aether --wg --bind 192.168.1.5:20808 --http-proxy 127.0.0.1:20808" }
        assertEquals(AetherFmt.Problem.LISTEN_ADDRESS_UNREACHABLE, AetherFmt.normalize(unreachable, takenPorts = setOf(20808)))
        assertEquals("--bind", AetherFmt.unreachableListener(unreachable))
        assertNull(AetherFmt.unreachableListener(anyPort))
        assertNull(AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 0.0.0.0:20808" }))
        // The app's own listener on port 0 is no port it can dial.
        assertEquals(AetherFmt.Problem.LISTEN_PORT_INVALID, AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:0" }))
        // Nothing to name for a profile built from its settings, nor for a blank command.
        assertNull(AetherFmt.invalidListenPort(profile { }))
        assertNull(AetherFmt.invalidListener(profile { aetherCommand = "   " }))
    }

    @Test
    fun withoutKnownLocalPortsNoPortIsTaken() {
        // The local proxy port is picked at random on every start, or the caller has none to name.
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }))
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }, emptySet()))
    }

    @Test
    fun aScreenChecksAProfileOnTheListenPortItHolds() {
        // The setting says 10819; the screen holds 20808, which is what the checks go by.
        onListenPort(10819) {
            assertEquals(
                AetherFmt.Problem.LISTEN_PORT_TAKEN,
                AetherFmt.normalize(profile { }, takenPorts = setOf(20808), listenPort = 20808)
            )
            assertNull(AetherFmt.normalize(profile { }, takenPorts = setOf(10819), listenPort = 20808))
            assertEquals(
                AetherFmt.Problem.NEXT_PORT_TAKEN,
                AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(20809), listenPort = 20808)
            )
            // A command naming no listener gets one on that port as well.
            assertEquals(
                AetherFmt.Problem.LISTEN_PORT_TAKEN,
                AetherFmt.normalize(profile { aetherCommand = "aether --wg" }, takenPorts = setOf(20808), listenPort = 20808)
            )
            assertNull(AetherFmt.normalize(profile { aetherCommand = "aether --wg" }, takenPorts = setOf(10819), listenPort = 20808))
        }
    }
}
