package com.aatorque.stats

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
 * Append-only diagnostics in Documents/V-Sport Gauges/Logs. No disk I/O on PID polling threads.
 * Android 10+ uses MediaStore; older Android uses public Documents.
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
        recordBattery(context, "app_start")
    }

    fun recordBattery(context: Context, source: String) {
        try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (level * 100.0 / scale) else null
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            event("BATTERY_STATUS",
                "source=$source percent=${percent ?: "unavailable"} charging=$charging plugged=$plugged")
        } catch (e: Exception) {
            event("BATTERY_READ_ERROR", "source=$source error=${e.javaClass.simpleName}")
        }
    }

    // Aggregate rapidly changing record values without blocking gauge polling.
    private data class RecordAggregate(val kind: String, val pid: String, val first: Double?, var last: Double, var count: Int, var lastMs: Long, var label: String?)
    private val aggregates = mutableMapOf<String, RecordAggregate>()
    private const val AGGREGATE_MS = 10_000L

    @Synchronized
    fun recordChange(kind: String, pid: String, old: Double?, new: Double, label: String? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "$kind|$pid"
        val existing = aggregates[key]
        if (existing != null && now - existing.lastMs < AGGREGATE_MS) {
            existing.last = new
            existing.count++
            if (label != null) existing.label = label
            return
        }
        if (existing != null) emitRecord(existing)
        aggregates[key] = RecordAggregate(kind, pid, old, new, 1, now, label)
    }

    private fun emitRecord(record: RecordAggregate) {
        event(record.kind, "gauge=${record.label ?: "unknown"} pid=${record.pid} old=${record.first} new=${record.last} changes=${record.count} aggregated=${record.count > 1}")
    }

    @Synchronized
    fun flushRecords() {
        aggregates.values.forEach(::emitRecord)
        aggregates.clear()
    }

    // Records are emitted when the aggregation window expires, even without
    // another reading. This worker never performs PID requests.
    private val aggregateFlushWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "V-Sport-Record-Flush").apply { isDaemon = true }
    }
    init {
        aggregateFlushWorker.scheduleWithFixedDelay({
            synchronized(this) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextMinFlushMs) {
                    flushMinRejects()
                    nextMinFlushMs = now + MIN_SUMMARY_MS
                }
                val expired = aggregates.filterValues { now - it.lastMs >= AGGREGATE_MS }
                expired.forEach { (key, value) ->
                    emitRecord(value)
                    aggregates.remove(key)
                }
            }
        }, 1000L, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // Engine-off MIN rejections are frequent. Persist the first occurrence and
    // interval counts independently of Android Auto shutdown callbacks.
    private data class MinRejectAggregate(var count: Int, var firstMs: Long, var lastMs: Long)
    private val minRejects = mutableMapOf<String, MinRejectAggregate>()
    private const val MIN_SUMMARY_MS = 5_000L
    private var nextMinFlushMs = android.os.SystemClock.elapsedRealtime() + MIN_SUMMARY_MS

    @Synchronized
    fun repeatedMinReject(pid: String, reason: String, detail: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "$pid|$reason"
        val existing = minRejects[key]
        if (existing == null) {
            minRejects[key] = MinRejectAggregate(0, now, now)
            event("MIN_REJECT", "$detail reason=$reason")
        } else {
            existing.count++
            existing.lastMs = now
        }
    }

    @Synchronized
    private fun flushMinRejects() {
        val now = android.os.SystemClock.elapsedRealtime()
        minRejects.forEach { (key, item) ->
            if (item.count > 0) {
                event("MIN_REJECT_SUMMARY", "key=$key repeated=${item.count} durationMs=${now - item.firstMs}")
                item.count = 0
                item.firstMs = now
            }
        }
    }

    fun endSession() {
        flushRecords()
        flushMinRejects()
        event("APP_STOP")
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(context: Context, line: String) {
        val resolver = context.contentResolver
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (currentUri == null || bytesWritten + bytes.size > MAX_BYTES) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName())
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, PublicDocuments.LOGS)
            }
            currentUri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("Unable to create Documents log")
            bytesWritten = 0L
            trimMediaStore(context)
        }
        resolver.openOutputStream(currentUri!!, "wa")?.use {
            it.write(bytes)
            it.flush()
            bytesWritten += bytes.size
        } ?: throw IllegalStateException("Unable to append Documents log")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun trimMediaStore(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val entries = mutableListOf<Pair<String, Uri>>()
        resolver.query(collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(PublicDocuments.LOGS, "$PREFIX%.log"), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith(PREFIX) && name.endsWith(".log")) {
                    entries.add(name to android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }
        }
        entries.sortedBy { it.first }.dropLast(KEEP_FILES).forEach { (_, uri) ->
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(line: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "V-Sport Gauges/Logs")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Documents unavailable")
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
