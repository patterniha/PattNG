package com.v2ray.ang.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionPolicyTest {
    private val gb = 1_000_000_000L

    @Test
    fun strongPhoneKeepsAnimations() { assertFalse(MotionPolicy.isWeakPhone(false, 8 * gb, 34, 8)) }

    @Test
    fun lowRamFlagIsWeak() { assertTrue(MotionPolicy.isWeakPhone(true, 8 * gb, 34, 8)) }

    @Test
    fun littleMemoryIsWeak() { assertTrue(MotionPolicy.isWeakPhone(false, 3 * gb, 34, 8)) }

    @Test
    fun oldAndroidIsWeak() { assertTrue(MotionPolicy.isWeakPhone(false, 8 * gb, 28, 8)) }

    @Test
    fun fourCoresIsWeak() { assertTrue(MotionPolicy.isWeakPhone(false, 8 * gb, 34, 4)) }

    @Test
    fun unknownMemoryAloneIsNotWeak() { assertFalse(MotionPolicy.isWeakPhone(false, 0, 34, 8)) }
}
