package com.v2ray.ang

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        // AppCompat reads the system locale from this context on Android 12 and earlier.
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        logPreviousExits()

        // PattNG: the Aether cores of this process listen on the port of the settings.
        AetherCoreManager.listenPortSource = SettingsManager::getAetherListenPort

        // Initialize theme state from MMKV
        ThemeManager.refresh()
    }

    /**
     * Writes down how the last processes of this app ended (a crash, a native crash, a kill), so that the log tells why
     * the service did not start when Android reported "process is bad". Only the main process asks; getHistoricalProcessExitReasons
     * needs Android 11, below that there is nothing to read.
     */
    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || getProcessName() != packageName) return
        Thread {
            try {
                val manager = getSystemService(ActivityManager::class.java)
                manager.getHistoricalProcessExitReasons(null, 0, 5).forEach {
                    LogUtil.i(AppConfig.TAG, "ExitInfo: process=${it.processName} reason=${it.reason} status=${it.status} at=${it.timestamp} description=${it.description}")
                }
            } catch (e: Throwable) {
                LogUtil.w(AppConfig.TAG, "ExitInfo: reading the exit reasons failed", e)
            }
        }.start()
    }
}
