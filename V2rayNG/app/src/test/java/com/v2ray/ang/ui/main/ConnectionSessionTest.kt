package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionSessionTest {
    @Test
    fun startIsPlacedThatManyMillisBeforeNow() {
        assertEquals(5_000_000_000L - 2_000_000_000L, ConnectionSession.nanosSince(1_000L, 3_000L, 5_000_000_000L))
    }

    @Test
    fun aClockSetBackNeverPutsTheStartInTheFuture() {
        assertEquals(7L, ConnectionSession.nanosSince(9_000L, 3_000L, 7L))
    }
}
