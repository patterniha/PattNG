package com.v2ray.ang.fmt

import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun aPinnedMasqueNodeSurvivesTheRoundTrip() {
        val original = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.STEALTH.type
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
        assertEquals("stealth", parsed?.aetherScanMode)
        assertEquals("aggressive", parsed?.aetherObfuscation)
        assertEquals("both", parsed?.aetherIpVersion)
        assertEquals(true, parsed?.aetherFragment)
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

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))

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
        assertTrue("uri should bracket the address: $uri", uri.startsWith("aether://[2606:4700:d0::a29f:c001]:443?"))

        val parsed = AetherFmt.parse(uri)
        assertEquals("2606:4700:d0::a29f:c001", parsed?.server)
        assertEquals("443", parsed?.serverPort)
    }

    @Test
    fun theHopsOnlyRideAlongWithGool() {
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

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))
        assertNull(AetherFmt.parse("aether://162.159.198.1:443?protocol=gool#X")?.server)
    }

    @Test
    fun theTransportOnlyRidesAlongWithMasque() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
        })

        assertFalse(uri.contains("transport="))
        assertFalse(uri.contains("fragment="))
        assertEquals("wg", AetherFmt.parse(uri)?.aetherProtocol)
    }

    @Test
    fun aLinkWithoutSettingsFallsBackToTheDefaults() {
        val parsed = AetherFmt.parse("aether://#Shared")

        assertEquals("Shared", parsed?.remarks)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h3", parsed?.aetherTransport)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("balanced", parsed?.aetherObfuscation)
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

        assertEquals("masque", parsed?.aetherProtocol)
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
    fun aChosenListenPortSurvivesTheRoundTripAndTheDefaultStaysOutOfTheLink() {
        val chosen = profile { aetherListenPort = "20808" }
        assertTrue(link(chosen).contains("listen=20808"))
        assertEquals("20808", AetherFmt.parse(link(chosen))?.aetherListenPort)

        assertFalse(link(profile { }).contains("listen="))
        assertFalse(link(profile { aetherListenPort = "10819" }).contains("listen="))
        assertNull(AetherFmt.parse(link(profile { }))?.aetherListenPort)
        // A link falls back to the default for a port that is none, as it does for its other settings.
        assertNull(AetherFmt.parse(link(profile { }).replace("?", "?listen=70000&"))?.aetherListenPort)
        assertNull(AetherFmt.parse(link(profile { }).replace("?", "?listen=10819&"))?.aetherListenPort)
    }

    @Test
    fun theListenPortIsCheckedAndStoredOnlyWhenItIsNotTheDefault() {
        val chosen = profile { aetherListenPort = " 020808 " }
        assertNull(AetherFmt.normalize(chosen))
        assertEquals("20808", chosen.aetherListenPort)

        val default = profile { aetherListenPort = "10819" }
        assertNull(AetherFmt.normalize(default))
        assertNull(default.aetherListenPort)

        val blank = profile { aetherListenPort = "  " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherListenPort)

        for (invalid in listOf("0", "65536", "-1", "socks", "10819.5")) {
            assertEquals(AetherFmt.Problem.INVALID_LISTEN_PORT, AetherFmt.normalize(profile { aetherListenPort = invalid }))
        }
    }

    @Test
    fun theListenPortCannotBeAPortTheLocalProxyListensOn() {
        val localProxy = setOf(10808, 10809)

        val onSocks = profile { aetherListenPort = "10808" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(onSocks, localProxy))
        // A refused profile keeps what was typed, for the editor to show again.
        assertEquals("10808", onSocks.aetherListenPort)
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = " 10809 " }, localProxy))

        val free = profile { aetherListenPort = "20808" }
        assertNull(AetherFmt.normalize(free, localProxy))
        assertEquals("20808", free.aetherListenPort)
        assertNull(AetherFmt.normalize(profile { }, localProxy))
    }

    @Test
    fun theDefaultListenPortIsTakenOnceTheLocalProxyWasMovedOntoIt() {
        val movedOntoIt = setOf(10819)

        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { }, movedOntoIt))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = "" }, movedOntoIt))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = "10819" }, movedOntoIt))
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "20808" }, movedOntoIt))
    }

    @Test
    fun withoutKnownLocalPortsOnlyTheListenPortItselfIsChecked() {
        // The local proxy port is picked at random on every start, or the caller has none to name.
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "10808" }))
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "10808" }, emptySet()))
        // What is no port at all is reported as that, whatever is taken.
        assertEquals(AetherFmt.Problem.INVALID_LISTEN_PORT, AetherFmt.normalize(profile { aetherListenPort = "0" }, setOf(10808)))
    }

    @Test
    fun theListenPortIsReadFromAProfile() {
        assertEquals(20808, AetherFmt.listenPortOf("20808"))
        assertEquals(1, AetherFmt.listenPortOf(" 1 "))
        assertEquals(65535, AetherFmt.listenPortOf("65535"))
        assertNull(AetherFmt.listenPortOf(null))
        assertNull(AetherFmt.listenPortOf(""))
        assertNull(AetherFmt.listenPortOf("0"))
        assertNull(AetherFmt.listenPortOf("65536"))
        assertEquals("20808", AetherFmt.storedListenPort(20808))
        assertNull(AetherFmt.storedListenPort(10819))
    }

    /**
     * The aetherCommand of a profile, split back into arguments the way the daemon runs a
     * hand-written one: the arguments the daemon would build for the profile, the binary's name
     * in place of the binary.
     */
    private fun commandOf(config: ProfileItem): List<String> =
        AetherCoreManager.splitCommand(AetherFmt.toCommand(config))

    @Test
    fun theCommandOfAProfileStartsTheSameCore() {
        val pinned = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.STEALTH.type
            aetherObfuscation = AetherObfuscation.AGGRESSIVE.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherFragment = true
            aetherFragmentSize = "16-32"
            aetherFragmentDelay = "5"
        }
        val arguments = AetherCoreManager.buildArguments(pinned, AetherCoreManager.listenPort(pinned))

        assertEquals("aether ${arguments.joinToString(" ")}", AetherFmt.toCommand(pinned))
        assertEquals(listOf("aether") + arguments, commandOf(pinned))
        assertTrue(AetherCoreManager.runsProfile(arguments.drop(0), pinned))
    }

    @Test
    fun theCommandOfAGoolProfileKeepsBothHops() {
        val gool = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:894"
            aetherFragment = false
        }

        val command = AetherFmt.toCommand(gool)
        assertTrue("--wiw-outer 162.159.192.1:2408" in command)
        assertTrue("--wiw-inner [2606:4700:d0::a29f:c001]:894" in command)
        assertEquals(AetherCoreManager.buildArguments(gool, AetherCoreManager.listenPort(gool)), commandOf(gool).drop(1))
    }

    @Test
    fun theCommandBindsThePortTheProfileListensOn() {
        val defaultPort = profile { }
        assertEquals(AetherCoreManager.socksPort, AetherFmt.bindPortOfCommand(commandOf(defaultPort).drop(1)))

        val elsewhere = profile { aetherListenPort = "20808" }
        assertTrue("--bind 127.0.0.1:20808" in AetherFmt.toCommand(elsewhere))
        assertEquals(20808, AetherFmt.bindPortOfCommand(commandOf(elsewhere).drop(1)))
    }

    @Test
    fun theBindPortIsReadFromACommandTheWayTheCoreReadsIt() {
        assertEquals(10819, AetherFmt.bindPortOfCommand(listOf("--bind", "127.0.0.1:10819", "--protocol", "wg")))
        assertEquals(41234, AetherFmt.bindPortOfCommand(listOf("--protocol", "wg", "--bind", "127.0.0.1:41234")))
        assertNull(AetherFmt.bindPortOfCommand(listOf("--protocol", "wg")))
        assertNull(AetherFmt.bindPortOfCommand(listOf("--bind")))
        assertNull(AetherFmt.bindPortOfCommand(listOf("--bind", "10819")))
        assertNull(AetherFmt.bindPortOfCommand(listOf("--bind", "127.0.0.1:0")))
        assertNull(AetherFmt.bindPortOfCommand(listOf("--bind", "127.0.0.1:65536")))
        assertNull(AetherFmt.bindPortOfCommand(emptyList()))
    }

    @Test
    fun commandTextIsSplitOnWhitespaceWithQuoting() {
        assertEquals(
            listOf("--bind", "127.0.0.1:10819", "--protocol", "wg"),
            AetherCoreManager.splitCommand("--bind 127.0.0.1:10819 --protocol wg")
        )
        // Blank text, leading and repeated whitespace: no empty arguments.
        assertEquals(emptyList<String>(), AetherCoreManager.splitCommand(""))
        assertEquals(emptyList<String>(), AetherCoreManager.splitCommand("  \t \n"))
        assertEquals(listOf("a", "b"), AetherCoreManager.splitCommand("  a   b  "))
        // Quotes keep text together, empty quoted text is still an argument.
        assertEquals(listOf("--peer", "188.114.96.77:443"), AetherCoreManager.splitCommand("--peer \"188.114.96.77:443\""))
        assertEquals(listOf("--remark", "my node", "--scan"), AetherCoreManager.splitCommand("--remark \"my node\" --scan"))
        assertEquals(listOf("a b", "c"), AetherCoreManager.splitCommand("'a b' c"))
        assertEquals(listOf(""), AetherCoreManager.splitCommand("''"))
        // A backslash quotes the next character, inside and outside quotes, but not in single quotes.
        assertEquals(listOf("a b"), AetherCoreManager.splitCommand("a\\ b"))
        assertEquals(listOf("a\"b"), AetherCoreManager.splitCommand("\"a\\\"b\""))
        assertEquals(listOf("a\\b"), AetherCoreManager.splitCommand("'a\\b'"))
        // An unterminated quote is no error: the text simply runs to the end.
        assertEquals(listOf("a b"), AetherCoreManager.splitCommand("\"a b"))
    }
}
