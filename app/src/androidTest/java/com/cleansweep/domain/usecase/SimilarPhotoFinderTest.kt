package com.cleansweep.domain.usecase

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.PhotoFeatureCache
import com.cleansweep.domain.model.ScanParameter
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.util.PhotoGeometry
import java.io.File
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class SimilarPhotoFinderTest {
    @Test
    fun readsCaptureMetadataAndComparesOnlyInsideTimeWindow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "similar-photo-test").apply { mkdirs() }
        val bitmap = makePhoto()
        try {
            val start = Instant.parse("2026-05-01T03:00:00Z").toEpochMilli()
            val original = save(directory, "original", bitmap, start)
            val boundary = save(directory, "boundary", bitmap, null)
            ExifInterface(requireNotNull(boundary.uri.path)).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:05:01 11:02:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
                saveAttributes()
            }
            val outside = save(directory, "outside", bitmap, start + 120_001)
            val undated = save(directory, "undated", bitmap, null)
            val inputs = listOf(outside, undated, boundary, original)
            val finder = SimilarPhotoFinder(context)
            var progress = 0
            val result = finder.find(inputs) {
                progress += it
            }
            assertEquals(listOf(listOf(original.id, boundary.id)),
                result.groups.map { group -> group.items.map { it.id } })
            assertEquals(emptyList<String>(), result.skippedFilePaths)
            assertEquals(inputs.size, progress)

            val shorterWindow = ScanThresholds().with(ScanParameter.CaptureSeconds, 90)
            val rescanned = finder.find(inputs, shorterWindow) {}
            assertEquals(listOf(listOf(boundary.id, outside.id)),
                rescanned.groups.map { group -> group.items.map { it.id } })

            val report = finder.simulate(inputs, ScanThresholds()) {}
            assertEquals(2, report.sampled)
            assertEquals(1, report.undated)
            assertEquals(1, report.singletons)
            assertEquals(listOf(2), report.windows.map { it.photos.size })
            val metrics = report.windows.last().comparisons.single()
            assertTrue(metrics.similar)
            assertEquals(0, metrics.hashDistance)
            assertEquals(0.0, metrics.colorDistancePercent, 0.0)
            assertEquals(null, metrics.geometry)
            assertEquals(result.groups, report.windows.last().groups)
        } finally {
            bitmap.recycle()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun fillsTheQuotaFromOlderComparableWindows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "similar-photo-quota-test").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            val start = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val a = save(directory, "a", bitmap, null)
            val b = save(directory, "b", bitmap, null)
            val pairs = (0..150).map { index ->
                val time = start + index * 3_600_000L
                listOf(a.copy(dateTaken = time), b.copy(dateTaken = time + 1))
            }
            val invalidTime = start + 151 * 3_600_000L
            val brokenWindow = listOf(
                a.copy(dateTaken = invalidTime),
                MediaItem(Uri.fromFile(File(directory, "missing.jpg")), "missing.jpg", invalidTime + 1)
            )
            val singletons = (152..452).map { a.copy(dateTaken = start + it * 3_600_000L) }
            var progress = 0
            val report = SimilarPhotoFinder(context).simulate(pairs.flatten() + brokenWindow + singletons, ScanThresholds()) {
                progress += it
            }
            assertEquals(300, report.sampled)
            assertEquals(300, progress)
            assertEquals(0, report.undated)
            assertEquals(singletons.size, report.singletons)
            assertEquals(1, report.unreadable)
            assertEquals(pairs.drop(1).asReversed(), report.windows.map { it.photos })
        } finally {
            bitmap.recycle()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun resumesFeatureWorkAndReusesOnlyUnchangedWindows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "feature-checkpoint").toFile()
        val original = makePhoto()
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
            Matrix().apply { setRotate(12f) }, true)
        val revision = directory.name
        val start = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        val inputs = listOf(
            save(directory, "first", original, start),
            save(directory, "duplicate", original, start + 1),
            save(directory, "source", original, start + 200_000),
            save(directory, "rotated", rotated, start + 200_001)
        ).map { it.copy(revision = revision) }
        try {
            val finder = SimilarPhotoFinder(context)
            val thresholds = ScanThresholds().with(ScanParameter.HashDistance, 0)
            var processed = 0
            val cancelled = try {
                finder.find(inputs, thresholds) { increment ->
                    processed += increment
                    if (processed == 3) throw CancellationException("test scan interruption")
                }
                false
            } catch (_: CancellationException) {
                true
            }
            assertTrue(cancelled)
            PhotoFeatureCache(context).use { cache ->
                fun feature(part: PhotoFeatureCache.Part, item: MediaItem) =
                    cache.read(part, item.id, requireNotNull(cache.revision(item)))
                assertTrue(feature(PhotoFeatureCache.Part.Appearance, inputs[2]) != null)
                assertTrue(feature(PhotoFeatureCache.Part.Appearance, inputs[3]) == null)
                assertTrue(feature(PhotoFeatureCache.Part.Orb, inputs[0]) == null)

                val resumed = mutableListOf<Int>()
                val result = SimilarPhotoFinder(context).find(inputs, thresholds) { resumed.add(it) }
                assertEquals(listOf(0, 2, 1, 1), resumed)
                assertTrue(feature(PhotoFeatureCache.Part.Orb, inputs[2]) != null)
                assertTrue(feature(PhotoFeatureCache.Part.Orb, inputs[3]) != null)

                val warm = mutableListOf<Int>()
                assertEquals(result, SimilarPhotoFinder(context).find(inputs, thresholds) { warm.add(it) })
                assertEquals(listOf(0, 2, 2), warm)

                val changed = inputs.toMutableList().apply { this[3] = this[3].copy(revision = "${revision}-changed") }
                val invalidated = mutableListOf<Int>()
                val recomputed = SimilarPhotoFinder(context).find(changed, thresholds) { invalidated.add(it) }
                assertEquals(result.groups.map { it.items.map(MediaItem::id) }, recomputed.groups.map { it.items.map(MediaItem::id) })
                assertEquals(listOf(0, 2, 1, 1), invalidated)
            }
        } finally {
            rotated.recycle()
            original.recycle()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun suggestsPreviouslyUngroupedPhotosAndReusesTheirModelFeatures() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "mobile-net-review").toFile()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val time = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            bitmap.eraseColor(Color.rgb(60, 110, 140))
            val first = save(directory, "first", bitmap, time).copy(revision = directory.name)
            bitmap.eraseColor(Color.rgb(66, 110, 140))
            val second = save(directory, "second", bitmap, time + 1).copy(revision = directory.name)
            val thresholds = ScanThresholds().with(ScanParameter.ColorDistancePercent, 0)
            val finder = SimilarPhotoFinder(context)
            val photos = listOf(first, second)

            val baseline = finder.simulate(photos, thresholds) {}
            assertTrue(baseline.windows.single().groups.isEmpty())
            val firstRun = finder.find(photos, thresholds) {}
            assertEquals(photos, firstRun.groups.single().items)
            assertTrue(firstRun.groups.single().suggested)

            val warmIncrements = mutableListOf<Int>()
            val warm = finder.find(photos, thresholds) { warmIncrements += it }
            assertEquals(firstRun, warm)
            assertEquals(listOf(0, 2), warmIncrements)

            val (evaluated, timing) = SimulationModelEvaluator(context).evaluate(SimulationModel.MobileNet, baseline.windows)
            assertEquals(0, timing.encoded)
            assertEquals(2, timing.cached)
            val modelScore = evaluated.single().comparisons.single().modelScores.getValue(SimulationModel.MobileNet)
            val normal = finder.simulate(photos, thresholds, reviewModel = SimulationModel.MobileNet) {}
            val pair = normal.windows.single().comparisons.single()
            assertTrue(pair.suggested)
            assertEquals(modelScore, pair.modelScores.getValue(SimulationModel.MobileNet), 0.0)

            val clipThresholds = thresholds.with(ScanParameter.MobileClipPercent, 50)
            val clipIncrements = mutableListOf<Int>()
            val clip = finder.find(photos, clipThresholds, SimulationModel.MobileClip) { clipIncrements += it }
            assertTrue(clip.groups.single().suggested)
            assertEquals(listOf(0, 1, 1), clipIncrements)

            val clipReport = finder.simulate(photos, clipThresholds, reviewModel = SimulationModel.MobileClip) {}
            val clipPair = clipReport.windows.single().comparisons.single()
            assertEquals(setOf(SimulationModel.MobileClip), clipPair.modelScores.keys)
            assertTrue(clipPair.suggested)

            val clipWarm = mutableListOf<Int>()
            assertEquals(clip, finder.find(photos, clipThresholds, SimulationModel.MobileClip) { clipWarm += it })
            assertEquals(listOf(0, 2), clipWarm)
            val netWarm = mutableListOf<Int>()
            assertEquals(firstRun, finder.find(photos, clipThresholds) { netWarm += it })
            assertEquals(listOf(0, 2), netWarm)
        } finally {
            bitmap.recycle()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun orbAndRansacRecoverARotatedImage() {
        assertTrue(OpenCVLoader.initLocal())
        val original = makePhoto()
        val rotation = Matrix().apply { setRotate(12f) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, rotation, true)
        try {
            PhotoGeometry(ScanThresholds()).use { geometry ->
                geometry.extract(original).use { a ->
                    geometry.extract(rotated).use { b ->
                        assertTrue(geometry.matches(a, b))
                    }
                }
            }
        } finally {
            rotated.recycle()
            original.recycle()
        }
    }

    private fun makePhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(220, 210, 198))
        val random = Random(81)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(220) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextInt(30, 480).toFloat()
            val y = random.nextInt(30, 480).toFloat()
            canvas.drawRect(x, y, x + random.nextInt(8, 35), y + random.nextInt(7, 29), paint)
        }
        return bitmap
    }

    private fun save(directory: File, name: String, bitmap: Bitmap, captured: Long?): MediaItem {
        val file = File(directory, "$name.jpg")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        return MediaItem(Uri.fromFile(file), file.name, captured)
    }
}
