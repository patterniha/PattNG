package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.EchOutbound
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()
    private val aloneMutex = Mutex()
    private val measurements = MutableStateFlow(Measurements(running = 0, alone = false))

    /** The measurements in progress: how many share the process, and whether one runs alone. */
    private data class Measurements(val running: Int, val alone: Boolean)

    suspend fun <T> run(configType: EConfigType, config: String, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { runShared(block) }
        } else if (EchOutbound.isUsedIn(config)) {
            runAlone(block)
        } else {
            runShared(block)
        }
    }

    private suspend fun <T> runShared(block: () -> T): T {
        while (true) {
            val current = measurements.first { !it.alone }
            if (measurements.compareAndSet(current, current.copy(running = current.running + 1))) break
        }
        try {
            return block()
        } finally {
            measurements.update { it.copy(running = it.running - 1) }
        }
    }

    /**
     * PattNG: Xray looks a dialerProxy up in the outbound manager of the core that started last in the
     * process (InitSystemDialer in transport/internet/dialer.go), and every measurement starts a core
     * of its own. The ECH config query of a configuration with an ECH outbound (echSockopt.dialerProxy)
     * would then go through another measurement's core, or find no such outbound there, and fail the
     * handshake of a working profile. So such a measurement starts once no other one runs, and none
     * starts while it runs; until then the others keep starting, so it does not hold up the batch.
     * Remove this once the pinned Xray looks the dialerProxy up in the core that dials.
     */
    private suspend fun <T> runAlone(block: () -> T): T = aloneMutex.withLock {
        while (true) {
            val current = measurements.first { it.running == 0 }
            if (measurements.compareAndSet(current, current.copy(alone = true))) break
        }
        try {
            block()
        } finally {
            measurements.update { it.copy(alone = false) }
        }
    }
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AETHER) {
            return AetherDelayTester.measure(context, guid, config, SettingsManager.getDelayTestUrl())
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        val aether = configResult.aetherCore
        if (aether != null) {
            // The configuration reaches the internet through an Aether outbound, so it is measured behind
            // that core: the live session, or a test tunnel on its own port. It is rebuilt to point at
            // that core unless it already dials its port. Its own server is not probed: it is only
            // reachable through that core.
            return AetherDelayTester.measureVia(context, guid, aether) { port, _ ->
                val content = if (port == aether.port) {
                    configResult.content
                } else {
                    CoreConfigManager.getV2rayConfig4Speedtest(context, guid, port).takeIf { it.status }?.content
                        ?: return@measureVia retFailure
                }
                RealPingExecutionLimiter.run(config.configType, content) {
                    CoreNativeManager.measureOutboundDelay(content, SettingsManager.getDelayTestUrl())
                }
            }
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        return RealPingExecutionLimiter.run(config.configType, configResult.content) {
            CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
        }
    }

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AETHER) {
            return AetherDelayTester.reachability(config)
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)

            return tcpTime
        }

        return retFailure
    }
}
