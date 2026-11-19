package com.cleansweep.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanTimeEstimateTest {
    @Test
    fun estimatesRemainingWorkAfterMeasuringProcessingSpeed() {
        val estimate = ScanTimeEstimate()
        assertNull(estimate.remainingMillis(100))
        estimate.recordPhoto(100)
        assertNull(estimate.remainingMillis(99))
        estimate.recordPhoto(300)
        assertEquals(19_600L, estimate.remainingMillis(98))
        assertEquals(19_200L, estimate.remainingMillis(96))
        assertNull(estimate.remainingMillis(0))
    }

    @Test
    fun showsAnEarlyEstimateWhenOnePhotoTakesLongEnough() {
        val estimate = ScanTimeEstimate()
        estimate.recordPhoto(400)
        assertEquals(1_200L, estimate.remainingMillis(3))
    }
}
