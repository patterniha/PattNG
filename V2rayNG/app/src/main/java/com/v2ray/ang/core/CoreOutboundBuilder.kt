package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Centralizes ProfileItem -> OutboundBean conversion.
 * Most protocol builders mirror the previous *Fmt.toOutbound behavior.
 */
object CoreOutboundBuilder {

    /** Dispatches a profile to protocol-specific outbound builder. */
    fun convert(profileItem: ProfileItem): OutboundBean? {
        val outbound = when (profileItem.configType) {
            EConfigType.VMESS -> toOutboundVmess(profileItem)
            EConfigType.SHADOWSOCKS -> toOutboundShadowsocks(profileItem)
            EConfigType.SOCKS -> toOutboundSocks(profileItem)
            EConfigType.VLESS -> toOutboundVless(profileItem)
            EConfigType.TROJAN -> toOutboundTrojan(profileItem)
            EConfigType.WIREGUARD -> toOutboundWireguard(profileItem)
            EConfigType.HYSTERIA2 -> toOutboundHysteria2(profileItem)
            EConfigType.MASQUE -> toOutboundMasque(profileItem)
            EConfigType.HTTP -> toOutboundHttp(profileItem)
            EConfigType.AETHER -> toOutboundAether(profileItem)
            else -> null
        }

        outbound ?: return null
        // PattNG: an Aether profile's outbound only reaches its core on the loopback address; its dialMode is
        // that of the exit-node its traffic leaves Xray by, see toOutboundAetherExit.
        if (profileItem.configType != EConfigType.AETHER) applyDialMode(outbound, profileItem)
        applyTargetStrategy(outbound, profileItem)
        val ret = updateOutboundWithGlobalSettings(outbound)
        if (!ret) return null
        return outbound
    }

    /**
     * Copies the profile dialMode into streamSettings.sockopt.dialMode.
     *
     * Only the dialMode field is written, so sockopt options set elsewhere
     * (dialerProxy, domainStrategy, happyEyeballs, ...) are kept.
     */
    internal fun applyDialMode(outbound: OutboundBean, profileItem: ProfileItem) = applyDialMode(outbound, profileItem.dialMode)

    /** [applyDialMode] with the dialMode itself, as the exit-node of an Aether core takes its profile's. */
    internal fun applyDialMode(outbound: OutboundBean, mode: String?) {
        val dialMode = mode.nullIfBlank() ?: return
        if (outbound.streamSettings == null) {
            // wireguard outbounds are built without streamSettings, but Xray still dials
            // their endpoint through the system dialer with streamSettings.sockopt.
            // Add one that only carries sockopt: network stays unset as there is no transport.
            outbound.streamSettings = OutboundBean.StreamSettingsBean(network = null)
        }
        outbound.ensureSockopt().dialMode = dialMode
    }

    /**
     * Copies the profile targetStrategy onto the outbound, or the profile's default when it stores none, see
     * [defaultTargetStrategy]. AsIs, Xray's default, leaves the field out.
     */
    internal fun applyTargetStrategy(outbound: OutboundBean, profileItem: ProfileItem) {
        val strategy = profileItem.targetStrategy?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultTargetStrategy(profileItem)
        outbound.targetStrategy = strategy.takeUnless { it.equals(AppConfig.TARGET_STRATEGY_AS_IS, ignoreCase = true) }
    }

    /**
     * PattNG: the targetStrategy of [profile] when it stores none. An Aether profile whose traffic leaves its core
     * through WARP gets ForceIPv4v6: the core looks names up inside the tunnel with no cache, once for every UDP
     * datagram, so Xray's DNS, with its cache, looks them up first, IPv4 before IPv6, and a name it cannot look up is
     * not sent at all. Every other profile passes names on as they are (AsIs, Xray's own): an Aether one whose traffic
     * leaves through Tor or Psiphon, which look names up at their exit; a WireGuard one, whose tunnel looks names up
     * with the profile's own DNS and keeps the answers; and one of any other type, whose server looks them up. Where an
     * outbound carries something else than your traffic, see [applyChainTargetStrategies] and [toOutboundAetherExit].
     */
    fun defaultTargetStrategy(profile: ProfileItem): String =
        if (profile.configType == EConfigType.AETHER && AetherCore.leavesThroughWarp(profile)) {
            AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        } else {
            AppConfig.TARGET_STRATEGY_AS_IS
        }

    /**
     * PattNG: the targetStrategy of the outbounds of a proxy chain, [hops] its profiles beside their outbounds, tagged,
     * the first carrying your traffic. A hop after the first carries the connection of the hop before it to that
     * hop's server, whose name a lookup by Xray would ask of the DNS that reaches out through this same chain: it
     * passes names on as they are unless its profile sets a targetStrategy. The exit-node of an Aether hop passes every
     * name on, see [toOutboundAetherExit].
     */
    internal fun applyChainTargetStrategies(hops: List<Pair<ProfileItem, OutboundBean>>) {
        hops.forEachIndexed { index, (profile, outbound) ->
            when {
                outbound.tag == AppConfig.TAG_EXIT_NODE -> outbound.targetStrategy = null
                index > 0 && profile.targetStrategy.isNullOrBlank() -> outbound.targetStrategy = null
            }
        }
    }

    /**
     * PattNG: a mux concurrency setting, [text], as Xray takes it: the whole number written, or [default] when the field,
     * which takes any text, holds none. A blank field left the outbound unbuilt, and the profile's traffic went out
     * directly while the app showed it connected.
     */
    internal fun muxConcurrency(text: String?, default: Int): Int = Utils.parseInt(text?.trim(), default)

    /** Applies global outbound options (mux, protocol-specific tweaks, etc.). */
    private fun updateOutboundWithGlobalSettings(outbound: OutboundBean): Boolean {
        try {
            var muxEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)
            val protocol = outbound.protocol
            if (protocol.equals(EConfigType.SHADOWSOCKS.name, true)
                || protocol.equals(EConfigType.SOCKS.name, true)
                || protocol.equals(EConfigType.HTTP.name, true)
                || protocol.equals(EConfigType.TROJAN.name, true)
                || protocol.equals(EConfigType.WIREGUARD.name, true)
                || protocol.equals(EConfigType.HYSTERIA2.name, true)
                || protocol.equals(EConfigType.HYSTERIA.name, true)
                || protocol.equals(EConfigType.MASQUE.name, true)
            ) {
                muxEnabled = false
            } else if (outbound.streamSettings?.network == NetworkType.XHTTP.type) {
                muxEnabled = false
            }

            if (muxEnabled) {
                outbound.mux?.enabled = true
                outbound.mux?.concurrency = muxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"), 8)
                outbound.mux?.xudpConcurrency = muxConcurrency(
                    MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY),
                    AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY.toInt(),
                )
                outbound.mux?.xudpProxyUDP443 = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")
                if (protocol.equals(EConfigType.VLESS.name, true) && outbound.settings?.flow?.isNotEmpty() == true) {
                    outbound.mux?.concurrency = -1
                }
            } else {
                outbound.mux?.enabled = false
                outbound.mux?.concurrency = -1
            }

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update outbound with global settings", e)
            return false
        }
        return true
    }

    /** Creates an initial outbound template for a protocol type. */
    fun createInitOutbound(configType: EConfigType): OutboundBean? {
        return when (configType) {
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.SHADOWSOCKS,
            EConfigType.SOCKS,
            EConfigType.HTTP,
            EConfigType.MASQUE,
            EConfigType.TROJAN -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = OutboundBean.StreamSettingsBean()
            )

            EConfigType.WIREGUARD -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(
                    secretKey = "",
                    peers = listOf(OutboundBean.OutSettingsBean.WireGuardBean())
                )
            )

            EConfigType.HYSTERIA,
            EConfigType.HYSTERIA2 -> OutboundBean(
                protocol = EConfigType.HYSTERIA.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = OutboundBean.StreamSettingsBean()
            )

            else -> null
        }
    }

    // ── Per-protocol builders — implementations are identical to each *Fmt.toOutbound ──

    private fun toOutboundVmess(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.VMESS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.id = profileItem.password.orEmpty()
            settings.security = profileItem.method
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundVless(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.VLESS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.id = profileItem.password.orEmpty()
            settings.encryption = profileItem.method
            settings.flow = profileItem.flow
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundShadowsocks(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.SHADOWSOCKS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.password = profileItem.password
            settings.method = profileItem.method
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundTrojan(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.TROJAN)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.password = profileItem.password
            settings.flow = profileItem.flow
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    /**
     * PattNG: internal for its unit test. The profile's finalMask goes on the stream settings the outbound is
     * created with, as PattN sets it for every protocol: a socks outbound has no transport, so
     * populateTransportSettings, which sets the mask of the other protocols, never runs for it. A blank mask is
     * left out, as for a WireGuard outbound, as it is no JSON.
     */
    internal fun toOutboundSocks(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.SOCKS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            if (profileItem.username.isNotNullEmpty()) {
                settings.user = profileItem.username.orEmpty()
                settings.pass = profileItem.password.orEmpty()
            }
        }

        if (!profileItem.finalMask.isNullOrBlank()) {
            outboundBean?.streamSettings?.let { updateOutboundFinalMask(it, profileItem) }
        }

        return outboundBean
    }

    /**
     * A SOCKS outbound to the Aether core, on the port the app dials the core of the profile on. The
     * core itself is named at the top of the configuration, as aetherCommand, once the configuration is built.
     */
    private fun toOutboundAether(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.SOCKS)

        outboundBean?.settings?.let { settings ->
            settings.address = AppConfig.LOOPBACK
            settings.port = AetherCore.of(profileItem).port
        }

        return outboundBean
    }

    /** PattNG: internal for its unit test; the profile's finalMask is set as on a socks outbound, see [toOutboundSocks]. */
    internal fun toOutboundHttp(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.HTTP)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            if (profileItem.username.isNotNullEmpty()) {
                settings.user = profileItem.username.orEmpty()
                settings.pass = profileItem.password.orEmpty()
            }
        }

        if (!profileItem.finalMask.isNullOrBlank()) {
            outboundBean?.streamSettings?.let { updateOutboundFinalMask(it, profileItem) }
        }

        return outboundBean
    }

    /**
     * PattNG: the remote DNS servers of a WireGuard outbound, and the entries of [remoteDNS] left out. The core parses
     * each server with Go's netip.ParseAddr and stops the whole process on anything it refuses, as a host name, an
     * address with a port, or "local", which it takes no more; so only what it takes goes, see [isNetipAddress], an
     * IPv6 address without its brackets, the IPv4 ones alone when IPv6 is off, each its own entry; and when none is
     * left, the default ones, see [AppConfig.WIREGUARD_LOCAL_REMOTE_DNS], split as well.
     */
    internal fun wireguardRemoteDns(remoteDNS: String?, ipv6Enabled: Boolean): Pair<List<String>, List<String>> {
        fun entries(list: String?) = list?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        fun address(entry: String): String? {
            val bare = if (entry.startsWith("[") && entry.endsWith("]")) entry.substring(1, entry.length - 1) else entry
            return bare.takeIf(::isNetipAddress)
        }

        val read = entries(remoteDNS).map { entry -> entry to address(entry) }
        val leftOut = read.filter { it.second == null }.map { it.first }
        val usable = read.mapNotNull { it.second }.filter { ipv6Enabled || !it.contains(":") }
        val servers = usable.ifEmpty { entries(AppConfig.WIREGUARD_LOCAL_REMOTE_DNS).filter { ipv6Enabled || !it.contains(":") } }
        return servers to leftOut
    }

    /**
     * PattNG: whether Go's netip.ParseAddr takes [text], as the core reads a WireGuard remoteDNS server, see
     * [wireguardRemoteDns]: IPv4 in four decimal fields without leading zeros, or IPv6 with one :: at most, standing for
     * one field or more, and a dotted IPv4 tail and a zone allowed; no brackets. Follows net/netip's ParseAddr,
     * parseIPv4Fields and parseIPv6 step by step.
     */
    internal fun isNetipAddress(text: String): Boolean {
        for (c in text) {
            when (c) {
                '.' -> return isNetipIpv4(text)
                ':' -> return isNetipIpv6(text)
                '%' -> return false
            }
        }
        return false
    }

    private fun isNetipIpv4(text: String): Boolean {
        val fields = text.split('.')
        return fields.size == 4 && fields.all { field ->
            field.isNotEmpty() && field.length <= 3 && field.all { it in '0'..'9' } &&
                (field.length == 1 || field[0] != '0') && field.toInt() <= 255
        }
    }

    private fun isNetipIpv6(text: String): Boolean {
        var s = text
        val zoneAt = s.indexOf('%')
        if (zoneAt >= 0) {
            if (zoneAt == s.length - 1) return false
            s = s.substring(0, zoneAt)
        }
        var ellipsis = -1
        if (s.startsWith("::")) {
            ellipsis = 0
            s = s.substring(2)
            if (s.isEmpty()) return true
        }
        var i = 0
        while (i < 16) {
            var off = 0
            while (off < s.length && isHexDigit(s[off])) {
                if (off > 3) return false
                off++
            }
            if (off == 0) return false
            if (off < s.length && s[off] == '.') {
                if (ellipsis < 0 && i != 12) return false
                if (i + 4 > 16) return false
                if (!isNetipIpv4(s)) return false
                s = ""
                i += 4
                break
            }
            i += 2
            s = s.substring(off)
            if (s.isEmpty()) break
            if (s[0] != ':' || s.length == 1) return false
            s = s.substring(1)
            if (s[0] == ':') {
                if (ellipsis >= 0) return false
                ellipsis = i
                s = s.substring(1)
                if (s.isEmpty()) break
            }
        }
        if (s.isNotEmpty()) return false
        return if (i < 16) ellipsis >= 0 else ellipsis < 0
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun toOutboundWireguard(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.WIREGUARD)

        val rawAddresses = profileItem.localAddress
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4, AppConfig.WIREGUARD_LOCAL_ADDRESS_V6)

        val ipv6Enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, AppConfig.DEFAULT_IPV6_ENABLED)
        val addresses = if (ipv6Enabled) {
            rawAddresses
        } else {
            val ipv4Addresses = rawAddresses.filter { !it.contains(":") }
            ipv4Addresses.ifEmpty { listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4) }
        }

        val (remotes, leftOut) = wireguardRemoteDns(
            profileItem.remoteDNS,
            ipv6Enabled = ipv6Enabled,
        )
        if (leftOut.isNotEmpty()) {
            // The entries themselves stay out of the log: one may be a DNS URL with an account in it.
            LogUtil.w(
                AppConfig.TAG,
                "CoreOutboundBuilder: WireGuard profile '${profileItem.remarks}': ${leftOut.size} remoteDNS entries left out, not IP addresses"
            )
        }

        outboundBean?.settings?.let { wireguard ->
            wireguard.secretKey = profileItem.secretKey
            wireguard.address = addresses
            wireguard.port = null
            wireguard.peers?.firstOrNull()?.let { peer ->
                peer.publicKey = profileItem.publicKey.orEmpty()
                peer.preSharedKey = profileItem.preSharedKey?.nullIfBlank()
                peer.endpoint = Utils.getIpv6Address(profileItem.server) + ":${profileItem.serverPort}"
            }
            wireguard.mtu = profileItem.mtu
            wireguard.remoteDNS = remotes
            wireguard.reserved = profileItem.reserved?.takeIf { it.isNotBlank() }?.split(",")?.filter { it.isNotBlank() }?.map { it.trim().toInt() }
        }

        if (!profileItem.finalMask.isNullOrBlank()) {
            outboundBean?.streamSettings = OutboundBean.StreamSettingsBean()
            outboundBean?.streamSettings?.let {
                updateOutboundFinalMask(it, profileItem)
                it.network = null
            }
        }
        return outboundBean
    }

    private fun toOutboundHysteria2(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.HYSTERIA2) ?: return null
        profileItem.network = NetworkType.HYSTERIA.type
        profileItem.alpn = "h3"

        outboundBean.settings?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
            server.version = 2
        }

        val sni = outboundBean.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundMasque(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.MASQUE) ?: return null
        profileItem.network = NetworkType.MASQUE.type
        profileItem.security = AppConfig.TLS
        profileItem.insecure = false
        profileItem.alpn = profileItem.alpn?.trim()?.nullIfBlank() ?: "h3"

        val rawAddresses = profileItem.localAddress
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: listOf(AppConfig.MASQUE_LOCAL_ADDRESS_V4, AppConfig.MASQUE_LOCAL_ADDRESS_V6)

        val addresses = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            rawAddresses
        } else {
            val ipv4Addresses = rawAddresses.filter { !it.contains(":") }
            ipv4Addresses.ifEmpty { listOf(AppConfig.MASQUE_LOCAL_ADDRESS_V4) }
        }

        val defaultDns = AppConfig.MASQUE_LOCAL_REMOTE_DNS.split(",").map { it.trim() }

        val rawDNS = profileItem.remoteDNS
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: defaultDns

        val remotes = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            rawDNS
        } else {
            rawDNS.filter { !it.contains(":") }
                .ifEmpty { defaultDns.filter { !it.contains(":") } }
        }

        outboundBean.settings?.let { masque ->
            masque.address = getServerAddress(profileItem)
            masque.port = profileItem.serverPort.orEmpty().toInt()
            masque.remoteDNS = remotes
        }

        val warp = if (!profileItem.secretKey.isNullOrBlank() || !profileItem.publicKey.isNullOrBlank()) {
            OutboundBean.StreamSettingsBean.MasqueSettingsBean.WarpBean(
                privateKey = profileItem.secretKey.orEmpty(),
                publicKey = profileItem.publicKey.orEmpty(),
                address = addresses
            )
        } else {
            null
        }
        val sni = outboundBean.streamSettings?.let {
            it.masqueSettings = OutboundBean.StreamSettingsBean.MasqueSettingsBean(
                host = profileItem.host.nullIfBlank(),
                path = profileItem.path.nullIfBlank(),
                warp = warp
            )
            if (!profileItem.headers.isNullOrBlank()) {
                try {
                    val headers = JsonUtil.fromJson(profileItem.headers.orEmpty(), JsonObject::class.java)
                    if (headers == null || headers.entrySet().any { !it.value.isJsonPrimitive || !it.value.asJsonPrimitive.isString }) {
                        LogUtil.e(AppConfig.TAG, "Failed to build MASQUE outbound headers: expected a JSON object with string values")
                        return null
                    }
                    it.masqueSettings?.headers = headers.entrySet().associate { entry -> entry.key to entry.value.asString }
                } catch (_: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to build MASQUE outbound headers: invalid JSON")
                    return null
                }
            }
            populateTransportSettings(it, profileItem)
        }

        outboundBean.streamSettings?.let {
            // PattNG: over HTTP/3 the fork core dials QUIC with Go's crypto/tls, which takes no fingerprint and, TLS 1.3
            // alone, no cipherSuites; ECH it uses over HTTP/3 and HTTP/2 alike
            val http2 = profileItem.alpn == "h2"
            val tlsProfile = profileItem.copy(
                publicKey = null,
                fingerPrint = if (http2) profileItem.fingerPrint else null,
                cipherSuites = if (http2) profileItem.cipherSuites else null,
            )
            populateTlsSettings(it, tlsProfile, sni)
        }

        return outboundBean
    }

    private fun createTcpHttpRequest(
        host: String?,
        path: String?
    ): OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean {
        val requestString =
            """{"version":"1.1","method":"GET","headers":{"User-Agent":["Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"],"Accept-Encoding":["gzip, deflate"],"Connection":["keep-alive"],"Pragma":"no-cache"}}"""
        val request = JsonUtil.fromJson(
            requestString,
            OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean::class.java
        ) ?: OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean()

        val parsedHost = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        request.headers.Host = parsedHost.ifEmpty { null }
        request.path = path.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("/") }
        return request
    }

    /**
     * Configures transport settings for an outbound connection.
     *
     * Sets up protocol-specific transport options based on the profile settings.
     *
     * @param streamSettings The stream settings to configure
     * @param profileItem The profile containing transport configuration
     * @return The Server Name Indication (SNI) value to use, or null if not applicable
     */
    fun populateTransportSettings(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem): String? {
        val transport = profileItem.network.orEmpty()
        val headerType = profileItem.headerType
        val host = profileItem.host
        val path = profileItem.path
        val seed = profileItem.seed
//        val quicSecurity = profileItem.quicSecurity
//        val key = profileItem.quicKey
        val mode = profileItem.mode
        val serviceName = profileItem.serviceName
        val authority = profileItem.authority
        val xhttpMode = profileItem.xhttpMode
        val xhttpExtra = profileItem.xhttpExtra
        val finalMask = profileItem.finalMask
        var sni: String? = null
        streamSettings.network = transport.ifEmpty { NetworkType.TCP.type }
        when (streamSettings.network) {
            NetworkType.TCP.type -> {
                val tcpSetting = OutboundBean.StreamSettingsBean.TcpSettingsBean()
                if (headerType == AppConfig.HEADER_TYPE_HTTP) {
                    tcpSetting.header.type = AppConfig.HEADER_TYPE_HTTP
                    val requestObj = createTcpHttpRequest(host, path)
                    tcpSetting.header.request = requestObj
                    sni = requestObj.headers.Host?.getOrNull(0)
                } else {
                    tcpSetting.header.type = "none"
                    sni = host
                }
                streamSettings.tcpSettings = tcpSetting
            }

            NetworkType.KCP.type -> {
                val kcpSetting = OutboundBean.StreamSettingsBean.KcpSettingsBean()
                profileItem.kcpMtu?.let { kcpSetting.mtu = it }
                profileItem.kcpTti?.let { kcpSetting.tti = it }
                streamSettings.kcpSettings = kcpSetting
                val udpMaskList =
                    mutableListOf<OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean>()
                if (seed.isNullOrEmpty()) {
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy"
                        )
                    )
                } else {
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy",
                            settings = OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                value = seed
                            )
                        )
                    )
                }
                if (!headerType.isNullOrEmpty() && headerType != "none") {
                    val kcpHeaderType = when {
                        headerType == "wechat-video" -> "wechat"
                        else -> headerType
                    }
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy",
                            settings =
                                OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                    header = kcpHeaderType,
                                    value = if (headerType == "dns" && !host.isNullOrEmpty()) {
                                        host
                                    } else {
                                        null
                                    }
                                )
                        )
                    )
                }
                streamSettings.finalmask = OutboundBean.StreamSettingsBean.FinalMaskBean(
                    udp = udpMaskList.toList()
                )
            }

            NetworkType.WS.type -> {
                val wssetting = OutboundBean.StreamSettingsBean.WsSettingsBean()
                wssetting.host = host.orEmpty()
                sni = host
                wssetting.path = path ?: "/"
                streamSettings.wsSettings = wssetting
            }

            NetworkType.HTTP_UPGRADE.type -> {
                val httpupgradeSetting = OutboundBean.StreamSettingsBean.HttpupgradeSettingsBean()
                httpupgradeSetting.host = host.orEmpty()
                sni = host
                httpupgradeSetting.path = path ?: "/"
                streamSettings.httpupgradeSettings = httpupgradeSetting
            }

            NetworkType.XHTTP.type -> {
                val xhttpSetting = OutboundBean.StreamSettingsBean.XhttpSettingsBean()
                xhttpSetting.host = host.orEmpty()
                sni = host
                xhttpSetting.path = path ?: "/"
                xhttpSetting.mode = xhttpMode
                xhttpSetting.extra = JsonUtil.parseString(xhttpExtra)
                streamSettings.xhttpSettings = xhttpSetting
            }

            NetworkType.H2.type, NetworkType.HTTP.type -> {
                streamSettings.network = NetworkType.H2.type
                val h2Setting = OutboundBean.StreamSettingsBean.HttpSettingsBean()
                h2Setting.host = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                sni = h2Setting.host.getOrNull(0)
                h2Setting.path = path ?: "/"
                streamSettings.httpSettings = h2Setting
            }

//                    "quic" -> {
//                        val quicsetting = QuicSettingBean()
//                        quicsetting.security = quicSecurity ?: "none"
//                        quicsetting.key = key.orEmpty()
//                        quicsetting.header.type = headerType ?: "none"
//                        quicSettings = quicsetting
//                    }

            NetworkType.GRPC.type -> {
                val grpcSetting = OutboundBean.StreamSettingsBean.GrpcSettingsBean()
                grpcSetting.multiMode = mode == "multi"
                grpcSetting.serviceName = serviceName.orEmpty()
                grpcSetting.authority = authority.orEmpty()
                grpcSetting.idle_timeout = 60
                grpcSetting.health_check_timeout = 20
                sni = authority
                streamSettings.grpcSettings = grpcSetting
            }

            NetworkType.HYSTERIA.type -> {
                val hysteriaSetting = OutboundBean.StreamSettingsBean.HysteriaSettingsBean(
                    version = 2,
                    auth = profileItem.password.orEmpty(),
                )
                val quicParams = OutboundBean.StreamSettingsBean.FinalMaskBean.QuicParamsBean(
                    brutalUp = profileItem.bandwidthUp?.nullIfBlank(),
                    brutalDown = profileItem.bandwidthDown?.nullIfBlank(),
                )
                quicParams.congestion = if (quicParams.brutalUp != null || quicParams.brutalDown != null) "brutal" else null
                val hyUdpFinalmasks = mutableListOf<OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean>()
                if (profileItem.obfsPassword.isNotNullEmpty() && !profileItem.obfs.equals("none", ignoreCase = true)) {
                    val isGecko = profileItem.isGeckoObfs()
                    val packetSize = if (isGecko) {
                        val min = profileItem.minPacketSize?.toIntOrNull() ?: 512
                        val max = profileItem.maxPacketSize?.toIntOrNull() ?: 1200
                        "$min-$max"
                    } else {
                        null
                    }
                    hyUdpFinalmasks.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "salamander",
                            settings = OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                password = profileItem.obfsPassword,
                                packetSize = packetSize
                            )
                        )
                    )
                }
                if (profileItem.portHopping.isNotNullEmpty()) {
                    val rawInterval = profileItem.portHoppingInterval?.trim().nullIfBlank()
                    val interval = if (rawInterval == null) {
                        "30"
                    } else {
                        val singleValue = rawInterval.toIntOrNull()
                        if (singleValue != null) {
                            if (singleValue < 5) {
                                "30"
                            } else {
                                rawInterval
                            }
                        } else {
                            val parts = rawInterval.split('-')
                            if (parts.size == 2) {
                                val start = parts[0].trim().toIntOrNull()
                                val end = parts[1].trim().toIntOrNull()
                                if (start != null && end != null) {
                                    val minStart = maxOf(5, start)
                                    val minEnd = maxOf(minStart, end)
                                    "$minStart-$minEnd"
                                } else {
                                    "30"
                                }
                            } else {
                                "30"
                            }
                        }
                    }
                    hyUdpFinalmasks.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "udphop",
                            settings = OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                mode = "intervallocal,intervalremote",
                                interval = interval,
                                remotePorts = profileItem.portHopping,
                            )
                        )
                    )
                }
                val finalmask = OutboundBean.StreamSettingsBean.FinalMaskBean(
                    udp = hyUdpFinalmasks.toList(),
                    quicParams = quicParams,
                )
                streamSettings.hysteriaSettings = hysteriaSetting
                streamSettings.finalmask = finalmask
            }
        }
        finalMask?.let {
            val parsedFinalMask = JsonUtil.parseString(finalMask)
            if (parsedFinalMask != null) {
                streamSettings.finalmask = parsedFinalMask
            } else {
                LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
            }
        }
        return sni
    }

    /** PattNG: the protocols [alpn] names, comma-separated, as the TLS settings of an outbound offer them; empty for none. */
    internal fun alpnProtocols(alpn: String?): List<String> =
        alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * PattNG: the TLS curvePreferences [curvePreferences] names, comma-separated as alpn's are, each trimmed, as the TLS
     * settings of an outbound offer them; null for none, so that none is written.
     */
    internal fun curvePreferenceNames(curvePreferences: String?): List<String>? =
        curvePreferences?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    /**
     * Configures TLS or REALITY security settings for an outbound connection.
     *
     * Sets up security-related parameters like certificates, fingerprints, and SNI.
     *
     * @param streamSettings The stream settings to configure
     * @param profileItem The profile containing security configuration
     * @param sniExt An external SNI value to use if the profile doesn't specify one
     */
    fun populateTlsSettings(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem, sniExt: String?) {
        val streamSecurity = profileItem.security.orEmpty()
        val allowInsecure = profileItem.insecure == true && profileItem.pinnedCA256.isNullOrEmpty()
        val sni = if (profileItem.sni.isNullOrEmpty()) {
            when {
                sniExt.isNotNullEmpty() && Utils.isDomainName(sniExt) -> sniExt
                profileItem.server.isNotNullEmpty() && Utils.isDomainName(profileItem.server) -> profileItem.server
                else -> sniExt
            }
        } else {
            profileItem.sni
        }

        streamSettings.security = streamSecurity.nullIfBlank()
        if (streamSettings.security == null) return
        val tlsSetting = OutboundBean.StreamSettingsBean.TlsSettingsBean(
            allowInsecure = allowInsecure,
            serverName = sni.nullIfBlank(),
            fingerprint = profileItem.fingerPrint.nullIfBlank(),
            alpn = alpnProtocols(profileItem.alpn).takeIf { it.isNotEmpty() },
            cipherSuites = profileItem.cipherSuites.nullIfBlank(),
            // PattNG: curvePreferences as a list, and enableSessionResumption only when it is on
            curvePreferences = curvePreferenceNames(profileItem.curvePreferences),
            enableSessionResumption = profileItem.enableSessionResumption.takeIf { it == true },
            echConfigList = profileItem.echConfigList.nullIfBlank(),
            verifyPeerCertByName = profileItem.verifyPeerCertByName.nullIfBlank(),
            pinnedPeerCertSha256 = profileItem.pinnedCA256.nullIfBlank(),
            publicKey = profileItem.publicKey.nullIfBlank(),
            shortId = profileItem.shortId.nullIfBlank(),
            spiderX = profileItem.spiderX.nullIfBlank(),
            mldsa65Verify = profileItem.mldsa65Verify.nullIfBlank(),
        )
        if (streamSettings.security == AppConfig.TLS) {
            streamSettings.tlsSettings = tlsSetting
            streamSettings.realitySettings = null
            // PattNG: the ECH config query goes through the profile's ECH outbound, which
            // EchOutbound.serialize checks, points echSockopt at and appends after every other outbound
            tlsSetting.echOutbound = profileItem.echOutbound.nullIfBlank()
        } else if (streamSettings.security == AppConfig.REALITY) {
            streamSettings.tlsSettings = null
            streamSettings.realitySettings = tlsSetting
        }
    }

    private fun getServerAddress(profileItem: ProfileItem): String {
        if (Utils.isPureIpAddress(profileItem.server.orEmpty())) {
            return profileItem.server.orEmpty()
        }

        val domain = HttpUtil.toIdnDomain(profileItem.server.orEmpty())
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD) != "2") {
            return domain
        }
        //Resolve and replace domain
        val resolvedIps = HttpUtil.resolveHostToIP(domain, MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6))
        if (resolvedIps.isNullOrEmpty()) {
            return domain
        }
        return resolvedIps.first()
    }

    fun updateOutboundFinalMask(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem) =
        updateOutboundFinalMask(streamSettings, profileItem.finalMask)

    /** [updateOutboundFinalMask] with the finalMask JSON itself, as the exit-node of an Aether core takes its profile's. */
    fun updateOutboundFinalMask(streamSettings: OutboundBean.StreamSettingsBean, finalMask: String?) {
        finalMask?.let {
            val parsedFinalMask = JsonUtil.parseString(finalMask)
            if (parsedFinalMask != null) {
                streamSettings.finalmask = parsedFinalMask
            } else {
                LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
            }
        }
    }

    /**
     * PattNG: the exit-node of an Aether core, the outbound that what the core dials out through leaves Xray
     * by: the outbound of the profile [exit] names as its node, which [nodeOutbound] gives, as a proxy chain
     * builds its hop, changed in its tag, and passing every name the core sends on as it is, whatever its
     * profile sets: a name Xray looked up for it would be asked of the DNS that reaches out through the core
     * itself, which is not up yet when it needs the name, or, where the configuration has no DNS, of the
     * phone's own resolver, outside the tunnel; or else a freedom outbound with the finalMask and the
     * dialMode of [exit] set as an ordinary profile sets them on its own outbound. Null when the node gives
     * none, see [ExitNodeOutbound.Problem]: the core would reach the internet without it. The session's
     * configuration carries it, and a core of its own dials out through it as well, see
     * [AetherCoreManager.withProcess].
     */
    fun toOutboundAetherExit(exit: AetherExit, nodeOutbound: (String) -> ExitNodeOutbound = ::toOutboundOfNode): OutboundBean? {
        exit.node?.let { name ->
            return (nodeOutbound(name) as? ExitNodeOutbound.Built)?.outbound?.apply {
                tag = AppConfig.TAG_EXIT_NODE
                targetStrategy = null
            }
        }
        val outbound = OutboundBean(tag = AppConfig.TAG_EXIT_NODE, protocol = "freedom", mux = null)
        if (!exit.finalMask.isNullOrBlank()) {
            // A freedom outbound has no transport; the stream settings carry the mask alone.
            outbound.streamSettings = OutboundBean.StreamSettingsBean(network = null)
            updateOutboundFinalMask(outbound.streamSettings!!, exit.finalMask)
        }
        applyDialMode(outbound, exit.dialMode)
        return outbound
    }

    /**
     * PattNG: the outbound of the profile named [name], built as for a hop of a proxy chain, or why there is
     * none: no profile that can be an exit-node has the name any more, several have it, or the one that has
     * it gives no outbound, or one whose ECH outbound cannot go beside it, see [nodeOutboundOf]. See
     * [AetherExit.node].
     */
    fun toOutboundOfNode(name: String): ExitNodeOutbound = when (val found = AetherExit.nodeProfile(name)) {
        is ByName.One -> try {
            nodeOf(found.value, ::convert)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to build the outbound of the Aether exit-node profile", e)
            ExitNodeOutbound.NoOutbound
        }

        ByName.None -> ExitNodeOutbound.NotFound
        ByName.Several -> ExitNodeOutbound.SameName
    }

    /**
     * PattNG: what [profile], the exit-node, gives with its outbound, which [build] makes, see [nodeOutboundOf], and the
     * digest of the profile as stored, as a test takes it: taken first, since building the outbound writes into the
     * profile, as a Hysteria2 one's does.
     */
    internal fun nodeOf(profile: ProfileItem, build: (ProfileItem) -> OutboundBean?): ExitNodeOutbound {
        val content = AetherExit.contentOf(profile)
        return when (val built = nodeOutboundOf(build(profile))) {
            is ExitNodeOutbound.Built -> built.copy(content = content)
            else -> built
        }
    }

    /**
     * PattNG: what the [outbound] a node's profile gives makes of it as the exit-node, see [toOutboundOfNode]: none, or
     * one whose ECH outbound the configuration the exit of a core of its own opens with would refuse, where it goes
     * beside the exit-node alone, see [AetherCoreManager.exitConfiguration], leaves it unusable.
     */
    internal fun nodeOutboundOf(outbound: OutboundBean?): ExitNodeOutbound = when {
        outbound == null -> ExitNodeOutbound.NoOutbound
        !EchOutbound.takes(outbound, setOf(AppConfig.TAG_EXIT_NODE)) -> ExitNodeOutbound.EchUnusable
        else -> ExitNodeOutbound.Built(outbound)
    }
}
