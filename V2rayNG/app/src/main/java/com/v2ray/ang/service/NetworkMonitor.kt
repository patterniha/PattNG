package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.ReloadOutcome
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * After the debounce window, on a background thread, a handover is first claimed with [claimHandover]
 * while this monitor is registered, under the gate that [unregister] closes first: it has to be short,
 * and once unregister() has returned no handover is claimed. Only a claimed handover goes on to
 * [onHandover], which may block. Both are passed this monitor. [describe] names the run mode and the
 * profile of the service it watches, for its failure logs.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val claimHandover: (NetworkMonitor) -> Boolean,
    private val onHandover: (NetworkMonitor) -> Unit,
    private val describe: () -> String,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    // PattNG: the network callbacks run on a thread of ConnectivityManager, register and unregister on the main
    // thread, so the state below is read and written under this lock.
    private val lock = Any()
    private var upstream: Network? = null
    // PattNG: the network the core was last settled on; a flap back to it needs no core reload.
    private var settled: Network? = null
    private var handoverJob: Job? = null
    private var registered = false

    /** PattNG: lets a handover be claimed from register() until unregister(), see [claimHandover]. */
    private val gate = HandoverGate()

    /** PattNG: owns the handover work, which [unregister] cancels. */
    private val handoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = synchronized(lock) { upstream.also { upstream = network; if (settled == null) settled = network } }
            onUnderlyingNetworksChanged(arrayOf(network))
            if (previous != null && previous != network) {
                scheduleHandover(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // it's a good idea to refresh capabilities
            onUnderlyingNetworksChanged(arrayOf(network))
        }

        override fun onLost(network: Network) {
            // PattNG: during a make-before-break switch onLost(old) arrives after onAvailable(new); only the
            // tracked network may clear the underlying networks, or the VPN looks like it has no network.
            val wasCurrent = synchronized(lock) {
                if (upstream == network) { upstream = null; true } else false
            }
            if (wasCurrent) onUnderlyingNetworksChanged(null)
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(lock) {
            if (registered) return
            try {
                connectivity.requestNetwork(request, callback)
                registered = true
                gate.open()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, register: requesting the network failed", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once. No handover is claimed once
     * it has returned: it closes the gate first, waiting for a claim under way, see [claimHandover].
     */
    fun unregister() {
        synchronized(lock) {
            // PattNG: closed under the lock it was opened under; a claim never takes this lock, so the wait is short.
            gate.close()
            handoverJob?.cancel()
            handoverJob = null
            handoverScope.coroutineContext.cancelChildren()
            upstream = null
            settled = null
            if (!registered) return
            registered = false
        }
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: ${describe()}, unregister: unregistering the callback failed", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        synchronized(lock) {
            // PattNG: a network callback that arrives after unregister() schedules nothing.
            if (!registered) return
            handoverJob?.cancel()
            handoverJob = handoverScope.launch {
                try {
                    delay(HANDOVER_DEBOUNCE_MS)
                    val changed = synchronized(lock) {
                        if (ReloadOutcome.networkChanged(settled, upstream)) { settled = upstream; true } else false
                    }
                    if (!changed) {
                        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is back on the settled network, no core reload")
                        return@launch
                    }
                    // PattNG: cancelling ends the wait above, not a handover that has just come out of it. The handover
                    // goes on only once claimed under the gate, which unregister() closes first: an unregistered monitor
                    // claims nothing and calls nothing.
                    gate.runClaimed({ claimHandover(this@NetworkMonitor) }) { onHandover(this@NetworkMonitor) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, handover: handling the upstream change failed", e)
                }
            }
        }
    }
}
