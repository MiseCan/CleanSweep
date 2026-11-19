@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cleansweep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cleansweep.R
import com.cleansweep.domain.model.CardCrop
import kotlin.math.roundToInt

@Composable
internal fun PhotoDisplayScreen(crop: CardCrop, onBack: () -> Unit, onSave: (CardCrop) -> Unit) {
    SettingsPage(R.string.photo_display, onBack) {
        item {
            val title = stringResource(R.string.card_crop)
            ListItem(
                supportingContent = { Text(stringResource(R.string.card_crop_hint)) },
                trailingContent = {
                    Switch(
                        checked = crop.enabled,
                        onCheckedChange = { onSave(crop.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = title }
                    )
                }
            ) { Text(title) }
        }
        item { CropThresholdSlider(crop, onSave) }
    }
}

@Composable
private fun CropThresholdSlider(crop: CardCrop, onSave: (CardCrop) -> Unit) {
    val bounds = CardCrop.ThresholdRange
    val slider = rememberSliderState(
        value = crop.threshold.toFloat(),
        steps = bounds.last - bounds.first - 1,
        trackRange = bounds.first.toFloat()..bounds.last.toFloat()
    )
    LaunchedEffect(crop.threshold) { slider.value = crop.threshold.toFloat() }
    val title = stringResource(R.string.card_crop_threshold)
    val value = stringResource(R.string.unit_percent, slider.value.roundToInt())

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.card_crop_threshold_hint), style = MaterialTheme.typography.bodySmall)
        Slider(
            state = slider,
            enabled = crop.enabled,
            onValueChange = { slider.value = it },
            onValueChangeFinished = {
                val updated = slider.value.roundToInt()
                if (updated != crop.threshold) onSave(crop.copy(thresholdPercent = updated))
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = value
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.unit_percent, bounds.first), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.unit_percent, bounds.last), style = MaterialTheme.typography.labelSmall)
        }
    }
}
