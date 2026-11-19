package com.cleansweep.ui

import android.net.Uri
import com.cleansweep.data.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortBatchTest {
    private val photos = List(108) { index ->
        MediaItem(Uri.parse("content://media/external/images/media/$index"), "$index.jpg")
    }

    @Test
    fun firstBatchSkipsHiddenLikesAndDoesNotRefillAsPhotosAreMarked() {
        val hidden = photos.first()
        val state = PhotoUiState(sortPhotos = photos, likedAt = mapOf(hidden.id to 10_000L), nowMillis = 10_000L)
            .withInitialSortBatch()
        assertEquals(photos.subList(1, 51).map { it.id }, state.sortBatchIds.toList())

        val mark = Review(photos[1].id, remove = true)
        val refresh = state.copy(reviews = listOf(mark), batchReviews = listOf(mark)).withInitialSortBatch()
        assertFalse(refresh.sortBatchComplete)
        assertEquals(state.sortBatchIds, refresh.sortBatchIds)
        assertFalse(photos[51].id in refresh.sortBatchIds)
    }

    @Test
    fun nextBatchStartsAfterMarkedPhotosAndStillKeepsTheirPendingDeletion() {
        val state = PhotoUiState(sortPhotos = photos, nowMillis = 10_000L).withInitialSortBatch()
        val marked = photos.take(50).map { Review(it.id, remove = true) }
        val completed = state.copy(reviews = marked, batchReviews = marked)
        assertTrue(completed.sortBatchComplete)
        val second = completed.withNextSortBatch()
        assertEquals(photos.subList(50, 100).map { it.id }, second.sortBatchIds.toList())
        assertEquals(marked, second.sortReviews)
        assertTrue(second.batchSummary.isEmpty())
        assertFalse(second.sortBatchComplete)
    }

    @Test
    fun deletedItemsDoNotBlockTheNextBatch() {
        val state = PhotoUiState(sortPhotos = photos).withInitialSortBatch()
        val mark = Review(photos.first().id, remove = true)
        val refreshed = state.copy(
            sortPhotos = photos.drop(50),
            batchReviews = listOf(mark)
        ).withInitialSortBatch()
        assertTrue(refreshed.sortBatchComplete)
        assertEquals(photos.subList(50, 100).map { it.id }, refreshed.withNextSortBatch().sortBatchIds.toList())
    }
}
