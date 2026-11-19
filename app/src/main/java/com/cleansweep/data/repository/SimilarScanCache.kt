package com.cleansweep.data.repository

import android.content.Context
import android.util.AtomicFile
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.model.SimilarScanScope
import com.cleansweep.domain.model.SimulationModel
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class CachedSimilarScan(
    val completedAtMillis: Long,
    val groups: List<Group>,
    val unreadable: Int,
    val scope: SimilarScanScope? = null,
    val reviewModel: SimulationModel = SimulationModel.MobileNet
) {
    @Serializable
    data class Group(val id: String, val photoIds: List<String>, val suggested: Boolean = false)

    fun isFresh(nowMillis: Long): Boolean =
        completedAtMillis > 0 && nowMillis - completedAtMillis <= TimeUnit.DAYS.toMillis(1)

    fun resolveGroups(photos: List<MediaItem>): List<SimilarGroup> {
        val byId = photos.associateBy { it.id }
        return groups.mapNotNull { group ->
            val items = group.photoIds.mapNotNull(byId::get)
            if (items.size >= 2) SimilarGroup(group.id, items, group.suggested) else null
        }
    }
}

internal class SimilarScanCache(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "similar-scan-v1.json"))
    private val lock = Mutex()

    suspend fun read(): CachedSimilarScan? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                Json.decodeFromString<CachedSimilarScan>(file.readFully().decodeToString())
            } catch (_: FileNotFoundException) {
                null
            } catch (error: IOException) {
                ErrorJournal.warn("Could not read the scan cache", error)
                null
            } catch (error: SerializationException) {
                ErrorJournal.warn("Invalid scan cache", error)
                null
            }
        }
    }

    suspend fun write(scan: CachedSimilarScan) = withContext(Dispatchers.IO) {
        val data = Json.encodeToString(scan).encodeToByteArray()
        lock.withLock {
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (error: IOException) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
