package com.v2ray.ang.ui.compose

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/** True when the animations of the app are to be fewer and lighter; [AppTheme] provides it. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Whether to reduce motion: the choice of the user, which starts as on for a low-RAM phone or when the system has animations off. */
object MotionPolicy {
    private const val WEAK_PHONE_RAM_BYTES = 4_500_000_000L

    @Volatile private var cachedDefault: Boolean? = null

    /** Computed once per process; it reads system services and runs at every start of the UI. */
    fun defaultReduced(context: Context): Boolean = cachedDefault ?: computeDefaultReduced(context).also { cachedDefault = it }

    private fun computeDefaultReduced(context: Context): Boolean {
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        // A phone with little memory is usually a slow one too, so it starts with fewer animations; the user can turn them on.
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return isWeakPhone(lowRam, info.totalMem, Build.VERSION.SDK_INT, Runtime.getRuntime().availableProcessors()) || scale == 0f
    }

    /** Little memory, an old Android (9 or lower) or four cores or fewer: any of them marks a phone that animates badly. */
    internal fun isWeakPhone(lowRam: Boolean, totalMemBytes: Long, sdkInt: Int, cores: Int): Boolean =
        lowRam || totalMemBytes in 1 until WEAK_PHONE_RAM_BYTES || sdkInt <= Build.VERSION_CODES.P || cores <= 4

    fun isReduced(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_REDUCE_MOTION, defaultReduced(context))
}
