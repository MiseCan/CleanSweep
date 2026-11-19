package com.cleansweep.domain.model

import com.cleansweep.data.model.MediaItem
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
internal data class PhotoAlbum(val id: String, val name: String)

@Serializable
internal data class SortScope(
    val album: PhotoAlbum? = null,
    val fromDay: Long? = null,
    val throughDay: Long? = null,
    val period: PhotoDatePeriod = if (fromDay != null && throughDay != null && fromDay <= throughDay) {
        PhotoDatePeriod.Custom
    } else {
        PhotoDatePeriod.Recent30
    }
) {
    val dateScope: SimilarScanScope
        get() = SimilarScanScope(period, fromDay, throughDay)

    fun select(
        photos: List<MediaItem>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<MediaItem> {
        val albumPhotos = if (album == null) photos else photos.filter { it.album?.id == album.id }
        return dateScope.select(albumPhotos, zone, today) { it.dateTaken ?: it.dateAddedMillis }
    }
}
