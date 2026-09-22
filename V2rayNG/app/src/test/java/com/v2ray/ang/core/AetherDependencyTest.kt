package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherDependencyTest {

    private val masque = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp"; aetherProtocol = AetherProtocol.MASQUE.type }
    private val masqueCopy = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp again"; aetherProtocol = AetherProtocol.MASQUE.type }
    private val wireguard = ProfileItem.create(EConfigType.AETHER).apply { remarks = "wg"; aetherProtocol = AetherProtocol.WIREGUARD.type }
    private val vless = ProfileItem.create(EConfigType.VLESS).apply { remarks = "vless"; server = "1.2.3.4"; serverPort = "443" }
    private val trojan = ProfileItem.create(EConfigType.TROJAN).apply { remarks = "trojan"; server = "5.6.7.8"; serverPort = "443" }

    private fun outbound(tag: String, type: CoreResolvedType, vararg profiles: ProfileItem) =
        CoreConfigContext.ResolvedOutbound(tag, profiles.first(), profiles.toList(), type)

    @Test
    fun aConfigurationWithoutAetherNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless))))
        assertEquals(AetherDependency.None, AetherDependency.of(emptyList()))
    }

    @Test
    fun theSelectedAetherProfileIsTheDependency() {
        assertEquals(AetherDependency.Single(masque), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque))))
    }

    @Test
    fun aChainMayHaveAetherAsItsEntryHopOnly() {
        // Chain profiles are stored exit first, entry last.
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque)))
        )
        assertEquals(
            AetherDependency.NotEntryHop("proxy"),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, masque, vless)))
        )
        assertEquals(
            AetherDependency.NotEntryHop("proxy"),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque, trojan)))
        )
    }

    @Test
    fun aRoutingTargetOrAGroupMemberMayBeAetherAnywhere() {
        assertEquals(
            AetherDependency.Single(wireguard),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless), outbound("warp", CoreResolvedType.NORMAL, wireguard)))
        )
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.POLICYGROUP, vless, masque, trojan)))
        )
    }

    @Test
    fun theSameSettingsUnderTwoNamesAreOneDependency() {
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(
                listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque), outbound("warp", CoreResolvedType.NORMAL, masqueCopy))
            )
        )
    }

    @Test
    fun theSameTunnelBehindTwoListenPortsNeedsTwoCores() {
        val elsewhere = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "warp on 20808"; aetherProtocol = AetherProtocol.MASQUE.type; aetherListenPort = "20808"
        }
        assertEquals(AetherDependency.Single(elsewhere), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, elsewhere))))
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque), outbound("warp", CoreResolvedType.NORMAL, elsewhere)))
        )
    }

    @Test
    fun twoDifferentAetherProfilesCannotShareOneCore() {
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque), outbound("warp", CoreResolvedType.NORMAL, wireguard)))
        )
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.POLICYGROUP, masque, wireguard)))
        )
    }

    private fun custom(vararg outbounds: String, command: String? = null): JsonObject {
        val top = command?.let { """"aetherCommand": "$it", """ } ?: ""
        return JsonParser.parseString("""{$top"inbounds": [], "outbounds": [${outbounds.joinToString(",")}], "routing": {}}""").asJsonObject
    }

    private fun aetherOutbound(tag: String, port: Any? = 10819, address: String = "127.0.0.1") =
        """{"tag": "$tag", "protocol": "socks", "settings": {"address": "$address", "port": $port}}"""

    private val warpScan = "aether --bind 127.0.0.1:20808 --protocol wg --scan balanced"

    private val freedom = """{"tag": "direct", "protocol": "freedom"}"""
    private val plainSocks = """{"tag": "local", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 1080}}"""

    @Test
    fun aCustomConfigurationWithoutAnAetherCommandNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(freedom, plainSocks)))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("{}").asJsonObject))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(freedom, command = "")))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(freedom, command = "   ")))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("""{"aetherCommand": null}""").asJsonObject))
        // A command that is no string starts nothing, as a key that was never written.
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("""{"aetherCommand": true}""").asJsonObject))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("""{"aetherCommand": {}}""").asJsonObject))
    }

    @Test
    fun theCommandOfACustomConfigurationIsRunAsItIsWritten() {
        val dependency = AetherDependency.ofCustom(
            custom(freedom, aetherOutbound("proxy", port = 20808), command = warpScan)
        ) as AetherDependency.Custom

        assertEquals(
            listOf("--bind", "127.0.0.1:20808", "--protocol", "wg", "--scan", "balanced"),
            dependency.command
        )
        assertEquals(20808, dependency.port)
    }

    @Test
    fun theCommandBindsThePortItsOutboundDials() {
        // The core listens where the command says, and only a SOCKS outbound to that port reaches it.
        val command = AetherDependency.ofCustom(
            custom(plainSocks, aetherOutbound("warp", port = 41234), command = "aether --bind 127.0.0.1:41234 --protocol gool --wiw-scan")
        ) as AetherDependency.Custom
        assertEquals(41234, command.port)

        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom(aetherOutbound("warp", port = 20808), command = "aether --bind 127.0.0.1:41234 --protocol wg"))
        )
        // Any number of other outbounds may dial the same core port.
        val shared = AetherDependency.ofCustom(
            custom(
                aetherOutbound("proxy", port = 20808),
                """{"tag": "same-core", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 20808}}""",
                freedom,
                command = "aether --bind 127.0.0.1:20808 --protocol wg"
            )
        )
        assertTrue(shared is AetherDependency.Custom)
    }

    @Test
    fun theCommandOfACustomConfigurationHasToBindAPortAndItsOutboundHasToDialTheCore() {
        val socks = aetherOutbound("proxy", port = 20808)
        // No listener in the command, or one the port cannot be read from.
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(socks, command = "aether --protocol wg")))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(socks, command = "aether --bind")))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(socks, command = "aether --bind 127.0.0.1:0 --protocol wg")))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(socks, command = "aether --bind 20808 --protocol wg")))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(socks, command = "aether --bind 127.0.0.1:65536 --protocol wg")))
        // No outbound dials the bound port on the loopback.
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(freedom, command = warpScan)))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(plainSocks, command = warpScan)))
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = 20808, address = "10.0.0.2"), command = warpScan))
        )
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = 20808, address = "localhost"), command = warpScan))
        )
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = "\"20808\""), command = warpScan))
        )
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom("""{"tag": "bare", "protocol": "socks"}""", command = warpScan))
        )
        // Only a SOCKS outbound reaches the core, so a dial from anywhere else counts for nothing.
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(
                custom("""{"protocol": "http", "settings": {"address": "127.0.0.1", "port": 20808}}""", command = warpScan)
            )
        )
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(
                custom("""{"protocol": "vless", "settings": {"address": "127.0.0.1", "port": 20808}}""", command = warpScan)
            )
        )
    }

    @Test
    fun theFullConfigurationOfAProfileAsksForTheSameCore() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = "h2"
            aetherFragment = true
            aetherFragmentSize = "16-32"
            server = "162.159.198.1"
            serverPort = "443"
            aetherListenPort = "20808"
        }

        // What the app writes when the profile's full configuration is exported.
        val command = AetherFmt.toCommand(profile)
        val reimported = AetherDependency.ofCustom(
            custom(aetherOutbound("proxy", port = 20808), freedom, command = command)
        ) as AetherDependency.Custom

        assertEquals(
            AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile)),
            reimported.command
        )
        assertEquals(20808, reimported.port)
    }

    @Test
    fun aTestTunnelTakesOverEveryOutboundDialingTheCore() {
        val config = custom(
            aetherOutbound("proxy", port = 20808),
            """{"tag": "same-core", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 20808}}""",
            plainSocks,
            """{"tag": "remote", "protocol": "socks", "settings": {"address": "10.0.0.2", "port": 20808}}""",
            """{"tag": "vless", "protocol": "vless", "settings": {"address": "127.0.0.1", "port": 20808}}""",
            command = warpScan,
        )

        AetherDependency.rebindCustom(config, from = 20808, port = 41234)

        val ports = config.getAsJsonArray("outbounds").map { it.asJsonObject.getAsJsonObject("settings").get("port").asInt }
        assertEquals(listOf(41234, 41234, 1080, 20808, 20808), ports)
        // The command's listener is moved by the caller with the same port, and still read from it.
        assertEquals("127.0.0.1:41234", CoreConfigManager.rebindCommand(AetherCoreManager.splitCommand(warpScan), 41234)[1])
    }

    private fun withInbounds(vararg inbounds: String) = """{"inbounds": [${inbounds.joinToString(",")}], "outbounds": []}"""

    @Test
    fun anInboundOnTheAetherPortIsFoundBeforeTheCoreIsStarted() {
        // What the app builds: the local proxy with a port, and a tun inbound without one.
        val built = withInbounds(
            """{"tag": "socks", "protocol": "socks", "listen": "127.0.0.1", "port": 10808}""",
            """{"tag": "tun", "protocol": "tun", "settings": {"mtu": 1500}}""",
        )
        assertTrue(AetherDependency.inboundListensOn(built, 10808))
        assertFalse(AetherDependency.inboundListensOn(built, 10819))

        // The local proxy moved onto the default Aether port, or picked there at random.
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": 10819}"""), 10819))
    }

    @Test
    fun theInboundPortsOfACustomConfigurationAreReadInEveryFormXrayReads() {
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "20808"}"""), 20808))
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "dokodemo-door", "port": "20000-21000"}"""), 20808))
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "http", "port": "53, 443 ,20800-20810"}"""), 20808))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "http", "port": "53,443,20800-20807"}"""), 20808))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "20000-21000"}"""), 21001))
        // A port taken from the environment is not known here, and it is not a reason to refuse the start.
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "env:PORT"}"""), 20808))
    }

    @Test
    fun aConfigurationWithoutReadableInboundsCollidesWithNothing() {
        assertFalse(AetherDependency.inboundListensOn("""{"outbounds": []}""", 10819))
        assertFalse(AetherDependency.inboundListensOn("""{"inbounds": {"port": 10819}}""", 10819))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("10819", """{"port": null}""", """{"port": [10819]}""", """{"port": true}"""), 10819))
        assertFalse(AetherDependency.inboundListensOn("", 10819))
        assertFalse(AetherDependency.inboundListensOn("not json {", 10819))
        assertFalse(AetherDependency.inboundListensOn("[]", 10819))
    }
}
