package com.cleansweep.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import com.cleansweep.data.model.MediaItem
import com.cleansweep.diagnostics.ErrorJournal

internal class PhotoFeatureCache(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    context.noBackupFilesDir.resolve("photo-features.db").absolutePath,
    null,
    2
) {
    private var available = true

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        Part.entries.forEach { createTable(db, it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTable(db, Part.Embedding)
    }

    private fun createTable(db: SQLiteDatabase, part: Part) {
        db.execSQL("CREATE TABLE ${part.table} (id TEXT PRIMARY KEY, revision TEXT NOT NULL, data BLOB NOT NULL)")
    }

    fun revision(item: MediaItem): String? = item.revision?.let { "1:${Build.FINGERPRINT}:$it" }

    fun read(part: Part, id: String, revision: String): ByteArray? = useCache {
        readableDatabase.query(part.table, arrayOf("data"), "id=? AND revision=?",
            arrayOf(id, revision), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
    }

    fun write(part: Part, id: String, revision: String, data: ByteArray) {
        useCache {
            writableDatabase.replaceOrThrow(part.table, null, ContentValues().apply {
                put("id", id)
                put("revision", revision)
                put("data", data)
            })
        }
    }

    private inline fun <T> useCache(action: () -> T): T? {
        if (!available) return null
        return try {
            action()
        } catch (error: SQLiteException) {
            available = false
            ErrorJournal.warn("Photo feature cache unavailable", error)
            null
        }
    }

    enum class Part(val table: String) { Capture("capture"), Appearance("appearance"), Orb("orb"), Window("scan_window"), Embedding("embedding") }
}
