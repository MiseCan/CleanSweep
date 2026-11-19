package com.cleansweep.domain.model

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
internal enum class PhotoDatePeriod { Recent30, Recent90, LastYear, All, Custom }

@Serializable
internal data class SimilarScanScope(
    val period: PhotoDatePeriod = PhotoDatePeriod.Recent30,
    val fromDay: Long? = null,
    val throughDay: Long? = null
) {
    val customDays: LongRange?
        get() = if (fromDay != null && throughDay != null && fromDay <= throughDay) fromDay..throughDay else null

    fun <T> select(
        photos: List<T>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        captureTime: (T) -> Long
    ): List<T> {
        val (start, end) = when (period) {
            PhotoDatePeriod.Recent30 -> today.minusDays(29) to today
            PhotoDatePeriod.Recent90 -> today.minusDays(89) to today
            PhotoDatePeriod.LastYear -> today.minusYears(1).plusDays(1) to today
            PhotoDatePeriod.All -> return photos
            PhotoDatePeriod.Custom -> customDays?.let {
                LocalDate.ofEpochDay(it.first) to LocalDate.ofEpochDay(it.last)
            } ?: return emptyList()
        }
        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return photos.filter { captureTime(it) in startMillis until endMillis }
    }
}
