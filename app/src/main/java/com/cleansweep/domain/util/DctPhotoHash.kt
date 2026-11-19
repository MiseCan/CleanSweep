package com.cleansweep.domain.util

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos

internal object DctPhotoHash {
    private const val SIZE = 32
    private const val LOW_FREQUENCIES = 8
    private val basis = Array(LOW_FREQUENCIES) { frequency ->
        DoubleArray(SIZE) { position ->
            cos(PI * (position + 0.5) * frequency / SIZE)
        }
    }

    fun hash(bitmap: Bitmap): Long {
        val thumbnail = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        try {
            thumbnail.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
        return hashLuminance(DoubleArray(pixels.size) { index ->
            val rgb = pixels[index]
            0.299 * ((rgb shr 16) and 255) + 0.587 * ((rgb shr 8) and 255) +
                0.114 * (rgb and 255)
        })
    }

    internal fun hashLuminance(luminance: DoubleArray): Long {
        val mean = luminance.average()
        val rows = Array(SIZE) { row ->
            DoubleArray(LOW_FREQUENCIES) { u ->
                (0 until SIZE).sumOf { x -> (luminance[row * SIZE + x] - mean) * basis[u][x] }
            }
        }
        val coefficients = DoubleArray(63) { index ->
            val u = (index + 1) % LOW_FREQUENCIES
            val v = (index + 1) / LOW_FREQUENCIES
            val scale = if (u == 0 || v == 0) 0.7071067811865476 else 1.0
            (0 until SIZE).sumOf { y -> rows[y][u] * basis[v][y] } * scale
        }
        val median = coefficients.sortedArray()[coefficients.size / 2]
        return coefficients.fold(0L) { bits, value ->
            (bits shl 1) or if (value > median + 1e-8) 1L else 0L
        }
    }
}
