@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cleansweep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.LikeDaysRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun LikedPhotosScreen(
    days: Int,
    photos: List<MediaItem>,
    activeLikes: Map<String, Long>,
    onBack: () -> Unit,
    onSetDays: (Int) -> Unit,
    onPreview: (MediaItem) -> Unit
) {
    val liked = remember(photos, activeLikes) {
        photos.mapNotNull { photo -> activeLikes[photo.id]?.let { photo to it } }
            .sortedByDescending { it.second }
    }
    SettingsPage(R.string.likes_title, onBack) {
        item { LikeDurationSlider(days, onSetDays) }
        if (liked.isEmpty()) item {
            ListItem(leadingContent = { Icon(Icons.Rounded.Favorite, null) }) {
                Text(stringResource(R.string.likes_empty))
            }
        }
        items(liked, key = { it.first.id }) { (photo, time) ->
            ListItem(
                onClick = { onPreview(photo) },
                leadingContent = {
                    AsyncImage(
                        model = photo.uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
                    )
                },
                supportingContent = {
                    Text(likedTime.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())))
                }
            ) { Text(photo.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun LikeDurationSlider(days: Int, onSetDays: (Int) -> Unit) {
    // Give commonly used short durations more space on the slider.
    val slider = rememberSliderState(
        value = sqrt(days.toFloat()),
        trackRange = sqrt(LikeDaysRange.first.toFloat())..sqrt(LikeDaysRange.last.toFloat())
    )
    LaunchedEffect(days) { slider.value = sqrt(days.toFloat()) }
    fun selectedDays() = (slider.value * slider.value).roundToInt().coerceIn(LikeDaysRange)
    val title = stringResource(R.string.likes_duration)
    val current = stringResource(R.string.likes_days_value, selectedDays())

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(current, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            state = slider,
            onValueChange = { slider.value = it },
            onValueChangeFinished = {
                val updated = selectedDays()
                if (updated != days) onSetDays(updated)
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = current
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.likes_days_value, LikeDaysRange.first), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.likes_days_value, LikeDaysRange.last), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val likedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
