package com.cleansweep.domain.model

import com.cleansweep.data.model.MediaItem

internal data class SimilarGroup(val id: String, val items: List<MediaItem>, val suggested: Boolean = false)

internal data class SimilarScanResult(
    val groups: List<SimilarGroup>,
    val skippedFilePaths: List<String>
)
