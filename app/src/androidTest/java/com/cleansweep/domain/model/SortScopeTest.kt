package com.cleansweep.domain.model

import android.net.Uri
import com.cleansweep.data.model.MediaItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SortScopeTest {
    @Test
    fun albumAndDatesIntersectAndIncludeTheLastLocalDay() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val album = PhotoAlbum("primary:1", "Camera")
        val other = PhotoAlbum("primary:2", "Downloads")
        val photos = listOf(
            MediaItem(Uri.EMPTY, "too early", start - 1, album),
            MediaItem(Uri.EMPTY, "first millisecond", start, album),
            MediaItem(Uri.EMPTY, "last millisecond", nextDay - 1, album),
            MediaItem(Uri.EMPTY, "too late", nextDay, album),
            MediaItem(Uri.EMPTY, "other album", start, other),
            MediaItem(Uri.EMPTY, "missing date", null, album)
        )
        val scope = SortScope(album, day.toEpochDay(), day.toEpochDay())

        assertEquals(photos.subList(1, 3), scope.select(photos, zone))
        assertEquals(photos.take(3) + photos[4], SortScope().select(photos, zone, day))
        assertEquals(photos.take(3), SortScope(album).select(photos, zone, day))
    }
}
