@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.cleansweep.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleansweep.R
import com.cleansweep.domain.model.PhotoAlbum
import com.cleansweep.domain.model.CardCrop
import com.cleansweep.domain.model.ScanParameter
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.model.SortScope
import com.cleansweep.domain.model.PhotoDatePeriod
import com.cleansweep.domain.model.SimilarScanScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
internal fun SettingsScreen(
    scope: SortScope,
    onBack: () -> Unit,
    onScope: () -> Unit,
    likeDays: Int,
    onLikes: () -> Unit,
    cardCrop: CardCrop,
    onPhotoDisplay: () -> Unit,
    scanScope: SimilarScanScope,
    onScanScope: () -> Unit,
    onThresholds: () -> Unit,
    onGuide: () -> Unit,
    onExportErrorLog: () -> Unit
) = SettingsPage(R.string.settings, onBack) {
    settingsHeading(R.string.sort_title)
    item {
        SettingsRow(
            index = 0, count = 3,
            title = stringResource(R.string.sort_scope),
            summary = scopeSummary(scope),
            icon = Icons.Rounded.PhotoLibrary,
            onClick = onScope
        )
    }
    item {
        SettingsRow(
            index = 1, count = 3,
            title = stringResource(R.string.photo_display),
            summary = if (cardCrop.enabled) {
                stringResource(R.string.card_crop_summary, cardCrop.threshold)
            } else stringResource(R.string.card_crop_disabled),
            icon = Icons.Rounded.PhotoLibrary,
            onClick = onPhotoDisplay
        )
    }
    item {
        SettingsRow(
            index = 2, count = 3,
            title = stringResource(R.string.likes_title),
            summary = stringResource(R.string.likes_summary, likeDays),
            icon = Icons.Rounded.Favorite,
            onClick = onLikes
        )
    }
    settingsHeading(R.string.similar_title)
    item {
        SettingsRow(
            index = 0, count = 2,
            title = stringResource(R.string.scan_scope),
            summary = scanScope.summary(),
            icon = Icons.Rounded.DateRange,
            onClick = onScanScope
        )
    }
    item {
        SettingsRow(
            index = 1, count = 2,
            title = stringResource(R.string.scan_thresholds),
            summary = stringResource(R.string.scan_thresholds_summary),
            icon = Icons.Rounded.Tune,
            onClick = onThresholds
        )
    }
    settingsHeading(R.string.settings_general)
    item {
        SettingsRow(
            index = 0, count = 2,
            title = stringResource(R.string.implementation_guide),
            summary = stringResource(R.string.implementation_guide_summary),
            icon = Icons.Rounded.Info,
            onClick = onGuide
        )
    }
    item {
        SettingsRow(
            index = 1, count = 2,
            title = stringResource(R.string.export_error_log),
            summary = stringResource(R.string.export_error_log_summary),
            icon = Icons.Rounded.Description,
            onClick = onExportErrorLog
        )
    }
}

@Composable
internal fun SortScopeScreen(
    saved: SortScope,
    albums: List<PhotoAlbum>,
    startWithDatePicker: Boolean,
    onBack: () -> Unit,
    onSave: (SortScope) -> Unit
) {
    var albumId by rememberSaveable { mutableStateOf(saved.album?.id) }
    var period by rememberSaveable { mutableStateOf(saved.period) }
    var fromDay by rememberSaveable { mutableStateOf(saved.dateScope.customDays?.first) }
    var throughDay by rememberSaveable { mutableStateOf(saved.dateScope.customDays?.last) }
    var choosingAlbum by rememberSaveable { mutableStateOf(false) }
    var choosingRange by rememberSaveable { mutableStateOf(startWithDatePicker) }
    val available = remember(albums, saved.album) {
        (albums + listOfNotNull(saved.album)).distinctBy { it.id }
    }
    val selectedAlbum = available.firstOrNull { it.id == albumId }
    val draft = if (period == PhotoDatePeriod.Custom) SortScope(selectedAlbum, fromDay, throughDay, period)
        else SortScope(album = selectedAlbum, period = period)

    SettingsPage(
        R.string.sort_scope,
        onBack,
        bottomBar = { SaveScopeBar { onSave(draft) } }
    ) {
        item {
            SettingsRow(
                index = 0, count = 1,
                title = stringResource(R.string.scope_album),
                summary = selectedAlbum?.name ?: stringResource(R.string.scope_all_albums),
                icon = Icons.Rounded.PhotoLibrary,
                onClick = { choosingAlbum = true }
            )
        }
        item {
            ScopePeriodChoices(draft.dateScope) { option ->
                if (option == PhotoDatePeriod.Custom) choosingRange = true else period = option
            }
        }
        item { SettingsParagraph(R.string.scope_date_hint) }
    }
    if (choosingAlbum) {
        AlbumDialog(available, albumId, onDismiss = { choosingAlbum = false }) {
            albumId = it
            choosingAlbum = false
        }
    }
    if (choosingRange) {
        ScopeDateRangeDialog(
            initial = fromDay?.let { start -> throughDay?.let { end -> start..end } },
            onDismiss = {
                choosingRange = false
                if (startWithDatePicker) onBack()
            },
            onSelect = { range ->
                if (startWithDatePicker) onSave(SortScope(selectedAlbum, range.first, range.last))
                else {
                    fromDay = range.first
                    throughDay = range.last
                    period = PhotoDatePeriod.Custom
                    choosingRange = false
                }
            }
        )
    }
}

@Composable
internal fun SimilarScanScopeScreen(
    saved: SimilarScanScope,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: (SimilarScanScope) -> Unit
) {
    var period by rememberSaveable { mutableStateOf(saved.period) }
    var fromDay by rememberSaveable { mutableStateOf(saved.customDays?.first) }
    var throughDay by rememberSaveable { mutableStateOf(saved.customDays?.last) }
    var choosingRange by rememberSaveable { mutableStateOf(false) }
    val draft = if (period == PhotoDatePeriod.Custom) SimilarScanScope(period, fromDay, throughDay)
        else SimilarScanScope(period)

    SettingsPage(R.string.similar_scan_period, onBack, bottomBar = {
        SaveScopeBar(enabled = canSave) { onSave(draft) }
    }) {
        item {
            ScopePeriodChoices(draft) { option ->
                if (option == PhotoDatePeriod.Custom) choosingRange = true else period = option
            }
        }
        item {
            SettingsParagraph(if (canSave) R.string.similar_scan_date_hint else R.string.scan_scope_wait)
        }
    }

    if (choosingRange) {
        ScopeDateRangeDialog(
            initial = fromDay?.let { start -> throughDay?.let { end -> start..end } },
            onDismiss = { choosingRange = false }
        ) { range ->
            fromDay = range.first
            throughDay = range.last
            period = PhotoDatePeriod.Custom
            choosingRange = false
        }
    }
}

@Composable
private fun ScopePeriodChoices(scope: SimilarScanScope, onSelect: (PhotoDatePeriod) -> Unit) {
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
    ) {
        for ((index, option) in PhotoDatePeriod.entries.withIndex()) {
            val selected = scope.period == option
            SegmentedListItem(
                selected = selected,
                onClick = { onSelect(option) },
                shapes = ListItemDefaults.segmentedShapes(index, PhotoDatePeriod.entries.size),
                leadingContent = {
                    Icon(if (option == PhotoDatePeriod.Custom) Icons.Rounded.DateRange
                        else Icons.Rounded.CalendarToday, null)
                },
                supportingContent = if (selected && option == PhotoDatePeriod.Custom) {
                    { Text(scope.summary()) }
                } else null
            ) { Text(stringResource(option.title)) }
        }
    }
}

@Composable
private fun SaveScopeBar(enabled: Boolean = true, onSave: () -> Unit) {
    FlexibleBottomAppBar(horizontalArrangement = Arrangement.Center) {
        Button(
            onClick = onSave,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Save, null, Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.save_scope), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

private val PhotoDatePeriod.title: Int
    @StringRes get() = when (this) {
        PhotoDatePeriod.Recent30 -> R.string.scope_recent
        PhotoDatePeriod.Recent90 -> R.string.similar_scan_90_days
        PhotoDatePeriod.LastYear -> R.string.similar_scan_year
        PhotoDatePeriod.All -> R.string.scope_all_photos
        PhotoDatePeriod.Custom -> R.string.scope_custom
    }

@Composable
private fun SimilarScanScope.summary(): String {
    val range = customDays ?: return stringResource(period.title)
    return stringResource(R.string.scope_range_value, formatDay(range.first), formatDay(range.last))
}

@Composable
private fun AlbumDialog(
    albums: List<PhotoAlbum>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PhotoLibrary, null) },
        title = { Text(stringResource(R.string.scope_album)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 320.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                itemsIndexed(listOf(null) + albums, key = { _, album -> album?.id ?: "all" }) { index, album ->
                    val selected = album?.id == selectedId
                    SegmentedListItem(
                        selected = selected,
                        onClick = { onSelect(album?.id) },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = albums.size + 1),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                    ) { Text(album?.name ?: stringResource(R.string.scope_all_albums)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun ScopeDateRangeDialog(initial: LongRange?, onDismiss: () -> Unit, onSelect: (LongRange) -> Unit) {
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.first?.times(dayMillis),
        initialSelectedEndDateMillis = initial?.last?.times(dayMillis),
        initialDisplayedMonthMillis = (initial?.first ?: LocalDate.now().toEpochDay()) * dayMillis
    )
    val first = picker.selectedStartDateMillis
    val last = picker.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (first != null && last != null) onSelect(Math.floorDiv(first, dayMillis)..Math.floorDiv(last, dayMillis))
                },
                enabled = first != null && last != null,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    ) {
        DateRangePicker(
            state = picker,
            title = { Text(stringResource(R.string.scope_custom), Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
        )
    }
}

internal data class ThresholdOption(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    @StringRes val unit: Int,
    @StringRes val guide: Int
)

internal val thresholdOptions = mapOf(
    ScanParameter.CaptureSeconds to ThresholdOption(R.string.threshold_time, R.string.threshold_time_hint,
        R.string.unit_seconds, R.string.guide_threshold_time),
    ScanParameter.HashDistance to ThresholdOption(R.string.threshold_hash, R.string.threshold_hash_hint,
        R.string.unit_bits, R.string.guide_threshold_hash),
    ScanParameter.ColorDistancePercent to ThresholdOption(R.string.threshold_color, R.string.threshold_color_hint,
        R.string.unit_percent, R.string.guide_threshold_color),
    ScanParameter.OrbRatioPercent to ThresholdOption(R.string.threshold_orb, R.string.threshold_orb_hint,
        R.string.unit_percent, R.string.guide_threshold_orb),
    ScanParameter.RansacErrorPixels to ThresholdOption(R.string.threshold_ransac, R.string.threshold_ransac_hint,
        R.string.unit_pixels, R.string.guide_threshold_ransac),
    ScanParameter.MinimumInliers to ThresholdOption(R.string.threshold_count, R.string.threshold_count_hint,
        R.string.unit_points, R.string.guide_threshold_count),
    ScanParameter.InlierPercent to ThresholdOption(R.string.threshold_inliers, R.string.threshold_inliers_hint,
        R.string.unit_percent, R.string.guide_threshold_inliers),
    ScanParameter.SpreadPercent to ThresholdOption(R.string.threshold_spread, R.string.threshold_spread_hint,
        R.string.unit_percent, R.string.guide_threshold_spread),
    ScanParameter.MobileNetPercent to ThresholdOption(R.string.threshold_mobilenet, R.string.threshold_mobilenet_hint,
        R.string.unit_percent, R.string.guide_threshold_mobilenet),
    ScanParameter.MobileClipPercent to ThresholdOption(R.string.threshold_mobileclip, R.string.threshold_mobilenet_hint,
        R.string.unit_percent, R.string.guide_threshold_mobilenet)
)

@Composable
internal fun ScanThresholdsScreen(
    thresholds: ScanThresholds,
    onBack: () -> Unit,
    onSave: (ScanThresholds) -> Unit,
    canSimulate: Boolean,
    reviewModel: SimulationModel,
    canChangeModel: Boolean,
    onReviewModel: (SimulationModel) -> Unit,
    onSimulate: () -> Unit
) {
    SettingsPage(R.string.scan_thresholds, onBack) {
        item { SettingsParagraph(R.string.threshold_effect_hint) }
        item {
            Column(Modifier.selectableGroup()) {
                SettingsParagraph(R.string.threshold_review_model)
                for (model in SimulationModel.entries) {
                    val selected = model == reviewModel
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = selected,
                            enabled = canChangeModel,
                            role = Role.RadioButton,
                            onClick = { onReviewModel(model) }
                        ),
                        leadingContent = { RadioButton(selected = selected, enabled = canChangeModel, onClick = null) },
                        supportingContent = if (model == SimulationModel.MobileClip) {
                            { Text(stringResource(R.string.sim_research_model)) }
                        } else null
                    ) { Text(model.label) }
                }
            }
        }
        thresholdGroup(R.string.threshold_group_model, thresholds, listOf(reviewModel.threshold), onSave)
        item {
            Button(onClick = onSimulate, enabled = canSimulate, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PlayArrow, null)
                Text(stringResource(R.string.simulation), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            SettingsParagraph(R.string.sim_description)
        }
        thresholdGroup(R.string.threshold_group_capture, thresholds, listOf(ScanParameter.CaptureSeconds), onSave)
        thresholdGroup(
            R.string.threshold_group_appearance, thresholds,
            listOf(ScanParameter.HashDistance, ScanParameter.ColorDistancePercent), onSave
        )
        thresholdGroup(
            R.string.threshold_group_geometry, thresholds,
            listOf(ScanParameter.OrbRatioPercent, ScanParameter.RansacErrorPixels, ScanParameter.MinimumInliers,
                ScanParameter.InlierPercent, ScanParameter.SpreadPercent), onSave
        )
        item {
            FilledTonalButton(
                onClick = { onSave(ScanThresholds()) },
                enabled = thresholds != ScanThresholds(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.reset_thresholds), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

private fun LazyListScope.thresholdGroup(
    @StringRes title: Int,
    thresholds: ScanThresholds,
    parameters: List<ScanParameter>,
    onSave: (ScanThresholds) -> Unit
) {
    settingsHeading(title)
    itemsIndexed(parameters, key = { _, parameter -> parameter.name }) { index, parameter ->
        val option = thresholdOptions.getValue(parameter)
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = index, count = parameters.size),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            val current = thresholds[parameter]
            ThresholdSlider(parameter, option, current) { value -> onSave(thresholds.with(parameter, value)) }
        }
    }
}

@Composable
private fun ThresholdSlider(parameter: ScanParameter, option: ThresholdOption, current: Int, onSave: (Int) -> Unit) {
    val steps = parameter.bounds.last - parameter.bounds.first - 1
    val slider = rememberSliderState(
        value = current.toFloat(),
        trackRange = parameter.bounds.first.toFloat()..parameter.bounds.last.toFloat(),
        steps = steps
    )
    LaunchedEffect(current) { slider.value = current.toFloat() }
    val title = stringResource(option.title)
    val value = stringResource(option.unit, slider.value.roundToInt())
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(option.hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Slider(
            state = slider,
            onValueChange = { slider.value = it },
            onValueChangeFinished = {
                val updated = slider.value.roundToInt()
                if (updated != current) onSave(updated)
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = value
            },
            track = { state ->
                if (steps <= 12) SliderDefaults.Track(sliderState = state)
                else SliderDefaults.Track(sliderState = state, drawTick = { _, _ -> })
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(option.unit, parameter.bounds.first), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(option.unit, parameter.bounds.last), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ImplementationGuideScreen(onBack: () -> Unit) = SettingsPage(R.string.implementation_guide, onBack) {
    item { GuideSection(R.string.sort_title, R.string.guide_sort, Icons.Rounded.Swipe) }
    item {
        Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            GuideSection(R.string.guide_grouping_title, R.string.guide_grouping, Icons.Rounded.DateRange)
            GuideSection(R.string.guide_hash_title, R.string.guide_hash, Icons.Rounded.PhotoLibrary)
            GuideSection(R.string.guide_geometry_title, R.string.guide_geometry, Icons.Rounded.Tune)
        }
    }
    item { GuideSection(R.string.guide_threshold_title, R.string.guide_threshold_intro, Icons.Rounded.Tune) }
    item {
        Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            ScanParameter.entries.forEach { parameter ->
                val option = thresholdOptions.getValue(parameter)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.guide_threshold_default, stringResource(option.title),
                            stringResource(option.unit, parameter.default)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(stringResource(option.guide), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    item { GuideSection(R.string.guide_cache_title, R.string.guide_cache, Icons.Rounded.Schedule) }
    item { GuideSection(R.string.guide_removal_title, R.string.guide_removal, Icons.Rounded.DeleteOutline) }
}

@Composable
private fun GuideSection(@StringRes title: Int, @StringRes body: Int, icon: ImageVector) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
        }
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun LazyListScope.settingsHeading(@StringRes title: Int) {
    item(key = title) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() }
        )
    }
}

@Composable
private fun SettingsParagraph(@StringRes content: Int) {
    Text(
        stringResource(content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsRow(
    index: Int,
    count: Int,
    title: String,
    summary: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        supportingContent = { Text(summary) },
        leadingContent = icon?.let { image -> { Icon(image, null, tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }
    ) { Text(title) }
}

@Composable
internal fun SettingsPage(
    @StringRes title: Int,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) = SettingsPage(stringResource(title), onBack, bottomBar, content)

@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { SecondaryBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                content = content
            )
        }
    }
}

@Composable
internal fun SimulationThresholdDialog(thresholds: ScanThresholds, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, null) },
        title = { Text(stringResource(R.string.sim_threshold_snapshot)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(ScanParameter.entries) { _, parameter ->
                    val option = thresholdOptions.getValue(parameter)
                    Text(stringResource(R.string.sim_threshold_value,
                        stringResource(option.title),
                        stringResource(option.unit, thresholds[parameter])))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish_selection)) } }
    )
}

@Composable
internal fun SecondaryBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
    }
}

@Composable
private fun scopeSummary(scope: SortScope): String {
    val album = scope.album?.name ?: stringResource(R.string.scope_all_photos)
    return stringResource(R.string.scope_summary_dates, album, scope.dateScope.summary())
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDay(day: Long): String = LocalDate.ofEpochDay(day).format(dateFormat)
