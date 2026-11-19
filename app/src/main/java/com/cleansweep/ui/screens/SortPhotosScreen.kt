package com.cleansweep.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.CardCrop
import com.cleansweep.ui.PhotoUiState
import com.cleansweep.ui.PhotoViewModel
import com.cleansweep.ui.Review
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val SwipeActionButtonSize = 64.dp
private const val MaxBatchPreviewBytes = 32L * 1024 * 1024
private const val MaxBatchPreviewSide = 720
private const val BatchPreviewWorkers = 2

@Composable
internal fun SortPhotosScreen(
    state: PhotoUiState,
    model: PhotoViewModel,
    onPreview: (MediaItem) -> Unit,
    showBatchDialog: Boolean,
    onChooseDates: () -> Unit
) {
    val likes = remember(state.likedAt, state.settings.likeDays, state.nowMillis) { state.activeLikes }
    val reviews = remember(state.sortPhotos, state.reviews, likes) { state.sortReviews }
    val markedForRemoval = remember(reviews) { reviews.filter { it.remove }.mapTo(hashSetOf()) { it.id } }
    val filmstripPhotos = remember(state.sortPhotos, state.sortBatchIds, likes, markedForRemoval) {
        val (currentBatch, existingMarks) = state.sortPhotos
            .filter { it.id !in likes && (it.id in state.sortBatchIds || it.id in markedForRemoval) }
            .partition { it.id in state.sortBatchIds }
        currentBatch + existingMarks
    }
    val batchPhotos = remember(state.sortPhotos, state.sortBatchIds) {
        state.sortPhotos.filter { it.id in state.sortBatchIds }
    }
    val positions = remember(filmstripPhotos) { filmstripPhotos.withIndex().associate { it.value.id to it.index } }
    var selectedId by rememberSaveable(state.settings.sortScope) { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    val selectedIndex = positions[selectedId]
        ?: filmstripPhotos.indexOfFirst { it.id !in markedForRemoval }.coerceAtLeast(0)
    val upcoming = remember(filmstripPhotos, selectedIndex, markedForRemoval) {
        val current = filmstripPhotos.getOrNull(selectedIndex)
        val after = filmstripPhotos.asSequence().drop(selectedIndex + 1)
        val before = filmstripPhotos.subList(0, selectedIndex).asReversed().asSequence()
        val neighbors = after + before
        val next = (neighbors.filterNot { it.id in markedForRemoval } +
            neighbors.filter { it.id in markedForRemoval }).take(3).toList()
        listOfNotNull(current) + next
    }
    val allHiddenByLikes = remember(state.sortPhotos, likes) { state.sortPhotos.all { it.id in likes } }
    val undoTarget = remember(state.sortPhotos, reviews) {
        reviews.lastOrNull()?.let { review ->
            state.sortPhotos.firstOrNull { it.id == review.id }?.let { UndoTarget(it, review) }
        }
    }

    val batch = remember(state.batchReviews) { state.batchSummary }
    val batchSize = state.sortBatchIds.size
    if (showBatchDialog && state.sortBatchComplete) {
        val removed = batch.count { it.remove }
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.sort_batch_complete, batch.size)) },
            text = { Text(stringResource(R.string.sort_batch_counts, batch.size - removed, removed)) },
            confirmButton = {
                TextButton(onClick = { selectedId = null; model.continueSortBatch() }) {
                    Text(stringResource(R.string.sort_batch_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onChooseDates) { Text(stringResource(R.string.scope_custom)) }
            }
        )
    }

    Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
        LinearProgressIndicator(
            progress = { if (batchSize == 0) 1f else batch.size.toFloat() / batchSize },
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp)
        )
        SwipePhotoDeck(
            photos = upcoming,
            batchPhotos = batchPhotos,
            markedForRemoval = markedForRemoval,
            cardCrop = state.settings.cardCrop,
            undoTarget = undoTarget,
            enabled = !state.removing,
            browsing = browsing,
            onUndo = { review ->
                model.undo(review).also { if (it) selectedId = review.id }
            },
            onDecision = { id, remove ->
                model.review(id, remove).also { accepted ->
                    if (accepted) {
                        selectedId = upcoming.getOrNull(1)?.id ?: id.takeIf { remove }
                    }
                }
            },
            onPreview = onPreview,
            filmstrip = { enabled, previews ->
                if (filmstripPhotos.isNotEmpty()) {
                    PhotoFilmstrip(
                        photos = filmstripPhotos,
                        previews = previews,
                        markedForRemoval = markedForRemoval,
                        selectedIndex = selectedIndex,
                        enabled = enabled,
                        onSelect = { selectedId = it },
                        onBrowsing = { browsing = it }
                    )
                }
            },
            modifier = Modifier.weight(1f)
        ) { undoing ->
            StatusPanel(
                icon = R.drawable.symbol_check_circle,
                title = if (allHiddenByLikes) R.string.likes_hidden_all else R.string.sort_complete,
                action = R.string.review_again.takeIf { !undoing && reviews.isNotEmpty() && reviews.none { it.remove } && state.sortPhotos.any { it.id !in likes } },
                onAction = model::reviewAgain
            )
        }
    }
}

private data class UndoTarget(val photo: MediaItem, val review: Review)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwipePhotoDeck(
    photos: List<MediaItem>,
    batchPhotos: List<MediaItem>,
    markedForRemoval: Set<String>,
    cardCrop: CardCrop,
    undoTarget: UndoTarget?,
    enabled: Boolean,
    browsing: Boolean,
    onUndo: (Review) -> Boolean,
    onDecision: (String, Boolean) -> Boolean,
    onPreview: (MediaItem) -> Unit,
    filmstrip: @Composable (enabled: Boolean, previews: Map<String, Bitmap>) -> Unit,
    modifier: Modifier = Modifier,
    completed: @Composable (undoing: Boolean) -> Unit
) {
    val photoScale = remember(cardCrop) { ReviewPhotoScale(cardCrop) }
    val current = photos.firstOrNull()
    val previous = undoTarget?.photo
    val currentMarked = current?.id in markedForRemoval
    var offset by remember(current?.id, currentMarked) { mutableFloatStateOf(0f) }
    var settling by remember(current?.id, currentMarked) { mutableStateOf(false) }
    var rebound by remember(current?.id) { mutableStateOf<Job?>(null) }
    var undoOffset by remember(current?.id, undoTarget?.review) { mutableFloatStateOf(0f) }
    var undoing by remember(current?.id, undoTarget?.review) { mutableStateOf(false) }
    val drag = rememberDraggableState { offset += it }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val returnMotion = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val batchPreviews = remember(batchPhotos) { mutableStateMapOf<String, Bitmap>() }

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val width = constraints.maxWidth.coerceAtLeast(1)
            val height = constraints.maxHeight.coerceAtLeast(1)
            val threshold = width * 0.23f
            val exitDistance = width * 1.2f + height * 0.08f
            val actionSpacing = ((maxWidth - SwipeActionButtonSize * 3) / 2).coerceIn(0.dp, 40.dp)
            val flickDistance = with(density) { 28.dp.toPx() }
            val flickVelocity = with(density) { 850.dp.toPx() }
            val requests = remember(context, photos, previous, width, height) {
                (photos + listOfNotNull(previous)).associate { photo ->
                    photo.id to ImageRequest.Builder(context)
                        .data(photo.uri)
                        .size(width, height)
                        .scale(Scale.FIT)
                        .memoryCacheKey("swipe:${photo.id}:$width:$height")
                        .placeholderMemoryCacheKey(filmstripThumbnailKey(photo))
                        .build()
                }
            }

            LaunchedEffect(imageLoader, batchPhotos, width, height) {
                if (batchPhotos.isEmpty()) return@LaunchedEffect

                // Retain a bounded set of display previews while keeping full-size requests near the cursor.
                val budget = minOf(MaxBatchPreviewBytes, Runtime.getRuntime().maxMemory() / 8)
                val pixelsPerPhoto = budget.toDouble() / (batchPhotos.size * 4)
                val scale = minOf(1.0, MaxBatchPreviewSide.toDouble() / maxOf(width, height),
                    sqrt(pixelsPerPhoto / (width.toDouble() * height)))
                val previewWidth = (width * scale).toInt().coerceAtLeast(1)
                val previewHeight = (height * scale).toInt().coerceAtLeast(1)
                val start = batchPhotos.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
                val ordered = batchPhotos.drop(start) + batchPhotos.take(start)
                coroutineScope {
                    repeat(BatchPreviewWorkers) { worker ->
                        launch {
                            for (index in worker until ordered.size step BatchPreviewWorkers) {
                                val photo = ordered[index]
                                val request = ImageRequest.Builder(context)
                                    .data(photo.uri)
                                    .size(previewWidth, previewHeight)
                                    .scale(Scale.FIT)
                                    .precision(Precision.EXACT)
                                    .allowHardware(false)
                                    .memoryCacheKey("sort-preview:${photo.id}:${photo.revision}:$previewWidth:$previewHeight")
                                    .build()
                                val result = imageLoader.execute(request) as? SuccessResult
                                (result?.drawable as? BitmapDrawable)?.bitmap?.let { batchPreviews[photo.id] = it }
                            }
                        }
                    }
                }
            }

            fun reboundToCenter() {
                rebound?.cancel()
                if (offset == 0f) return
                rebound = scope.launch {
                    AnimationState(offset).animateTo(0f, returnMotion) { offset = value }
                    offset = 0f
                }
            }

            suspend fun settle(remove: Boolean, velocity: Float = 0f) {
                if (current == null || settling || undoing || browsing || !enabled) return
                if (remove && currentMarked) {
                    reboundToCenter()
                    return
                }
                rebound?.cancel()
                settling = true
                var accepted = false
                try {
                    val direction = if (remove) -1 else 1
                    val destination = direction * exitDistance
                    val duration = if (velocity * direction > 0) {
                        (abs(destination - offset) / abs(velocity) * 1000f).roundToInt().coerceIn(140, 240)
                    } else 210
                    AnimationState(offset).animateTo(destination, tween(duration, easing = LinearOutSlowInEasing)) {
                        offset = value
                    }
                    accepted = onDecision(current.id, remove)
                    if (!accepted) {
                        AnimationState(offset).animateTo(0f, returnMotion, block = { offset = value })
                    }
                } finally {
                    if (!accepted) {
                        offset = 0f
                        settling = false
                    }
                }
            }

            suspend fun restorePrevious() {
                if (undoTarget == null || !enabled || settling || undoing || browsing) return
                rebound?.cancel()
                offset = 0f
                val start = if (undoTarget.review.remove) -exitDistance else exitDistance
                undoOffset = start
                undoing = true
                var accepted = false
                try {
                    AnimationState(start).animateTo(0f, returnMotion, block = { undoOffset = value })
                    accepted = onUndo(undoTarget.review)
                } finally {
                    if (!accepted) undoing = false
                }
            }

            // Keep two photos composed so the next one retains its decoded image when promoted.
            val visible = photos.take(2)
            for (photo in photos.drop(2)) key(photo.id) {
                val request = requests.getValue(photo.id)
                LaunchedEffect(imageLoader, request) { imageLoader.execute(request) }
            }

            Box(
                Modifier.fillMaxSize().draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    enabled = current != null && enabled && !settling && !undoing && !browsing,
                    onDragStarted = { rebound?.cancel() },
                    onDragStopped = { velocity ->
                        if (enabled && !settling && !undoing && !browsing) {
                            val flick = abs(offset) >= flickDistance && abs(velocity) >= flickVelocity && offset * velocity > 0
                            if (abs(offset) >= threshold || flick) {
                                val remove = offset < 0
                                scope.launch { settle(remove, velocity) }
                            } else {
                                reboundToCenter()
                            }
                        }
                    }
                )
            ) {
                if (current == null) completed(undoing)
                for (photo in (visible + listOfNotNull(previous)).distinctBy { it.id }) key(photo.id) {
                    val isCurrent = photo.id == current?.id
                    val isPrevious = photo.id == previous?.id
                    val isReturning = isPrevious && undoing
                    Surface(
                        modifier = Modifier.fillMaxSize()
                            .zIndex(if (isReturning) 2f else if (isCurrent) 1f else 0f)
                            .graphicsLayer {
                                when {
                                    isCurrent || isReturning -> {
                                        alpha = 1f
                                        translationX = if (isReturning) undoOffset else offset
                                        rotationZ = (translationX / width * 9f).coerceIn(-10f, 10f)
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                    isPrevious && photo !in visible -> alpha = 0f
                                    else -> {
                                        val reveal = (abs(offset) / threshold).coerceIn(0f, 1f)
                                        alpha = reveal
                                        translationX = 0f
                                        rotationZ = 0f
                                        scaleX = 0.96f + 0.04f * reveal
                                        scaleY = scaleX
                                    }
                                }
                            },
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        val previewClick = if (isCurrent) {
                            Modifier.clickable(
                                enabled = enabled && !settling && !undoing && !browsing,
                                onClickLabel = stringResource(R.string.preview_photo),
                                role = Role.Button,
                                onClick = { onPreview(photo) }
                            )
                        } else Modifier
                        val request = requests.getValue(photo.id)
                        val removalFilter = PendingRemovalFilter.takeIf { photo.id in markedForRemoval && !isReturning }
                        val preview = batchPreviews[photo.id]
                        val previewPainter = remember(preview) { preview?.let { BitmapPainter(it.asImageBitmap()) } }
                        var loadedSize by remember(request) { mutableStateOf(Size.Zero) }
                        val imageSize = if (loadedSize != Size.Zero) loadedSize else previewPainter?.intrinsicSize ?: Size.Zero
                        val showBackdrop = imageSize.width > 0 && imageSize.height > 0 &&
                            !cardCrop.shouldCrop(imageSize.width, imageSize.height, width.toFloat(), height.toFloat()) &&
                            imageSize.width.toDouble() * height != imageSize.height.toDouble() * width
                        Box(Modifier.fillMaxSize()) {
                            if (showBackdrop) {
                                val backdrop = remember(photo.uri, context) {
                                    ImageRequest.Builder(context).data(photo.uri)
                                        .size(12, 12).scale(Scale.FIT)
                                        .memoryCacheKey("swipe-backdrop:${photo.id}")
                                        .build()
                                }
                                AsyncImage(
                                    model = backdrop,
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize().blur(24.dp),
                                    contentScale = ContentScale.Crop,
                                    colorFilter = removalFilter
                                )
                            }
                            AsyncImage(
                                model = request,
                                imageLoader = imageLoader,
                                contentDescription = photo.displayName.takeIf { isReturning || (isCurrent && !undoing) },
                                modifier = Modifier.fillMaxSize().then(previewClick),
                                contentScale = photoScale,
                                placeholder = previewPainter,
                                colorFilter = removalFilter,
                                onSuccess = { result ->
                                    val image = result.result.drawable
                                    loadedSize = Size(image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
                                },
                                error = painterResource(R.drawable.symbol_photo)
                            )
                        }
                    }
                }
            }
            if (current != null || undoTarget != null) Row(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (current != null) 42.dp else 20.dp)
                    .graphicsLayer {
                        val displacement = if (undoing) undoOffset else offset
                        alpha = (1f - abs(displacement) / threshold).coerceIn(0f, 1f)
                    },
                horizontalArrangement = Arrangement.spacedBy(actionSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val idle = enabled && !settling && !undoing && !browsing
                if (current != null) SwipeActionButton(
                    icon = Icons.Rounded.Close,
                    label = R.string.mark_delete,
                    tint = Color(0xFF078457),
                    enabled = idle && !currentMarked,
                    onClick = { scope.launch { settle(true) } }
                )
                SwipeActionButton(
                    icon = Icons.Rounded.Replay,
                    label = R.string.undo,
                    tint = Color(0xFF60646B),
                    enabled = idle && undoTarget != null,
                    onClick = { scope.launch { restorePrevious() } }
                )
                if (current != null) SwipeActionButton(
                    icon = Icons.Rounded.Favorite,
                    label = R.string.like_photo,
                    tint = Color(0xFFE43D43),
                    enabled = idle,
                    onClick = { scope.launch { settle(false) } }
                )
            }
        }
        filmstrip(enabled && !settling && !undoing && abs(offset) < 0.5f, batchPreviews)
    }
}

@Composable
private fun SwipeActionButton(
    icon: ImageVector,
    @StringRes label: Int,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(SwipeActionButtonSize),
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White,
            contentColor = tint,
            disabledContainerColor = Color.White.copy(alpha = 0.80f),
            disabledContentColor = Color(0xFF96989B)
        )
    ) {
        Icon(icon, stringResource(label), modifier = Modifier.size(28.dp))
    }
}

private class ReviewPhotoScale(private val crop: CardCrop) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val scale = if (crop.shouldCrop(srcSize.width, srcSize.height, dstSize.width, dstSize.height)) {
            ContentScale.Crop
        } else {
            ContentScale.Fit
        }
        return scale.computeScaleFactor(srcSize, dstSize)
    }
}
