package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.GEOIP_PRIVATE
import com.v2ray.ang.AppConfig.GEOSITE_PRIVATE
import com.v2ray.ang.AppConfig.TAG_DIRECT
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.PsiphonServerList
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.enums.VpnInterfaceAddressConfig
import com.v2ray.ang.handler.MmkvManager.decodeAllServerList
import com.v2ray.ang.handler.MmkvManager.decodeServerConfig
import com.v2ray.ang.handler.MmkvManager.decodeSubsList
import com.v2ray.ang.handler.MmkvManager.decodeSubscription
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

object SettingsManager {

    @Volatile
    private var runtimeSocksPort: Int? = null

    fun initApp(context: Context) {
        ensureDefaultSettings()
        //ensureDefaultSubscription()
        initRoutingRulesets(context)
        migrateServerListToSubscriptions()
        migrateHysteria2PinSHA256()
    }

    /**
     * Initialize routing rulesets.
     * @param context The application context.
     */
    private fun initRoutingRulesets(context: Context) {
        // PattNG: one change of the stored rules at a time, see changeRoutingRulesets.
        changeRoutingRulesets {
            val exist = MmkvManager.decodeRoutingRulesets()
            if (exist.isNullOrEmpty()) {
                val rulesetList = getPresetRoutingRulesets(context, RoutingType.WHITE_IRAN)
                MmkvManager.encodeRoutingRulesets(rulesetList)
            }
        }
    }

    /**
     * Get preset routing rulesets.
     * @param context The application context.
     * @param type The routing preset type.
     * @return A mutable list of RulesetItem.
     */
    private fun getPresetRoutingRulesets(context: Context, type: RoutingType = RoutingType.WHITE): MutableList<RulesetItem>? {
        val assets = Utils.readTextFromAssets(context, type.fileName)
        if (TextUtils.isEmpty(assets)) {
            return null
        }

        return JsonUtil.fromJsonSafe(assets, Array<RulesetItem>::class.java)?.toMutableList()
    }

    /**
     * Reset routing rulesets from presets.
     * @param context The application context.
     * @param type The routing preset type.
     */
    fun resetRoutingRulesetsFromPresets(context: Context, type: RoutingType): Boolean {
        val rulesetList = getPresetRoutingRulesets(context, type) ?: return false
        return resetRoutingRulesetsCommon(rulesetList)
    }

    /**
     * Reset routing rulesets.
     * @param content The content of the rulesets.
     * @return True if successful, false otherwise.
     */
    fun resetRoutingRulesets(content: String?): Boolean {
        if (content.isNullOrEmpty()) {
            return false
        }

        try {
            val rulesetList = JsonUtil.fromJsonSafe(content, Array<RulesetItem>::class.java)?.toMutableList()
            if (rulesetList.isNullOrEmpty()) {
                return false
            }

            return resetRoutingRulesetsCommon(rulesetList)
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "Failed to reset routing rulesets", e)
            return false
        }
    }

    /**
     * Common method to reset routing rulesets.
     * @param rulesetList The list of rulesets.
     * @return PattNG: whether the storage took them; a refusal is logged.
     */
    private fun resetRoutingRulesetsCommon(rulesetList: MutableList<RulesetItem>): Boolean {
        val stored = changeRoutingRulesets {
            MmkvManager.encodeRoutingRulesets(rulesetsAfterImport(MmkvManager.decodeRoutingRulesets(), rulesetList))
        }
        if (!stored) LogUtil.e(AppConfig.TAG, "SettingsManager: the storage refused the imported routing rulesets")
        return stored
    }

    /** PattNG: what every read, change and write of the stored routing rulesets holds, see [changeRoutingRulesets]. */
    private val routingRulesetsLock = Any()

    /**
     * PattNG: runs [change], a read of the stored routing rulesets, its change and its write, while no other runs, so
     * that none writes back a list another changed meanwhile: an import, the routing list, the editor of a rule.
     */
    internal fun <T> changeRoutingRulesets(change: () -> T): T = synchronized(routingRulesetsLock) { change() }

    /**
     * The rulesets an import of [imported] leaves: the locked ones of [stored] first, kept as they are, then [imported].
     * PattNG: but for the copy of a locked one, which an export of it brings back with its id: the routing list tells its
     * rules apart by their ids, and two with one id would make it fail to show. Each gets an id of its own besides, see
     * [rulesetsWithOwnIds], a locked one without an id as well.
     */
    internal fun rulesetsAfterImport(
        stored: List<RulesetItem>?,
        imported: List<RulesetItem>,
        newId: () -> String = ::newRulesetId,
    ): MutableList<RulesetItem> {
        val locked = stored.orEmpty().filter { it.locked == true }
        val lockedIds = locked.map { it.id }.filterTo(HashSet()) { it.isNotEmpty() }
        val rulesets = locked + imported.filter { it.id !in lockedIds }
        return rulesetsWithOwnIds(rulesets, newId) ?: rulesets.toMutableList()
    }

    /**
     * PattNG: [rulesets] with an id of their own each, which the routing list tells them apart by: one that repeats whole
     * a ruleset before it, the id it came with included, goes, as the copies of a locked one an import stored before it
     * was left out; one with the id of a ruleset before it and other content gets a new id from [newId], as does one
     * without an id. Null when each has its own already.
     */
    internal fun rulesetsWithOwnIds(
        rulesets: List<RulesetItem>,
        newId: () -> String = ::newRulesetId,
    ): MutableList<RulesetItem>? {
        // The rulesets kept so far of each id they came with, as they came.
        val keptWithId = HashMap<String, MutableList<RulesetItem>>()
        val result = ArrayList<RulesetItem>(rulesets.size)
        var changed = false
        for (ruleset in rulesets) {
            val kept = keptWithId.getOrPut(ruleset.id) { mutableListOf() }
            when {
                ruleset in kept -> changed = true

                ruleset.id.isEmpty() || kept.isNotEmpty() -> {
                    kept += ruleset
                    result += ruleset.copy(id = newId())
                    changed = true
                }

                else -> {
                    kept += ruleset
                    result += ruleset
                }
            }
        }
        return result.takeIf { changed }
    }

    /** PattNG: a new id for a routing ruleset, as the routing editor gives one. */
    private fun newRulesetId(): String = UUID.randomUUID().toString()

    /**
     * Get a routing ruleset by index.
     * @param index The index of the ruleset.
     * @return The RulesetItem.
     */
    fun getRoutingRuleset(index: Int): RulesetItem? {
        if (index < 0) return null

        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return null

        return rulesetList[index]
    }

    /**
     * Save a routing ruleset.
     * @param index The index of the ruleset.
     * @param ruleset The RulesetItem to save.
     */
    fun saveRoutingRuleset(index: Int, ruleset: RulesetItem?) {
        if (ruleset == null) return

        var rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            rulesetList = mutableListOf()
        }

        if (index < 0 || index >= rulesetList.count()) {
            rulesetList.add(0, ruleset)
        } else {
            rulesetList[index] = ruleset
        }
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    /**
     * Remove a routing ruleset by index.
     * @param index The index of the ruleset.
     */
    fun removeRoutingRuleset(index: Int) {
        if (index < 0) return

        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return

        rulesetList.removeAt(index)
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    /**
     * Check if routing rulesets bypass LAN.
     * @return True if bypassing LAN, false otherwise.
     */
    fun routingRulesetsBypassLan(): Boolean {
        val vpnBypassLan = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)
        if (vpnBypassLan == "1") {
            return true
        } else if (vpnBypassLan == "2") {
            return false
        }

        val guid = MmkvManager.getSelectServer() ?: return false
        val config = decodeServerConfig(guid) ?: return false
        if (config.configType == EConfigType.CUSTOM) {
            val raw = MmkvManager.decodeServerRaw(guid) ?: return false
            val v2rayConfig = JsonUtil.fromJsonSafe(raw, V2rayConfig::class.java)
            val exist = v2rayConfig?.routing?.rules?.filter { it.outboundTag == TAG_DIRECT }?.any {
                it.domain?.contains(GEOSITE_PRIVATE) == true || it.ip?.contains(GEOIP_PRIVATE) == true
            }
            return exist == true
        }

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        val exist = rulesetItems?.filter { it.enabled && it.outboundTag == TAG_DIRECT }?.any {
            it.domain?.contains(GEOSITE_PRIVATE) == true || it.ip?.contains(GEOIP_PRIVATE) == true
        }
        return exist == true
    }

    /**
     * Get server via remarks.
     * @param remarks The remarks of the server.
     * @return The ProfileItem.
     */
    fun getProfileEntryViaRemarks(remarks: String?): ProfileEntry? {
        if (remarks.isNullOrEmpty()) {
            return null
        }
        val serverList = decodeAllServerList()
        return serverList
            .mapNotNull { guid -> decodeServerConfig(guid)?.let { ProfileEntry(guid, it) } }
            .firstOrNull { it.profile.remarks == remarks }
    }

    /**
     * PattNG: what [remarks] finds among the profiles [takes] accepts, each with its guid, see [ByName]. Unlike
     * [getProfileEntryViaRemarks], which takes the first profile of any kind, it tells a name no profile has from one
     * several have, as the hops of a proxy chain and the exit-node of an Aether core are named.
     */
    fun findProfileEntryViaRemarks(remarks: String?, takes: (ProfileItem) -> Boolean): ByName<ProfileEntry> =
        ByName.find(
            remarks,
            decodeAllServerList().asSequence().mapNotNull { guid -> decodeServerConfig(guid)?.let { ProfileEntry(guid, it) } }.filter { takes(it.profile) }
        ) { it.profile.remarks }

    /** PattNG: what [remarks] finds among the profiles [takes] accepts, see [findProfileEntryViaRemarks]. */
    fun findServerViaRemarks(remarks: String?, takes: (ProfileItem) -> Boolean): ByName<ProfileItem> =
        findProfileEntryViaRemarks(remarks, takes).map { it.profile }

    /**
     * Collects non-empty profile remarks while excluding specific config types.
     */
    fun getProfileRemarks(excludeConfigTypes: Set<EConfigType> = setOf(EConfigType.CUSTOM)): List<String> {
        return decodeAllServerList()
            .asSequence()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .filter { profile -> profile.configType !in excludeConfigTypes }
            .map { it.remarks.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * PattNG: removes the subscription [subid] names with its profiles, its updates stopped first, and, when none is
     * left, creates the default subscription, which keeps the profiles of no subscription. Tells whether the storage took
     * it, see [MmkvManager.tryRemoveSubscription]: refused, the subscription stays as it was, its updates scheduled again.
     * A default subscription the storage refused is logged.
     */
    fun tryRemoveSubscriptionWithDefault(subid: String): Boolean {
        SubscriptionUpdater.cancelOne(subId = subid)
        if (!MmkvManager.tryRemoveSubscription(subid)) {
            SubscriptionUpdater.syncOne(subId = subid)
            return false
        }
        if (decodeSubsList().isEmpty()) {
            MmkvManager.tryEncodeSubscription(DEFAULT_SUBSCRIPTION_ID, SubscriptionItem(remarks = "Default"), listFirst = true)
        }
        return true
    }

    /**
     * Get the SOCKS port.
     * @return The SOCKS port.
     */
    fun getSocksPort(): Int {
        val port =
            if (IsDynamicSocksPort()) {
                runtimeSocksPort ?: refreshRuntimeSocksPort()
            } else {
                Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
            }
        return port ?: AppConfig.PORT_SOCKS.toInt()
    }

    @Synchronized
    fun refreshRuntimeSocksPort(): Int? {
        if (IsDynamicSocksPort()) {
            runtimeSocksPort = generateRandomSocksPort()
            return runtimeSocksPort
        }
        return null
    }

    fun getSocksUsername(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getSocksPassword(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Get the HTTP port.
     * @return The HTTP port.
     */
    fun getHttpPort(): Int {
        return getSocksPort() + if (Utils.isXray()) 0 else 1
    }

    /**
     * The loopback ports the local proxy is set to listen on, which nothing else the app starts can
     * share. Empty while the SOCKS port is picked at random on every start: no port is known before
     * the service runs then, and asking for one here would pick one for this process only.
     */
    fun getLocalProxyPorts(): Set<Int> {
        return if (IsDynamicSocksPort()) emptySet() else setOf(getSocksPort(), getHttpPort())
    }

    /**
     * PattNG: the loopback port every Aether core listens on, whatever its profile, as every profile
     * shares the local proxy port; the app runs one core at a time. The three ports after it go to
     * Tor and Psiphon and to the inbound the core dials out through. A value that is no such port
     * gives way to the default, see AetherCoreManager.listenPortOf.
     */
    fun getAetherListenPort(): Int =
        AetherCoreManager.listenPortOf(MmkvManager.decodeSettingsString(AppConfig.PREF_AETHER_LISTEN_PORT))

    private fun IsDynamicSocksPort(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    }

    private fun generateRandomSocksPort(): Int {
        return Random.nextInt(10000, 65535)
    }

    /**
     * Initialize assets.
     * @param context The application context.
     * @param assets The AssetManager.
     */
    fun initAssets(context: Context, assets: AssetManager) {
        val extFolder = Utils.userAssetPath(context)

        try {
            val geo = arrayOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)
            // The bundled Psiphon list is the newest the build could fetch. It goes over the copy only when it
            // was published after the copy was made, never over a file the user picked, and the copy takes the
            // list's publication time so that the next build is compared with the list, not with the copy.
            val publishedAt = PsiphonServerList.publishedAt(
                runCatching { assets.open(AppConfig.PSIPHON_SERVERS_STAMP).use { it.bufferedReader().readText() } }.getOrNull()
            )
            // "file" is the address the Asset files screen saves for a file the user picked.
            val keptByUser = MmkvManager.decodeAssetUrls().any { it.assetUrl.remarks == AppConfig.PSIPHON_SERVERS_DAT && it.assetUrl.url == "file" }
            assets.list("")
                ?.filter { geo.contains(it) || it == AppConfig.PSIPHON_SERVERS_DAT }
                ?.filter { name ->
                    val copy = File(extFolder, name)
                    if (name == AppConfig.PSIPHON_SERVERS_DAT) PsiphonServerList.bundledListGoesOver(copy, publishedAt, keptByUser) else !copy.exists()
                }
                ?.forEach {
                    val target = File(extFolder, it)
                    assets.open(it).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (it == AppConfig.PSIPHON_SERVERS_DAT && publishedAt > 0) target.setLastModified(publishedAt)
                    LogUtil.i(AppConfig.TAG, "Copied from apk assets folder to ${target.absolutePath}")
                }
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "asset copy failed", e)
        }
    }

    /**
     * Get domestic DNS servers from preference.
     * @return A list of domestic DNS servers.
     */
    fun getDomesticDnsServers(): List<String> {
        val domesticDns =
            MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS) ?: AppConfig.DNS_DIRECT
        val ret = domesticDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        if (ret.isEmpty()) {
            return listOf(AppConfig.DNS_DIRECT)
        }
        return ret
    }

    /**
     * Get remote DNS servers from preference.
     * @return A list of remote DNS servers.
     */
    fun getRemoteDnsServers(): List<String> {
        val remoteDns =
            MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS) ?: AppConfig.DNS_PROXY
        val ret = remoteDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        if (ret.isEmpty()) {
            return listOf(AppConfig.DNS_PROXY)
        }
        return ret
    }

    /**
     * Get VPN DNS servers from preference.
     * @return A list of VPN DNS servers.
     */
    fun getVpnDnsServers(): List<String> {
        val vpnDns = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS) ?: AppConfig.DNS_VPN
        return vpnDns.split(",").filter { Utils.isPureIpAddress(it) }
    }

    /**
     * Get delay test URL.
     * @param second Whether to use the second URL.
     * @return The delay test URL.
     */
    fun getDelayTestUrl(second: Boolean = false): String {
        return if (second) {
            AppConfig.DELAY_TEST_URL2
        } else {
            MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL)
                ?: AppConfig.DELAY_TEST_URL
        }
    }

    /**
     * Get real ping concurrency.
     * @return The number of concurrent real-ping tests (clamped to 1..64).
     */
    fun getRealPingConcurrency(): Int {
        val value = MmkvManager.decodeSettingsString(AppConfig.PREF_REAL_PING_CONCURRENCY)?.toIntOrNull() ?: 16
        return value.coerceIn(1, 128)
    }

    /**
     * Retrieves the currently selected VPN interface address configuration.
     * This method reads the user's preference for VPN interface addressing and returns
     * the corresponding configuration containing IPv4 and IPv6 addresses.
     *
     * @return The selected VpnInterfaceAddressConfig instance, or the default configuration
     *         if no valid selection is found or if the stored index is invalid.
     */
    fun getCurrentVpnInterfaceAddressConfig(): VpnInterfaceAddressConfig {
        val selectedIndex = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")?.toInt()
        return VpnInterfaceAddressConfig.getConfigByIndex(selectedIndex ?: 0)
    }

    /**
     * Get the VPN MTU from settings, defaulting to AppConfig.VPN_MTU.
     */
    fun getVpnMtu(): Int {
        return vpnMtu(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_MTU))
    }

    /**
     * PattNG: the VPN MTU [stored] holds, spaces and line breaks around it aside, as the settings dialog takes several
     * lines and Enter after the number stores a line break with it; or AppConfig.VPN_MTU when it is not a whole
     * number above 0, see [getVpnMtu]: VpnService.Builder.setMtu refuses 0 and less with an exception, and the VPN
     * would not start.
     */
    internal fun vpnMtu(stored: String?): Int {
        return Utils.parseInt(stored?.trim(), AppConfig.VPN_MTU).takeIf { it > 0 } ?: AppConfig.VPN_MTU
    }

    /**
     * Check if HEV TUN is being used.
     * @return True if HEV TUN is used, false otherwise.
     */
    fun isUsingHevTun(): Boolean {
        // PattNG: Xray-core's own TUN is the default
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, false)
    }

    /**
     * Check if VPN mode is enabled.
     * @return True if VPN mode is enabled, false otherwise.
     */
    fun isVpnMode(): Boolean {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        return mode == null || mode == VPN
    }

    /**
     * Check if a root (system-wide) run mode is selected.
     */
    fun isRootMode(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)
    }

    /**
     * Check if Xray-core's own TUN inbound carries the traffic: the VPN mode with HEV TUN off. The root mode always
     * runs its own HEV tun, whichever mode is selected.
     */
    fun isUsingXrayTun(): Boolean {
        return usesXrayTun(isVpnMode(), isRootMode(), isUsingHevTun())
    }

    /** Whether Xray-core's own TUN inbound carries the traffic, see [isUsingXrayTun]. */
    internal fun usesXrayTun(vpnMode: Boolean, rootMode: Boolean, hevTun: Boolean): Boolean {
        return vpnMode && !rootMode && !hevTun
    }

    /**
     * Check if the core keeps its local proxy, the SOCKS inbound that takes HTTP as well: always for HEV TUN in the VPN
     * mode, the root mode and its LAN sharing, which all reach the core through it, and otherwise as set.
     */
    fun isLocalProxyKept(): Boolean {
        return keepsLocalProxy(
            vpnMode = isVpnMode(),
            hevTun = isUsingHevTun(),
            rootMode = isRootMode(),
            lanSharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING),
            localProxySet = MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true),
        )
    }

    /** Whether the core keeps its local proxy, see [isLocalProxyKept]. */
    internal fun keepsLocalProxy(vpnMode: Boolean, hevTun: Boolean, rootMode: Boolean, lanSharing: Boolean, localProxySet: Boolean): Boolean {
        return (vpnMode && hevTun) || rootMode || lanSharing || localProxySet
    }

    /**
     * Check if the VPN hands apps the local proxy as their HTTP proxy: as set, and only while the core keeps the local
     * proxy, since the HTTP proxy would point at a closed port otherwise.
     */
    fun isAppendingHttpProxy(): Boolean {
        return appendsHttpProxy(MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY), isLocalProxyKept())
    }

    /** Whether the VPN hands apps the local HTTP proxy, see [isAppendingHttpProxy]. */
    internal fun appendsHttpProxy(appendSet: Boolean, localProxyKept: Boolean): Boolean {
        return appendSet && localProxyKept
    }

    /**
     *  Check if process routing can be used.
     */
    fun canUseProcessRouting(): Boolean {
        // Must xray tun
        if (!isUsingXrayTun()) {
            return false
        }

        // Must have route only enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false) == false) {
            return false
        }

        return true
    }

    /**
     * Ensure default settings are present in MMKV.
     */
    private fun ensureDefaultSettings() {
        // Write defaults in the exact order requested by the user
        ensureDefaultValue(AppConfig.PREF_MODE, VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_MTU, AppConfig.VPN_MTU.toString())
        ensureDefaultValue(AppConfig.PREF_SOCKS_PORT, AppConfig.PORT_SOCKS)
        ensureDefaultValue(AppConfig.PREF_AETHER_LISTEN_PORT, AppConfig.PORT_AETHER_SOCKS)
        ensureDefaultValue(AppConfig.PREF_REMOTE_DNS, AppConfig.DNS_PROXY)
        ensureDefaultValue(AppConfig.PREF_DOMESTIC_DNS, AppConfig.DNS_DIRECT)
        ensureDefaultValue(AppConfig.PREF_DELAY_TEST_URL, AppConfig.DELAY_TEST_URL)
        ensureDefaultValue(AppConfig.PREF_IP_API_URL, AppConfig.IP_API_URL)
        ensureDefaultValue(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, AppConfig.HEVTUN_RW_TIMEOUT)
        ensureDefaultValue(AppConfig.PREF_MUX_CONCURRENCY, "8")
        ensureDefaultValue(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY)
        ensureDefaultValue(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
        ensureDefaultValue(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
        ensureDefaultValue(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
        ensureDefaultValue(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING)
        ensureDefaultValue(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)
    }

    private fun ensureDefaultValue(key: String, default: String) {
        if (MmkvManager.decodeSettingsString(key).isNullOrEmpty()) {
            MmkvManager.encodeSettings(key, default)
        }
    }

    private fun migrateHysteria2PinSHA256() {
        // Check if migration has already been done
        val migrationKey = "hysteria2_pin_sha256_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }

        val serverList = decodeAllServerList()

        for (guid in serverList) {
            val profile = decodeServerConfig(guid) ?: continue
            if (profile.configType != EConfigType.HYSTERIA2) {
                continue
            }
            if (profile.pinSHA256.isNullOrEmpty() || !profile.pinnedCA256.isNullOrEmpty()) {
                continue
            }
            profile.pinnedCA256 = profile.pinSHA256
            profile.pinSHA256 = null
            MmkvManager.encodeServerConfig(guid, profile)
        }

        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * Migrates server list from legacy KEY_ANG_CONFIGS to subscription-based storage.
     * This method should be called once during app initialization after the storage structure change.
     * Servers are grouped by their subscriptionId into respective subscription's serverList.
     * Servers without subscription are moved to the default subscription.
     * After migration, KEY_ANG_CONFIGS is removed.
     */
    private fun migrateServerListToSubscriptions() {
        // Check if migration has already been done
        val migrationKey = "server_list_to_subscriptions_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }

        // Ensure default subscription exists before migration
        ensureDefaultSubscription()

        // Read existing server list from legacy KEY_ANG_CONFIGS
        val oldJson = MmkvManager.readLegacyServerList()
        if (oldJson.isNullOrBlank()) {
            // No data to migrate, mark as done
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }

        val guids = JsonUtil.fromJsonSafe(oldJson, Array<String>::class.java) ?: run {
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }

        val subscriptionServerMap = mutableMapOf<String, MutableList<String>>()

        // Group servers by subscription (use default subscription for empty subscriptionId)
        guids.forEach { guid ->
            val config = decodeServerConfig(guid) ?: return@forEach
            val subId = config.subscriptionId.ifEmpty { DEFAULT_SUBSCRIPTION_ID }

            subscriptionServerMap.getOrPut(subId) { mutableListOf() }.add(guid)
        }

        // Update each subscription's serverList (including default subscription)
        subscriptionServerMap.forEach { (subId, serverGuids) ->
            MmkvManager.encodeServerList(serverGuids, subId)
        }


        // Mark migration as complete
        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * Ensures the default subscription exists for ungrouped servers.
     * This subscription is used internally to store servers without a subscription.
     * Made public for migration in SettingsManager.
     */
    private fun ensureDefaultSubscription() {
        if (decodeSubscription(DEFAULT_SUBSCRIPTION_ID) == null) {
            val defaultSub = SubscriptionItem(
                remarks = "Default",
            )
            // PattNG: stored and listed first in one hold of the profile index lock, checked; a refusal is logged there.
            MmkvManager.tryEncodeSubscription(DEFAULT_SUBSCRIPTION_ID, defaultSub, listFirst = true)
        }
    }

}
