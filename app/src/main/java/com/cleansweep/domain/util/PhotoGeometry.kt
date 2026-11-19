package com.cleansweep.domain.util

import android.graphics.Bitmap
import com.cleansweep.domain.model.GeometryMetrics
import com.cleansweep.domain.model.GeometryStage
import com.cleansweep.domain.model.ScanParameter
import com.cleansweep.domain.model.ScanThresholds
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

internal class PhotoGeometry(thresholds: ScanThresholds) : AutoCloseable {
    private val minimumInliers = thresholds[ScanParameter.MinimumInliers]
    private val matchRatio = thresholds[ScanParameter.OrbRatioPercent] / 100.0
    private val reprojectionError = thresholds[ScanParameter.RansacErrorPixels].toDouble()
    private val inlierPercent = thresholds[ScanParameter.InlierPercent]
    private val pointSpread = thresholds[ScanParameter.SpreadPercent] / 100.0
    private val detector = ORB.create(600)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    class Features(
        val descriptors: Mat,
        val points: List<Point>,
        val width: Int,
        val height: Int
    ) : AutoCloseable {
        override fun close() = descriptors.release()
    }

    fun extract(bitmap: Bitmap): Features {
        val rgba = Mat()
        val gray = Mat()
        val mask = Mat()
        val points = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            detector.detectAndCompute(gray, mask, points, descriptors)
            return Features(descriptors, points.toList().map { it.pt }, bitmap.width, bitmap.height)
        } catch (error: Exception) {
            descriptors.release()
            throw error
        } finally {
            points.release()
            mask.release()
            gray.release()
            rgba.release()
        }
    }

    fun matches(a: Features, b: Features, onMetrics: ((GeometryMetrics) -> Unit)? = null): Boolean {
        if (a.points.size < minimumInliers || b.points.size < minimumInliers) {
            onMetrics?.invoke(GeometryMetrics(GeometryStage.TooFewKeypoints, a.points.size, b.points.size))
            return false
        }
        val nearest = mutableListOf<MatOfDMatch>()
        val source = MatOfPoint2f()
        val target = MatOfPoint2f()
        val inlierMask = Mat()
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, nearest, 2)
            val good = nearest.mapNotNull { neighbors ->
                val pair = neighbors.toArray()
                pair.firstOrNull()?.takeIf { pair.size == 2 && it.distance < matchRatio * pair[1].distance }
            }.sortedBy { it.distance }.distinctBy { it.trainIdx }
            if (good.size < minimumInliers) {
                onMetrics?.invoke(GeometryMetrics(GeometryStage.TooFewMatches, a.points.size, b.points.size, good.size))
                return false
            }

            val sourcePoints = good.map { a.points[it.queryIdx] }
            val targetPoints = good.map { b.points[it.trainIdx] }
            source.fromList(sourcePoints)
            target.fromList(targetPoints)
            val homography = Calib3d.findHomography(source, target, Calib3d.RANSAC, reprojectionError, inlierMask)
            try {
                if (homography.empty() || inlierMask.total() != good.size.toLong()) {
                    onMetrics?.invoke(GeometryMetrics(GeometryStage.NoTransform, a.points.size, b.points.size, good.size))
                    return false
                }
                val mask = ByteArray(good.size)
                inlierMask.get(0, 0, mask)
                val inliers = mask.indices.filter { mask[it] != 0.toByte() }
                if (onMetrics != null) {
                    val span = if (inliers.isEmpty()) 0.0 else minOf(
                        minSpread(inliers.map(sourcePoints::get), a),
                        minSpread(inliers.map(targetPoints::get), b)
                    )
                    onMetrics(GeometryMetrics(
                        GeometryStage.Estimated, a.points.size, b.points.size, good.size, inliers.size, span * 100.0
                    ))
                }
                return inliers.size >= minimumInliers && inliers.size * 100 >= good.size * inlierPercent &&
                    hasSpread(inliers.map(sourcePoints::get), a) &&
                    hasSpread(inliers.map(targetPoints::get), b)
            } finally {
                homography.release()
            }
        } finally {
            inlierMask.release()
            target.release()
            source.release()
            nearest.forEach { it.release() }
        }
    }

    private fun hasSpread(points: List<Point>, features: Features): Boolean =
        points.maxOf { it.x } - points.minOf { it.x } >= features.width * pointSpread &&
            points.maxOf { it.y } - points.minOf { it.y } >= features.height * pointSpread

    private fun minSpread(points: List<Point>, features: Features): Double = minOf(
        (points.maxOf { it.x } - points.minOf { it.x }) / features.width,
        (points.maxOf { it.y } - points.minOf { it.y }) / features.height
    )

    override fun close() {
        matcher.clear()
        detector.clear()
    }
}
