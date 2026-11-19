package com.cleansweep.data.repository

import android.content.Context
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.domain.model.CardCrop
import com.cleansweep.domain.model.ScanThresholds
import com.cleansweep.domain.model.SortScope
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.model.SimilarScanScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal val LikeDaysRange = 1..3650

// Older settings can contain the minimum-ratio field that preceded the crop threshold.
internal val AppSettingsJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class AppSettings(
    val sortScope: SortScope? = null,
    val thresholds: ScanThresholds = ScanThresholds(),
    val likeDays: Int = 30,
    @SerialName("portraitCrop") val cardCrop: CardCrop = CardCrop(),
    val similarScanScope: SimilarScanScope = SimilarScanScope(),
    val reviewModel: SimulationModel = SimulationModel.MobileNet
) {
    val effectiveSortScope: SortScope get() = sortScope ?: SortScope()
}

internal class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("photo_settings", Context.MODE_PRIVATE)

    fun read(): AppSettings {
        val saved = preferences.getString("settings", null) ?: return AppSettings()
        return try {
            AppSettingsJson.decodeFromString<AppSettings>(saved)
        } catch (error: SerializationException) {
            ErrorJournal.warn("Could not read saved settings", error)
            AppSettings()
        }
    }

    fun write(settings: AppSettings) {
        preferences.edit().putString("settings", AppSettingsJson.encodeToString(settings)).apply()
    }

    fun readLikes(): Map<String, Long> {
        val saved = preferences.getString("likes", null) ?: return emptyMap()
        return try {
            Json.decodeFromString(saved)
        } catch (error: SerializationException) {
            ErrorJournal.warn("Could not read liked photos", error)
            emptyMap()
        }
    }

    fun writeLikes(likes: Map<String, Long>) {
        preferences.edit().putString("likes", Json.encodeToString(likes)).apply()
    }
}
