package com.cleansweep.domain.usecase

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.PhotoFeatureCache
import com.cleansweep.domain.model.SimulationModel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.round
import kotlin.math.sqrt

internal class ImageEmbeddingSession(
    context: Context,
    private val cache: PhotoFeatureCache,
    private val model: SimulationModel
) : AutoCloseable {
    private val encoder = lazy { ImageEmbedder(context, model) }
    private val embeddings = mutableMapOf<String, FloatArray?>()
    var encoded = 0
        private set
    var cached = 0
        private set

    fun clearWindow() = embeddings.clear()

    fun vector(photo: MediaItem, bitmap: (() -> Bitmap?)? = null): FloatArray? {
        if (embeddings.containsKey(photo.id)) return embeddings[photo.id]
        return load(photo, bitmap).also { embeddings[photo.id] = it }
    }

    private fun load(photo: MediaItem, bitmap: (() -> Bitmap?)?): FloatArray? {
        val format = if (model == SimulationModel.MobileNet) "embed-v2" else "embed-v1"
        val revision = cache.revision(photo)?.let { "$it:$format" }
        val id = "${model.name}:${photo.id}"
        val saved = revision?.let { cache.read(PhotoFeatureCache.Part.Embedding, id, it) }
        if (saved?.size == model.features * Float.SIZE_BYTES) {
            cached++
            val bytes = ByteBuffer.wrap(saved)
            return FloatArray(model.features) { bytes.float }
        }
        val vector = encoder.value.encode(photo, bitmap) ?: return null
        encoded++
        if (revision != null) {
            val bytes = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            vector.forEach { bytes.putFloat(it) }
            cache.write(PhotoFeatureCache.Part.Embedding, id, revision, bytes.array())
        }
        return vector
    }

    override fun close() {
        if (encoder.isInitialized()) encoder.value.close()
    }
}

internal fun embeddingSimilarity(a: FloatArray, b: FloatArray): Double {
    var similarity = 0.0
    for (index in a.indices) similarity += a[index].toDouble() * b[index]
    return similarity.coerceIn(-1.0, 1.0)
}

private class ImageEmbedder(private val context: Context, private val model: SimulationModel) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = context.assets.openFd("models/${model.asset}").use { asset ->
        asset.createInputStream().use { stream ->
            val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                environment.createSession(mapped, options)
            }
        }
    }

    fun encode(photo: MediaItem, analysisBitmap: (() -> Bitmap?)?): FloatArray? {
        val size = model.inputSize
        val plane = size * size
        val pixels = IntArray(plane)
        if (model == SimulationModel.MobileNet) {
            val source = if (analysisBitmap == null) decodeAnalysisPhoto(context, photo) else analysisBitmap()
            if (source == null) return null
            val input = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val shortest = minOf(source.width, source.height)
                val width = (source.width * 256L / shortest).toInt()
                val height = (source.height * 256L / shortest).toInt()
                val left = round((width - size) / 2.0).toFloat()
                val top = round((height - size) / 2.0).toFloat()
                Canvas(input).drawBitmap(source, null, RectF(-left, -top, width - left, height - top),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                input.getPixels(pixels, 0, size, 0, 0, size, size)
            } finally {
                input.recycle()
                if (analysisBitmap == null) source.recycle()
            }
        } else {
            val bitmap = decode(photo) ?: return null
            try {
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            } finally {
                bitmap.recycle()
            }
        }
        val data = FloatArray(plane * 3)
        val means = if (model == SimulationModel.MobileNet) floatArrayOf(.485f, .456f, .406f) else FloatArray(3)
        val deviations = if (model == SimulationModel.MobileNet) floatArrayOf(.229f, .224f, .225f) else FloatArray(3) { 1f }
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            for (i in pixels.indices) {
                data[channel * plane + i] = (((pixels[i] ushr shift) and 255) / 255f - means[channel]) / deviations[channel]
            }
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data),
            longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf("image" to tensor)).use { outputs ->
                val vector = (outputs[0].value as Array<*>).single() as FloatArray
                check(vector.size == model.features)
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                if (norm == 0.0 || !norm.isFinite()) null else FloatArray(vector.size) { (vector[it] / norm).toFloat() }
            }
        }
    }

    private fun decode(photo: MediaItem): Bitmap? {
        return try {
            val source = if (photo.uri.scheme == "file") {
                ImageDecoder.createSource(File(requireNotNull(photo.uri.path)))
            } else ImageDecoder.createSource(context.contentResolver, photo.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val crop = model.inputSize
                val minimum = minOf(info.size.width, info.size.height)
                val width = (info.size.width * 256L / minimum).toInt().coerceAtLeast(crop)
                val height = (info.size.height * 256L / minimum).toInt().coerceAtLeast(crop)
                val left = round((width - crop) / 2.0).toInt()
                val top = round((height - crop) / 2.0).toInt()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSize(width, height)
                decoder.setCrop(Rect(left, top, left + crop, top + crop))
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun close() { session.close() }
}
