package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * PattNG (PattN TcpUserTimeoutRuleTests): where the tcpUserTimeout of a profile counts, which is only where the
 * connection of its outbound is TCP as the fork core dials it, and how a link's or a field's text is read.
 */
class TcpUserTimeoutRuleTest {

    private data class Case(val type: EConfigType, val network: String?, val security: String?, val alpn: String?)

    @Test
    fun aTcpConnectionTakesIt() {
        listOf(
            Case(EConfigType.VMESS, "tcp", null, null),
            Case(EConfigType.VMESS, null, null, null),
            Case(EConfigType.VLESS, "ws", AppConfig.TLS, "h2"),
            // Only XHTTP dials QUIC for h3, and only with TLS; REALITY keeps it on HTTP/2.
            Case(EConfigType.VLESS, "grpc", AppConfig.TLS, "h3"),
            Case(EConfigType.VLESS, "httpupgrade", AppConfig.TLS, null),
            Case(EConfigType.VLESS, "h2", AppConfig.TLS, "h2"),
            Case(EConfigType.VLESS, "xhttp", AppConfig.TLS, "h2"),
            Case(EConfigType.VLESS, "xhttp", AppConfig.TLS, "h3,h2"),
            Case(EConfigType.VLESS, "xhttp", AppConfig.REALITY, "h3"),
            Case(EConfigType.VLESS, "xhttp", null, "h3"),
            Case(EConfigType.TROJAN, "tcp", AppConfig.TLS, null),
            Case(EConfigType.SHADOWSOCKS, "tcp", null, null),
            Case(EConfigType.SOCKS, null, null, null),
            Case(EConfigType.HTTP, "tcp", null, null),
            // Their outbound takes no transport of the profile's.
            Case(EConfigType.SOCKS, "kcp", null, null),
            Case(EConfigType.HTTP, "xhttp", AppConfig.TLS, "h3"),
            // MASQUE runs over HTTP/2 with the alpn h2, as the outbound trims it.
            Case(EConfigType.MASQUE, "masque", AppConfig.TLS, "h2"),
            Case(EConfigType.MASQUE, "masque", AppConfig.TLS, " h2 "),
        ).forEach { assertTrue(TcpUserTimeoutRule.applies(it.type, it.network, it.security, it.alpn), it.toString()) }
    }

    @Test
    fun aUdpConnectionOrATypeWithoutAnOutboundOfItsOwnDoesNot() {
        listOf(
            Case(EConfigType.WIREGUARD, null, null, null),
            Case(EConfigType.HYSTERIA2, "hysteria", AppConfig.TLS, "h3"),
            Case(EConfigType.MASQUE, "masque", AppConfig.TLS, "h3"),
            Case(EConfigType.MASQUE, "masque", AppConfig.TLS, null),
            Case(EConfigType.VLESS, "kcp", null, null),
            Case(EConfigType.VMESS, "kcp", AppConfig.TLS, "h2"),
            Case(EConfigType.VLESS, "xhttp", AppConfig.TLS, "h3"),
            // The outbound offers the alpn trimmed, so this is h3 alone too.
            Case(EConfigType.VLESS, "xhttp", AppConfig.TLS, " h3 "),
            // An Aether profile goes by its core instead (AetherCore.connectsOverTcp).
            Case(EConfigType.AETHER, null, null, null),
            Case(EConfigType.CUSTOM, null, null, null),
            Case(EConfigType.POLICYGROUP, null, null, null),
        ).forEach { assertFalse(TcpUserTimeoutRule.applies(it.type, it.network, it.security, it.alpn), it.toString()) }
    }

    @Test
    fun theValueIsTheProfilesWhereItCountsAndIsAboveZero() {
        val vless = ProfileItem.create(EConfigType.VLESS).apply {
            network = "tcp"
            tcpUserTimeout = 10000
        }
        assertEquals(10000, TcpUserTimeoutRule.valueOf(vless))

        vless.network = "kcp"
        assertNull(TcpUserTimeoutRule.valueOf(vless))
        vless.network = "tcp"
        for (none in listOf(0, -5, null)) {
            vless.tcpUserTimeout = none
            assertNull(TcpUserTimeoutRule.valueOf(vless), none.toString())
        }

        val wireguard = ProfileItem.create(EConfigType.WIREGUARD).apply { tcpUserTimeout = 10000 }
        assertNull(TcpUserTimeoutRule.valueOf(wireguard))
    }

    @Test
    fun parseTakesAWholeNumberAboveZeroAlone() {
        assertEquals(10000, TcpUserTimeoutRule.parse("10000"))
        assertEquals(2000, TcpUserTimeoutRule.parse(" 2000 "))
        assertEquals(20, TcpUserTimeoutRule.parse("+20"))
        assertEquals(1, TcpUserTimeoutRule.parse("1"))
        assertEquals(Int.MAX_VALUE, TcpUserTimeoutRule.parse("2147483647"))
        for (text in listOf(null, "", " ", "0", "-5", "1.5", "2s", "1 000", "abc", "2147483648")) {
            assertNull(TcpUserTimeoutRule.parse(text), text.toString())
        }
    }

    @Test
    fun anAetherCoreConnectsOverTcpThroughTorOrPsiphonOrMasqueOverHttp2() {
        fun over(
            protocol: AetherProtocol,
            transport: AetherTransport,
            tor: AetherTor = AetherTor.OFF,
            psiphon: AetherPsiphon = AetherPsiphon.OFF,
        ) = AetherCoreManager.connectsOverTcp(protocol, transport, tor, psiphon)

        // WARP alone: only a tunnel over MASQUE that runs over HTTP/2.
        assertTrue(over(AetherProtocol.MASQUE, AetherTransport.HTTP2))
        assertTrue(over(AetherProtocol.MIM, AetherTransport.HTTP2))
        assertTrue(over(AetherProtocol.WG_OVER_MASQUE, AetherTransport.HTTP2))
        assertFalse(over(AetherProtocol.MASQUE, AetherTransport.HTTP3))
        assertFalse(over(AetherProtocol.MIM, AetherTransport.HTTP3))
        // The transport is MASQUE's alone: WireGuard and WARP-in-WARP reach WARP over UDP whatever it says.
        assertFalse(over(AetherProtocol.WIREGUARD, AetherTransport.HTTP2))
        assertFalse(over(AetherProtocol.GOOL, AetherTransport.HTTP2))
        // Tor or Psiphon around the tunnel carry TCP alone, and the core takes HTTP/2 for it.
        assertTrue(over(AetherProtocol.MASQUE, AetherTransport.HTTP3, tor = AetherTor.REVERSE))
        assertTrue(over(AetherProtocol.MASQUE, AetherTransport.HTTP3, psiphon = AetherPsiphon.REVERSE))
        // Inside the tunnel they leave the tunnel's own connection as it is.
        assertFalse(over(AetherProtocol.MASQUE, AetherTransport.HTTP3, tor = AetherTor.CHAIN))
        assertFalse(over(AetherProtocol.WIREGUARD, AetherTransport.HTTP3, psiphon = AetherPsiphon.CHAIN))
        assertTrue(over(AetherProtocol.MASQUE, AetherTransport.HTTP2, tor = AetherTor.CHAIN))
        // Alone they are the whole of it.
        assertTrue(over(AetherProtocol.WIREGUARD, AetherTransport.HTTP3, tor = AetherTor.ONLY))
        assertTrue(over(AetherProtocol.WIREGUARD, AetherTransport.HTTP3, psiphon = AetherPsiphon.ONLY))
    }

    @Test
    fun theMasqueTransportOfACommandIsTheLastFlagAsTheCoreReadsIt() {
        assertEquals(AetherTransport.HTTP3, AetherCoreManager.masqueTransportOf(emptyList()))
        assertEquals(AetherTransport.HTTP2, AetherCoreManager.masqueTransportOf(listOf("--h2")))
        assertEquals(AetherTransport.HTTP2, AetherCoreManager.masqueTransportOf(listOf("--http2")))
        assertEquals(AetherTransport.HTTP3, AetherCoreManager.masqueTransportOf(listOf("--h2", "--quic")))
        assertEquals(AetherTransport.HTTP2, AetherCoreManager.masqueTransportOf(listOf("--h3", "--h2")))
        assertEquals(AetherTransport.HTTP3, AetherCoreManager.masqueTransportOf(listOf("--sni", "h2")))
    }

    @Test
    fun anAetherProfileGoesByTheCommandWrittenByHandInPlaceOfItsSettings() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.MASQUE.type
            tcpUserTimeout = 10000
        }
        assertFalse(AetherCore.connectsOverTcp(profile))
        assertNull(TcpUserTimeoutRule.valueOf(profile))

        profile.aetherTransport = AetherTransport.HTTP2.type
        assertTrue(AetherCore.connectsOverTcp(profile))
        assertEquals(10000, TcpUserTimeoutRule.valueOf(profile))

        // The command runs in place of the settings, as for leavesThroughWarp.
        profile.aetherCommand = "aether --protocol masque --h3"
        assertFalse(AetherCore.connectsOverTcp(profile))
        profile.aetherTransport = AetherTransport.HTTP3.type
        profile.aetherCommand = "aether --protocol masque --http2"
        assertTrue(AetherCore.connectsOverTcp(profile))
        profile.aetherCommand = "aether --wg --tor-only"
        assertTrue(AetherCore.connectsOverTcp(profile))
        profile.aetherCommand = "aether --wg"
        assertFalse(AetherCore.connectsOverTcp(profile))
    }

    @Test
    fun theExitNodeOfAnAetherProfileTakesItWhereItsCoreConnectsOverTcp() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = AetherTransport.HTTP2.type
            dialMode = "oob.0"
            tcpUserTimeout = 10000
        }
        assertEquals(AetherExit(dialMode = "oob.0", tcpUserTimeout = 10000), AetherExit.of(profile))

        profile.aetherTransport = AetherTransport.HTTP3.type
        assertEquals(AetherExit(dialMode = "oob.0"), AetherExit.of(profile))

        // A profile of its own as the exit-node: that profile's outbound, its own tcpUserTimeout included, is the exit.
        profile.aetherTransport = AetherTransport.HTTP2.type
        profile.aetherExitNode = "germany"
        assertEquals(AetherExit(node = "germany"), AetherExit.of(profile))
    }

    @Test
    fun theKeyOfAnExitNodeTellsItsTcpUserTimeoutAndStaysWhatItWasWithout() {
        val plain = AetherExit(finalMask = "{}", dialMode = "oob.0")
        val timed = plain.copy(tcpUserTimeout = 10000)

        assertNotEquals(plain.key, timed.key)
        assertNotEquals(timed.key, timed.copy(tcpUserTimeout = 9000).key)
        // The key of an exit-node without one is the one it had before tcpUserTimeout came, which the core of a session
        // started then carries in its environment.
        val before = MessageDigest.getInstance("SHA-256").digest("{}\u0000oob.0\u0000".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(before, plain.key)
    }
}
