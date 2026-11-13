package com.aatorque.stats

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Append-only diagnostics in Downloads. No disk I/O on PID polling threads.
 * Android 10+ uses MediaStore; older Android uses public Downloads.
 */
object DiagnosticLog {
    private const val PREFIX = "V-Sport-Gauges-"
    private const val SUBFOLDER = "V-Sport Gauges"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val KEEP_FILES = 8
    private const val MAX_PENDING = 512
    private val pending = AtomicInteger()
    private val eventSequence = java.util.concurrent.atomic.AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "V-Sport-Diagnostic-Log").apply { isDaemon = true }
    }
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val filenameStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    @Volatile private var app: Context? = null
    private var currentUri: Uri? = null
    private var currentFile: File? = null
    private var bytesWritten = 0L
    private var sequence = 0

    fun initialize(context: Context) {
        app = context.applicationContext
        event("APP_START", "version=${BuildConfig.VERSION_NAME}")
    }

    fun event(kind: String, detail: String = "") {
        val context = app ?: return
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            return
        }
        val eventId = eventSequence.incrementAndGet()
        val line = "${ZonedDateTime.now().format(timestamp)} seq=$eventId " +
            kind.replace(Regex("[^A-Za-z0-9_]"), "_") + " " +
            detail.replace('\n', ' ').replace('\r', ' ').take(1200) + "\n"
        worker.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeMediaStore(context, line)
                } else {
                    writeLegacy(line)
                }
            } catch (e: Exception) {
                Log.w("V-Sport-Diagnostic-Log", "Log write failed: ${e.message}")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun writeMediaStore(context: Context, line: String) {
        val resolver = context.contentResolver
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (currentUri == null || bytesWritten + bytes.size > MAX_BYTES) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, newName())
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER + "/")
            }
            currentUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create Downloads log")
            bytesWritten = 0L
            trimMediaStore(context)
        }
        resolver.openOutputStream(currentUri!!, "wa")?.use {
            it.write(bytes)
            it.flush()
            bytesWritten += bytes.size
        } ?: throw IllegalStateException("Unable to append Downloads log")
    }

    private fun trimMediaStore(context: Context) {
        val resolver = context.contentResolver
        val entries = mutableListOf<Pair<String, Uri>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%.log"), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith(PREFIX) && name.endsWith(".log")) {
                    entries.add(name to android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
                }
            }
        }
        // Only delete our own logs; Android enforces ownership for app-created MediaStore entries.
        entries.sortedBy { it.first }.dropLast(KEEP_FILES).forEach { (_, uri) ->
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(line: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Downloads unavailable")
        if (currentFile == null || bytesWritten + line.toByteArray().size > MAX_BYTES) {
            currentFile = File(dir, newName())
            bytesWritten = 0L
            dir.listFiles()?.filter {
                it.name.startsWith(PREFIX) && it.name.endsWith(".log")
            }?.sortedBy { it.name }?.dropLast(KEEP_FILES)?.forEach { it.delete() }
        }
        currentFile!!.appendText(line)
        bytesWritten += line.toByteArray(Charsets.UTF_8).size
    }

    private fun newName(): String =
        "$PREFIX${ZonedDateTime.now().format(filenameStamp)}-${sequence++}.log"
}
