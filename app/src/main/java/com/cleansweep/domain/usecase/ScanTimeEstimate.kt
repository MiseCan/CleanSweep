package com.cleansweep.domain.usecase

internal class ScanTimeEstimate {
    private var samples = 0
    private var elapsedMillis = 0L

    fun recordPhoto(millis: Long) {
        samples++
        elapsedMillis += millis
    }

    // Ignore checkpoints and skipped time windows when learning the processing speed.
    fun remainingMillis(photos: Int): Long? =
        if (elapsedMillis == 0L || (samples < 2 && elapsedMillis < 250L) || photos <= 0) null
        else (elapsedMillis.toDouble() * photos / samples).toLong().coerceAtLeast(1)
}
