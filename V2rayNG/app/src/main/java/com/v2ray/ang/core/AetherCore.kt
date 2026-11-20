package com.v2ray.ang.core

import androidx.annotation.StringRes
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import java.security.MessageDigest
import java.util.UUID

/**
 * The Aether core a configuration runs on, as the arguments its process is started with, the
 * listeners among them. The core of a profile is built from the profile's settings, or is the
 * command line the profile carries in their place; the core of a custom configuration is the
 * command line it carries as aetherCommand. A command line is read as written and run as written,
 * so that what the profile or the configuration says is what runs. Two cores with the same
 * arguments are one core, which is how one process comes to serve several outbounds, as long as
 * they dial out through the same [exit] as well: a process dials out through one.
 */
data class AetherCore(val arguments: List<String>, val exit: AetherExit = AetherExit.PLAIN) {

    /**
     * The loopback port the app dials: Psiphon's or Tor's listener when one of them runs inside the
     * tunnel and is what the app reaches, the core's own listener otherwise.
     */
    val port: Int get() = AetherCoreManager.listenerPortOf(arguments) ?: AetherCoreManager.socksPort

    /**
     * Every port the core listens on as its modes run, its HTTP proxies' too, see [boundListeners]: the ports it is told,
     * and its defaults for the listeners it is not told about; an inbound of the configuration cannot share one.
     */
    val ports: List<Int>
        get() = portsOf(boundListeners).distinct()

    /**
     * The first port two of the listeners the core holds at one time share, as a command written by hand may name;
     * null when each has a port of its own. The core would bind one of them and fail on the other. Ports are told
     * apart by number, as everywhere else in the app. PattNG: Tor or Psiphon alone lets the core's own HTTP proxy go
     * before that carrier's is bound, so those two may share a port, see [listenerGroups].
     */
    val sharedPort: Int?
        get() = listenerGroups.firstNotNullOfOrNull { group ->
            val seen = mutableSetOf<Int>()
            portsOf(group).firstOrNull { !seen.add(it) }
        }

    /**
     * PattNG: the first option of a listener the core binds whose address the core does not take; null when it takes
     * each. The core reads each as an IP address and a port, and where it cannot read one it quietly listens on its
     * default, for its own, Tor's or Psiphon's listener, or not at all, for an HTTP proxy, while the app would go by the
     * address written (aether lib.rs, tor.rs and psiphon.rs). A blank address, or "off", turns an HTTP proxy off. A
     * message names the option, not the address, which is free text the log must not carry.
     */
    val invalidListener: String?
        get() = boundListeners.firstOrNull { flag ->
            val address = AetherCoreManager.listenerAddressAfter(arguments, flag) ?: return@firstOrNull false
            !AetherCoreManager.isSocketAddress(address) && !(flag in HTTP_LISTENERS && (address.isEmpty() || address == "off"))
        }

    /**
     * PattNG: the option of the listener the app dials, see [AetherCoreManager.listenerFlagOf], when the core takes its
     * address but the app does not reach it there; null when it does, or when the command names none and the app gives it
     * one. The app dials the core on 127.0.0.1 (the Xray outbound, the readiness probe, the port checks), which a listener
     * on 0.0.0.0 or :: answers as well, see [AetherCoreManager.reachesLoopback], but one on 127.0.0.2, ::1 or a network's
     * address does not: the session would stay connecting.
     */
    val unreachableListener: String?
        get() {
            val flag = AetherCoreManager.listenerFlagOf(arguments)
            val address = AetherCoreManager.listenerAddressAfter(arguments, flag) ?: return null
            if (!AetherCoreManager.isSocketAddress(address)) return null
            return flag.takeUnless { AetherCoreManager.reachesLoopback(address.substringBeforeLast(':')) }
        }

    /**
     * The ports of [listeners], in their order, but 0, which asks for any port: the port the arguments name, or the
     * core's default for a listener they leave out, see [AetherCoreManager.defaultListenerPorts]. A command written by
     * hand that names only Tor's listener still has the core's own on 1819.
     */
    private fun portsOf(listeners: List<String>): List<Int> =
        listeners.mapNotNull { flag ->
            if (flag in arguments) AetherCoreManager.portAfter(arguments, flag) else AetherCoreManager.defaultListenerPorts[flag]
        }.filter { it != 0 }

    /** Every listener option the core binds as [arguments] run it, in the order of [LISTENERS], see [listenerGroups]. */
    private val boundListeners: List<String>
        get() {
            val bound = listenerGroups.flatten().toSet()
            return LISTENERS.filter { it in bound }
        }

    /**
     * The listener options the core binds as [arguments] run it, in the groups it holds at one time: its own and its
     * HTTP proxy always, Tor's listener with Tor inside or around the tunnel and Tor's HTTP proxy with Tor run in any
     * way, and the same for Psiphon, all of them for the whole run. An option of a mode that does not run binds nothing
     * (aether lib.rs, tor.rs and psiphon.rs). Tor or Psiphon alone, see [AetherCoreManager.aloneOf]: the core binds its
     * own listener and its own HTTP proxy as it starts, then lets that HTTP proxy go and serves its own listener with
     * Tor or Psiphon, which binds that carrier's HTTP proxy (lib.rs, Listeners::bind and into_socks). PattNG: a
     * registration of keys serves no proxy of its own, and binds only the listeners of a carrier around the tunnel,
     * which carries its requests: that carrier's own and its HTTP proxy (lib.rs, register_identities; tor.rs and
     * psiphon.rs, start_reverse).
     */
    private val listenerGroups: List<List<String>>
        get() {
            val tor = AetherCoreManager.torModeOf(arguments)
            val psiphon = AetherCoreManager.psiphonModeOf(arguments)
            if (AetherKeys.REGISTER in arguments) {
                return listOf(
                    LISTENERS.filter { flag ->
                        (tor == AetherTor.REVERSE && (flag == AetherCoreManager.TOR_BIND || flag == AetherCoreManager.TOR_HTTP)) ||
                            (psiphon == AetherPsiphon.REVERSE && (flag == AetherCoreManager.PSIPHON_BIND || flag == AetherCoreManager.PSIPHON_HTTP))
                    }
                )
            }
            if (AetherCoreManager.aloneOf(arguments) != null) {
                val aloneHttp = if (tor == AetherTor.ONLY) AetherCoreManager.TOR_HTTP else AetherCoreManager.PSIPHON_HTTP
                return listOf(listOf("--bind", AetherCoreManager.HTTP_PROXY), listOf("--bind", aloneHttp))
            }
            return listOf(LISTENERS.filter { flag ->
                when (flag) {
                    AetherCoreManager.TOR_BIND -> tor == AetherTor.CHAIN || tor == AetherTor.REVERSE
                    AetherCoreManager.TOR_HTTP -> tor != AetherTor.OFF
                    AetherCoreManager.PSIPHON_BIND -> psiphon == AetherPsiphon.CHAIN || psiphon == AetherPsiphon.REVERSE
                    AetherCoreManager.PSIPHON_HTTP -> psiphon != AetherPsiphon.OFF
                    else -> true
                }
            })
        }

    /** The protocol, which tells whose identity files the core uses. */
    val protocol: AetherProtocol get() = AetherCoreManager.protocolOf(arguments)

    /** The tunnel as the names of its parts from the outside in, carriers included; see [AetherCoreManager.pathOf]. */
    val path: List<String> get() = AetherCoreManager.pathOf(arguments)

    /** [path], with the WARP protocol named by [label], as a screen names it. */
    fun path(label: (AetherProtocol) -> String): List<String> = AetherCoreManager.pathOf(arguments, label)

    /** The command line a profile or a custom configuration carries for this core; [ofCommand] reads it back. */
    val command: String get() = (listOf(COMMAND_NAME) + arguments).joinToString(" ", transform = ::quoted)

    /** True when a process started with [processArguments] runs this core, on whatever SOCKS ports and at whatever log level. */
    fun runsAs(processArguments: List<String>): Boolean =
        AetherCoreManager.tunnelArguments(processArguments) == AetherCoreManager.tunnelArguments(arguments)

    /** True when the core is told which proxy to dial out through, as a command written by hand may be. */
    val hasUpstream: Boolean get() = AetherCoreManager.UPSTREAM in arguments

    /**
     * This core dialling out through the SOCKS inbound on [port] of the loopback address, which Xray
     * serves so that what the core sends leaves through Xray, signing in with [password] when it is
     * given. A core told an upstream of its own keeps it.
     */
    fun through(port: Int, password: String? = null): AetherCore {
        if (hasUpstream) return this
        val account = password?.let { "${AetherCoreManager.UPSTREAM_USER}:$it@" }.orEmpty()
        return copy(arguments = arguments + listOf(AetherCoreManager.UPSTREAM, "socks5://$account${AppConfig.LOOPBACK}:$port"))
    }

    /**
     * PattNG: a new password for the inbound this core dials out through, see [through]. The app's own process is
     * outside the VPN, so an inbound without one lets any app on the device leave past the VPN. Null when Tor dials
     * out through the upstream itself, before WARP or alone: Tor takes a socks5 address without a password (aether
     * tor.rs), so that inbound stays open until the core can hand Tor one.
     */
    fun newUpstreamPassword(): String? = when (AetherCoreManager.torModeOf(arguments)) {
        AetherTor.REVERSE, AetherTor.ONLY -> null
        else -> UUID.randomUUID().toString().replace("-", "")
    }

    companion object {

        /** The name a command line starts with; the app runs its own copy of the core whatever the name says. */
        const val COMMAND_NAME = "aether"

        /**
         * The listeners a core may be told to bind: the core's own, Tor's, Psiphon's, and the HTTP proxies a command
         * written by hand may open beside them.
         */
        private val LISTENERS = listOf(
            "--bind",
            AetherCoreManager.TOR_BIND,
            AetherCoreManager.PSIPHON_BIND,
            AetherCoreManager.HTTP_PROXY,
            AetherCoreManager.TOR_HTTP,
            AetherCoreManager.PSIPHON_HTTP,
        )

        /** PattNG: the HTTP proxies among [LISTENERS], which a blank address or "off" turns off. */
        private val HTTP_LISTENERS = setOf(AetherCoreManager.HTTP_PROXY, AetherCoreManager.TOR_HTTP, AetherCoreManager.PSIPHON_HTTP)

        /**
         * The core of [profile]: the command line it carries, or its settings as arguments on
         * [listenPort], the Aether listen port of the app, dialling out through the exit-node of its
         * settings. The log level is the session's to add. A command the app cannot read is left aside
         * for the settings; the profile editor refuses to store one. A screen passes the port it holds,
         * since the setting is read from storage; a command naming no listener gets one there as well.
         */
        fun of(profile: ProfileItem, listenPort: Int = AetherCoreManager.socksPort): AetherCore {
            val core = profile.aetherCommand?.takeIf { it.isNotBlank() }?.let { ofCommand(it, listenPort) }
                ?: AetherCore(
                    AetherCoreManager.withoutOption(
                        AetherCoreManager.buildArguments(profile, listenPort),
                        "--log-level",
                    )
                )
            return core.copy(exit = AetherExit.of(profile))
        }

        /**
         * The core [command] describes, or null when it names nothing the app can run: no argument
         * at all, or a listener whose port cannot be read. Words are split on whitespace, quotes keep a
         * word together, and a program name in front is dropped. A command that names no listener for
         * the app to dial gets one on [listenPort], [AetherCoreManager.socksPort] unless a screen passes
         * the port it holds, the port the Aether outbounds of the app dial unless told otherwise; the
         * core's own defaults are other ports, which nothing in the app dials.
         */
        fun ofCommand(command: String, listenPort: Int = AetherCoreManager.socksPort): AetherCore? {
            val arguments = readableArguments(command) ?: return null
            val listener = AetherCoreManager.listenerFlagOf(arguments)
            if (listener !in arguments) return AetherCore(AetherCoreManager.withListener(arguments, listener, listenPort))
            return AetherCore(arguments)
        }

        /**
         * The arguments of [command] when it names something the app can run, see [ofCommand]: not no argument at all,
         * nor a listener whose port cannot be read. Null otherwise.
         */
        private fun readableArguments(command: String): List<String>? {
            val arguments = argumentsOf(command)
            if (arguments.isEmpty()) return null
            val listener = AetherCoreManager.listenerFlagOf(arguments)
            return arguments.takeIf { listener !in arguments || AetherCoreManager.portAfter(arguments, listener) != null }
        }

        /**
         * PattNG: whether what the app sends through the core of [profile] leaves it through WARP, rather than through
         * Tor or Psiphon, see [AetherCoreManager.leavesThroughWarp]: as the command line the profile carries says, or
         * else, as for [of], as its settings say.
         */
        fun leavesThroughWarp(profile: ProfileItem): Boolean {
            val arguments = profile.aetherCommand?.takeIf { it.isNotBlank() }?.let(::readableArguments)
            return if (arguments != null) {
                AetherCoreManager.leavesThroughWarp(arguments)
            } else {
                AetherCoreManager.leavesThroughWarp(AetherTor.fromString(profile.aetherTor), AetherPsiphon.fromString(profile.aetherPsiphon))
            }
        }

        /**
         * PattNG: whether the core of [profile] connects out over TCP, see [AetherCoreManager.connectsOverTcp]: as the
         * command line the profile carries says, or else, as for [of], as its settings say.
         */
        fun connectsOverTcp(profile: ProfileItem): Boolean {
            val arguments = profile.aetherCommand?.takeIf { it.isNotBlank() }?.let(::readableArguments)
            return if (arguments != null) {
                AetherCoreManager.connectsOverTcp(arguments)
            } else {
                AetherCoreManager.connectsOverTcp(
                    AetherProtocol.fromString(profile.aetherProtocol),
                    AetherTransport.fromString(profile.aetherTransport),
                    AetherTor.fromString(profile.aetherTor),
                    AetherPsiphon.fromString(profile.aetherPsiphon),
                )
            }
        }

        /** The arguments of [command]: its [words], without a program name in front. */
        internal fun argumentsOf(command: String): List<String> {
            val words = words(command)
            return if (words.firstOrNull()?.startsWith("-") == false) words.drop(1) else words
        }

        /** The words of a command line: split on whitespace, with single or double quotes keeping a word together. */
        internal fun words(command: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var open = false
            for (c in command) {
                when {
                    quote != null -> if (c == quote) quote = null else word.append(c)
                    c == '"' || c == '\'' -> {
                        quote = c
                        open = true
                    }

                    c.isWhitespace() -> if (open) {
                        words.add(word.toString())
                        word.setLength(0)
                        open = false
                    }

                    else -> {
                        word.append(c)
                        open = true
                    }
                }
            }
            if (open) words.add(word.toString())
            return words
        }

        /** [word] as a command line carries it: quoted when whitespace would split it. */
        private fun quoted(word: String): String = if (word.isEmpty() || word.any(Char::isWhitespace)) "\"$word\"" else word
    }
}

/**
 * PattNG: the exit-node of an Aether core, the outbound that what the core dials out through leaves
 * Xray by. As a rule it is a plain freedom outbound with the finalMask, the dialMode and the
 * tcpUserTimeout of the core's Aether profile, as an ordinary profile sets them on its own outbound:
 * an Aether profile's outbound only reaches the core on the loopback address, where they would do
 * nothing. See [CoreOutboundBuilder.toOutboundAetherExit].
 *
 * In a proxy chain where the Aether profile is not the entry hop, the one that dials the internet
 * itself, the core dials out through the hop on its entry side instead: that hop's outbound, as the
 * chain builds it, is the exit-node, and [hops] tells those hops apart. The profile's finalMask and
 * dialMode do not count then, nor does anything else of a plain exit-node, its [node] included.
 *
 * Without a chain, the profile, or the WARP keys page, can name a profile of its own as the
 * exit-node, one a chain takes for a hop: [node], by its name, as a chain names its hops. That
 * profile's outbound, as a chain builds it, is the exit-node then, changed in its tag alone, and the
 * finalMask and the dialMode do not count either. Who builds it looks the profile up, see
 * [CoreOutboundBuilder.toOutboundOfNode]: a name no profile has any more, or several have, gives none.
 */
data class AetherExit(
    val finalMask: String? = null,
    val dialMode: String? = null,
    val hops: String? = null,
    val node: String? = null,
    /**
     * A digest of the profile [node] names, as [contentOf] makes it, where it was looked up: as [hops] does for the
     * hops of a chain, it tells the profile the core dials out through from the one that has the name by now, which an
     * update of its subscription may have changed. See [withNodeContent].
     */
    val nodeContent: String? = null,
    /**
     * The tcpUserTimeout of the freedom exit-node, in milliseconds: the profile's where its core connects out over TCP
     * ([TcpUserTimeoutRule.valueOf]), else null. The WARP keys page sets none.
     */
    val tcpUserTimeout: Int? = null,
) {

    /**
     * What tells this exit-node from another in another process, without what it is made of: a digest
     * of it, which the session's core carries in its environment, see [AetherCoreManager.EXIT_ENV]. A
     * node and its content join only where there are some, and so does a tcpUserTimeout, named, so that the key of any
     * other exit-node stays what it was.
     */
    val key: String
        get() = digest(
            (listOf(finalMask, dialMode, hops) + listOfNotNull(node, nodeContent, tcpUserTimeout?.let { "tcpUserTimeout=$it" }))
                .joinToString("\u0000") { it.orEmpty() }
        )

    /**
     * This exit-node with the content of the profile its [node] names, as [find] finds it, see [nodeContent]: as it is
     * without a node, or with one no profile, or several, have the name of, which no core dials out through.
     */
    fun withNodeContent(find: (String) -> ByName<ProfileItem> = Companion::nodeProfile): AetherExit {
        val name = node ?: return this
        val profile = (find(name) as? ByName.One)?.value ?: return this
        return copy(nodeContent = contentOf(profile))
    }

    companion object {
        /** An exit-node with nothing set, as the core of a custom configuration dials out through. */
        val PLAIN = AetherExit()

        /**
         * The exit-node of the core of [profile]: the profile it names as its node, or else a plain one with its finalMask
         * and dialMode, and its tcpUserTimeout where the core connects out over TCP.
         */
        fun of(profile: ProfileItem): AetherExit =
            profile.aetherExitNode.nullIfBlank()?.let { AetherExit(node = it.trim()) }
                ?: AetherExit(profile.finalMask.nullIfBlank(), profile.dialMode.nullIfBlank(), tcpUserTimeout = TcpUserTimeoutRule.valueOf(profile))

        /**
         * Whether [profile] can be an exit-node: a profile a proxy chain takes for a hop, and no Aether one, whose
         * core is the one that dials out.
         */
        fun takesAsNode(profile: ProfileItem): Boolean =
            CoreConfigContextBuilder.takesAsHop(profile) && profile.configType != EConfigType.AETHER

        /** What [name] finds among the profiles that can be an exit-node, see [ByName]. */
        fun nodeProfile(name: String): ByName<ProfileItem> = SettingsManager.findServerViaRemarks(name, ::takesAsNode)

        /** The names of the profiles that can be an exit-node, see [nodesOf], in the order of the server list. */
        fun nodes(): List<AetherExitNode> =
            nodesOf(MmkvManager.decodeAllServerList().asSequence().mapNotNull { MmkvManager.decodeServerConfig(it) })

        /**
         * The names those of [profiles] that can be an exit-node have, each once, in their order, with how many have it:
         * a name several have names none of them, see [ByName]. A profile without a name is left out, for none names it.
         */
        internal fun nodesOf(profiles: Sequence<ProfileItem>): List<AetherExitNode> =
            profiles.filter(::takesAsNode).map { it.remarks.trim() }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCountTo(LinkedHashMap())
                .map { (name, count) -> AetherExitNode(name, count) }

        /**
         * Whether an exit-node takes [finalMask]: blank, or a JSON object, read by the parser JsonUtil uses but
         * without the error JsonUtil logs for text that is not one, since a typing mistake is no failure. The Aether
         * editor and the WARP keys page check theirs alike.
         */
        fun takesFinalMask(finalMask: String?): Boolean = finalMask.isNullOrBlank() || try {
            JsonParser.parseString(finalMask).isJsonObject
        } catch (_: RuntimeException) {
            false
        }

        /**
         * The exit-node of an Aether hop of a proxy chain that dials out through [hops]: the hops on its
         * entry side, in the order a chain lists its profiles, from the one it dials out through, which
         * is the exit-node, to the entry hop. They are told apart by a digest of their profiles, which
         * hold secrets, see [contentOf].
         */
        fun through(hops: List<ProfileItem>): AetherExit = AetherExit(hops = digest(JsonUtil.toJson(hops.map { it.duplicateIdentity() })))

        /**
         * The digest [nodeContent] holds of [profile], the exit-node a [node] names; it holds secrets as well. PattNG:
         * like the digest of hops, see [through], it covers what the profile connects with, not its name, its
         * subscription or when it was added, which every update of its subscription renews, see
         * [ProfileItem.duplicateIdentity]: a node an update leaves as it was is the same node.
         */
        fun contentOf(profile: ProfileItem): String = digest(JsonUtil.toJson(profile.duplicateIdentity()))

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * PattNG: a [name] the profiles that can be the exit-node of an Aether core have, see [AetherExit.node], and how many
 * [profiles] have it; with more than one, it names none of them.
 */
data class AetherExitNode(val name: String, val profiles: Int)

/**
 * PattNG: what the name of the profile an Aether core dials out through gives, see [AetherExit.node]: the outbound of
 * the one profile that has it, built as a proxy chain builds a hop, or the [Problem] that leaves the core without one.
 */
sealed interface ExitNodeOutbound {
    /** The [outbound] of the one profile that has the name, and the [content] of that profile, see [AetherExit.contentOf]. */
    data class Built(val outbound: V2rayConfig.OutboundBean, val content: String? = null) : ExitNodeOutbound

    /** Why there is no exit-node, with the [message] that tells it, whose argument is the name. */
    sealed interface Problem : ExitNodeOutbound {
        @get:StringRes
        val message: Int
    }

    /** No profile that can be an exit-node has the name any more: it was renamed or deleted. */
    data object NotFound : Problem {
        override val message: Int get() = R.string.toast_profile_name_not_found
    }

    /** Several have it, and the name cannot tell the one meant. */
    data object SameName : Problem {
        override val message: Int get() = R.string.toast_profile_name_duplicate
    }

    /** The one that has it gives no outbound. */
    data object NoOutbound : Problem {
        override val message: Int get() = R.string.aether_exit_node_unusable
    }

    /**
     * The one that has it has an ECH outbound that cannot go beside it: one no configuration takes, see
     * [EchOutbound.validate], or one tagged exit-node, as the profile's own outbound is here.
     */
    data object EchUnusable : Problem {
        override val message: Int get() = R.string.aether_exit_node_ech_unusable
    }
}
