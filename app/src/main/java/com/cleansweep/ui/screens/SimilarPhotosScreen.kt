package com.cleansweep.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.ui.PhotoUiState
import com.cleansweep.ui.PhotoViewModel
import com.cleansweep.ui.ScanProgress

internal val PhotoSelectionToolbarBottomGap = 12.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SimilarPhotosScreen(
    state: PhotoUiState,
    model: PhotoViewModel,
    onPreview: (MediaItem) -> Unit,
    active: Boolean
) {
    LaunchedEffect(model, active, state.settings.reviewModel, state.settings.similarScanScope) {
        if (active) model.scanIfStale()
    }
    val groups = state.groups
    val progress = state.scanProgress
    when {
        progress != null -> AnalysisProgressPane(progress, onCancel = model::cancelScan)
        state.scanPaused && groups.isNullOrEmpty() -> StatusPanel(
            icon = R.drawable.symbol_image_search,
            title = R.string.scan_paused,
            description = R.string.scan_pause_hint,
            action = R.string.resume_scan,
            onAction = model::scan
        )
        groups.isNullOrEmpty() -> StatusPanel(
            icon = R.drawable.symbol_image_search,
            title = if (groups == null) R.string.search_title else R.string.no_similar,
            description = if (state.unreadable > 0) R.string.some_unreadable else null
        )
        else -> {
            val grid = rememberLazyGridState()
            val photoIds = remember(groups) { groups.flatMap { group -> group.items.map { it.id } } }
            AnalysisResultsGrid(
                state = grid,
                bottomPadding = if (state.selectionMode) {
                    FloatingToolbarDefaults.ContainerSize + PhotoSelectionToolbarBottomGap + 8.dp
                } else 8.dp,
                modifier = Modifier.dragSelectPhotos(
                    grid = grid,
                    photoIds = photoIds,
                    selected = state.selected,
                    selectionMode = state.selectionMode,
                    enabled = !state.removing,
                    onSelect = model::setResultSelection
                )
            ) {
                if (state.unreadable > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.scan_unreadable, state.unreadable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                similarGroupItems(
                    groups = groups,
                    onPreview = onPreview,
                    selecting = state.selectionMode,
                    selected = state.selected,
                    enabled = !state.removing,
                    onToggle = model::toggle,
                    onToggleGroup = model::toggleResultGroup
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AnalysisProgressPane(
    progress: ScanProgress,
    onCancel: () -> Unit,
    simulation: Boolean = false,
    title: String? = null
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LoadingIndicator()
            Text(title ?: stringResource(R.string.scanning), style = MaterialTheme.typography.headlineSmall)
            val fraction = (progress.processed.toFloat() / progress.total.coerceAtLeast(1)).coerceIn(0f, 1f)
            if (progress.total > 0 && fraction < 1f) {
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(if (simulation) stringResource(R.string.sim_progress, progress.processed)
                    else stringResource(R.string.scan_progress, progress.processed, progress.total))
            } else if (progress.total > 0) {
                Text(stringResource(R.string.comparing))
            }
            if (!simulation) {
                if (progress.firstScan) {
                    Text(stringResource(R.string.scan_first_hint), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                }
                val remaining = progress.remainingMillis
                if (remaining != null || progress.total == 0 || progress.processed < progress.total) {
                    Text(
                        when {
                            remaining == null -> stringResource(R.string.scan_estimating)
                            remaining >= 60_000 -> stringResource(R.string.scan_eta_minutes, (remaining + 59_999) / 60_000)
                            else -> stringResource(R.string.scan_eta_seconds, (remaining + 999) / 1_000)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Text(stringResource(R.string.scan_pause_hint), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(if (simulation) R.string.cancel else R.string.pause_scan))
            }
        }
    }
}

@Composable
internal fun AnalysisResultsGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    bottomPadding: Dp = 8.dp,
    content: LazyGridScope.() -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize().padding(horizontal = 12.dp).then(modifier),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
            content = content
        )
    }
}

internal fun LazyGridScope.similarGroupItems(
    groups: List<SimilarGroup>,
    onPreview: (MediaItem) -> Unit,
    keyPrefix: String = "",
    selecting: Boolean = false,
    selected: Set<String> = emptySet(),
    enabled: Boolean = true,
    onToggle: ((String) -> Unit)? = null,
    onToggleGroup: ((String) -> Unit)? = null
) {
    groups.forEachIndexed { index, group ->
        item(key = "${keyPrefix}heading:${group.id}", contentType = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (group.suggested) R.string.suggested_group_heading else R.string.group_heading,
                    index + 1, group.items.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                if (onToggleGroup != null) {
                    val count = group.items.count { it.id in selected }
                    val check = when (count) {
                        0 -> ToggleableState.Off
                        group.items.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    val description = stringResource(R.string.select_group, index + 1)
                    TriStateCheckbox(
                        state = check,
                        onClick = { onToggleGroup(group.id) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = description }
                    )
                }
            }
        }
        items(group.items, key = { keyPrefix + it.id }, contentType = { "photo" }) { photo ->
            ResultPhoto(
                photo = photo,
                selecting = selecting,
                selected = photo.id in selected,
                enabled = enabled,
                onPreview = { onPreview(photo) },
                onToggle = onToggle?.let { { it(photo.id) } }
            )
        }
    }
}

@Composable
private fun ResultPhoto(
    photo: MediaItem,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onPreview: () -> Unit,
    onToggle: (() -> Unit)?
) {
    val selectLabel = stringResource(if (selected) R.string.clear_selection else R.string.select_photo)
    val clickLabel = if (selecting) selectLabel else stringResource(R.string.preview_photo)
    Card {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .semantics {
                    if (selecting) this.selected = selected
                    if (enabled && onToggle != null) onLongClick(selectLabel) { onToggle(); true }
                }
                .clickable(
                    enabled = enabled,
                    role = if (selecting) Role.Checkbox else Role.Button,
                    onClickLabel = clickLabel,
                    onClick = if (selecting && onToggle != null) onToggle else onPreview
                )
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.symbol_photo)
            )
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PhotoSelectionToolbar(
    count: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier.height(FloatingToolbarDefaults.ContainerSize)
    ) {
        Text(
            stringResource(R.string.selected_photos, count),
            modifier = Modifier.weight(1f, fill = false).align(Alignment.CenterVertically)
                .padding(start = 12.dp, end = 8.dp).widthIn(max = 128.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onToggleAll, enabled = enabled) {
            Icon(
                if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)
            )
        }
        val action = if (Build.VERSION.SDK_INT >= 30) R.string.trash_count else R.string.delete_count
        IconButton(onClick = onRemove, enabled = enabled && count > 0) {
            Icon(Icons.Filled.DeleteOutline, stringResource(action, count))
        }
        IconButton(onClick = onClear, enabled = enabled) {
            Icon(Icons.Filled.Check, stringResource(R.string.finish_selection))
        }
    }
}
