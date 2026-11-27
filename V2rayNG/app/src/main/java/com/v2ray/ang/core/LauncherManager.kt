package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object LauncherManager {

    // PattNG: a start right behind a stop reaches a core and a tunnel that are still going down, and repeated quick
    // toggles have left the daemon process marked bad by Android ("Unable to launch app ... for service"), so
    // for some minutes nothing could start. A start waits until the stop has had this long to finish.
    private const val START_AFTER_STOP_MILLIS = 1500L
    @Volatile private var lastStopAtMillis = 0L
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var pendingStart: Runnable? = null

    /** Runs [start] now, or once [START_AFTER_STOP_MILLIS] have passed since the last stop; a newer start replaces a waiting one. */
    private fun startAfterStop(start: () -> Unit) {
        val wait = lastStopAtMillis + START_AFTER_STOP_MILLIS - android.os.SystemClock.elapsedRealtime()
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        if (wait <= 0) {
            // The RESTART receiver calls from a worker thread, where a toast in the failure path would itself throw.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) start() else mainHandler.post(start)
            return
        }
        val run = Runnable { pendingStart = null; start() }
        pendingStart = run
        mainHandler.postDelayed(run, wait)
    }

    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startAfterStop {
            try {
                startContextService(context)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
                context.toast(e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    fun startService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        startAfterStop {
            try {
                startContextService(context)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
                context.toast(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stopService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        // A stop also ends a start that is still waiting behind the last stop.
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        lastStopAtMillis = android.os.SystemClock.elapsedRealtime()
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Restarts the active daemon without starting a stopped service. */
    fun restartService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /** Restarts the active daemon, or delegates to the caller's permission-aware start flow. */
    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
        }
    }

    internal fun hasUsableServer(config: ProfileItem): Boolean =
        config.configType.isComplexType()
            || config.configType == EConfigType.AETHER
            || Utils.isPureIpAddress(config.server.orEmpty())
            || Utils.isValidUrl(config.server)

    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        // Note: isRunning check is removed here to avoid loading Native libraries in the UI process.
        // The check is performed in CoreServiceManager when the service starts in the daemon process.

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!hasUsableServer(config)) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        SettingsManager.refreshRuntimeSocksPort()

        if (config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            Utils.setClipboard(context, context.getString(R.string.toast_allow_insecure_deprecated))
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}
