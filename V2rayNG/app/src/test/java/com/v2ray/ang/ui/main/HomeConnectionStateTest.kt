package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeConnectionStateTest {

    // ---------- pendingStartAfter ----------

    @Test
    fun anAnswerEndsAPendingStart() {
        assertFalse(HomeConnection.pendingStartAfter(true, RunningSignal.RUNNING))
        assertFalse(HomeConnection.pendingStartAfter(true, RunningSignal.START_FAILED))
    }

    @Test
    fun aStopOrAnUnansweredQueryDoesNotEndAPendingStart() {
        assertTrue(HomeConnection.pendingStartAfter(true, RunningSignal.STOPPED))
        assertTrue(HomeConnection.pendingStartAfter(true, RunningSignal.NOT_RUNNING))
    }

    @Test
    fun noStartIsPendingWithoutOneAsked() {
        RunningSignal.entries.forEach { assertFalse(HomeConnection.pendingStartAfter(false, it)) }
    }

    // ---------- issueAfter ----------

    @Test
    fun aStopNobodyAskedForOfARunningServiceIsADroppedConnection() {
        assertEquals(
            ConnectionIssue.Dropped,
            HomeConnection.issueAfter(null, wasRunning = true, signal = RunningSignal.NOT_RUNNING, userRequested = false)
        )
        assertEquals(
            ConnectionIssue.Dropped,
            HomeConnection.issueAfter(null, wasRunning = true, signal = RunningSignal.STOPPED, userRequested = false)
        )
    }

    @Test
    fun aStopTheUserAskedForIsNotADroppedConnection() {
        assertNull(
            HomeConnection.issueAfter(null, wasRunning = true, signal = RunningSignal.STOPPED, userRequested = true)
        )
        assertNull(
            HomeConnection.issueAfter(null, wasRunning = true, signal = RunningSignal.NOT_RUNNING, userRequested = true)
        )
    }

    @Test
    fun aServiceThatDidNotRunCannotHaveDropped() {
        assertNull(
            HomeConnection.issueAfter(null, wasRunning = false, signal = RunningSignal.NOT_RUNNING, userRequested = false)
        )
    }

    @Test
    fun aRepeatedStopSignalKeepsWhatWasShown() {
        assertEquals(
            ConnectionIssue.Dropped,
            HomeConnection.issueAfter(ConnectionIssue.Dropped, wasRunning = false, signal = RunningSignal.NOT_RUNNING, userRequested = false)
        )
        assertEquals(
            ConnectionIssue.StartFailed,
            HomeConnection.issueAfter(ConnectionIssue.StartFailed, wasRunning = false, signal = RunningSignal.NOT_RUNNING, userRequested = false)
        )
    }

    @Test
    fun aFailedStartIsAnError() {
        assertEquals(
            ConnectionIssue.StartFailed,
            HomeConnection.issueAfter(null, wasRunning = false, signal = RunningSignal.START_FAILED, userRequested = true)
        )
    }

    @Test
    fun runningAgainClearsTheIssue() {
        assertNull(
            HomeConnection.issueAfter(ConnectionIssue.Dropped, wasRunning = false, signal = RunningSignal.RUNNING, userRequested = true)
        )
    }

    // ---------- cardStatus ----------

    private fun state(
        isRunning: Boolean = false,
        status: MainStatus = MainStatus.Disconnected,
        pendingStart: Boolean = false,
        issue: ConnectionIssue? = null,
        selectedGuid: String? = "guid",
    ) = MainUiState(
        isRunning = isRunning,
        status = status,
        pendingStart = pendingStart,
        connectionIssue = issue,
        selectedGuid = selectedGuid,
    )

    @Test
    fun noSelectedServerIsEmpty() {
        assertEquals(HomeCardStatus.Empty, HomeConnection.cardStatus(state(selectedGuid = null)))
        assertEquals(HomeCardStatus.Empty, HomeConnection.cardStatus(state(selectedGuid = "")))
    }

    @Test
    fun aSelectedServerThatDoesNotRunIsDisconnected() {
        assertEquals(HomeCardStatus.Disconnected, HomeConnection.cardStatus(state()))
    }

    @Test
    fun aStartAwaitedIsConnecting() {
        assertEquals(HomeCardStatus.Connecting, HomeConnection.cardStatus(state(pendingStart = true)))
    }

    @Test
    fun aRunningServiceWhoseTunnelIsComingUpIsConnecting() {
        assertEquals(
            HomeCardStatus.Connecting,
            HomeConnection.cardStatus(state(isRunning = true, status = MainStatus.Connecting("tunnel")))
        )
    }

    @Test
    fun aRunningServiceIsConnectedWhateverATestShows() {
        listOf(MainStatus.Connected, MainStatus.Testing, MainStatus.TestProgress("2")).forEach {
            assertEquals(HomeCardStatus.Connected, HomeConnection.cardStatus(state(isRunning = true, status = it)))
        }
    }

    @Test
    fun theIssuesShowAsErrorAndDroppedConnection() {
        assertEquals(HomeCardStatus.Error, HomeConnection.cardStatus(state(issue = ConnectionIssue.StartFailed)))
        assertEquals(
            HomeCardStatus.DisconnectedUnexpected,
            HomeConnection.cardStatus(state(issue = ConnectionIssue.Dropped))
        )
    }

    @Test
    fun anIssueShowsEvenWhenNoServerIsSelectedAnymore() {
        assertEquals(
            HomeCardStatus.DisconnectedUnexpected,
            HomeConnection.cardStatus(state(issue = ConnectionIssue.Dropped, selectedGuid = null))
        )
    }

    // ---------- formatDuration ----------

    @Test
    fun durationsUnderAnHourShowMinutesAndSeconds() {
        assertEquals("0:00", HomeConnection.formatDuration(0))
        assertEquals("0:07", HomeConnection.formatDuration(7))
        assertEquals("30:47", HomeConnection.formatDuration(30 * 60 + 47))
        assertEquals("59:59", HomeConnection.formatDuration(3599))
    }

    @Test
    fun durationsFromAnHourShowHours() {
        assertEquals("1:00:00", HomeConnection.formatDuration(3600))
        assertEquals("26:03:09", HomeConnection.formatDuration(26 * 3600 + 3 * 60 + 9))
    }

    @Test
    fun aNegativeSpanCountsAsZero() {
        assertEquals("0:00", HomeConnection.formatDuration(-5))
    }
}
