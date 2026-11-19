package com.cleansweep.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import com.cleansweep.data.model.MediaItem
import java.io.File
import java.io.IOException

internal fun decodeAnalysisPhoto(context: Context, item: MediaItem): Bitmap? {
    return try {
        val source = if (item.uri.scheme == "file") {
            ImageDecoder.createSource(File(requireNotNull(item.uri.path)))
        } else {
            ImageDecoder.createSource(context.contentResolver, item.uri)
        }
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 640) decoder.setTargetSize(
                (info.size.width * 640L / longest).toInt().coerceAtLeast(1),
                (info.size.height * 640L / longest).toInt().coerceAtLeast(1)
            )
        }
    } catch (error: IOException) {
        Log.w("SimilarPhotoFinder", "Unable to decode ${item.displayName}", error)
        null
    } catch (error: RuntimeException) {
        Log.w("SimilarPhotoFinder", "Unable to decode ${item.displayName}", error)
        null
    }
}
