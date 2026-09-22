package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.AetherRange
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

object AetherFmt : FmtBase() {

    enum class Problem {
        INVALID_PEER,
        INVALID_HOP,
        SHARED_HOP,
        INVALID_FRAGMENT,
        INVALID_LISTEN_PORT,
        LISTEN_PORT_TAKEN,
    }

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AETHER)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        val protocol = AetherProtocol.fromString(queryParam["protocol"])

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "Aether" }
        config.aetherProtocol = protocol.type
        config.aetherTransport = AetherTransport.fromString(queryParam["transport"]).type
        config.aetherScanMode = AetherScanMode.fromString(queryParam["scan"]).type
        config.aetherObfuscation = AetherObfuscation.fromString(queryParam["noize"]).type
        config.aetherIpVersion = AetherIpVersion.fromString(queryParam["ip"]).type
        config.aetherFragment = queryParam["fragment"] == "1"
        config.aetherFragmentSize = AetherRange.parse(queryParam["fragment_size"], AetherRange.FRAGMENT_SIZE)?.toString()
        config.aetherFragmentDelay = AetherRange.parse(queryParam["fragment_delay"], AetherRange.FRAGMENT_DELAY)?.toString()
        config.aetherListenPort = listenPortOf(queryParam["listen"])?.let(::storedListenPort)

        if (protocol == AetherProtocol.GOOL) {
            val outer = AetherEndpoint.parse(queryParam["outer"])
            val inner = AetherEndpoint.parse(queryParam["inner"])?.takeUnless { it.host == outer?.host }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
        } else {
            val endpoint = AetherEndpoint.of(uri.idnHost, uri.port.takeIf { it > 0 }?.toString())
            config.server = endpoint?.host
            config.serverPort = endpoint?.port?.toString()
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val protocol = AetherProtocol.fromString(config.aetherProtocol)
        val query = linkedMapOf(
            "protocol" to protocol.type,
            "scan" to AetherScanMode.fromString(config.aetherScanMode).type,
            "noize" to AetherObfuscation.fromString(config.aetherObfuscation).type,
            "ip" to AetherIpVersion.fromString(config.aetherIpVersion).type,
        )
        if (protocol == AetherProtocol.MASQUE) {
            query["transport"] = AetherTransport.fromString(config.aetherTransport).type
            if (config.aetherFragment == true) {
                query["fragment"] = "1"
                AetherRange.parse(config.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                    ?.let { query["fragment_size"] = it.toString() }
                AetherRange.parse(config.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                    ?.let { query["fragment_delay"] = it.toString() }
            }
        }
        if (protocol == AetherProtocol.GOOL) {
            AetherEndpoint.parse(config.aetherWiwOuter)?.let { query["outer"] = it.toString() }
            AetherEndpoint.parse(config.aetherWiwInner)?.let { query["inner"] = it.toString() }
        }
        listenPortOf(config.aetherListenPort)?.let(::storedListenPort)?.let { query["listen"] = it }
        val endpoint = AetherEndpoint.of(config.server, config.serverPort).takeUnless { protocol == AetherProtocol.GOOL }

        val queryText = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        return "${endpoint ?: ""}?$queryText#${Utils.encodeURIComponent(config.remarks)}"
    }

    /**
     * The aetherCommand of a profile: the command line a custom configuration runs the same core
     * with, written as the full configuration of an Aether profile carries it. The endpoint
     * resolution is left to the core, as on a start from the profile itself; unlike that start,
     * the arguments are handed to the core word for word, so they can be edited before a run.
     */
    fun toCommand(config: ProfileItem): String {
        val arguments = AetherCoreManager.buildArguments(config, AetherCoreManager.listenPort(config))
        return (listOf("aether") + arguments).joinToString(" ")
    }

    /**
     * The loopback port an aetherCommand's arguments bind, null when they name none. A custom
     * configuration with an aetherCommand needs a SOCKS outbound that dials this port.
     */
    fun bindPortOfCommand(arguments: List<String>): Int? {
        val bind = arguments.indexOf("--bind")
        if (bind < 0) return null
        return arguments.getOrNull(bind + 1)?.substringAfterLast(':', "")?.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /** The loopback port [text] names for the core to listen on, null when it names none. */
    fun listenPortOf(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * The listen port as a profile stores it: nothing for the default, so a profile saved before
     * the port could be chosen and one saved with the default stay the same profile.
     */
    fun storedListenPort(port: Int): String? = port.toString().takeUnless { it == AppConfig.PORT_AETHER_SOCKS }

    /**
     * [takenPorts] are loopback ports something else of the app listens on, the local proxy above
     * all; the core of the profile cannot listen there as well.
     */
    fun normalize(config: ProfileItem, takenPorts: Set<Int> = emptySet()): Problem? =
        normalizeFragment(config) ?: normalizeEndpoints(config) ?: normalizeListenPort(config, takenPorts)

    private fun normalizeListenPort(config: ProfileItem, takenPorts: Set<Int>): Problem? {
        val text = config.aetherListenPort?.trim().orEmpty()
        val port = listenPortOf(text)
        if (text.isNotEmpty() && port == null) return Problem.INVALID_LISTEN_PORT
        // The default port can be taken too, once the local proxy has been moved onto it.
        if ((port ?: AppConfig.PORT_AETHER_SOCKS.toInt()) in takenPorts) return Problem.LISTEN_PORT_TAKEN
        config.aetherListenPort = port?.let(::storedListenPort)
        return null
    }

    private fun normalizeFragment(config: ProfileItem): Problem? {
        val inUse = AetherProtocol.fromString(config.aetherProtocol) == AetherProtocol.MASQUE &&
            AetherTransport.fromString(config.aetherTransport) == AetherTransport.HTTP2 &&
            config.aetherFragment == true
        val sizeText = config.aetherFragmentSize?.trim().orEmpty()
        val delayText = config.aetherFragmentDelay?.trim().orEmpty()
        val size = AetherRange.parse(sizeText, AetherRange.FRAGMENT_SIZE)
        val delay = AetherRange.parse(delayText, AetherRange.FRAGMENT_DELAY)
        if (inUse && (sizeText.isNotEmpty() && size == null || delayText.isNotEmpty() && delay == null)) {
            return Problem.INVALID_FRAGMENT
        }
        config.aetherFragmentSize = size?.toString()
        config.aetherFragmentDelay = delay?.toString()
        return null
    }

    private fun normalizeEndpoints(config: ProfileItem): Problem? {
        if (AetherProtocol.fromString(config.aetherProtocol) == AetherProtocol.GOOL) {
            val outerText = config.aetherWiwOuter?.trim().orEmpty()
            val innerText = config.aetherWiwInner?.trim().orEmpty()
            val outer = AetherEndpoint.parse(outerText)
            val inner = AetherEndpoint.parse(innerText)
            if (outerText.isNotEmpty() && outer == null || innerText.isNotEmpty() && inner == null) {
                return Problem.INVALID_HOP
            }
            if (outer != null && inner != null && outer.host == inner.host) {
                return Problem.SHARED_HOP
            }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
            config.server = null
            config.serverPort = null
            return null
        }

        val address = config.server?.trim().orEmpty()
        val endpoint = AetherEndpoint.of(address, config.serverPort)
        if (address.isNotEmpty() && endpoint == null) {
            return Problem.INVALID_PEER
        }
        config.server = endpoint?.host
        config.serverPort = endpoint?.port?.toString()
        config.aetherWiwOuter = null
        config.aetherWiwInner = null
        return null
    }
}
