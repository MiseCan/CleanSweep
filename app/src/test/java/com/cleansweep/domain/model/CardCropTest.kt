package com.cleansweep.domain.model

import com.cleansweep.data.repository.AppSettings
import com.cleansweep.data.repository.AppSettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCropTest {
    @Test
    fun comparesBothDirectionsAgainstTheActualCard() {
        val crop = CardCrop()
        assertTrue(crop.shouldCrop(60f, 100f, 60f, 100f))
        assertTrue(crop.shouldCrop(48f, 100f, 60f, 100f))
        assertTrue(crop.shouldCrop(75f, 100f, 60f, 100f))
        assertFalse(crop.shouldCrop(47f, 100f, 60f, 100f))
        assertFalse(crop.shouldCrop(76f, 100f, 60f, 100f))
        assertTrue(crop.shouldCrop(100f, 75f, 100f, 60f))
    }

    @Test
    fun increasingTheThresholdAllowsMoreCropWithoutCroppingExtremePhotos() {
        val strict = CardCrop(thresholdPercent = 20)
        val loose = CardCrop(thresholdPercent = 40)
        assertFalse(strict.shouldCrop(40f, 100f, 60f, 100f))
        assertTrue(loose.shouldCrop(40f, 100f, 60f, 100f))
        assertFalse(strict.shouldCrop(95f, 100f, 60f, 100f))
        assertTrue(loose.shouldCrop(95f, 100f, 60f, 100f))
        assertFalse(loose.shouldCrop(20f, 100f, 60f, 100f))
        assertFalse(loose.shouldCrop(160f, 100f, 60f, 100f))
        assertFalse(loose.copy(enabled = false).shouldCrop(60f, 100f, 60f, 100f))
        assertFalse(loose.shouldCrop(Float.NaN, 100f, 60f, 100f))
    }

    @Test
    fun olderSettingsKeepTheSwitchAndOtherPreferences() {
        val legacy = """{"likeDays":42,"portraitCrop":{"enabled":false,"minimumRatio":"SixteenByNine"}}"""
        val settings = AppSettingsJson.decodeFromString<AppSettings>(legacy)
        assertEquals(42, settings.likeDays)
        assertEquals(CardCrop(enabled = false), settings.cardCrop)

        val updated = settings.copy(cardCrop = CardCrop(thresholdPercent = 35))
        assertEquals(updated, AppSettingsJson.decodeFromString<AppSettings>(AppSettingsJson.encodeToString(updated)))
    }
}
