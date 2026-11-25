package com.v2ray.ang.ui.main

import java.util.Locale

/** What the service last said about its running state, which decides what a stop means. */
internal enum class RunningSignal {
    /** The service runs: started, or answered a query. */
    RUNNING,

    /** The service does not run: a query nobody acknowledged, or its own say-so. */
    NOT_RUNNING,

    /** The service finished a stop. */
    STOPPED,

    /** A start failed, or was not answered in time. */
    START_FAILED,
}

/** The rules that turn the signals of the service, and the user's own requests, into what the home card shows. */
internal object HomeConnection {

    /** How long a start the user asked for may go unanswered before it is shown as failed. */
    const val START_TIMEOUT_MILLIS = 30_000L

    /**
     * Whether a start is still awaited after [signal]. Only an answer ends it: a stop or a repeated "not running" while
     * a restart is on its way does not.
     */
    fun pendingStartAfter(pending: Boolean, signal: RunningSignal): Boolean = when (signal) {
        RunningSignal.RUNNING, RunningSignal.START_FAILED -> false
        RunningSignal.NOT_RUNNING, RunningSignal.STOPPED -> pending
    }

    /**
     * The issue to show after [signal], given that the service [wasRunning] and whether the user asked for the change,
     * [userRequested]: a start that failed is an error; a stop nobody asked for is a dropped connection; any other
     * signal leaves what was shown, so a repeated one does not clear it. Running again clears it.
     */
    fun issueAfter(
        current: ConnectionIssue?,
        wasRunning: Boolean,
        signal: RunningSignal,
        userRequested: Boolean,
    ): ConnectionIssue? = when (signal) {
        RunningSignal.RUNNING -> null
        RunningSignal.START_FAILED -> ConnectionIssue.StartFailed
        RunningSignal.NOT_RUNNING, RunningSignal.STOPPED ->
            if (wasRunning && !userRequested) ConnectionIssue.Dropped else current
    }

    /** What the card shows for [state]. */
    fun cardStatus(state: MainUiState): HomeCardStatus = when {
        state.isRunning ->
            if (state.status is MainStatus.Connecting) HomeCardStatus.Connecting else HomeCardStatus.Connected

        state.pendingStart -> HomeCardStatus.Connecting
        state.connectionIssue == ConnectionIssue.StartFailed -> HomeCardStatus.Error
        state.connectionIssue == ConnectionIssue.Dropped -> HomeCardStatus.DisconnectedUnexpected
        state.selectedGuid.isNullOrEmpty() -> HomeCardStatus.Empty
        else -> HomeCardStatus.Disconnected
    }

    /** "m:ss", or "h:mm:ss" from an hour on; a negative span counts as zero. */
    fun formatDuration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0L)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** Normalize the ISO country code supplied by the exit-IP lookup; names are deliberately not guessed. */
internal fun normalizeCountryCode(country: String?): String? =
    country?.trim()?.uppercase(Locale.ROOT)?.takeIf { code ->
        code.length == 2 && code.all { it in 'A'..'Z' }
    }

/** Render a regional-indicator flag only when the lookup returned a valid ISO alpha-2 code. */
internal fun countryFlagEmoji(countryCode: String?): String =
    normalizeCountryCode(countryCode)
        ?.map { character -> String(Character.toChars(0x1F1E6 + (character - 'A'))) }
        ?.joinToString("")
        .orEmpty()
