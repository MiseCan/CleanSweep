package com.cleansweep.domain.model

import kotlinx.serialization.Serializable

@Serializable
internal data class CardCrop(
    val enabled: Boolean = true,
    val thresholdPercent: Int = 20
) {
    val threshold: Int get() = thresholdPercent.coerceIn(ThresholdRange)

    fun shouldCrop(imageWidth: Float, imageHeight: Float, cardWidth: Float, cardHeight: Float): Boolean {
        if (!enabled || imageWidth <= 0f || imageHeight <= 0f || cardWidth <= 0f || cardHeight <= 0f) return false

        val scaledWidth = imageWidth.toDouble() * cardHeight
        val scaledHeight = imageHeight.toDouble() * cardWidth
        if (!scaledWidth.isFinite() || !scaledHeight.isFinite()) return false

        // The overlap of the two aspect ratios is the fraction of the image kept by a center crop.
        return minOf(scaledWidth, scaledHeight) * 100 >= maxOf(scaledWidth, scaledHeight) * (100 - threshold)
    }

    companion object {
        val ThresholdRange = 0..40
    }
}
