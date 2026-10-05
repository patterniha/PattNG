package com.v2ray.ang.core

import android.content.Context
import android.content.res.AssetManager
import android.text.TextUtils
import android.util.Log
import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.FakeMmkv
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.File
import java.io.FileInputStream

/**
 * The configuration of a session as [CoreConfigManager.getV2rayConfig] builds it from the profiles and
 * settings in storage. The MMKV stores are those of [FakeMmkv], the assets are the app's own, and the
 * Android log goes nowhere.
 */
class CoreConfigBuildTest {

    private lateinit var mmkv: MockedStatic<MMKV>
    private lateinit var log: MockedStatic<Log>
    private lateinit var text: MockedStatic<TextUtils>

    private val context: Context = Mockito.mock(Context::class.java).also { context ->
        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(Mockito.anyString())).thenAnswer { FileInputStream(File(ASSETS, it.getArgument<String>(0))) }
        Mockito.`when`(context.assets).thenReturn(assets)
        Mockito.`when`(context.getString(R.string.config_main_outbound_missing)).thenReturn(MAIN_OUTBOUND_MISSING)
        Mockito.`when`(context.getString(R.string.aether_tor_needs_exit_node)).thenReturn(TOR_NEEDS_EXIT_NODE)
        Mockito.`when`(context.getString(R.string.aether_custom_tor_open_inbound)).thenReturn(CUSTOM_TOR_OPEN_INBOUND)
    }

    @BeforeEach
    fun emptyStorage() {
        FakeMmkv.clear()
        mmkv = FakeMmkv.open()
        log = Mockito.mockStatic(Log::class.java)
        text = Mockito.mockStatic(TextUtils::class.java) { call ->
            if (call.method.name == "isEmpty") (call.arguments[0] as CharSequence?).isNullOrEmpty() else Mockito.RETURNS_DEFAULTS.answer(call)
        }
    }

    @AfterEach
    fun release() {
        text.close()
        log.close()
        mmkv.close()
        FakeMmkv.clear()
    }

    private fun vless(remarks: String, server: String) = ProfileItem.create(EConfigType.VLESS).apply {
        this.remarks = remarks
        this.server = server
        serverPort = "443"
        password = "b831381d-6324-4d53-ad4f-8cda48b30811"
    }

    /**
     * [profile] in storage as MmkvManager keeps it, in the default subscription; its guid. Not through
     * MmkvManager.encodeServerConfig: that takes MMKV's lock, which is native.
     */
    private fun saved(profile: ProfileItem): String {
        val guid = Utils.getUuid()
        FakeMmkv.values.getOrPut("PROFILE_FULL_CONFIG") { mutableMapOf() }[guid] = JsonUtil.toJson(profile)
        val main = FakeMmkv.values.getOrPut("MAIN") { mutableMapOf() }
        val listKey = "SUB_SERVERS_${AppConfig.DEFAULT_SUBSCRIPTION_ID}"
        val guids = (main[listKey] as String?)?.let { JsonUtil.fromJson(it, Array<String>::class.java)?.toList() }.orEmpty()
        main[listKey] = JsonUtil.toJson(guids + guid)
        return guid
    }

    private fun outboundTags(content: String): List<String> =
        JsonUtil.parseString(content)!!.asJsonObject.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }

    @Test
    fun aMainServerThatCannotBeBuiltFailsTheConfigurationInsteadOfGoingDirect() {
        // A chain whose hops were renamed by a subscription update: none of them is found any more.
        val chain = saved(ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "chain"; proxyChainProfiles = "gone-1,gone-2" })

        val alone = CoreConfigManager.getV2rayConfig(context, chain)
        assertFalse(alone.status)
        assertTrue(alone.localizedError)
        assertEquals(MAIN_OUTBOUND_MISSING, alone.errorMessage)

        // A routing target that builds is no main server: Xray would still send the rest out by direct.
        saved(vless("us", "1.2.3.4"))
        MmkvManager.encodeRoutingRulesets(mutableListOf(RulesetItem(domain = listOf("domain:example.com"), outboundTag = "us")))
        val beside = CoreConfigManager.getV2rayConfig(context, chain)
        assertFalse(beside.status)
        assertEquals(MAIN_OUTBOUND_MISSING, beside.errorMessage)
    }

    @Test
    fun theReasonAMainServerCannotBeBuiltReachesTheMainScreen() {
        val chain = saved(ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "chain"; proxyChainProfiles = "gone" })

        // What the service starts with, and what it hands the UI with MSG_STATE_START_FAILURE.
        val failure = configFailure(CoreConfigManager.getV2rayConfig(context, chain))
        assertEquals(MAIN_OUTBOUND_MISSING, userFacingReason(failure))

        // A failure without a localized message reaches the UI without a reason, which then shows its generic one.
        val technical = configFailure(ConfigResult(status = false, guid = chain, errorMessage = "Failed to get V2ray config: boom"))
        assertEquals("", userFacingReason(technical))
    }

    private fun aether(block: ProfileItem.() -> Unit) = ProfileItem.create(EConfigType.AETHER).apply {
        remarks = "aether"
        aetherProtocol = AetherProtocol.WIREGUARD.type
        server = "188.114.96.77"
        serverPort = "443"
        block()
    }

    /** The secondary-socks inbounds of the configuration [content]. */
    private fun secondaryInbounds(content: String) = JsonUtil.parseString(content)!!.asJsonObject.getAsJsonArray("inbounds")
        .map { it.asJsonObject }.filter { it.get("tag")?.asString == AppConfig.TAG_SECONDARY_SOCKS }

    @Test
    fun anAetherSessionDialsOutThroughAnInboundOnlyItsCoreCanSignInTo() {
        val result = CoreConfigManager.getV2rayConfig(context, saved(aether {}))
        assertTrue(result.status, result.errorMessage)

        val settings = secondaryInbounds(result.content).single().getAsJsonObject("settings")
        assertEquals("password", settings.get("auth").asString)
        val account = settings.getAsJsonArray("accounts").single().asJsonObject
        val upstream = "socks5://${account.get("user").asString}:${account.get("pass").asString}@127.0.0.1:${AetherCoreManager.secondarySocksPort}"
        assertEquals(upstream, AetherCoreManager.upstreamOf(result.aetherCore!!.arguments))
    }

    @Test
    fun anAetherSessionWhoseTorWouldNeedAnInboundWithoutAPasswordIsRefused() {
        // The exit-node's finalMask is Xray's to apply, and Tor takes no password.
        val guid = saved(aether {
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTor = AetherTor.REVERSE.type
            finalMask = """{"tcp": [{"type": "fragment"}]}"""
        })

        val result = CoreConfigManager.getV2rayConfig(context, guid)
        assertFalse(result.status)
        assertEquals(TOR_NEEDS_EXIT_NODE, result.errorMessage)
        assertEquals(TOR_NEEDS_EXIT_NODE, userFacingReason(configFailure(result)))
    }

    @Test
    fun aCustomConfigurationWhoseTorWouldDialAnInboundWithoutAPasswordIsRefused() {
        // As a Tor session exported it before the inbound asked for an account.
        val raw = """
            {
              "aetherCommand": "aether --bind 127.0.0.1:10819 --tor-only --upstream socks5://127.0.0.1:10822",
              "inbounds": [{"tag": "secondary-socks", "port": 10822, "listen": "127.0.0.1", "protocol": "mixed", "settings": {"udp": true}}],
              "outbounds": [
                {"tag": "proxy", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 10819}},
                {"tag": "exit-node", "protocol": "freedom"}
              ],
              "routing": {"rules": [{"inboundTag": ["secondary-socks"], "outboundTag": "exit-node"}]}
            }
        """
        val guid = saved(ProfileItem.create(EConfigType.CUSTOM).apply { remarks = "exported" })
        FakeMmkv.values.getOrPut("SERVER_RAW") { mutableMapOf() }[guid] = raw

        val result = CoreConfigManager.getV2rayConfig(context, guid)
        assertFalse(result.status)
        assertEquals(CUSTOM_TOR_OPEN_INBOUND, result.errorMessage)
        assertEquals(CUSTOM_TOR_OPEN_INBOUND, userFacingReason(configFailure(result)))
    }

    @Test
    fun anAetherSessionWhoseTorDialsTheInternetItselfOpensNoInbound() {
        val result = CoreConfigManager.getV2rayConfig(context, saved(aether { aetherProtocol = AetherProtocol.MASQUE.type; aetherTor = AetherTor.REVERSE.type }))
        assertTrue(result.status, result.errorMessage)

        assertTrue(secondaryInbounds(result.content).isEmpty())
        assertFalse(result.aetherCore!!.hasUpstream)
    }

    /** The mux of the main server's outbound, with mux on and the two concurrency settings as written. */
    private fun builtMux(concurrency: String, xudpConcurrency: String): JsonObject {
        FakeMmkv.clear()
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, concurrency)
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_CONCURRENCY, xudpConcurrency)

        val result = CoreConfigManager.getV2rayConfig(context, saved(vless("main", "1.2.3.4")))
        assertTrue(result.status, "concurrency \"$concurrency\", xudp \"$xudpConcurrency\": ${result.errorMessage}")
        val proxy = JsonUtil.parseString(result.content)!!.asJsonObject.getAsJsonArray("outbounds").first().asJsonObject
        assertEquals(AppConfig.TAG_PROXY, proxy.get("tag").asString)
        return proxy.getAsJsonObject("mux")
    }

    @Test
    fun aMuxConcurrencyThatIsNoNumberFallsBackToTheDefault() {
        for (written in NO_NUMBERS) {
            assertEquals(8, builtMux(written, "16").get("concurrency").asInt, "concurrency \"$written\"")
        }
        assertEquals(4, builtMux("4", "16").get("concurrency").asInt)
    }

    @Test
    fun anXudpConcurrencyThatIsNoNumberFallsBackToTheDefault() {
        val default = AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY.toInt()
        for (written in NO_NUMBERS) {
            assertEquals(default, builtMux("4", written).get("xudpConcurrency").asInt, "xudp concurrency \"$written\"")
        }
        assertEquals(16, builtMux("4", "16").get("xudpConcurrency").asInt)
    }

    @Test
    fun aPolicyGroupIsTheMainServerByItsBalancer() {
        saved(vless("a", "1.2.3.4"))
        saved(vless("b", "5.6.7.8"))
        val group = saved(ProfileItem.create(EConfigType.POLICYGROUP).apply { remarks = "group" })

        val result = CoreConfigManager.getV2rayConfig(context, group)
        assertTrue(result.status, result.errorMessage)
        val config = JsonUtil.parseString(result.content)!!.asJsonObject
        assertFalse(AppConfig.TAG_PROXY in outboundTags(result.content))
        val balancers = config.getAsJsonObject("routing").getAsJsonArray("balancers").map { it.asJsonObject.get("tag").asString }
        assertEquals(listOf(AppConfig.TAG_BALANCER), balancers)
    }

    companion object {
        private val ASSETS = File("src/main/assets")

        /** What the context gives for the resource strings the failures carry. */
        private const val MAIN_OUTBOUND_MISSING = "the main server could not be built"
        private const val TOR_NEEDS_EXIT_NODE = "Tor cannot sign in to the proxy its exit-node needs"
        private const val CUSTOM_TOR_OPEN_INBOUND = "the custom configuration's Tor dials a proxy without a password"

        /** What the free-text concurrency settings may hold that is no Int: blank, malformed, overflowing. */
        private val NO_NUMBERS = listOf("", " ", "eight", "8.5", "99999999999")
    }
}
