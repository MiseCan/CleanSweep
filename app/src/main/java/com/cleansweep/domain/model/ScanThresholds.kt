package com.cleansweep.domain.model

import kotlinx.serialization.Serializable

internal enum class ScanParameter(val default: Int, val bounds: IntRange) {
    CaptureSeconds(120, 1..600),
    HashDistance(5, 0..63),
    ColorDistancePercent(35, 0..100),
    OrbRatioPercent(75, 1..100),
    RansacErrorPixels(3, 1..10),
    MinimumInliers(14, 4..100),
    InlierPercent(50, 1..100),
    SpreadPercent(15, 0..100),
    MobileNetPercent(78, 50..100),
    MobileClipPercent(84, 50..100)
}

@Serializable
internal data class ScanThresholds(private val overrides: Map<ScanParameter, Int> = emptyMap()) {
    operator fun get(parameter: ScanParameter): Int =
        (overrides[parameter] ?: parameter.default).coerceIn(parameter.bounds)

    fun with(parameter: ScanParameter, value: Int): ScanThresholds =
        ScanThresholds(if (value == parameter.default) overrides - parameter else overrides + (parameter to value))
}
