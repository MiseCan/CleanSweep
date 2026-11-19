package com.cleansweep.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import kotlin.math.abs

internal val PendingRemovalFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private val ThumbnailSlot = 28.dp
private val FilmstripHeight = 52.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PhotoFilmstrip(
    photos: List<MediaItem>,
    previews: Map<String, Bitmap>,
    markedForRemoval: Set<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onBrowsing: (Boolean) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var browsing by remember { mutableStateOf(false) }
    val selectPhoto by rememberUpdatedState(onSelect)
    val reportBrowsing by rememberUpdatedState(onBrowsing)
    val selectedId = photos[selectedIndex].id

    LaunchedEffect(listState) {
        snapshotFlow { Triple(dragging, listState.isScrollInProgress, listState.layoutInfo.centeredPhoto()) }
            .collect { (dragged, scrolling, centered) ->
                if (!browsing && dragged) {
                    browsing = true
                    reportBrowsing(true)
                }
                if (browsing) {
                    centered?.let(selectPhoto)
                    if (!scrolling && !dragged) {
                        browsing = false
                        reportBrowsing(false)
                    }
                }
            }
    }
    LaunchedEffect(selectedId, selectedIndex, browsing) {
        if (!browsing && listState.layoutInfo.centeredPhoto() != selectedId) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (browsing) reportBrowsing(false) }
    }

    val spacing by animateFloatAsState(
        if (browsing) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "thumbnail spacing"
    )
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val gap = with(LocalDensity.current) { 10.dp.toPx() }
    val label = stringResource(R.string.photo_filmstrip)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sidePadding = ((maxWidth - ThumbnailSlot) / 2).coerceAtLeast(0.dp)
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(FilmstripHeight).semantics { contentDescription = label },
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            flingBehavior = rememberSnapFlingBehavior(listState),
            userScrollEnabled = enabled
        ) {
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                val selected = index == selectedIndex
                val direction = index.compareTo(selectedIndex) * layoutSign
                FilmstripThumbnail(
                    photo = photo,
                    preview = previews[photo.id],
                    marked = photo.id in markedForRemoval,
                    selected = selected,
                    expanded = selected && !browsing,
                    enabled = enabled,
                    modifier = Modifier.zIndex(if (selected) 1f else 0f).graphicsLayer {
                        translationX = gap * direction * spacing
                    },
                    onClick = { onSelect(photo.id) }
                )
            }
        }
    }
}

private fun LazyListLayoutInfo.centeredPhoto(): String? {
    val center = (viewportStartOffset + viewportEndOffset) / 2f
    return visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.key as? String
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilmstripThumbnail(
    photo: MediaItem,
    preview: Bitmap?,
    marked: Boolean,
    selected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        if (expanded) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "thumbnail focus"
    )
    val description = if (marked) stringResource(R.string.filmstrip_marked, photo.displayName)
        else photo.displayName
    val filter = PendingRemovalFilter.takeIf { marked }
    val imageModifier = Modifier.size(24.dp, 36.dp).graphicsLayer {
        scaleX = 1f + emphasis * 0.5f
        scaleY = 1f + emphasis * 0.08f
    }.clip(MaterialTheme.shapes.small)

    Box(
        modifier = modifier.size(ThumbnailSlot, FilmstripHeight).selectable(
            selected = selected, enabled = enabled, role = Role.Button, onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        if (preview != null) {
            Image(preview.asImageBitmap(), description, imageModifier, contentScale = ContentScale.Crop, colorFilter = filter)
        } else {
            val context = LocalContext.current
            val decodeSize = with(LocalDensity.current) { 52.dp.roundToPx() }
            val request = remember(context, photo.uri, decodeSize) {
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(decodeSize, decodeSize)
                    .scale(Scale.FIT)
                    .memoryCacheKey(filmstripThumbnailKey(photo))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = description,
                colorFilter = filter,
                modifier = imageModifier,
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.symbol_photo)
            )
        }
    }
}

internal fun filmstripThumbnailKey(photo: MediaItem): String = "review-thumbnail:${photo.id}"
