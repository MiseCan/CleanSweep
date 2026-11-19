package com.cleansweep.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.PhotoFeatureCache
import com.cleansweep.data.repository.PhotoFeatureCache.Part
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.domain.model.GeometryMetrics
import com.cleansweep.domain.model.PhotoComparison
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.model.SimilarScanResult
import com.cleansweep.domain.model.SimilarScanScope
import com.cleansweep.domain.model.ScanParameter
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.SimilaritySimulation
import com.cleansweep.domain.model.SimulationWindow
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.util.DctPhotoHash
import com.cleansweep.domain.util.PhotoGeometry
import com.cleansweep.domain.util.captureWindows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class SimilarPhotoFinder(private val context: Context) {
    private data class TimedPhoto(val item: MediaItem, val capturedAt: Long)

    private val cache = PhotoFeatureCache(context)

    private inner class WindowBitmaps : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        fun obtain(item: MediaItem): Bitmap? =
            get(item.id) ?: decodeAnalysisPhoto(context, item)?.also { put(item.id, it) }

        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) oldValue.recycle()
        }
    }

    private inner class Photo(
        val source: TimedPhoto,
        val hash: Long,
        val colorHistogram: IntArray,
        private val bitmaps: WindowBitmaps
    ) : AutoCloseable {
        private var orb: PhotoGeometry.Features? = null
        val item: MediaItem get() = source.item

        fun geometry(detector: PhotoGeometry): PhotoGeometry.Features {
            orb?.let { return it }
            val revision = cache.revision(item)
            val cached = revision?.let { cache.read(Part.Orb, item.id, it) }?.let(::decodeOrb)
            if (cached != null) {
                orb = cached
                return cached
            }
            val bitmap = analysisBitmap() ?: throw IOException("Could not reopen a photo for ORB")
            val features = detector.extract(bitmap)
            orb = features
            if (revision != null) cache.write(Part.Orb, item.id, revision, encodeOrb(features))
            return features
        }

        fun analysisBitmap(): Bitmap? = bitmaps.obtain(item)

        override fun close() { orb?.close() }
    }

    suspend fun find(
        images: List<MediaItem>,
        thresholds: ScanThresholds = ScanThresholds(),
        reviewModel: SimulationModel = SimulationModel.MobileNet,
        onProgress: (Int) -> Unit
    ): SimilarScanResult = withContext(Dispatchers.Default) {
        scan(readCaptureTimes(images), images.size, thresholds, reviewModel) { increment, _ -> onProgress(increment) }
    }

    suspend fun findInScope(
        images: List<MediaItem>,
        thresholds: ScanThresholds,
        scope: SimilarScanScope,
        reviewModel: SimulationModel,
        onProgress: (processed: Int, total: Int, remainingMillis: Long?) -> Unit
    ): SimilarScanResult = withContext(Dispatchers.Default) {
        val timed = scope.select(readCaptureTimes(images)) { it.capturedAt }
        var processed = 0
        scan(timed, timed.size, thresholds, reviewModel) { increment, remainingMillis ->
            processed += increment
            onProgress(processed, timed.size, remainingMillis)
        }
    }

    suspend fun simulate(
        images: List<MediaItem>,
        thresholds: ScanThresholds,
        reviewModel: SimulationModel? = null,
        onProgress: (Int) -> Unit
    ): SimilaritySimulation = withContext(Dispatchers.Default) {
        val timed = readCaptureTimes(images)
        val windows = captureWindows(timed, thresholds[ScanParameter.CaptureSeconds] * 1_000L) { it.capturedAt }
        val singletons = timed.size - windows.sumOf { it.size }
        val undated = images.size - timed.size
        if (windows.isEmpty()) {
            return@withContext SimilaritySimulation(0, undated, singletons, 0, thresholds, emptyList())
        }

        val report = mutableListOf<SimulationWindow>()
        var remaining = SIMULATION_SAMPLE_LIMIT
        var unreadable = 0
        check(OpenCVLoader.initLocal()) { "OpenCV could not be loaded" }
        PhotoGeometry(thresholds).use { geometry ->
            reviewModel?.let { ModelReview(thresholds, it) }.use { review ->
                for (window in windows.asReversed()) {
                    currentCoroutineContext().ensureActive()
                    if (remaining < 2) break
                    val readable = mutableListOf<Photo>()
                    val bitmaps = WindowBitmaps()
                    var failures = 0
                    try {
                        for (item in window.asReversed()) {
                            currentCoroutineContext().ensureActive()
                            val photo = loadFeatures(item, bitmaps)
                            if (photo == null) failures++ else readable.add(photo)
                            if (readable.size == remaining) break
                        }
                        unreadable += failures
                        if (readable.size < 2) continue

                        remaining -= readable.size
                        onProgress(readable.size)
                        report.add(simulateWindow(readable.asReversed(), failures, geometry, thresholds,
                            review))
                    } finally {
                        readable.forEach { it.close() }
                        bitmaps.evictAll()
                        review?.clearWindow()
                    }
                }
            }
        }
        SimilaritySimulation(SIMULATION_SAMPLE_LIMIT - remaining, undated, singletons, unreadable, thresholds, report)
    }

    private suspend fun readCaptureTimes(images: List<MediaItem>): List<TimedPhoto> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        images.sortedBy { it.id }.mapNotNull { item ->
            job.ensureActive()
            val time = item.dateTaken ?: cachedCaptureTime(item)
            time?.takeIf { it > 0 }?.let { TimedPhoto(item, it) }
        }
    }

    private suspend fun scan(
        timed: List<TimedPhoto>,
        inputCount: Int,
        thresholds: ScanThresholds,
        reviewModel: SimulationModel,
        onProgress: (Int, Long?) -> Unit
    ): SimilarScanResult {
        val windows = captureWindows(timed, thresholds[ScanParameter.CaptureSeconds] * 1_000L) { it.capturedAt }
        var remaining = windows.sumOf { it.size }
        onProgress(inputCount - remaining, null)
        if (remaining == 0) return SimilarScanResult(emptyList(), emptyList())

        val estimate = ScanTimeEstimate()
        fun advance(count: Int) {
            remaining -= count
            onProgress(count, estimate.remainingMillis(remaining))
        }

        val results = mutableListOf<SimilarGroup>()
        val skipped = mutableListOf<String>()
        check(OpenCVLoader.initLocal()) { "OpenCV could not be loaded" }
        PhotoGeometry(thresholds).use { geometry ->
            ModelReview(thresholds, reviewModel).use { review ->
                for (window in windows) {
                    currentCoroutineContext().ensureActive()
                    val key = windowKey(window, thresholds, reviewModel)
                    val checkpoint = key?.let { readWindow(it, window) }
                    if (checkpoint != null) {
                        results.addAll(checkpoint)
                        advance(window.size)
                        continue
                    }

                    val photos = mutableListOf<Photo>()
                    val bitmaps = WindowBitmaps()
                    val clusters = mutableListOf<MutableList<Photo>>()
                    var checkpointTime = SystemClock.elapsedRealtime()
                    fun completedPhoto() {
                        val now = SystemClock.elapsedRealtime()
                        estimate.recordPhoto(now - checkpointTime)
                        advance(1)
                        checkpointTime = SystemClock.elapsedRealtime()
                    }
                    try {
                        for ((index, item) in window.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val photo = loadFeatures(item, bitmaps)
                            if (photo == null) {
                                skipped.add(item.item.id)
                            } else {
                                photos.add(photo)
                                addToCluster(clusters, photo) { a, b -> matches(a, b, geometry, thresholds) }
                            }
                            if (index < window.lastIndex) completedPhoto()
                        }
                        currentCoroutineContext().ensureActive()
                        val groups = clusters.toGroups() + review.suggest(clusters)
                        if (key != null && photos.size == window.size && review.canCheckpoint) {
                            cache.write(Part.Window, key, WINDOW_FORMAT, Json.encodeToString(
                                groups.map { group -> WindowGroup(group.items.map { it.id }, group.suggested) }
                            ).encodeToByteArray())
                        }
                        results.addAll(groups)
                        completedPhoto()
                    } finally {
                        photos.forEach { it.close() }
                        bitmaps.evictAll()
                        review.clearWindow()
                    }
                }
            }
        }
        return SimilarScanResult(results, skipped)
    }

    private fun loadFeatures(photo: TimedPhoto, bitmaps: WindowBitmaps): Photo? {
        val item = photo.item
        val revision = cache.revision(item)
        val saved = revision?.let { cache.read(Part.Appearance, item.id, it) }
        if (saved != null && saved.size == 136) {
            val bytes = ByteBuffer.wrap(saved)
            return Photo(photo, bytes.long, IntArray(64) { bytes.short.toInt() }, bitmaps)
        }
        val bitmap = bitmaps.obtain(item) ?: return null
        val hash = DctPhotoHash.hash(bitmap)
        val histogram = colorHistogram(bitmap)
        if (revision != null) {
            val bytes = ByteBuffer.allocate(136).putLong(hash)
            histogram.forEach { bytes.putShort(it.toShort()) }
            cache.write(Part.Appearance, item.id, revision, bytes.array())
        }
        return Photo(photo, hash, histogram, bitmaps)
    }

    private fun cachedCaptureTime(item: MediaItem): Long? {
        val revision = cache.revision(item)?.let { "$it:${ZoneId.systemDefault().id}" }
        val saved = revision?.let { cache.read(Part.Capture, item.id, it) }
        if (saved?.size == Long.SIZE_BYTES) return ByteBuffer.wrap(saved).long
        return exifCaptureTime(item) {
            if (revision != null) cache.write(Part.Capture, item.id, revision,
                ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it).array())
        }
    }

    private fun encodeOrb(features: PhotoGeometry.Features): ByteArray {
        val count = features.points.size
        val descriptors = ByteArray(count * 32)
        if (count > 0) features.descriptors.get(0, 0, descriptors)
        val bytes = ByteBuffer.allocate(12 + count * 40)
        bytes.putInt(features.width).putInt(features.height).putInt(count).put(descriptors)
        features.points.forEach { bytes.putFloat(it.x.toFloat()).putFloat(it.y.toFloat()) }
        return bytes.array()
    }

    private fun decodeOrb(data: ByteArray): PhotoGeometry.Features? {
        if (data.size < 12) return null
        val bytes = ByteBuffer.wrap(data)
        val width = bytes.int
        val height = bytes.int
        val count = bytes.int
        if (width <= 0 || height <= 0 || count < 0 || data.size.toLong() != 12L + count * 40L) return null
        val descriptors = if (count == 0) Mat() else Mat(count, 32, CvType.CV_8UC1)
        try {
            if (count > 0) descriptors.put(0, 0, ByteArray(count * 32).also { bytes.get(it) })
            val points = List(count) { Point(bytes.float.toDouble(), bytes.float.toDouble()) }
            return PhotoGeometry.Features(descriptors, points, width, height)
        } catch (error: Exception) {
            descriptors.release()
            throw error
        }
    }

    private fun windowKey(
        window: List<TimedPhoto>,
        thresholds: ScanThresholds,
        reviewModel: SimulationModel
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.encodeToByteArray())
            digest.update(0.toByte())
        }
        // Preserve the existing MobileNet window keys and keep MobileCLIP results separate.
        if (reviewModel != SimulationModel.MobileNet) add(reviewModel.name)
        for (parameter in ScanParameter.entries) {
            if (parameter == ScanParameter.MobileNetPercent || parameter == ScanParameter.MobileClipPercent) continue
            add(thresholds[parameter].toString())
        }
        add(thresholds[reviewModel.threshold].toString())
        for (photo in window) {
            add(photo.item.id)
            add(cache.revision(photo.item) ?: return null)
            add(photo.capturedAt.toString())
        }
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
    }

    private fun readWindow(key: String, window: List<TimedPhoto>): List<SimilarGroup>? {
        val saved = cache.read(Part.Window, key, WINDOW_FORMAT) ?: return null
        val groups = try {
            Json.decodeFromString<List<WindowGroup>>(saved.decodeToString())
        } catch (_: kotlinx.serialization.SerializationException) {
            return null
        }
        val byId = window.associate { it.item.id to it.item }
        return groups.map { group ->
            val ids = group.photoIds
            if (ids.size < 2) return null
            SimilarGroup(ids.first(), ids.map { byId[it] ?: return null }, group.suggested)
        }
    }

    @kotlinx.serialization.Serializable
    private data class WindowGroup(val photoIds: List<String>, val suggested: Boolean)

    private suspend fun simulateWindow(
        photos: List<Photo>,
        unreadable: Int,
        geometry: PhotoGeometry,
        thresholds: ScanThresholds,
        review: ModelReview?
    ): SimulationWindow {
        val comparisons = linkedMapOf<Pair<String, String>, PhotoComparison>()
        fun compare(later: Photo, earlier: Photo): Boolean {
            val key = earlier.item.id to later.item.id
            return comparisons[key]?.similar ?: matches(later, earlier, geometry, thresholds) {
                comparisons[key] = it
            }
        }
        val clusters = mutableListOf<MutableList<Photo>>()
        photos.forEach { addToCluster(clusters, it, ::compare) }
        var groups = clusters.toGroups()
        if (review != null) groups += review.suggest(clusters) { a, b, score ->
            val key = b.item.id to a.item.id
            comparisons[key]?.let { comparisons[key] = it.copy(modelScores = mapOf(review.model to score)) }
        }
        val suggestedMembership = buildMap {
            groups.filter { it.suggested }.forEach { group ->
                group.items.forEach { put(it.id, group.id) }
            }
        }
        // Record the remaining pairs for diagnostics after running the production grouping order.
        for (laterIndex in photos.indices) {
            for (earlierIndex in 0 until laterIndex) {
                currentCoroutineContext().ensureActive()
                compare(photos[laterIndex], photos[earlierIndex])
            }
        }
        return SimulationWindow(
            photos.first().source.capturedAt,
            photos.last().source.capturedAt,
            photos.map { it.item },
            unreadable,
            groups,
            comparisons.values.map { pair ->
                val group = suggestedMembership[pair.earlier.id]
                if (group != null && group == suggestedMembership[pair.later.id]) pair.copy(suggested = true) else pair
            }
        )
    }

    companion object {
        const val SIMULATION_SAMPLE_LIMIT = 300
        private const val WINDOW_FORMAT = "2"
    }

    private suspend fun addToCluster(
        clusters: MutableList<MutableList<Photo>>,
        photo: Photo,
        matches: (Photo, Photo) -> Boolean
    ) {
        val cluster = clusters.firstOrNull { members ->
            members.all { other ->
                currentCoroutineContext().ensureActive()
                matches(photo, other)
            }
        }
        if (cluster == null) clusters.add(mutableListOf(photo)) else cluster.add(photo)
    }

    private fun List<List<Photo>>.toGroups(suggested: Boolean = false): List<SimilarGroup> =
        filter { it.size > 1 }.map { cluster ->
            SimilarGroup(cluster.first().item.id, cluster.map { it.item }, suggested)
        }

    private inner class ModelReview(thresholds: ScanThresholds, val model: SimulationModel) : AutoCloseable {
        private val threshold = thresholds[model.threshold] / 100.0
        private val embeddings = ImageEmbeddingSession(context, cache, model)
        private var failed = false
        var canCheckpoint = true
            private set

        fun clearWindow() {
            embeddings.clearWindow()
            canCheckpoint = !failed
        }

        suspend fun suggest(
            clusters: List<List<Photo>>,
            onScore: ((Photo, Photo, Double) -> Unit)? = null
        ): List<SimilarGroup> {
            if (failed) return emptyList()
            val suggestions = mutableListOf<MutableList<Photo>>()
            try {
                // Photos in confirmed groups keep their existing membership.
                for (cluster in clusters) {
                    if (cluster.size != 1) continue
                    addToCluster(suggestions, cluster.single()) { a, b ->
                        val first = embeddings.vector(a.item, a::analysisBitmap)
                        val second = if (first != null) embeddings.vector(b.item, b::analysisBitmap) else null
                        if (first == null || second == null) {
                            canCheckpoint = false
                            false
                        } else {
                            val score = embeddingSimilarity(first, second)
                            onScore?.invoke(a, b, score)
                            score >= threshold
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                canCheckpoint = false
                ErrorJournal.warn("${model.label} similarity review failed", error)
                return emptyList()
            } catch (error: LinkageError) {
                failed = true
                canCheckpoint = false
                ErrorJournal.warn("${model.label} runtime unavailable", error)
                return emptyList()
            }
            return suggestions.toGroups(suggested = true)
        }

        override fun close() = embeddings.close()
    }

    private fun matches(
        a: Photo,
        b: Photo,
        geometry: PhotoGeometry,
        thresholds: ScanThresholds,
        onComparison: ((PhotoComparison) -> Unit)? = null
    ): Boolean {
        val hashDistance = java.lang.Long.bitCount(a.hash xor b.hash)
        val colorDistance = histogramDistance(a.colorHistogram, b.colorHistogram)
        val fastMatch = hashDistance <= thresholds[ScanParameter.HashDistance] &&
            colorDistance <= thresholds[ScanParameter.ColorDistancePercent] / 100.0
        if (onComparison == null) return fastMatch || geometry.matches(a.geometry(geometry), b.geometry(geometry))
        var metrics: GeometryMetrics? = null
        val similar = fastMatch || geometry.matches(a.geometry(geometry), b.geometry(geometry)) { metrics = it }
        onComparison(PhotoComparison(
            b.item, a.item, a.source.capturedAt - b.source.capturedAt,
            hashDistance, colorDistance * 100.0, similar, metrics
        ))
        return similar
    }

    private fun exifCaptureTime(item: MediaItem, onRead: (Long) -> Unit): Long? {
        return try {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val timestamp = exif.dateTimeOriginal
                if (timestamp == null) {
                    onRead(0L)
                    return@use null
                }
                val epoch = if (exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) != null) {
                    timestamp
                } else {
                    // AndroidX parses an offsetless EXIF clock as UTC. Interpret it in device time.
                    Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC).toLocalDateTime()
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                onRead(epoch.coerceAtLeast(0L))
                epoch.takeIf { it > 0 }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun colorHistogram(bitmap: Bitmap): IntArray {
        val thumbnail = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(256)
        try {
            thumbnail.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
        val bins = IntArray(64)
        for (pixel in pixels) {
            val red = (pixel ushr 22) and 3
            val green = (pixel ushr 14) and 3
            val blue = (pixel ushr 6) and 3
            bins[(red shl 4) or (green shl 2) or blue]++
        }
        return bins
    }

    private fun histogramDistance(a: IntArray, b: IntArray): Double =
        1.0 - a.indices.sumOf { minOf(a[it], b[it]) } / 256.0

}
