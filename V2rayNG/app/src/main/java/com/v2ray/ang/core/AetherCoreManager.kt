package com.v2ray.ang.core

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.AetherRange
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Owns the Aether core process of the daemon: one live session at a time, an exit callback the
 * service reacts to, output relayed into the app log, readiness probing, and cleanup of leftover
 * processes. `service/ProcessService` is a fire-and-forget wrapper with none of that lifecycle,
 * which is why this is a separate owner rather than an extension of it.
 */
object AetherCoreManager {

    private const val BINARY_NAME = "libaether.so"
    private const val PROBE_TIMEOUT_MS = 1000
    private const val READY_POLL_MS = 500L
    private const val DEFAULT_LOG_LEVEL = "info"

    /**
     * Environment variable naming the app process that spawned a core process. Rust ignores
     * SIGPIPE and the core has no parent-death handling, so a core whose owner was killed keeps
     * running until something else kills it; [reapStale] recognises such orphans by this value.
     */
    internal const val OWNER_ENV = "PATTNG_AETHER_OWNER"

    /**
     * Environment variable set on the daemon's session core and on no other. A profile and a custom
     * configuration choose the port their core listens on, so the port does not tell the session
     * from a scan or a test core; this does, for the processes that have to leave the session's key
     * alone.
     */
    internal const val SESSION_ENV = "PATTNG_AETHER_SESSION"

    private val logLevels = setOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
    private val procDir = File("/proc")

    private val lifecycle = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aether-core").apply { isDaemon = true }
    }

    /** The port a core listens on unless its profile or its custom configuration names another. */
    val socksPort: Int get() = AppConfig.PORT_AETHER_SOCKS.toInt()

    /** The loopback port the session core of [profile] listens on, and the one its SOCKS outbound dials. */
    fun listenPort(profile: ProfileItem): Int = AetherFmt.listenPortOf(profile.aetherListenPort) ?: socksPort

    @Volatile
    private var session: Session? = null

    val isRunning: Boolean get() = session != null

    fun isSupported(context: Context): Boolean = binary(context).canExecute()

    fun buildArguments(
        profile: ProfileItem,
        port: Int,
        scan: Boolean = false,
        logLevel: String = DEFAULT_LOG_LEVEL,
    ): List<String> {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        return buildList {
            addAll(listOf("--bind", "${AppConfig.LOOPBACK}:$port"))
            addAll(listOf("--protocol", protocol.type))
            addAll(listOf("--scan", AetherScanMode.fromString(profile.aetherScanMode).type))
            addAll(listOf("--noize", AetherObfuscation.fromString(profile.aetherObfuscation).type))
            addAll(listOf("--ip", AetherIpVersion.fromString(profile.aetherIpVersion).type))

            if (protocol == AetherProtocol.MASQUE &&
                AetherTransport.fromString(profile.aetherTransport) == AetherTransport.HTTP2
            ) {
                add("--h2")
                if (profile.aetherFragment == true) {
                    add("--fragment")
                    AetherRange.parse(profile.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                        ?.let { addAll(listOf("--fragment-size", it.toString())) }
                    AetherRange.parse(profile.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                        ?.let { addAll(listOf("--fragment-delay", it.toString())) }
                }
            }

            if (protocol == AetherProtocol.GOOL) {
                val outer = AetherEndpoint.parse(profile.aetherWiwOuter).takeUnless { scan }
                val inner = AetherEndpoint.parse(profile.aetherWiwInner).takeUnless { scan }
                outer?.let { addAll(listOf("--wiw-outer", it.toString())) }
                inner?.let { addAll(listOf("--wiw-inner", it.toString())) }
                if (outer == null && inner == null) add("--wiw-scan")
            } else if (!scan) {
                AetherEndpoint.of(profile.server, profile.serverPort)?.let { addAll(listOf("--peer", it.toString())) }
            }

            add(if (scan) "--no-quick-reconnect" else "--quick-reconnect")
            addAll(listOf("--log-level", logLevel))
        }
    }

    /**
     * Maps the app's core log level setting onto the levels the core accepts. Only the session
     * follows the setting: scans and key renewals keep the default because they read info lines.
     */
    internal fun coreLogLevel(appLevel: String?): String = when (appLevel?.lowercase(Locale.US)) {
        "debug" -> "debug"
        "info" -> "info"
        "warning", "warn" -> "warn"
        "error", "none" -> "error"
        else -> DEFAULT_LOG_LEVEL
    }

    internal fun startProcess(context: Context, arguments: List<String>, markSession: Boolean = false): Process {
        val workDir = AetherIdentityManager.workDir(context).apply { mkdirs() }
        val builder = ProcessBuilder(listOf(binary(context).absolutePath) + arguments)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put(OWNER_ENV, android.os.Process.myPid().toString())
            if (markSession) put(SESSION_ENV, "1")
            put("HOME", workDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("AETHER_CONFIG", File(workDir, AetherIdentityManager.BASE_FILE).absolutePath)
            put("AETHER_MASQUE_CONFIG", File(workDir, AetherIdentityManager.MASQUE_FILE).absolutePath)
            put("AETHER_WG_CONFIG", File(workDir, AetherIdentityManager.WIREGUARD_FILE).absolutePath)
        }
        return builder.start()
    }

    internal suspend fun <T> withProcess(
        context: Context,
        arguments: List<String>,
        source: String,
        onOutput: (String) -> Unit,
        block: suspend (output: ReceiveChannel<String>) -> T?,
    ): T? = coroutineScope {
        // A cancellation can land while the spawn runs or while its result is on the way back to this
        // coroutine; either way the core would keep running with nobody holding its handle, so the
        // handle is kept aside and the core is destroyed on that path.
        val spawned = AtomicReference<Process?>()
        val process = try {
            withContext(Dispatchers.IO) {
                try {
                    reapStale(context, null)
                    startProcess(context, arguments).also(spawned::set)
                } catch (e: IOException) {
                    LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch $source", e)
                    null
                }
            }
        } catch (e: CancellationException) {
            spawned.get()?.destroy()
            throw e
        } ?: return@coroutineScope null

        val output = Channel<String>(Channel.UNLIMITED)
        launch(Dispatchers.IO) { forward(process, source, onOutput, output) }
        try {
            ensureActive()
            block(output)
        } finally {
            process.destroy()
        }
    }

    internal suspend fun <T : Any> runUntil(
        context: Context,
        arguments: List<String>,
        timeoutMs: Long,
        source: String,
        onOutput: (String) -> Unit,
        match: (String) -> T?,
    ): T? = withProcess(context, arguments, source, onOutput) { output ->
        withTimeoutOrNull(timeoutMs) { output.receiveAsFlow().mapNotNull(match).firstOrNull() }
    }

    /** Starts the session core for [profile], listening on the port it chooses. */
    @Synchronized
    fun start(context: Context, profile: ProfileItem, onExit: () -> Unit) {
        stop()
        val port = listenPort(profile)
        val next = Session(port, onExit)
        session = next
        val appContext = context.applicationContext
        val logLevel = coreLogLevel(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL))
        val arguments = buildArguments(profile, port, logLevel = logLevel)
        lifecycle.execute { open(next, appContext, arguments) }
    }

    /**
     * Starts the session core with the [arguments] of a custom configuration's aetherCommand,
     * which the app runs as they are written; [port] is where the warm-up waits for the listener.
     */
    @Synchronized
    fun start(context: Context, arguments: List<String>, port: Int, onExit: () -> Unit) {
        stop()
        val next = Session(port, onExit)
        session = next
        val appContext = context.applicationContext
        lifecycle.execute { open(next, appContext, arguments) }
    }

    /**
     * Splits hand-written command text into arguments on whitespace; a quoted section keeps its
     * text together, and a backslash quotes the next character, so an endpoint or a path with a
     * space can still be written. There is no variable or pattern expansion: the text runs as it
     * is written, nothing runs through a shell.
     */
    fun splitCommand(text: String): List<String> {
        val arguments = mutableListOf<String>()
        val argument = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false

        fun flush() {
            if (started) arguments.add(argument.toString())
            argument.clear()
            started = false
        }

        for (char in text) {
            when {
                escaped -> {
                    argument.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null -> if (char == quote) quote = null else argument.append(char)
                char == '\'' || char == '"' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> flush()
                else -> {
                    argument.append(char)
                    started = true
                }
            }
        }
        flush()
        return arguments
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        lifecycle.execute { current.process?.destroy() }
    }

    suspend fun awaitListening(timeoutMs: Long): Boolean =
        awaitReady(timeoutMs, READY_POLL_MS, { isRunning }, { session?.port?.let(::acceptsConnections) == true })

    internal suspend fun awaitReady(
        timeoutMs: Long,
        pollMs: Long,
        running: () -> Boolean,
        listening: () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (running()) {
            if (withContext(Dispatchers.IO) { listening() }) return@withTimeoutOrNull true
            delay(pollMs)
        }
        false
    } ?: false

    /** What the daemon's warm-up wait ends with once it stops polling. */
    internal enum class WarmUpOutcome {
        /** The listener accepts connections; the profile can carry traffic. */
        LISTENING,

        /** The core exited before its listener came up while the service still runs. */
        CORE_EXITED,

        /** The wait was cancelled or the service stopped meanwhile; nothing is left to report. */
        ABANDONED,
    }

    /**
     * Decides what the warm-up wait reports. The exit callback cannot stop the service while
     * Xray is still starting, so a core that died in that window is caught here instead.
     */
    internal fun warmUpOutcome(listening: Boolean, active: Boolean, serviceRunning: Boolean): WarmUpOutcome = when {
        !active || !serviceRunning -> WarmUpOutcome.ABANDONED
        listening -> WarmUpOutcome.LISTENING
        else -> WarmUpOutcome.CORE_EXITED
    }

    internal fun acceptsConnections(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    internal fun relay(line: String, source: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val message = "[$source] $text"
        when (outputPriority(text)) {
            Log.ERROR -> LogUtil.e(AppConfig.TAG, message)
            Log.WARN -> LogUtil.w(AppConfig.TAG, message)
            Log.DEBUG -> LogUtil.d(AppConfig.TAG, message)
            else -> LogUtil.i(AppConfig.TAG, message)
        }
    }

    internal fun outputPriority(line: String): Int {
        if (line.startsWith("Error:")) return Log.ERROR
        return when (logHeader(line)?.get(1)) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "DEBUG", "TRACE" -> Log.DEBUG
            else -> Log.INFO
        }
    }

    internal fun outputMessage(line: String): String {
        val text = line.trim()
        return if (logHeader(text) != null) text.substringAfter(']').trim() else text
    }

    private fun logHeader(line: String): List<String>? {
        if (!line.startsWith('[')) return null
        val headerEnd = line.indexOf(']').takeIf { it > 0 } ?: return null
        return line.substring(1, headerEnd)
            .split(' ')
            .filter(String::isNotEmpty)
            .takeIf { it.size >= 3 && it[1] in logLevels }
    }

    /**
     * Kills leftover core processes of this app: any whose owning app process is gone and, when
     * [bindAddress] is given, any still holding that listener address. Android can kill the
     * daemon, the editor or the test service without their child processes following, and a
     * survivor on the session port would otherwise make every later start fail until a reboot.
     */
    internal fun reapStale(context: Context, bindAddress: String?) {
        for (core in coreProcesses(context)) {
            if (!isStale(core.argv, core.ownerAlive, bindAddress)) continue
            LogUtil.w(
                AppConfig.TAG,
                "AetherCore: killing a leftover core process, pid=${core.pid} bind=${bindAddressOf(core.argv)} ownerAlive=${core.ownerAlive}"
            )
            android.os.Process.killProcess(core.pid)
        }
    }

    /**
     * The arguments of the daemon's live session core, without the binary, or null when no core
     * owned by a living app process is the session. They are read from /proc, so they are
     * available during the scanning phase before the listener exists, which is exactly when the
     * shared key files must not be replaced and no second tunnel must be opened on the same key.
     */
    fun sessionArguments(context: Context): List<String>? =
        coreProcesses(context).firstOrNull { isSession(it.argv, it.ownerAlive, it.sessionMarked, sessionAddress) }?.argv?.drop(1)

    /** The protocol of the daemon's live session, or null without one; see [sessionArguments]. */
    fun sessionProtocol(context: Context): AetherProtocol? = sessionArguments(context)?.let(::protocolOf)

    /**
     * True when [arguments] are those the daemon starts [profile] with, apart from the log level,
     * which follows a setting that can change while the session runs, and from the listener: the
     * same tunnel behind another port, as another profile or a custom configuration may run it,
     * serves the profile just as well. This is how another process tells the running profile from
     * a merely selected one.
     */
    fun runsProfile(arguments: List<String>, profile: ProfileItem): Boolean =
        tunnelArguments(arguments) == tunnelArguments(buildArguments(profile, socksPort))

    private fun tunnelArguments(arguments: List<String>): List<String> =
        withoutOption(withoutOption(arguments, "--log-level"), "--bind")

    private fun withoutOption(arguments: List<String>, flag: String): List<String> {
        val index = arguments.indexOf(flag)
        return if (index < 0) arguments else arguments.filterIndexed { i, _ -> i != index && i != index + 1 }
    }

    /** A core process is stale when its owner is known to be dead or it holds the address we are about to bind. */
    internal fun isStale(argv: List<String>, ownerAlive: Boolean?, bindAddress: String?): Boolean =
        ownerAlive == false || (bindAddress != null && bindAddressOf(argv) == bindAddress)

    /**
     * A core process counts as the session while its owner is not known to be dead and it carries
     * the session mark. When its environment could not be read, [sessionMarked] is null and the
     * address a session holds by default stands in for the mark.
     */
    internal fun isSession(argv: List<String>, ownerAlive: Boolean?, sessionMarked: Boolean?, sessionAddress: String): Boolean =
        ownerAlive != false && (sessionMarked ?: (bindAddressOf(argv) == sessionAddress))

    private val sessionAddress: String get() = "${AppConfig.LOOPBACK}:$socksPort"

    /**
     * A core process of this app found in /proc; [ownerAlive] is null when its owner could not be
     * read, [sessionMarked] when its environment could not.
     */
    internal class CoreProcess(val pid: Int, val argv: List<String>, val ownerAlive: Boolean?, val sessionMarked: Boolean?)

    private fun coreProcesses(context: Context): List<CoreProcess> {
        val binary = binary(context).absolutePath
        val entries = procDir.listFiles() ?: return emptyList()
        return entries.mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val argv = readNulSeparated(File(entry, "cmdline")) ?: return@mapNotNull null
            if (argv.firstOrNull() != binary) return@mapNotNull null
            val environ = readNulSeparated(File(entry, "environ"))
            val ownerAlive = ownerPid(environ)?.let { File(procDir, it.toString()).isDirectory }
            CoreProcess(pid, argv, ownerAlive, environ?.let(::isSessionMarked))
        }
    }

    internal fun bindAddressOf(argv: List<String>): String? = valueAfter(argv, "--bind")

    /** The port of the listener [argv] asks for, null when it names none. */
    internal fun bindPortOf(argv: List<String>): Int? =
        bindAddressOf(argv)?.substringAfterLast(':', "")?.toIntOrNull()

    internal fun protocolOf(argv: List<String>): AetherProtocol = AetherProtocol.fromString(valueAfter(argv, "--protocol"))

    private fun valueAfter(argv: List<String>, flag: String): String? =
        argv.indexOf(flag).takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    internal fun ownerPid(environ: List<String>?): Int? =
        environ?.firstOrNull { it.startsWith("$OWNER_ENV=") }?.substringAfter('=')?.toIntOrNull()

    internal fun isSessionMarked(environ: List<String>): Boolean = environ.any { it.startsWith("$SESSION_ENV=") }

    private fun readNulSeparated(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun open(target: Session, context: Context, arguments: List<String>) {
        if (session !== target) return
        reapStale(context, bindAddressOf(arguments))
        val process = try {
            startProcess(context, arguments, markSession = true)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch the core", e)
            if (release(target)) target.onExit()
            return
        }
        target.process = process
        thread(name = "aether-core-output", isDaemon = true) { watch(target, process) }
    }

    private fun forward(process: Process, source: String, onOutput: (String) -> Unit, output: Channel<String>) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                relay(line, source)
                onOutput(line)
                output.trySend(line)
            }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: $source output closed: ${e.message}")
        } finally {
            output.close()
        }
    }

    private fun watch(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { relay(it, "aether") }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: output closed: ${e.message}")
        }
        val exitCode = process.waitFor()
        if (!release(target)) return
        LogUtil.e(AppConfig.TAG, "AetherCore: the core exited on its own with code $exitCode")
        target.onExit()
    }

    @Synchronized
    private fun release(target: Session): Boolean {
        if (session !== target) return false
        session = null
        return true
    }

    private class Session(val port: Int, val onExit: () -> Unit) {
        var process: Process? = null
    }
}
