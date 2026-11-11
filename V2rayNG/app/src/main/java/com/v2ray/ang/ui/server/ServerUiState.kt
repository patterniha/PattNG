package com.v2ray.ang.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.v2ray.ang.AppConfig.AETHER_ECH_DNS
import com.v2ray.ang.AppConfig.AETHER_ECH_DOMAIN
import com.v2ray.ang.AppConfig.AETHER_MASQUE_SNI
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.MASQUE_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.MASQUE_LOCAL_ADDRESS_V6
import com.v2ray.ang.AppConfig.MASQUE_LOCAL_REMOTE_DNS
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V6
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_REMOTE_DNS
import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherPsiphonCdnSet
import com.v2ray.ang.enums.AetherPsiphonMode
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTorBridges
import com.v2ray.ang.enums.AetherTorRelays
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil

class ServerUiState(
    configType: EConfigType,
    remarks: String = "",
    address: String = "",
    port: String = DEFAULT_PORT.toString(),
    password: String = "",
    method: String = "",
    flow: String = "",
    encryption: String = "",
    username: String = "",
    secretKey: String = "",
    publicKey: String = "",
    preSharedKey: String = "",
    reserved: String = "0,0,0",
    localAddress: String = if (configType == EConfigType.MASQUE) "$MASQUE_LOCAL_ADDRESS_V4,$MASQUE_LOCAL_ADDRESS_V6" else "$WIREGUARD_LOCAL_ADDRESS_V4,$WIREGUARD_LOCAL_ADDRESS_V6",
    mtu: String = WIREGUARD_LOCAL_MTU,
    obfs: String = "",
    remoteDNS: String = if (configType == EConfigType.MASQUE) MASQUE_LOCAL_REMOTE_DNS else WIREGUARD_LOCAL_REMOTE_DNS,
    obfsPassword: String = "",
    minPacketSize: String = "",
    maxPacketSize: String = "",
    portHopping: String = "",
    portHoppingInterval: String = "",
    bandwidthDown: String = "",
    bandwidthUp: String = "",
    network: String = NetworkType.TCP.type,
    headerType: String = "none",
    mode: String = "",
    xhttpMode: String = "",
    serviceName: String = "",
    authority: String = "",
    host: String = "",
    path: String = "",
    headers: String = "",
    xhttpExtra: String = "",
    finalMask: String = "",
    seed: String = "",
    kcpMtu: String = "",
    kcpTti: String = "",
    browserDialerMode: String = "",
    dialMode: String = "",
    targetStrategy: String = "",
    streamSecurity: String = "",
    sni: String = "",
    allowInsecure: Boolean = false,
    fingerPrint: String = "",
    alpn: String = "",
    cipherSuites: String = "",
    curvePreferences: String = "",
    enableSessionResumption: Boolean = false,
    publicKeyReality: String = "",
    shortId: String = "",
    spiderX: String = "",
    mldsa65Verify: String = "",
    echConfigList: String = "",
    echOutbound: String = "",
    verifyPeerCertByName: String = "",
    pinnedCA256: String = "",
    isFetchingCert: Boolean = false,
    aetherProtocol: String = AetherProtocol.WIREGUARD.type,
    aetherTransport: String = AetherTransport.HTTP3.type,
    aetherMasqueSni: String = AETHER_MASQUE_SNI,
    aetherScanMode: String = AetherScanMode.BALANCED.type,
    aetherObfuscation: String = AetherObfuscation.AUTO.type,
    aetherFingerprint: String = AetherFingerprint.CHROME.type,
    aetherIpVersion: String = AetherIpVersion.V4.type,
    aetherWiwOuter: String = "",
    aetherWiwInner: String = "",
    aetherFragment: Boolean = false,
    aetherFragmentSize: String = "",
    aetherFragmentDelay: String = "",
    aetherEch: Boolean = false,
    aetherEchDns: String = AETHER_ECH_DNS,
    aetherEchDomain: String = AETHER_ECH_DOMAIN,
    aetherDns: String = "",
    aetherExitLoc: String = "",
    aetherExitNode: String = "",
    aetherPsiphon: String = AetherPsiphon.OFF.type,
    aetherPsiphonMode: String = AetherPsiphonMode.AUTO.type,
    aetherPsiphonCdnIps: String = "",
    aetherPsiphonCdnSni: String = "",
    aetherPsiphonCdnSets: String = "",
    aetherPsiphonRegion: String = "",
    aetherPsiphonBundledList: Boolean = true,
    aetherTor: String = AetherTor.OFF.type,
    aetherTorBridges: String = AetherTorBridges.AUTO.type,
    aetherTorBridgeLines: String = "",
    aetherTorRelays: String = AetherTorRelays.AUTO.type,
    aetherCommand: String = ""
) {
    var configType by mutableStateOf(configType)
    var remarks by mutableStateOf(remarks)
    var address by mutableStateOf(address)
    var port by mutableStateOf(port)
    var password by mutableStateOf(password)
    var method by mutableStateOf(method)
    var flow by mutableStateOf(flow)
    var encryption by mutableStateOf(encryption)
    var username by mutableStateOf(username)
    var secretKey by mutableStateOf(secretKey)
    var publicKey by mutableStateOf(publicKey)
    var preSharedKey by mutableStateOf(preSharedKey)
    var reserved by mutableStateOf(reserved)
    var localAddress by mutableStateOf(localAddress)
    var mtu by mutableStateOf(mtu)
    var remoteDNS by mutableStateOf(remoteDNS)
    var obfs by mutableStateOf(obfs)
    var obfsPassword by mutableStateOf(obfsPassword)
    var minPacketSize by mutableStateOf(minPacketSize)
    var maxPacketSize by mutableStateOf(maxPacketSize)
    var portHopping by mutableStateOf(portHopping)
    var portHoppingInterval by mutableStateOf(portHoppingInterval)
    var bandwidthDown by mutableStateOf(bandwidthDown)
    var bandwidthUp by mutableStateOf(bandwidthUp)
    var network by mutableStateOf(network)
    var headerType by mutableStateOf(headerType)
    var mode by mutableStateOf(mode)
    var xhttpMode by mutableStateOf(xhttpMode)
    var serviceName by mutableStateOf(serviceName)
    var authority by mutableStateOf(authority)
    var host by mutableStateOf(host)
    var path by mutableStateOf(path)
    var headers by mutableStateOf(headers)
    var xhttpExtra by mutableStateOf(xhttpExtra)
    var finalMask by mutableStateOf(finalMask)
    var seed by mutableStateOf(seed)
    var kcpMtu by mutableStateOf(kcpMtu)
    var kcpTti by mutableStateOf(kcpTti)
    var browserDialerMode by mutableStateOf(browserDialerMode)
    var dialMode by mutableStateOf(dialMode)
    /** The targetStrategy chosen; blank while the profile follows its default, see [shownTargetStrategy]. */
    var targetStrategy by mutableStateOf(targetStrategy)
    var streamSecurity by mutableStateOf(streamSecurity)
    var sni by mutableStateOf(sni)
    var allowInsecure by mutableStateOf(allowInsecure)
    var fingerPrint by mutableStateOf(fingerPrint)
    var alpn by mutableStateOf(alpn)
    var cipherSuites by mutableStateOf(cipherSuites)
    var curvePreferences by mutableStateOf(curvePreferences)
    var enableSessionResumption by mutableStateOf(enableSessionResumption)
    var publicKeyReality by mutableStateOf(publicKeyReality)
    var shortId by mutableStateOf(shortId)
    var spiderX by mutableStateOf(spiderX)
    var mldsa65Verify by mutableStateOf(mldsa65Verify)
    var echConfigList by mutableStateOf(echConfigList)
    var echOutbound by mutableStateOf(echOutbound)
    var verifyPeerCertByName by mutableStateOf(verifyPeerCertByName)
    var pinnedCA256 by mutableStateOf(pinnedCA256)
    var isFetchingCert by mutableStateOf(isFetchingCert)
    var aetherProtocol by mutableStateOf(aetherProtocol)
    var aetherTransport by mutableStateOf(aetherTransport)
    var aetherMasqueSni by mutableStateOf(aetherMasqueSni)
    var aetherScanMode by mutableStateOf(aetherScanMode)
    var aetherObfuscation by mutableStateOf(aetherObfuscation)
    var aetherFingerprint by mutableStateOf(aetherFingerprint)
    var aetherIpVersion by mutableStateOf(aetherIpVersion)
    var aetherWiwOuter by mutableStateOf(aetherWiwOuter)
    var aetherWiwInner by mutableStateOf(aetherWiwInner)
    var aetherFragment by mutableStateOf(aetherFragment)
    var aetherFragmentSize by mutableStateOf(aetherFragmentSize)
    var aetherFragmentDelay by mutableStateOf(aetherFragmentDelay)
    var aetherEch by mutableStateOf(aetherEch)
    var aetherEchDns by mutableStateOf(aetherEchDns)
    var aetherEchDomain by mutableStateOf(aetherEchDomain)
    var aetherDns by mutableStateOf(aetherDns)
    var aetherExitLoc by mutableStateOf(aetherExitLoc)

    /** The name of the profile the core dials out through, see ProfileItem.aetherExitNode; blank for freedom. */
    var aetherExitNode by mutableStateOf(aetherExitNode)
    var aetherPsiphon by mutableStateOf(aetherPsiphon)
    var aetherPsiphonMode by mutableStateOf(aetherPsiphonMode)
    var aetherPsiphonCdnIps by mutableStateOf(aetherPsiphonCdnIps)
    var aetherPsiphonCdnSni by mutableStateOf(aetherPsiphonCdnSni)
    var aetherPsiphonCdnSets by mutableStateOf(aetherPsiphonCdnSets)

    /** The CDN edge lists the Psiphon fronting scan tries, as chosen; none chosen means all of them. */
    val aetherPsiphonCdnSetChoice: Set<AetherPsiphonCdnSet>
        get() = AetherPsiphonCdnSet.parse(aetherPsiphonCdnSets).toSet()

    /** Chooses or drops one CDN edge list; the choice is kept in the order the core tries them. */
    fun setPsiphonCdnSet(set: AetherPsiphonCdnSet, chosen: Boolean) {
        val choice = aetherPsiphonCdnSetChoice.let { if (chosen) it + set else it - set }
        aetherPsiphonCdnSets = AetherPsiphonCdnSet.join(choice).orEmpty()
    }
    var aetherPsiphonRegion by mutableStateOf(aetherPsiphonRegion)
    var aetherPsiphonBundledList by mutableStateOf(aetherPsiphonBundledList)
    var aetherTor by mutableStateOf(aetherTor)
    var aetherTorBridges by mutableStateOf(aetherTorBridges)
    var aetherTorBridgeLines by mutableStateOf(aetherTorBridgeLines)
    var aetherTorRelays by mutableStateOf(aetherTorRelays)
    var aetherCommand by mutableStateOf(aetherCommand)

    /**
     * Whether an Aether setting the editor keeps folded away holds a value of its own, so that the
     * folded section opens by itself and nothing set stays out of sight.
     */
    val hasOtherAetherSettings: Boolean
        get() = aetherDns.isNotBlank() ||
            aetherExitLoc.isNotBlank() ||
            (targetStrategy.isNotBlank() && targetStrategy != defaultTargetStrategy)

    /**
     * PattNG: the targetStrategy of the profile as the screen sets it up now, while it stores none: an Aether
     * profile's follows where Tor and Psiphon stand, in its settings or in the command it carries in their place, see
     * [CoreOutboundBuilder.defaultTargetStrategy].
     */
    val defaultTargetStrategy: String
        get() = CoreOutboundBuilder.defaultTargetStrategy(
            ProfileItem.create(configType).also {
                it.aetherTor = aetherTor
                it.aetherPsiphon = aetherPsiphon
                it.aetherCommand = aetherCommand.nullIfBlank()
            }
        )

    /** The targetStrategy the screen shows: the one chosen, or the profile's default while none is. */
    val shownTargetStrategy: String
        get() = targetStrategy.ifBlank { defaultTargetStrategy }

    /**
     * PattNG: the Hysteria2 obfs the screen shows, by the rule the outbound and the link go by
     * (ProfileItem.isGeckoObfs): packet sizes make it gecko, whatever obfs names.
     */
    val shownHysteria2Obfs: String
        get() = when {
            obfs.equals("none", ignoreCase = true) -> "none"
            obfs.equals("gecko", ignoreCase = true) || minPacketSize.isNotBlank() || maxPacketSize.isNotBlank() -> "gecko"
            obfs.equals("salamander", ignoreCase = true) || obfsPassword.isNotBlank() -> "salamander"
            else -> "none"
        }

    /**
     * PattNG: picks the Hysteria2 obfs. Any pick but gecko drops gecko's packet sizes, which would keep the profile
     * gecko (see [shownHysteria2Obfs]); none drops the password too.
     */
    fun chooseHysteria2Obfs(type: String) {
        obfs = if (type == "none") "" else type
        if (type != "gecko") {
            minPacketSize = ""
            maxPacketSize = ""
        }
        if (type == "none") obfsPassword = ""
    }

    var isRemarksError by mutableStateOf(false)
    var isAddressError by mutableStateOf(false)
    var isPortError by mutableStateOf(false)
    var isPasswordError by mutableStateOf(false)

    /**
     * The profile the editor holds, built on [initialConfig]. An Aether profile's command line counts
     * as one of its own only when it says something else than its settings would on [aetherListenPort],
     * the Aether listen port of the settings when null. PattNG: [keepCommand] keeps it as typed, trimmed and
     * unweighed, as the screen's saved state does, which has no port of its own to weigh it on.
     */
    fun toProfileItem(initialConfig: ProfileItem, aetherListenPort: Int? = null, keepCommand: Boolean = false): ProfileItem {
        val isVmess = configType == EConfigType.VMESS
        val isVless = configType == EConfigType.VLESS
        val isShadowsocks = configType == EConfigType.SHADOWSOCKS
        val isSocksOrHttp = configType == EConfigType.SOCKS || configType == EConfigType.HTTP
        val isWireguard = configType == EConfigType.WIREGUARD
        val isMasque = configType == EConfigType.MASQUE
        val isHysteria2 = configType == EConfigType.HYSTERIA2
        val isAether = configType == EConfigType.AETHER
        val isPsiphon = isAether && aetherPsiphon != AetherPsiphon.OFF.type
        val isTor = isAether && aetherTor != AetherTor.OFF.type

        val profile = initialConfig.copy(
            configType = configType,
            remarks = remarks,
            server = address,
            serverPort = port,
            password = password,
            method = when {
                isVmess || isShadowsocks -> method
                isVless -> encryption
                else -> null
            },
            flow = if (isVless) flow else null,
            username = if (isSocksOrHttp) username else null,
            secretKey = if (isWireguard || isMasque) secretKey else null,
            publicKey = when {
                isWireguard || isMasque -> publicKey
                streamSecurity == REALITY -> publicKeyReality
                else -> null
            },
            preSharedKey = if (isWireguard) preSharedKey else null,
            reserved = if (isWireguard) reserved else null,
            localAddress = if (isWireguard || isMasque) localAddress else null,
            mtu = if (isWireguard) mtu.toIntOrNull() else null,
            remoteDNS = if (isWireguard || isMasque) remoteDNS else null,
            obfs = if (isHysteria2) obfs.nullIfBlank() else null,
            obfsPassword = if (isHysteria2) obfsPassword else null,
            minPacketSize = if (isHysteria2) minPacketSize.nullIfBlank() else null,
            maxPacketSize = if (isHysteria2) maxPacketSize.nullIfBlank() else null,
            portHopping = if (isHysteria2) portHopping else null,
            portHoppingInterval = if (isHysteria2) portHoppingInterval else null,
            bandwidthDown = if (isHysteria2) bandwidthDown else null,
            bandwidthUp = if (isHysteria2) bandwidthUp else null,
            network = network,
            headerType = headerType,
            mode = mode.nullIfBlank(),
            xhttpMode = xhttpMode.nullIfBlank(),
            serviceName = serviceName.nullIfBlank(),
            authority = authority.nullIfBlank(),
            host = host,
            path = path,
            headers = if (isMasque) headers.nullIfBlank() else null,
            xhttpExtra = xhttpExtra.nullIfBlank(),
            finalMask = finalMask.nullIfBlank(),
            seed = seed.nullIfBlank(),
            kcpMtu = kcpMtu.toIntOrNull(),
            kcpTti = kcpTti.toIntOrNull(),
            browserDialerMode = if (network in listOf(NetworkType.WS.type, NetworkType.XHTTP.type)) {
                browserDialerMode.nullIfBlank()
            } else {
                null
            },
            dialMode = dialMode.nullIfBlank(),
            // Set below, against the default of the profile as built here.
            targetStrategy = null,
            security = streamSecurity,
            sni = sni,
            insecure = allowInsecure,
            fingerPrint = fingerPrint,
            alpn = alpn,
            cipherSuites = cipherSuites,
            curvePreferences = curvePreferences,
            enableSessionResumption = enableSessionResumption,
            shortId = shortId,
            spiderX = spiderX,
            mldsa65Verify = mldsa65Verify,
            echConfigList = echConfigList,
            echOutbound = echOutbound.nullIfBlank(),
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedCA256 = pinnedCA256,
            aetherProtocol = if (isAether) aetherProtocol else null,
            aetherTransport = if (isAether) aetherTransport else null,
            // Kept whatever the protocol, as the transport is.
            aetherMasqueSni = if (isAether) aetherMasqueSni.nullIfBlank() else null,
            aetherScanMode = if (isAether) aetherScanMode else null,
            aetherObfuscation = if (isAether) aetherObfuscation else null,
            aetherFingerprint = if (isAether) aetherFingerprint else null,
            aetherIpVersion = if (isAether) aetherIpVersion else null,
            aetherWiwOuter = if (isAether) aetherWiwOuter.nullIfBlank() else null,
            aetherWiwInner = if (isAether) aetherWiwInner.nullIfBlank() else null,
            aetherFragment = if (isAether) aetherFragment else null,
            aetherFragmentSize = if (isAether) aetherFragmentSize.nullIfBlank() else null,
            aetherFragmentDelay = if (isAether) aetherFragmentDelay.nullIfBlank() else null,
            aetherEch = if (isAether) aetherEch else null,
            // Kept while ECH is off as well, as the WARP keys page keeps its own.
            aetherEchDns = if (isAether) aetherEchDns.nullIfBlank() else null,
            aetherEchDomain = if (isAether) aetherEchDomain.nullIfBlank() else null,
            aetherDns = if (isAether) aetherDns.nullIfBlank() else null,
            aetherExitLoc = if (isAether) aetherExitLoc.nullIfBlank() else null,
            aetherExitNode = if (isAether) aetherExitNode.nullIfBlank() else null,
            aetherPsiphon = if (isPsiphon) aetherPsiphon else null,
            aetherPsiphonMode = if (isPsiphon) aetherPsiphonMode else null,
            aetherPsiphonCdnIps = if (isPsiphon) aetherPsiphonCdnIps.nullIfBlank() else null,
            aetherPsiphonCdnSni = if (isPsiphon) aetherPsiphonCdnSni.nullIfBlank() else null,
            aetherPsiphonCdnSets = if (isPsiphon) aetherPsiphonCdnSets.nullIfBlank() else null,
            aetherPsiphonRegion = if (isPsiphon) aetherPsiphonRegion.nullIfBlank() else null,
            aetherPsiphonBundledList = if (isPsiphon && !aetherPsiphonBundledList) false else null,
            aetherTor = if (isTor) aetherTor else null,
            aetherTorBridges = if (isTor) aetherTorBridges else null,
            aetherTorBridgeLines = if (isTor) aetherTorBridgeLines.nullIfBlank() else null,
            aetherTorRelays = if (isTor) aetherTorRelays else null,
            aetherCommand = null,
        )
        // A command that says what the settings say is no command of its own: the profile follows the settings.
        val command = aetherCommand.trim()
        val built = if (!isAether || command.isEmpty() ||
            !keepCommand && command == AetherCore.of(profile, aetherListenPort ?: AetherCoreManager.socksPort).command
        ) {
            profile
        } else {
            profile.copy(aetherCommand = command)
        }
        // The profile's default is stored as none, so that the profile follows it, as Tor and Psiphon move it; anything
        // else as it is.
        return built.copy(targetStrategy = targetStrategy.takeUnless { it.isBlank() || it == CoreOutboundBuilder.defaultTargetStrategy(built) })
    }

    companion object {
        fun fromProfileItem(
            initialConfig: ProfileItem
        ): ServerUiState =
            ServerUiState(
                configType = initialConfig.configType,
                remarks = initialConfig.remarks,
                address = initialConfig.server ?: "",
                port = initialConfig.serverPort
                    ?: if (initialConfig.configType == EConfigType.AETHER) "" else DEFAULT_PORT.toString(),
                password = initialConfig.password ?: "",
                method = initialConfig.method ?: "",
                flow = initialConfig.flow ?: "",
                encryption = initialConfig.method ?: "",
                username = initialConfig.username ?: "",
                secretKey = initialConfig.secretKey ?: "",
                publicKey = initialConfig.publicKey ?: "",
                preSharedKey = initialConfig.preSharedKey ?: "",
                reserved = initialConfig.reserved ?: "0,0,0",
                localAddress = initialConfig.localAddress ?: "",
                mtu = initialConfig.mtu?.toString() ?: WIREGUARD_LOCAL_MTU,
                remoteDNS = initialConfig.remoteDNS ?: "",
                obfs = initialConfig.obfs ?: "",
                obfsPassword = initialConfig.obfsPassword ?: "",
                minPacketSize = initialConfig.minPacketSize ?: "",
                maxPacketSize = initialConfig.maxPacketSize ?: "",
                portHopping = initialConfig.portHopping ?: "",
                portHoppingInterval = initialConfig.portHoppingInterval ?: "",
                bandwidthDown = initialConfig.bandwidthDown ?: "",
                bandwidthUp = initialConfig.bandwidthUp ?: "",
                network = initialConfig.network ?: NetworkType.TCP.type,
                headerType = initialConfig.headerType ?: "none",
                mode = initialConfig.mode ?: "",
                xhttpMode = initialConfig.xhttpMode ?: "",
                serviceName = initialConfig.serviceName ?: "",
                authority = initialConfig.authority ?: "",
                host = initialConfig.host ?: "",
                path = initialConfig.path ?: "",
                headers = initialConfig.headers ?: "",
                xhttpExtra = initialConfig.xhttpExtra ?: "",
                finalMask = initialConfig.finalMask ?: "",
                seed = initialConfig.seed ?: "",
                kcpMtu = initialConfig.kcpMtu?.toString() ?: "",
                kcpTti = initialConfig.kcpTti?.toString() ?: "",
                browserDialerMode = initialConfig.browserDialerMode ?: "",
                dialMode = initialConfig.dialMode ?: "",
                targetStrategy = initialConfig.targetStrategy.orEmpty(),
                streamSecurity = initialConfig.security ?: "",
                sni = initialConfig.sni ?: "",
                allowInsecure = initialConfig.insecure == true,
                fingerPrint = initialConfig.fingerPrint ?: "",
                alpn = initialConfig.alpn ?: "",
                cipherSuites = initialConfig.cipherSuites ?: "",
                curvePreferences = initialConfig.curvePreferences ?: "",
                enableSessionResumption = initialConfig.enableSessionResumption == true,
                publicKeyReality = initialConfig.publicKey ?: "",
                shortId = initialConfig.shortId ?: "",
                spiderX = initialConfig.spiderX ?: "",
                mldsa65Verify = initialConfig.mldsa65Verify ?: "",
                echConfigList = initialConfig.echConfigList ?: "",
                echOutbound = initialConfig.echOutbound ?: "",
                verifyPeerCertByName = initialConfig.verifyPeerCertByName ?: "",
                pinnedCA256 = initialConfig.pinnedCA256 ?: "",
                // Normalized so the dropdowns always hold one of their own values, whatever was persisted.
                aetherProtocol = AetherProtocol.fromString(initialConfig.aetherProtocol).type,
                aetherTransport = AetherTransport.fromString(initialConfig.aetherTransport).type,
                // Shown filled in, as the ECH settings are, so that what the core is told is in sight.
                aetherMasqueSni = initialConfig.aetherMasqueSni.nullIfBlank() ?: AETHER_MASQUE_SNI,
                aetherScanMode = AetherScanMode.fromString(initialConfig.aetherScanMode).type,
                aetherObfuscation = AetherObfuscation.fromString(initialConfig.aetherObfuscation).type,
                aetherFingerprint = AetherFingerprint.fromString(initialConfig.aetherFingerprint).type,
                aetherIpVersion = AetherIpVersion.fromString(initialConfig.aetherIpVersion).type,
                aetherWiwOuter = initialConfig.aetherWiwOuter ?: "",
                aetherWiwInner = initialConfig.aetherWiwInner ?: "",
                aetherFragment = initialConfig.aetherFragment ?: false,
                aetherFragmentSize = initialConfig.aetherFragmentSize ?: "",
                aetherFragmentDelay = initialConfig.aetherFragmentDelay ?: "",
                aetherEch = initialConfig.aetherEch == true,
                // Shown filled in, so that what the core is told is in sight.
                aetherEchDns = initialConfig.aetherEchDns.nullIfBlank() ?: AETHER_ECH_DNS,
                aetherEchDomain = initialConfig.aetherEchDomain.nullIfBlank() ?: AETHER_ECH_DOMAIN,
                aetherDns = initialConfig.aetherDns ?: "",
                aetherExitLoc = initialConfig.aetherExitLoc ?: "",
                aetherExitNode = initialConfig.aetherExitNode ?: "",
                aetherPsiphon = AetherPsiphon.fromString(initialConfig.aetherPsiphon).type,
                aetherPsiphonMode = AetherPsiphonMode.fromString(initialConfig.aetherPsiphonMode).type,
                aetherPsiphonCdnIps = initialConfig.aetherPsiphonCdnIps ?: "",
                aetherPsiphonCdnSni = initialConfig.aetherPsiphonCdnSni ?: "",
                aetherPsiphonCdnSets = initialConfig.aetherPsiphonCdnSets ?: "",
                aetherPsiphonRegion = initialConfig.aetherPsiphonRegion ?: "",
                aetherPsiphonBundledList = initialConfig.aetherPsiphonBundledList != false,
                aetherTor = AetherTor.fromString(initialConfig.aetherTor).type,
                aetherTorBridges = AetherTorBridges.fromString(initialConfig.aetherTorBridges).type,
                aetherTorBridgeLines = initialConfig.aetherTorBridgeLines ?: "",
                aetherTorRelays = AetherTorRelays.fromString(initialConfig.aetherTorRelays).type,
                aetherCommand = initialConfig.aetherCommand ?: ""
            )

        fun from(
            initialConfig: ProfileItem
        ): ServerUiState = fromProfileItem(initialConfig)

        val Saver: Saver<ServerUiState, String> = Saver(
            // PattNG: kept as typed, trimmed, see toProfileItem: weighing it would read the listen port of the settings.
            save = { JsonUtil.toJson(it.toProfileItem(ProfileItem.create(it.configType), keepCommand = true)) },
            restore = { saved ->
                JsonUtil.fromJsonSafe(saved, ProfileItem::class.java)?.let {
                    fromProfileItem(it)
                }
            }
        )
    }
}
