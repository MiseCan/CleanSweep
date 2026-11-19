package com.cleansweep.data.model

import android.net.Uri
import com.cleansweep.domain.model.PhotoAlbum

internal data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long? = null,
    val album: PhotoAlbum? = null,
    val dateAddedMillis: Long = 0L,
    val revision: String? = null
) {
    val id: String get() = uri.toString()
}
