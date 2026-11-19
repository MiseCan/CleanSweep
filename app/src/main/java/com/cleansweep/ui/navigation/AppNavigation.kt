package com.cleansweep.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.PhotoAccess
import com.cleansweep.ui.PhotoUiState
import com.cleansweep.ui.PhotoViewModel
import com.cleansweep.ui.SimulationUi
import com.cleansweep.ui.screens.ImplementationGuideScreen
import com.cleansweep.ui.screens.LikedPhotosScreen
import com.cleansweep.ui.screens.SimilarPhotosScreen
import com.cleansweep.ui.screens.SimilarScanScopeScreen
import com.cleansweep.ui.screens.PhotoDisplayScreen
import com.cleansweep.ui.screens.PhotoSelectionToolbar
import com.cleansweep.ui.screens.PhotoSelectionToolbarBottomGap
import com.cleansweep.ui.screens.ScanThresholdsScreen
import com.cleansweep.ui.screens.SettingsScreen
import com.cleansweep.ui.screens.SecondaryBackButton
import com.cleansweep.ui.screens.SimulationScreen
import com.cleansweep.ui.screens.SimulationWindowScreen
import com.cleansweep.ui.screens.SortScopeScreen
import com.cleansweep.ui.screens.SortPhotosScreen
import com.cleansweep.ui.screens.StatusPanel
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Serializable
private sealed interface Route : NavKey {
    @Serializable data object Gallery : Route
    @Serializable data class Preview(val id: String, val showName: Boolean = true) : Route
    @Serializable data object Settings : Route
    @Serializable data object Likes : Route
    @Serializable data object PhotoDisplay : Route
    @Serializable data class SortScope(val pickDates: Boolean = false) : Route
    @Serializable data object SimilarScope : Route
    @Serializable data object Thresholds : Route
    @Serializable data object Guide : Route
    @Serializable data object Simulation : Route
    @Serializable data class SimulationWindow(val index: Int) : Route
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppNavigation(
    state: PhotoUiState,
    model: PhotoViewModel,
    onExportErrorLog: () -> Unit,
    grant: () -> Unit
) {
    val stack = rememberNavBackStack<Route>(Route.Gallery)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val back = { if (stack.size > 1) stack.removeLastOrNull(); Unit }
    val openPreview: (MediaItem) -> Unit = { photo ->
        val current = stack.lastOrNull()
        if (current == Route.Gallery || current == Route.Likes || current == Route.Simulation || current is Route.SimulationWindow) {
            stack.add(Route.Preview(photo.id, showName = current !is Route.SimulationWindow))
        }
    }
    val canSimulate = state.access != PhotoAccess.None && state.photos.isNotEmpty() &&
        !state.loading && !state.removing && state.scanProgress == null

    LaunchedEffect(stack.lastOrNull()) {
        if (Route.Simulation !in stack) model.clearSimulation()
    }
    val openScope = {
        if (stack.lastOrNull() == Route.Gallery) stack.add(Route.Settings)
        if (stack.lastOrNull() == Route.Settings) stack.add(Route.SortScope())
        Unit
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(context.getString(message))
            model.clearMessage(message)
        }
    }
    NavDisplay(backStack = stack, modifier = Modifier.fillMaxSize()) {
        entry<Route.Gallery> {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val tabStates = rememberSaveableStateHolder()
            val similarTab = tab == 1
            val groupCount = state.groups?.size
            val sortQueue = remember(state.sortPhotos, state.reviews) {
                state.sortReviews.filter { it.remove }.mapTo(hashSetOf()) { it.id }
            }
            val showToolbar = tab == 1 && state.selectionMode &&
                state.scanProgress == null && !state.groups.isNullOrEmpty() &&
                state.access != PhotoAccess.None && !state.loading
            BackHandler(enabled = stack.size == 1 && showToolbar && !state.removing) {
                model.clearSelection()
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            val title = when {
                                !similarTab -> stringResource(R.string.app_name)
                                groupCount != null && state.scanProgress == null && !state.loading ->
                                    stringResource(R.string.result_groups, groupCount)
                                else -> stringResource(R.string.similar_title)
                            }
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        actions = {
                            if (state.access == PhotoAccess.Selected) {
                                if (similarTab) IconButton(onClick = grant) {
                                    Icon(painterResource(R.drawable.symbol_photo_library), stringResource(R.string.choose_photos))
                                } else TextButton(onClick = grant) {
                                    Text(stringResource(R.string.choose_photos))
                                }
                            }
                            if (similarTab && state.access != PhotoAccess.None && state.photos.isNotEmpty()) {
                                IconButton(
                                    enabled = !showToolbar && !state.removing && state.scanProgress == null && !state.loading,
                                    onClick = model::scan
                                ) {
                                    Icon(painterResource(R.drawable.symbol_refresh), stringResource(R.string.rescan))
                                }
                            }
                            if (tab == 0 && sortQueue.isNotEmpty()) {
                                val action = if (Build.VERSION.SDK_INT >= 30) R.string.trash_count else R.string.delete_count
                                IconButton(
                                    onClick = { model.remove(sortQueue) },
                                    enabled = !state.removing && state.scanProgress == null && !state.loading
                                ) {
                                    Icon(Icons.Filled.DeleteOutline, stringResource(action, sortQueue.size))
                                }
                            }
                            IconButton(onClick = { if (stack.lastOrNull() == Route.Gallery) stack.add(Route.Settings) }) {
                                Icon(Icons.Filled.Settings, stringResource(R.string.settings))
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(painterResource(R.drawable.symbol_swipe), null) },
                            label = { Text(stringResource(R.string.sort_title)) }
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(painterResource(R.drawable.symbol_image_search), null) },
                            label = { Text(stringResource(R.string.similar_title)) }
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
                    AnimatedVisibility(
                        visible = showToolbar,
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f)
                            .padding(horizontal = 16.dp, vertical = PhotoSelectionToolbarBottomGap),
                        enter = fadeIn(effects) + slideInVertically(spatial) { it / 3 },
                        exit = fadeOut(effects) + slideOutVertically(spatial) { it / 3 }
                    ) {
                        val total = state.groups.orEmpty().sumOf { it.items.size }
                        PhotoSelectionToolbar(
                            count = state.selected.size,
                            allSelected = total > 0 && state.selected.size == total,
                            enabled = showToolbar && !state.removing,
                            onToggleAll = model::toggleAllResults,
                            onClear = model::clearSelection,
                            onRemove = model::removeSelected
                        )
                    }
                    when {
                        state.access == PhotoAccess.None -> StatusPanel(
                            icon = R.drawable.symbol_photo_library,
                            title = R.string.permission_title,
                            description = R.string.permission_description,
                            action = R.string.allow_photos,
                            onAction = grant
                        )
                        state.loading -> StatusPanel(
                            icon = R.drawable.symbol_photo_library, title = R.string.loading_photos,
                            busy = true
                        )
                        state.photos.isEmpty() -> StatusPanel(
                            icon = R.drawable.symbol_photo_library, title = R.string.empty_gallery,
                            action = R.string.refresh, onAction = model::refresh
                        )
                        tab == 0 && state.sortPhotos.isEmpty() -> StatusPanel(
                            icon = R.drawable.symbol_photo_library,
                            title = R.string.scope_empty,
                            action = R.string.sort_scope,
                            onAction = openScope
                        )
                        else -> tabStates.SaveableStateProvider(tab) {
                            if (tab == 0) {
                                SortPhotosScreen(state, model, openPreview, showBatchDialog = stack.lastOrNull() == Route.Gallery) {
                                    if (stack.lastOrNull() == Route.Gallery) stack.add(Route.SortScope(pickDates = true))
                                }
                            } else {
                                SimilarPhotosScreen(state, model, openPreview, active = stack.lastOrNull() == Route.Gallery)
                            }
                        }
                    }
                }
            }
        }
        entry<Route.Preview>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            val photo = state.photos.firstOrNull { it.id == route.id }
            Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, topBar = {
                TopAppBar(
                    title = { Text(photo?.displayName?.takeIf { route.showName } ?: stringResource(R.string.preview),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { SecondaryBackButton(back) },
                    actions = {
                        if (photo != null) {
                            IconButton(onClick = { openPhotoInAnotherApp(context, photo.uri) }) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open_photo_with))
                            }
                        }
                    }
                )
            }) { padding ->
                Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    if (photo == null) {
                        StatusPanel(icon = R.drawable.symbol_photo, title = R.string.photo_unavailable)
                    } else {
                        var loading by remember(photo.id) { mutableStateOf(true) }
                        Box(contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = photo.uri, contentDescription = photo.displayName.takeIf { route.showName } ?: stringResource(R.string.preview_photo),
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                onSuccess = { loading = false },
                                onError = { loading = false },
                                error = painterResource(R.drawable.symbol_photo)
                            )
                            if (loading) LoadingIndicator()
                        }
                    }
                }
            }
        }
        entry<Route.Settings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            SettingsScreen(
                scope = state.settings.effectiveSortScope,
                likeDays = state.settings.likeDays,
                cardCrop = state.settings.cardCrop,
                onPhotoDisplay = { if (stack.lastOrNull() == Route.Settings) stack.add(Route.PhotoDisplay) },
                onLikes = { if (stack.lastOrNull() == Route.Settings) stack.add(Route.Likes) },
                onBack = back,
                onScope = openScope,
                scanScope = state.settings.similarScanScope,
                onScanScope = { if (stack.lastOrNull() == Route.Settings) stack.add(Route.SimilarScope) },
                onThresholds = { if (stack.lastOrNull() == Route.Settings) stack.add(Route.Thresholds) },
                onGuide = { if (stack.lastOrNull() == Route.Settings) stack.add(Route.Guide) },
                onExportErrorLog = onExportErrorLog
            )
        }
        entry<Route.PhotoDisplay>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            PhotoDisplayScreen(state.settings.cardCrop, back, model::setCardCrop)
        }
        entry<Route.Likes>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            LikedPhotosScreen(state.settings.likeDays, state.photos, state.activeLikes, back, model::setLikeDays, openPreview)
        }
        entry<Route.SortScope>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            val albums = remember(state.photos) {
                state.photos.mapNotNull { it.album }.distinctBy { it.id }.sortedBy { it.name }
            }
            SortScopeScreen(state.settings.effectiveSortScope, albums, route.pickDates, back) { scope ->
                model.setSortScope(scope)
                back()
            }
        }
        entry<Route.SimilarScope>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            SimilarScanScopeScreen(
                state.settings.similarScanScope,
                canSave = state.scanProgress == null && !state.removing,
                back
            ) { scope ->
                if (model.setSimilarScanScope(scope)) back()
            }
        }
        entry<Route.Thresholds>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            ScanThresholdsScreen(
                state.settings.thresholds, back, model::setScanThresholds, canSimulate,
                state.settings.reviewModel, state.scanProgress == null && !state.removing, model::setReviewModel
            ) {
                if (stack.lastOrNull() == Route.Thresholds) stack.add(Route.Simulation)
            }
        }
        entry<Route.Simulation>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            SimulationScreen(
                state.simulation, canSimulate, state.settings.reviewModel, back, { model.simulate(it) }, model::clearSimulation
            ) { index ->
                if (stack.lastOrNull() == Route.Simulation) stack.add(Route.SimulationWindow(index))
            }
        }
        entry<Route.SimulationWindow>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            val report = (state.simulation as? SimulationUi.Complete)?.report
            SimulationWindowScreen(route.index, report, back, openPreview)
        }
        entry<Route.Guide>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            ImplementationGuideScreen(back)
        }
    }
}

private fun openPhotoInAnotherApp(context: Context, uri: Uri) {
    val opened = try {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, context.getText(R.string.open_photo_with)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    if (!opened) Toast.makeText(context, R.string.open_photo_with_error, Toast.LENGTH_SHORT).show()
}
