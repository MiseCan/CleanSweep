package com.cleansweep.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureWindowsTest {
    @Test
    fun includesExactly120SecondsAndStartsANewWindowAfterward() {
        val times = listOf(241_001L, 121_001L, 121_000L, 1_000L, null, 0L)
        assertEquals(
            listOf(listOf(1_000L, 121_000L), listOf(121_001L, 241_001L)),
            captureWindows(times) { it }
        )
    }

    @Test
    fun aChainOfCloseCapturesDoesNotExtendTheFirstWindow() {
        val times = listOf(1_000L, 100_000L, 200_000L)
        assertEquals(listOf(listOf(1_000L, 100_000L)), captureWindows(times) { it })
    }

    @Test
    fun dctHashIsStableAcrossUniformBrightnessChanges() {
        val luminance = DoubleArray(32 * 32) { ((it * 47) xor (it / 5)).mod(256).toDouble() }
        val brighter = DoubleArray(luminance.size) { luminance[it] * 0.8 + 24 }
        assertEquals(DctPhotoHash.hashLuminance(luminance), DctPhotoHash.hashLuminance(brighter))
    }
}
