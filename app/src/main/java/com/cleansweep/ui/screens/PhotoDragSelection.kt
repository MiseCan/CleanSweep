package com.cleansweep.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

@Composable
internal fun Modifier.dragSelectPhotos(
    grid: LazyGridState,
    photoIds: List<String>,
    selected: Set<String>,
    selectionMode: Boolean,
    enabled: Boolean,
    onSelect: (Set<String>, Boolean) -> Unit
): Modifier {
    val positions = remember(photoIds) { photoIds.withIndex().associate { it.value to it.index } }
    val currentIds by rememberUpdatedState(photoIds)
    val currentPositions by rememberUpdatedState(positions)
    val currentSelection by rememberUpdatedState(selected)
    val currentMode by rememberUpdatedState(selectionMode)
    val applySelection by rememberUpdatedState(onSelect)
    val haptics = LocalHapticFeedback.current
    if (!enabled) return this

    return pointerInput(grid, haptics) {
        coroutineScope {
            val edge = 64.dp.toPx()
            val sampleStep = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val selectionWasActive = currentMode
                val order = currentIds
                val indices = currentPositions
                var firstMove = down.position
                val horizontal = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.singleOrNull()?.takeIf { it.id == down.id && it.pressed && !it.isConsumed }
                            ?: return@withTimeoutOrNull false
                        val delta = change.position - down.position
                        if (delta.getDistance() < viewConfiguration.touchSlop) continue
                        if (!selectionWasActive || abs(delta.y) >= abs(delta.x)) return@withTimeoutOrNull false
                        firstMove = change.position
                        change.consume()
                        return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (horizontal == false) return@awaitEachGesture
                if (horizontal == null) {
                    if (grid.photoIndexAt(down.position, indices) == null) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }

                val visited = hashSetOf<String>()
                var previousIndex: Int? = null
                var lastPoint = firstMove
                var selecting: Boolean? = null
                var edgeScroll: Job? = null

                fun selectAlong(from: Offset, to: Offset) {
                    val delta = to - from
                    val steps = ceil(delta.getDistance() / sampleStep).toInt().coerceAtLeast(1)
                    val targets = mutableSetOf<String>()
                    for (i in 0..steps) {
                        val index = grid.photoIndexAt(from + delta * (i.toFloat() / steps), indices) ?: continue
                        val previous = previousIndex ?: index
                        previousIndex = index
                        selecting = selecting ?: (order[index] !in currentSelection)
                        for (position in minOf(previous, index)..maxOf(previous, index)) {
                            val id = order[position]
                            if (visited.add(id)) targets.add(id)
                        }
                    }
                    val direction = selecting
                    if (direction != null && targets.isNotEmpty()) applySelection(targets, direction)
                }

                selectAlong(down.position, firstMove)
                try {
                    while (true) {
                        // Act before the grid's scrolling gesture once selection owns this drag.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val point = change.position
                        change.consume()
                        selectAlong(lastPoint, point)
                        lastPoint = point

                        if (edgeScroll == null) edgeScroll = launch {
                            while (true) {
                                delay(16)
                                val height = this@pointerInput.size.height.toFloat()
                                if (height < 1f) continue
                                val band = minOf(edge, height / 2)
                                val distance = when {
                                    lastPoint.y < band -> lastPoint.y - band
                                    lastPoint.y > height - band -> lastPoint.y - (height - band)
                                    else -> 0f
                                }.coerceIn(-band, band)
                                if (distance != 0f && grid.scrollBy(distance * 0.18f) != 0f) {
                                    val hit = lastPoint.copy(y = lastPoint.y.coerceIn(0f, height - 1))
                                    selectAlong(hit, hit)
                                }
                            }
                        }
                    }
                } finally {
                    edgeScroll?.cancel()
                }
            }
        }
    }
}

private fun LazyGridState.photoIndexAt(point: Offset, positions: Map<String, Int>): Int? {
    val layout = layoutInfo
    val y = point.y + layout.viewportStartOffset
    return layout.visibleItemsInfo.firstOrNull { item ->
        item.key in positions && point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
            y >= item.offset.y && y < item.offset.y + item.size.height
    }?.key?.let { positions[it] }
}
