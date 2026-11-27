package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReloadOutcomeTest {

    @Test
    fun aReloadThatBroughtXrayBackLeavesTheServiceRunning() {
        assertEquals(ReloadOutcome.KEEP_RUNNING, ReloadOutcome.of(coreRunning = true, stoppedMeanwhile = false))
    }

    @Test
    fun aReloadThatLeftXrayStoppedStopsTheWholeService() {
        // Otherwise the tunnel and the Aether core stay up around a core that is gone.
        assertEquals(ReloadOutcome.STOP_SERVICE, ReloadOutcome.of(coreRunning = false, stoppedMeanwhile = false))
    }

    @Test
    fun aStopThatRacedTheReloadReleasesWhatTheReloadStarted() {
        // The teardown found no Xray to stop; the reload then started one, and maybe an Aether core, for a service that is gone.
        assertEquals(ReloadOutcome.RELEASE_CORES, ReloadOutcome.of(coreRunning = true, stoppedMeanwhile = true))
        // A reload that also failed has nothing of its own left, and releasing is harmless then.
        assertEquals(ReloadOutcome.RELEASE_CORES, ReloadOutcome.of(coreRunning = false, stoppedMeanwhile = true))
    }

    @Test
    fun aReloadGoesOnOnlyWhileTheMonitorThatAskedForItIsTheServicesStill() {
        val asked = Any()
        assertTrue(ReloadOutcome.goesOn(askedBy = asked, current = asked))
        // Stopped while the reload waited: the stop cleared the monitor.
        assertFalse(ReloadOutcome.goesOn(askedBy = asked, current = null))
        // Stopped and started again: the new start has a monitor of its own.
        assertFalse(ReloadOutcome.goesOn(askedBy = asked, current = Any()))
    }

    @Test
    fun onlyTheServicesOwnMonitorReloadsItsRunningCore() {
        val asking = Any()
        assertTrue(ReloadOutcome.handoverReloads(askedBy = asking, current = asking, coreRunning = true, reloading = false))
        // A stop and a new start came after the handover began: the new start's monitor is not the one that saw it.
        assertFalse(ReloadOutcome.handoverReloads(askedBy = asking, current = Any(), coreRunning = true, reloading = false))
        // Stopped and not started again.
        assertFalse(ReloadOutcome.handoverReloads(askedBy = asking, current = null, coreRunning = false, reloading = false))
        // Xray does not run, or another reload is under way.
        assertFalse(ReloadOutcome.handoverReloads(askedBy = asking, current = asking, coreRunning = false, reloading = false))
        assertFalse(ReloadOutcome.handoverReloads(askedBy = asking, current = asking, coreRunning = true, reloading = true))
    }

    @Test
    fun aServiceThatReloadsIsUpThoughXrayIsStoppedForAMoment() {
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = false, stoppedMeanwhile = false))
        // A client that asked then would be told "not running" with nothing to correct it later, and a
        // toggle meant as a stop would start a running service, beside the reload.
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = false, reloading = true, stoppedMeanwhile = false))
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = true, stoppedMeanwhile = false))
        assertFalse(ReloadOutcome.serviceRuns(coreRunning = false, reloading = false, stoppedMeanwhile = false))
    }

    @Test
    fun aReloadWhoseServiceWasStoppedDoesNotCountAsARunningService() {
        // It is only finishing: taking it for a running service would swallow the start that follows the stop.
        assertFalse(ReloadOutcome.serviceRuns(coreRunning = false, reloading = true, stoppedMeanwhile = true))
        // No reload at all: a stopped service, as before a first start or on a system without a network monitor.
        assertFalse(ReloadOutcome.serviceRuns(coreRunning = false, reloading = false, stoppedMeanwhile = true))
        // Xray itself running is a running service whatever else holds.
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = false, stoppedMeanwhile = true))
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = true, stoppedMeanwhile = true))
    }

    @Test
    fun aHandoverIsNeededOnlyWhenTheNetworkDiffersFromTheSettledOne() {
        assertFalse(ReloadOutcome.networkChanged(settled = "wifi", current = "wifi"))
        assertTrue(ReloadOutcome.networkChanged(settled = "wifi", current = "cell"))
        assertFalse(ReloadOutcome.networkChanged(settled = "wifi", current = null))
    }
}
