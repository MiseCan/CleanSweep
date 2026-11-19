package com.cleansweep.data.repository

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.PhotoAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class PhotoAccess { None, Selected, All }

internal class PhotoRepository(private val context: Context) {
    private val resolver = context.contentResolver

    val permissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun access(): PhotoAccess {
        val full = context.checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED
        return when {
            full -> PhotoAccess.All
            Build.VERSION.SDK_INT >= 34 &&
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED -> PhotoAccess.Selected
            else -> PhotoAccess.None
        }
    }

    suspend fun readPhotos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED) else emptyArray()
        val volumeVersions = mutableMapOf<String, String?>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        checkNotNull(resolver.query(collection, projection, null, null, sort)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    ensureActive()
                    val volume = cursor.getString(3) ?: MediaStore.VOLUME_EXTERNAL
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(volume), cursor.getLong(0)
                    )
                    val album = cursor.getString(4)?.let { id ->
                        val name = cursor.getString(5)?.takeIf { it.isNotBlank() }
                        name?.let { PhotoAlbum("$volume:$id", it) }
                    }
                    val added = cursor.getLong(6) * 1_000L
                    val revision = if (Build.VERSION.SDK_INT >= 30 && !cursor.isNull(9)) {
                        if (volume !in volumeVersions) {
                            volumeVersions[volume] = runCatching { MediaStore.getVersion(context, volume) }.getOrNull()
                        }
                        volumeVersions[volume]?.let { version ->
                            "$version:${cursor.getLong(9)}:${cursor.getLong(7)}:${cursor.getLong(8)}:$added"
                        }
                    } else null
                    add(MediaItem(uri, cursor.getString(1).orEmpty(), cursor.getLong(2).takeIf { it > 0 },
                        album, added, revision))
                }
            }
        }
    }

    suspend fun prepareRemoval(uris: List<Uri>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 30) {
            val pending = MediaStore.createTrashRequest(resolver, uris, true)
            IntentSenderRequest.Builder(pending.intentSender).build()
        } else {
            // Android 10 issues write consent for one image at a time. Retry after approval.
            val uri = uris.single()
            try {
                resolver.delete(uri, null, null)
                null
            } catch (error: RecoverableSecurityException) {
                IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
            }
        }
    }

    suspend fun deleteAfterConsent(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.delete(uri, null, null)
    }
}
