@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cleansweep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.GeometryStage
import com.cleansweep.domain.model.PhotoComparison
import com.cleansweep.domain.model.ScanParameter
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.SimilaritySimulation
import com.cleansweep.domain.model.SimulationWindow
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.model.SimulationTimings
import com.cleansweep.ui.ScanProgress
import com.cleansweep.ui.SimulationUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SimulationScreen(
    ui: SimulationUi,
    canStart: Boolean,
    reviewModel: SimulationModel,
    onBack: () -> Unit,
    onStart: (Set<SimulationModel>) -> Unit,
    onCancel: () -> Unit,
    onWindow: (Int) -> Unit
) {
    var showThresholds by rememberSaveable { mutableStateOf(false) }
    var modelSelection by rememberSaveable { mutableIntStateOf(0) }
    val report = (ui as? SimulationUi.Complete)?.report
    val groupCount = report?.windows?.sumOf { it.groups.size }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (groupCount == null) stringResource(R.string.simulation)
                        else stringResource(R.string.sim_title_groups, groupCount))
                },
                navigationIcon = { SecondaryBackButton(onBack) }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (ui) {
                SimulationUi.Idle, SimulationUi.Failed -> SimulationModelSelection(
                    modelSelection, { modelSelection = it }, canStart, ui == SimulationUi.Failed, reviewModel, onStart
                )
                is SimulationUi.Running -> AnalysisProgressPane(
                    if (ui.model == null) ui.progress else ScanProgress(0, 0),
                    onCancel,
                    simulation = true,
                    title = ui.model?.let { stringResource(R.string.sim_running_model, it.label) }
                )
                is SimulationUi.Complete -> LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val result = ui.report
                    item {
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.sim_summary,
                                result.sampled, result.windows.size, result.windows.sumOf { it.groups.size }),
                                style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.sim_window_size, result.thresholds[ScanParameter.CaptureSeconds]))
                            if (result.undated > 0) Text(stringResource(R.string.sim_undated, result.undated))
                            if (result.singletons > 0) Text(stringResource(R.string.sim_singletons, result.singletons))
                            if (result.unreadable > 0) Text(stringResource(R.string.scan_unreadable, result.unreadable))
                            result.timings?.let { SimulationTimingSummary(it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showThresholds = true }) {
                                    Text(stringResource(R.string.sim_threshold_snapshot))
                                }
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.sim_choose_again)) }
                            }
                        }
                    }
                    itemsIndexed(result.windows, key = { _, window -> window.startMillis }) { index, window ->
                        SimulationWindowEntry(window, onClick = { onWindow(index) })
                    }
                }
            }
        }
    }
    if (showThresholds && report != null) SimulationThresholdDialog(report.thresholds) { showThresholds = false }
}

@Composable
private fun SimulationModelSelection(
    selection: Int,
    onSelectionChange: (Int) -> Unit,
    canStart: Boolean,
    failed: Boolean,
    reviewModel: SimulationModel,
    onStart: (Set<SimulationModel>) -> Unit
) {
    LazyColumn(
        Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.sim_choose_models), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp))
            Text(
                stringResource(if (failed) R.string.sim_failed else R.string.sim_model_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        item {
            ListItem(
                onClick = { onSelectionChange(0) },
                leadingContent = { RadioButton(selected = selection == 0, onClick = null) },
                supportingContent = { Text(stringResource(R.string.sim_production_hint, reviewModel.label)) }
            ) { Text(stringResource(R.string.sim_normal_config)) }
        }
        items(SimulationModel.entries, key = { it.name }) { model ->
            val mask = 1 shl model.ordinal
            val selected = selection and mask != 0
            ListItem(
                onClick = { onSelectionChange(selection xor mask) },
                leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                supportingContent = if (model == SimulationModel.MobileClip) {
                    { Text(stringResource(R.string.sim_research_model)) }
                } else null
            ) { Text(model.label) }
        }
        item {
            Button(
                onClick = {
                    onStart(SimulationModel.entries.filter { selection and (1 shl it.ordinal) != 0 }.toSet())
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) { Text(stringResource(R.string.sim_start_test)) }
        }
    }
}

@Composable
private fun SimulationTimingSummary(timings: SimulationTimings) {
    Text(stringResource(R.string.sim_timing_total, timings.totalMillis / 1000.0),
        style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (timings.models.isEmpty()) R.string.sim_timing_baseline else R.string.sim_timing_classical,
        timings.baselineMillis / 1000.0))
    timings.models.forEach { timing ->
        Text(
            if (timing.failed) stringResource(R.string.sim_timing_failed, timing.model.label, timing.millis / 1000.0)
            else stringResource(R.string.sim_timing_model, timing.model.label, timing.millis / 1000.0,
                timing.scoredPairs, timing.encoded, timing.cached),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SimulationWindowEntry(window: SimulationWindow, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(window.photos, key = { it.id }) { photo ->
                    SimulationImage(photo, Modifier.size(54.dp).clip(MaterialTheme.shapes.small))
                }
            }
            Text(
                stringResource(R.string.sim_window_summary, window.photos.size, window.groups.size),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
internal fun SimulationWindowScreen(
    index: Int,
    report: SimilaritySimulation?,
    onBack: () -> Unit,
    onPreview: (MediaItem) -> Unit
) {
    val window = report?.windows?.getOrNull(index)
    val comparisons = remember(window) {
        val membership = buildMap {
            window?.groups?.forEachIndexed { number, group -> group.items.forEach { put(it.id, number) } }
        }
        window?.comparisons.orEmpty().groupBy { pair ->
            membership[pair.earlier.id]?.takeIf { it == membership[pair.later.id] }
        }
    }
    SettingsPage(stringResource(R.string.sim_window_number, index + 1), onBack) {
        if (window == null) {
            item { StatusPanel(R.drawable.symbol_photo_library, R.string.sim_ready) }
            return@SettingsPage
        }
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sim_window_stats, window.photos.size, window.groups.size,
                    window.comparisons.size), style = MaterialTheme.typography.titleMedium)
                Text(captureRange(window), style = MaterialTheme.typography.bodyMedium)
                if (window.unreadable > 0) Text(stringResource(R.string.scan_unreadable, window.unreadable))
                if (window.comparisons.isEmpty()) Text(stringResource(R.string.sim_no_pairs))
            }
        }
        for (section in window.groups.indices.map { it } + listOf(null)) {
            val pairs = comparisons[section].orEmpty()
            if (pairs.isEmpty()) continue
            item {
                Text(
                    if (section == null) stringResource(R.string.sim_other_pairs)
                    else if (window.groups[section].suggested) stringResource(R.string.sim_suggested_group_label, section + 1)
                    else stringResource(R.string.sim_group_label, section + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
            }
            items(pairs, key = { "${it.earlier.id}|${it.later.id}" }) {
                ComparisonCard(it, report.thresholds, onPreview)
            }
        }
    }
}

@Composable
private fun SimulationImage(photo: MediaItem, modifier: Modifier) {
    AsyncImage(
        model = photo.uri,
        contentDescription = stringResource(R.string.preview_photo),
        modifier = modifier,
        contentScale = ContentScale.Crop,
        error = painterResource(R.drawable.symbol_photo)
    )
}

@Composable
private fun ComparisonCard(pair: PhotoComparison, thresholds: ScanThresholds, onPreview: (MediaItem) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (pair.suggested) stringResource(R.string.sim_pair_suggested, pair.modelScores.keys.first().label)
                else stringResource(if (pair.similar) R.string.sim_pair_match else R.string.sim_pair_rejected),
                style = MaterialTheme.typography.titleMedium,
                color = if (pair.similar || pair.suggested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (photo in listOf(pair.earlier, pair.later)) {
                    Card(onClick = { onPreview(photo) }, modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium) {
                        SimulationImage(photo, Modifier.fillMaxWidth().aspectRatio(1.5f))
                    }
                }
            }
            ParameterRow(stringResource(R.string.sim_parameter), stringResource(R.string.sim_observed),
                stringResource(R.string.sim_limit), heading = true)
            HorizontalDivider()
            for (parameter in ScanParameter.entries) {
                val option = thresholdOptions.getValue(parameter)
                ParameterRow(
                    stringResource(option.title),
                    observedValue(pair, parameter),
                    stringResource(option.unit, thresholds[parameter])
                )
            }
            if (pair.modelScores.isNotEmpty()) {
                HorizontalDivider()
                pair.modelScores.forEach { (model, score) ->
                    Text(stringResource(R.string.sim_model_score, model.label, score),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            HorizontalDivider()
            Text(comparisonReason(pair), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ParameterRow(label: String, value: String, limit: String, heading: Boolean = false) {
    val style = if (heading) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1.7f), style = style)
        Text(value, Modifier.weight(1f), style = style)
        Text(limit, Modifier.weight(1f), color = detailColor, style = style)
    }
}

@Composable
private fun observedValue(pair: PhotoComparison, parameter: ScanParameter): String {
    val estimated = pair.geometry?.takeIf { it.stage == GeometryStage.Estimated }
    return when (parameter) {
        ScanParameter.CaptureSeconds -> stringResource(R.string.sim_seconds_decimal, pair.captureGapMillis / 1000.0)
        ScanParameter.HashDistance -> stringResource(R.string.unit_bits, pair.hashDistance)
        ScanParameter.ColorDistancePercent -> stringResource(R.string.sim_percent_decimal, pair.colorDistancePercent)
        ScanParameter.OrbRatioPercent, ScanParameter.RansacErrorPixels -> stringResource(R.string.sim_no_measured_value)
        ScanParameter.MinimumInliers -> estimated?.let { stringResource(R.string.unit_points, it.inliers) }
        ScanParameter.InlierPercent -> estimated?.let {
            stringResource(R.string.sim_percent_decimal, it.inliers * 100.0 / it.matches)
        }
        ScanParameter.MobileNetPercent, ScanParameter.MobileClipPercent ->
            pair.modelScores.entries.firstOrNull { it.key.threshold == parameter }?.let {
                stringResource(R.string.sim_percent_decimal, it.value * 100.0)
            }
        ScanParameter.SpreadPercent -> estimated?.let {
            stringResource(R.string.sim_percent_decimal, it.minSpreadPercent)
        }
    } ?: stringResource(R.string.sim_no_measured_value)
}

@Composable
private fun comparisonReason(pair: PhotoComparison): String {
    val geometry = pair.geometry ?: return stringResource(R.string.sim_fast_path)
    return when (geometry.stage) {
        GeometryStage.TooFewKeypoints -> stringResource(R.string.sim_few_keypoints,
            geometry.earlierKeypoints, geometry.laterKeypoints)
        GeometryStage.TooFewMatches -> stringResource(R.string.sim_few_matches, geometry.matches)
        GeometryStage.NoTransform -> stringResource(R.string.sim_no_transform, geometry.matches)
        GeometryStage.Estimated -> stringResource(R.string.sim_ransac_count, geometry.matches)
    }
}

private val captureDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val captureClock = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun captureRange(window: SimulationWindow): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(window.startMillis).atZone(zone)
    if (window.startMillis == window.lastMillis) return captureDate.format(start)
    val end = Instant.ofEpochMilli(window.lastMillis).atZone(zone)
    val endFormat = if (start.toLocalDate() == end.toLocalDate()) captureClock else captureDate
    return "${captureDate.format(start)} – ${endFormat.format(end)}"
}
