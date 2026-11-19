package com.cleansweep.domain.util

import com.cleansweep.domain.model.ScanParameter

/** The first capture anchors an inclusive window. Untimed items are excluded. */
internal fun <T> captureWindows(
    items: List<T>,
    windowMillis: Long = ScanParameter.CaptureSeconds.default * 1_000L,
    time: (T) -> Long?
): List<List<T>> {
    val timed = items.mapNotNull { item ->
        time(item)?.takeIf { it > 0 }?.let { it to item }
    }.sortedBy { it.first }

    val windows = mutableListOf<List<T>>()
    var start = 0
    while (start < timed.size) {
        var end = start + 1
        while (end < timed.size && timed[end].first - timed[start].first <= windowMillis) {
            end++
        }
        if (end - start > 1) windows.add(timed.subList(start, end).map { it.second })
        start = end
    }
    return windows
}
