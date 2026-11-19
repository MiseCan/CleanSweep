package com.cleansweep.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant

internal object ErrorJournal {
    private const val Tag = "CleanSweep"
    private const val LogLimitBytes = 128 * 1024L
    private val lock = Any()
    private var directory: File? = null
    private var appVersion: String = ""

    fun install(context: Context) {
        directory = context.noBackupFilesDir
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        appVersion = "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                append(Instant.now(), "FATAL", "Uncaught exception", error, sync = true)
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else Process.killProcess(Process.myPid())
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Thread({ importSystemExits(context) }, "cleansweep-exit-history").start()
        }
    }

    fun warn(event: String, error: Throwable) {
        Log.w(Tag, event, error)
        append(Instant.now(), "ERROR", event, error)
    }

    fun export(output: OutputStream) {
        val snapshot = synchronized(lock) {
            buildString {
                appendLine("CleanSweep diagnostic log")
                appendLine("Version: $appVersion | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Exported: ${Instant.now()}")
                appendLine()
                val folder = directory ?: return@buildString
                for (name in listOf("cleansweep-errors-previous.log", "cleansweep-errors.log")) {
                    val file = File(folder, name)
                    if (file.isFile) append(file.readText(Charsets.UTF_8))
                }
            }
        }
        output.write(snapshot.toByteArray(Charsets.UTF_8))
    }

    private fun append(
        time: Instant,
        level: String,
        event: String,
        error: Throwable? = null,
        sync: Boolean = false
    ): Boolean {
        val folder = directory ?: return false
        return try {
            val entry = buildString {
                appendLine("[$time] $level $event | CleanSweep $appVersion")
                // Exception messages can contain filenames and paths.
                var cause = error
                repeat(5) {
                    val current = cause ?: return@repeat
                    appendLine(current.javaClass.name)
                    current.stackTrace.take(32).forEach { appendLine("    at $it") }
                    cause = current.cause
                }
                appendLine()
            }.toByteArray(Charsets.UTF_8)
            synchronized(lock) {
                val log = File(folder, "cleansweep-errors.log")
                if (log.length() + entry.size > LogLimitBytes) {
                    val previous = File(folder, "cleansweep-errors-previous.log")
                    previous.delete()
                    if (!log.renameTo(previous)) log.delete()
                }
                FileOutputStream(log, true).use { output ->
                    output.write(entry)
                    if (sync) output.fd.sync()
                }
            }
            true
        } catch (error: Exception) {
            Log.w(Tag, "Could not save diagnostic entry", error)
            false
        }
    }

    @RequiresApi(30)
    private fun importSystemExits(context: Context) {
        try {
            val marker = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            var latest = marker.getLong("last_system_exit", 0)
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 20).asReversed().forEach { exit ->
                if (exit.timestamp <= latest) return@forEach
                val event = when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "Crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_ANR -> "Application not responding"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
                    else -> null
                }
                if (event != null && !append(Instant.ofEpochMilli(exit.timestamp), "SYSTEM",
                        "$event (pid=${exit.pid}, status=${exit.status})")) return
                latest = exit.timestamp
            }
            marker.edit().putLong("last_system_exit", latest).apply()
        } catch (error: Exception) {
            Log.w(Tag, "Could not read Android exit history", error)
        }
    }
}
