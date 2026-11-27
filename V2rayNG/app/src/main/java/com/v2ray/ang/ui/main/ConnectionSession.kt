package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/** What the home card knows of the running connection; kept in memory so that it outlives the screen and its ViewModel. */
data class ConnectionFigures(val pingMillis: Long?, val ipAddress: String?, val countryCode: String?)

/**
 * The start of the connection (wall clock, in the settings) and its last figures (in memory). Without it, a ViewModel that is created again while the
 * service keeps running would count the duration from zero and measure the ping and the address anew.
 */
object ConnectionSession {
    /** The monotonic start the stored wall-clock start stands for at [nowNanos]; never in the future. */
    internal fun nanosSince(storedWallMillis: Long, nowWallMillis: Long, nowNanos: Long): Long =
        nowNanos - (nowWallMillis - storedWallMillis).coerceAtLeast(0L) * NANOS_PER_MILLI

    /** The monotonic start of the running connection: the stored one, or [nowNanos] when none is stored (and it is stored now). */
    fun startedAtNanos(nowNanos: Long): Long = runCatching {
        val wall = System.currentTimeMillis()
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_HOME_CONNECTED_SINCE)?.toLongOrNull()
        if (stored != null && stored > 0) return@runCatching nanosSince(stored, wall, nowNanos)
        MmkvManager.encodeSettings(AppConfig.PREF_HOME_CONNECTED_SINCE, wall.toString())
        nowNanos
    }.getOrDefault(nowNanos)

    // The figures include the exit IP, so they stay in memory only: never in the settings, which a backup exports.
    @Volatile
    private var figures: ConnectionFigures? = null

    fun saveFigures(value: ConnectionFigures) {
        figures = value
    }

    fun restoreFigures(): ConnectionFigures? = figures

    fun clear() {
        figures = null
        runCatching {
            MmkvManager.encodeSettings(AppConfig.PREF_HOME_CONNECTED_SINCE, "")
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
