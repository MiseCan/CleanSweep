package com.cleansweep.domain.model

import com.cleansweep.data.model.MediaItem
import kotlinx.serialization.Serializable

internal data class SimilaritySimulation(
    val sampled: Int,
    val undated: Int,
    val singletons: Int,
    val unreadable: Int,
    val thresholds: ScanThresholds,
    val windows: List<SimulationWindow>,
    val timings: SimulationTimings? = null
)

internal data class SimulationWindow(
    val startMillis: Long,
    val lastMillis: Long,
    val photos: List<MediaItem>,
    val unreadable: Int,
    val groups: List<SimilarGroup>,
    val comparisons: List<PhotoComparison>
)

internal data class PhotoComparison(
    val earlier: MediaItem,
    val later: MediaItem,
    val captureGapMillis: Long,
    val hashDistance: Int,
    val colorDistancePercent: Double,
    val similar: Boolean,
    val geometry: GeometryMetrics?,
    val modelScores: Map<SimulationModel, Double> = emptyMap(),
    val suggested: Boolean = false
)

internal enum class GeometryStage { TooFewKeypoints, TooFewMatches, NoTransform, Estimated }

internal data class GeometryMetrics(
    val stage: GeometryStage,
    val laterKeypoints: Int,
    val earlierKeypoints: Int,
    val matches: Int = 0,
    val inliers: Int = 0,
    val minSpreadPercent: Double = 0.0
)

@Serializable
internal enum class SimulationModel(
    val label: String,
    val asset: String,
    val inputSize: Int,
    val features: Int,
    val threshold: ScanParameter
) {
    MobileNet("MobileNetV3-Small 1.0×", "mobilenet-v3-small.onnx", 224, 576, ScanParameter.MobileNetPercent),
    MobileClip("MobileCLIP-S0", "mobileclip-s0.onnx", 256, 512, ScanParameter.MobileClipPercent)
}

internal data class SimulationTimings(
    val totalMillis: Long,
    val baselineMillis: Long,
    val models: List<ModelTiming>
)

internal data class ModelTiming(
    val model: SimulationModel,
    val millis: Long,
    val scoredPairs: Int,
    val encoded: Int,
    val cached: Int,
    val failed: Boolean = false
)
