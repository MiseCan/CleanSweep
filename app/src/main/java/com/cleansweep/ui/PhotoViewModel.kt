package com.cleansweep.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.AppSettings
import com.cleansweep.data.repository.CachedSimilarScan
import com.cleansweep.data.repository.PhotoAccess
import com.cleansweep.data.repository.PhotoRepository
import com.cleansweep.data.repository.LikeDaysRange
import com.cleansweep.data.repository.SettingsRepository
import com.cleansweep.data.repository.SimilarScanCache
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.model.CardCrop
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.PhotoDatePeriod
import com.cleansweep.domain.model.SimilarScanScope
import com.cleansweep.domain.model.SimilaritySimulation
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.model.SimulationTimings
import com.cleansweep.domain.model.ModelTiming
import com.cleansweep.domain.usecase.SimulationModelEvaluator
import com.cleansweep.domain.model.SortScope
import com.cleansweep.domain.usecase.SimilarPhotoFinder
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SortBatchSize = 50

internal data class Review(val id: String, val remove: Boolean, val previousLike: Long? = null)

internal data class PhotoUiState(
    val access: PhotoAccess = PhotoAccess.None,
    val loading: Boolean = false,
    val photos: List<MediaItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val sortPhotos: List<MediaItem> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val batchReviews: List<Review> = emptyList(),
    val sortBatchIds: Set<String> = emptySet(),
    val likedAt: Map<String, Long> = emptyMap(),
    val nowMillis: Long = System.currentTimeMillis(),
    val groups: List<SimilarGroup>? = null,
    val scanProgress: ScanProgress? = null,
    val scanPaused: Boolean = false,
    val simulation: SimulationUi = SimulationUi.Idle,
    val unreadable: Int = 0,
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val removing: Boolean = false,
    val permissionRequest: IntentSenderRequest? = null,
    val message: Int? = null
) {
    val activeLikes get() = likedAt.filterValues { nowMillis - it < TimeUnit.DAYS.toMillis(settings.likeDays.toLong()) }

    val batchSummary: List<Review> get() = batchReviews.asReversed().distinctBy { it.id }

    val sortReviews: List<Review>
        get() {
            val ids = sortPhotos.mapTo(hashSetOf()) { it.id }
            val likes = activeLikes
            return reviews.asReversed().distinctBy { it.id }.asReversed()
                .filter { it.id in ids && (it.remove || it.id in likes) }
        }

    val sortBatchComplete: Boolean
        get() {
            if (sortBatchIds.isEmpty()) return false
            val summary = batchSummary
            if (summary.size == SortBatchSize) return true
            if (summary.isEmpty()) return false

            val eligible = eligibleSortPhotos()
            return eligible.none { it.id in sortBatchIds } && eligible.any { it.id !in sortBatchIds }
        }

    fun withNextSortBatch(): PhotoUiState = copy(
        sortBatchIds = eligibleSortPhotos().take(SortBatchSize).map { it.id }.toCollection(linkedSetOf()),
        batchReviews = emptyList()
    )

    fun withInitialSortBatch(): PhotoUiState =
        if (sortBatchIds.isEmpty() || (batchReviews.isEmpty() && sortPhotos.none { it.id in sortBatchIds })) {
            withNextSortBatch()
        } else this

    private fun eligibleSortPhotos(): Sequence<MediaItem> {
        val likes = activeLikes
        val marked = sortReviews.filter { it.remove }.mapTo(hashSetOf()) { it.id }
        return sortPhotos.asSequence().filter { it.id !in likes && it.id !in marked }
    }

}

internal data class ScanProgress(
    val processed: Int,
    val total: Int,
    val firstScan: Boolean = false,
    val remainingMillis: Long? = null
)

internal sealed interface SimulationUi {
    data object Idle : SimulationUi
    data class Running(val progress: ScanProgress, val model: SimulationModel? = null) : SimulationUi
    data class Complete(val report: SimilaritySimulation) : SimulationUi
    data object Failed : SimulationUi
}

internal class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = PhotoRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val finder = SimilarPhotoFinder(app)
    private val scanCache = SimilarScanCache(app)
    private val mutableState = MutableStateFlow(PhotoUiState(
        settings = settingsRepository.read(), likedAt = settingsRepository.readLikes()
    ))
    val state = mutableState.asStateFlow()
    val permissions get() = repository.permissions

    private var galleryJob: Job? = null
    private var likeExpiryJob: Job? = null
    private var scanJob: Job? = null
    private var simulationJob: Job? = null
    private var cacheLoaded = false
    private var lastSuccessfulScan: CachedSimilarScan? = null
    private val removalQueue = ArrayDeque<List<Uri>>()
    private var removalFromSelection = false

    fun refresh() {
        scheduleLikeExpiry()
        val access = repository.access()
        galleryJob?.cancel()
        if (access != mutableState.value.access) clearSimulation()
        if (access == PhotoAccess.None) {
            scanJob?.cancel()
            mutableState.update { it.copy(access = access, loading = false, photos = emptyList(), sortPhotos = emptyList(),
                groups = null, reviews = emptyList(), batchReviews = emptyList(), sortBatchIds = emptySet(), selectionMode = false,
                selected = emptySet(), scanProgress = null, scanPaused = false, unreadable = 0) }
            return
        }

        galleryJob = viewModelScope.launch {
            mutableState.update { it.copy(access = access, loading = it.photos.isEmpty() || it.access != access) }
            try {
                if (!cacheLoaded) {
                    lastSuccessfulScan = scanCache.read()
                    cacheLoaded = true
                }
                reconcile(repository.readPhotos())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ErrorJournal.warn("Could not refresh gallery", error)
                mutableState.update { it.copy(loading = false, message = R.string.gallery_error) }
            }
        }
    }

    private fun reconcile(photos: List<MediaItem>) {
        val ids = photos.mapTo(hashSetOf()) { it.id }
        val report = (mutableState.value.simulation as? SimulationUi.Complete)?.report
        if (report?.windows?.any { window -> window.photos.any { it.id !in ids } } == true) {
            clearSimulation()
        }
        val cached = cachedScan(mutableState.value.settings)
        val groups = cached?.resolveGroups(photos)
        val inResults = groups.orEmpty().flatMap { group -> group.items.map { it.id } }.toSet()
        mutableState.update { old ->
            old.copy(
                photos = photos, sortPhotos = old.settings.effectiveSortScope.select(photos),
                loading = false, reviews = old.reviews.filter { it.id in ids },
                groups = groups, unreadable = cached?.unreadable ?: 0,
                selectionMode = old.selectionMode && inResults.isNotEmpty(),
                selected = old.selected.intersect(inResults)
            ).withInitialSortBatch()
        }
    }

    fun setSortScope(scope: SortScope) {
        if (scope.fromDay != null && scope.throughDay != null && scope.fromDay > scope.throughDay) return
        val current = mutableState.value
        val settings = current.settings.copy(sortScope = scope)
        settingsRepository.write(settings)
        mutableState.update { old ->
            val next = old.copy(settings = settings, sortPhotos = scope.select(old.photos))
            if (old.settings.effectiveSortScope != scope || old.sortBatchComplete) next.withNextSortBatch()
            else next.withInitialSortBatch()
        }
    }

    fun continueSortBatch() {
        mutableState.update { old ->
            if (old.sortBatchComplete) old.withNextSortBatch() else old
        }
    }

    private fun cachedScan(settings: AppSettings): CachedSimilarScan? = lastSuccessfulScan?.takeIf {
        it.scope == settings.similarScanScope && it.reviewModel == settings.reviewModel
    }

    fun setReviewModel(reviewModel: SimulationModel) {
        val current = mutableState.value
        if (reviewModel == current.settings.reviewModel || scanJob?.isCompleted == false || current.removing) return
        val settings = current.settings.copy(reviewModel = reviewModel)
        settingsRepository.write(settings)
        clearSimulation()
        val cached = cachedScan(settings)
        mutableState.update {
            it.copy(settings = settings, groups = cached?.resolveGroups(it.photos), unreadable = cached?.unreadable ?: 0,
                scanPaused = false, selectionMode = false, selected = emptySet())
        }
    }

    fun setScanThresholds(thresholds: ScanThresholds) {
        val settings = mutableState.value.settings.copy(thresholds = thresholds)
        settingsRepository.write(settings)
        mutableState.update { it.copy(settings = settings) }
    }

    fun review(id: String, remove: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val decision = Review(id, remove, mutableState.value.likedAt[id])
        val updated = mutableState.updateAndGet { old ->
            val alreadyMarked = old.reviews.lastOrNull { it.id == id }?.remove == true
            if (old.removing || old.sortBatchComplete || id in old.activeLikes || (remove && alreadyMarked) ||
                (id !in old.sortBatchIds && !alreadyMarked) || old.sortPhotos.none { it.id == id }) old
            else old.copy(
                reviews = if (alreadyMarked) old.reviews + decision else old.reviews.filterNot { it.id == id } + decision,
                batchReviews = if (id in old.sortBatchIds) old.batchReviews + decision else old.batchReviews,
                likedAt = if (remove) old.likedAt else old.likedAt + (id to now),
                nowMillis = now
            )
        }
        val accepted = updated.reviews.lastOrNull() === decision
        if (accepted && !remove) {
            settingsRepository.writeLikes(updated.likedAt)
            scheduleLikeExpiry()
        }
        return accepted
    }

    fun undo(expected: Review): Boolean {
        val previous = mutableState.getAndUpdate { old ->
            if (old.removing || old.sortReviews.lastOrNull() != expected) old
            else {
                val updated = old.copy(
                    reviews = old.reviews - expected,
                    batchReviews = old.batchReviews - expected,
                    likedAt = if (expected.remove) old.likedAt else {
                        expected.previousLike?.let { old.likedAt + (expected.id to it) } ?: (old.likedAt - expected.id)
                    }
                )
                if (expected.id in updated.sortBatchIds || updated.sortReviews.any { it.id == expected.id && it.remove }) {
                    updated
                } else {
                    // Undo can restore a photo from the preceding batch. Keep this batch within its limit.
                    val reviewed = updated.batchSummary.mapTo(hashSetOf()) { it.id }
                    val available = updated.sortBatchIds
                    val replace = available.lastOrNull { it !in reviewed }
                    when {
                        available.size < SortBatchSize -> updated.copy(sortBatchIds = available + expected.id)
                        replace != null -> updated.copy(sortBatchIds = available - replace + expected.id)
                        else -> updated
                    }
                }
            }
        }
        val accepted = !previous.removing && previous.sortReviews.lastOrNull() == expected
        if (accepted && !expected.remove) {
            settingsRepository.writeLikes(mutableState.value.likedAt)
            scheduleLikeExpiry()
        }
        return accepted
    }

    fun setCardCrop(crop: CardCrop) {
        val settings = mutableState.value.settings.copy(cardCrop = crop)
        settingsRepository.write(settings)
        mutableState.update { it.copy(settings = settings) }
    }

    fun setLikeDays(days: Int) {
        if (days !in LikeDaysRange) return
        val settings = mutableState.value.settings.copy(likeDays = days)
        settingsRepository.write(settings)
        mutableState.update { it.copy(settings = settings, nowMillis = System.currentTimeMillis()).withInitialSortBatch() }
        scheduleLikeExpiry()
    }

    private fun scheduleLikeExpiry() {
        likeExpiryJob?.cancel()
        likeExpiryJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val current = mutableState.updateAndGet { it.copy(nowMillis = now).withInitialSortBatch() }
                val duration = TimeUnit.DAYS.toMillis(current.settings.likeDays.toLong())
                val nextDelay = current.activeLikes.values.minOrNull()?.let { it + duration - now } ?: break
                delay(nextDelay.coerceAtLeast(1))
            }
        }
    }

    fun reviewAgain() {
        mutableState.update { old ->
            val scoped = old.sortReviews
            val reviewedIds = scoped.mapTo(hashSetOf()) { it.id }
            if (old.removing || scoped.any { it.remove }) old
            else old.copy(reviews = old.reviews.filterNot { it.id in reviewedIds }).withNextSortBatch()
        }
    }

    fun scanIfStale() {
        val current = mutableState.value
        if (current.scanPaused) return
        if (cachedScan(current.settings)?.isFresh(System.currentTimeMillis()) != true) scan()
    }

    fun setSimilarScanScope(scope: SimilarScanScope): Boolean {
        val current = mutableState.value
        if (current.removing || scanJob?.isCompleted == false ||
            (scope.period == PhotoDatePeriod.Custom && scope.customDays == null)
        ) return false
        if (scope == current.settings.similarScanScope) return true

        val settings = current.settings.copy(similarScanScope = scope)
        settingsRepository.write(settings)
        val cached = cachedScan(settings)
        mutableState.update {
            it.copy(settings = settings, groups = cached?.resolveGroups(it.photos), unreadable = cached?.unreadable ?: 0,
                scanPaused = false, selectionMode = false, selected = emptySet())
        }
        return true
    }

    fun simulate(models: Set<SimulationModel>): Boolean {
        val snapshot = mutableState.value
        if (snapshot.access == PhotoAccess.None || snapshot.loading || snapshot.photos.isEmpty() ||
            snapshot.removing || snapshot.scanProgress != null || simulationJob?.isCompleted == false
        ) return false

        val total = minOf(snapshot.photos.size, SimilarPhotoFinder.SIMULATION_SAMPLE_LIMIT)
        mutableState.update { it.copy(simulation = SimulationUi.Running(ScanProgress(0, total))) }
        simulationJob = viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                var processed = 0
                val baseline = finder.simulate(
                    snapshot.photos, snapshot.settings.thresholds, snapshot.settings.reviewModel.takeIf { models.isEmpty() }
                ) { increment ->
                    processed += increment
                    mutableState.update {
                        if (it.simulation is SimulationUi.Running) {
                            it.copy(simulation = SimulationUi.Running(ScanProgress(processed, total)))
                        } else it
                    }
                }
                val baselineTime = SystemClock.elapsedRealtime() - started
                val modelTimes = mutableListOf<ModelTiming>()
                var windows = baseline.windows
                val evaluator = SimulationModelEvaluator(getApplication())
                for (model in SimulationModel.entries.filter { it in models }) {
                    mutableState.update { current ->
                        if (current.simulation is SimulationUi.Running) {
                            current.copy(simulation = SimulationUi.Running(ScanProgress(processed, total), model))
                        } else current
                    }
                    val (evaluated, timing) = withContext(Dispatchers.Default) { evaluator.evaluate(model, windows) }
                    windows = evaluated
                    modelTimes.add(timing)
                }
                val report = baseline.copy(windows = windows, timings = SimulationTimings(
                    SystemClock.elapsedRealtime() - started, baselineTime, modelTimes
                ))
                mutableState.update { current ->
                    val allowed = current.photos.mapTo(hashSetOf()) { it.id }
                    val available = report.windows.all { window -> window.photos.all { it.id in allowed } }
                    if (current.simulation !is SimulationUi.Running) current
                    else current.copy(simulation = if (available) SimulationUi.Complete(report) else SimulationUi.Idle)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ErrorJournal.warn("Similarity simulation failed", error)
                mutableState.update { if (it.simulation is SimulationUi.Running) it.copy(simulation = SimulationUi.Failed) else it }
            }
        }
        return true
    }

    fun clearSimulation() {
        simulationJob?.cancel()
        mutableState.update { it.copy(simulation = SimulationUi.Idle) }
    }

    fun scan() {
        val snapshot = mutableState.value
        if (!cacheLoaded || snapshot.access == PhotoAccess.None || snapshot.loading ||
            snapshot.photos.isEmpty() || snapshot.removing || scanJob?.isCompleted == false
        ) return
        scanJob = viewModelScope.launch {
            val firstScan = cachedScan(snapshot.settings) == null
            mutableState.update {
                it.copy(scanProgress = ScanProgress(0, 0, firstScan), scanPaused = false, message = null)
            }
            try {
                val scope = snapshot.settings.similarScanScope
                val result = finder.findInScope(
                    snapshot.photos, snapshot.settings.thresholds, scope, snapshot.settings.reviewModel
                ) { processed, total, remaining ->
                    mutableState.update { it.copy(scanProgress = ScanProgress(processed, total, firstScan, remaining)) }
                }
                val completed = CachedSimilarScan(
                    completedAtMillis = System.currentTimeMillis(),
                    groups = result.groups.map { group ->
                        CachedSimilarScan.Group(group.id, group.items.map { it.id }, group.suggested)
                    },
                    unreadable = result.skippedFilePaths.size,
                    scope = scope,
                    reviewModel = snapshot.settings.reviewModel
                )
                try {
                    scanCache.write(completed)
                } catch (error: IOException) {
                    ErrorJournal.warn("Could not save the scan cache", error)
                }
                lastSuccessfulScan = completed
                val groups = completed.resolveGroups(mutableState.value.photos)
                mutableState.update { it.copy(groups = groups, selectionMode = false, selected = emptySet(),
                    unreadable = completed.unreadable) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ErrorJournal.warn("Similar photo scan failed", error)
                mutableState.update { it.copy(message = R.string.scan_error) }
            } finally {
                mutableState.update { it.copy(scanProgress = null) }
            }
        }
    }

    fun cancelScan() {
        val job = scanJob?.takeIf { it.isActive } ?: return
        mutableState.update {
            it.copy(scanPaused = true, message = if (it.groups.isNullOrEmpty()) null else R.string.scan_pause_hint)
        }
        job.cancel()
    }

    fun toggle(id: String) {
        mutableState.update { old ->
            if (old.removing) old
            else old.copy(
                selectionMode = true,
                selected = if (id in old.selected) old.selected - id else old.selected + id
            )
        }
    }

    fun setResultSelection(ids: Set<String>, selected: Boolean) {
        mutableState.update { old ->
            if (old.removing || old.scanProgress != null) return@update old
            val affected = old.groups.orEmpty().asSequence().flatMap { it.items.asSequence() }
                .filter { it.id in ids }.mapTo(hashSetOf()) { it.id }
            if (affected.isEmpty()) old else old.copy(
                selectionMode = true,
                selected = if (selected) old.selected + affected else old.selected - affected
            )
        }
    }

    fun toggleResultGroup(id: String) {
        val current = mutableState.value
        val ids = current.groups?.firstOrNull { it.id == id }?.items?.mapTo(hashSetOf()) { it.id } ?: return
        setResultSelection(ids, !current.selected.containsAll(ids))
    }

    fun toggleAllResults() {
        mutableState.update { old ->
            if (old.removing || !old.selectionMode) return@update old
            val ids = old.groups.orEmpty().flatMap { group -> group.items.map { it.id } }.toSet()
            old.copy(selected = if (old.selected.containsAll(ids)) emptySet() else ids)
        }
    }

    fun clearSelection() {
        mutableState.update {
            if (it.removing) it else it.copy(selectionMode = false, selected = emptySet())
        }
    }

    fun removeSelected() = remove(mutableState.value.selected, fromSelection = true)

    fun remove(ids: Set<String>, fromSelection: Boolean = false) {
        val current = mutableState.value
        if (current.removing || current.scanProgress != null) return
        val uris = current.photos.filter { it.id in ids }.map { it.uri }
        if (uris.isEmpty()) return
        // The platform accepts at most 2,000 items in a single request.
        removalQueue.addAll(uris.chunked(if (Build.VERSION.SDK_INT >= 30) 2000 else 1))
        removalFromSelection = fromSelection
        mutableState.update { it.copy(removing = true) }
        viewModelScope.launch { continueRemoval() }
    }

    fun requestLaunched() { mutableState.update { it.copy(permissionRequest = null) } }

    fun removalAnswered(approved: Boolean) {
        if (!mutableState.value.removing) return
        viewModelScope.launch {
            if (approved && removalQueue.isNotEmpty()) {
                val approvedBatch = removalQueue.removeFirst()
                if (Build.VERSION.SDK_INT < 30) {
                    try {
                        repository.deleteAfterConsent(approvedBatch.single())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ErrorJournal.warn("Could not delete approved photo", error)
                        finishRemoval(R.string.removal_error)
                        return@launch
                    }
                }
                continueRemoval()
            } else {
                finishRemoval()
            }
        }
    }

    private suspend fun continueRemoval() {
        try {
            while (removalQueue.isNotEmpty()) {
                val request = repository.prepareRemoval(removalQueue.first())
                if (request != null) {
                    mutableState.update { it.copy(permissionRequest = request) }
                    return
                }
                removalQueue.removeFirst()
            }
            finishRemoval(completed = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ErrorJournal.warn("Photo removal failed", error)
            finishRemoval(R.string.removal_error)
        }
    }

    private fun finishRemoval(message: Int? = null, completed: Boolean = false) {
        val exitSelection = completed && removalFromSelection
        removalFromSelection = false
        removalQueue.clear()
        mutableState.update {
            it.copy(
                removing = false,
                permissionRequest = null,
                message = message,
                selectionMode = it.selectionMode && !exitSelection,
                selected = if (exitSelection) emptySet() else it.selected
            )
        }
        refresh()
    }

    fun clearMessage(message: Int) {
        mutableState.update { if (it.message == message) it.copy(message = null) else it }
    }
}
