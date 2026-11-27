package com.aatorque.stats

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded asynchronous writer; only completed events enter the queue. */
class AplLogWriter(context: Context) {
    private val app = context.applicationContext
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4),
        { task -> Thread(task, "V-Sport-APL-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val zone = ZoneId.systemDefault()

    init {
        executor.execute {
            try { PublicDocuments.cleanupPerformanceTemps(app) }
            catch (e: Exception) { DiagnosticLog.event("APL_TEMP_CLEANUP_FAILED", e.javaClass.simpleName) }
        }
    }

    fun submit(event: AplRecorder.Event, complete: (Boolean) -> Unit) {
        try {
            executor.execute {
                var success = false
                try {
                    val time = Instant.ofEpochMilli(event.triggerWallMs).atZone(zone).format(stamp)
                    val name = "APL-" + time + "-" + UUID.randomUUID().toString().take(8) + ".csv"
                    val bytes = encode(event).toByteArray(Charsets.UTF_8)
                    PublicDocuments.writePerformanceAtomic(app, name, bytes)
                    success = true
                    DiagnosticLog.event("APL_SAVED", "file=$name rows=${event.rows.size} reason=${event.reason}")
                } catch (e: Exception) {
                    DiagnosticLog.event("APL_SAVE_FAILED", "reason=${e.javaClass.simpleName} rows=${event.rows.size}")
                } finally {
                    complete(success)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            DiagnosticLog.event("APL_SAVE_FAILED", "reason=writer_queue_full_or_closed")
            complete(false)
        }
    }

    private fun encode(event: AplRecorder.Event): String = buildString {
        append("local_time,relative_ms,session,event_end_reason")
        for (pid in AplPidInventory.requirements) {
            append(",${pid.key}_raw,${pid.key}_quality,${pid.key}_source_token,${pid.key}_age_ms")
        }
        append("\r\n")
        for (row in event.rows) {
            append(Instant.ofEpochMilli(row.wallMs).atZone(zone).toOffsetDateTime())
            append(',').append(row.elapsedMs - event.triggerMs)
            append(',').append(event.session).append(',').append(event.reason)
            for (pid in AplPidInventory.requirements) {
                val sample = row.samples[pid.id]
                val age = sample?.ageMs(row.elapsedMs)
                val quality = when {
                    sample == null -> "MISSING"
                    sample.quality == PidValidator.Quality.SUSPECT -> "SUSPECT"
                    age == null -> "UNKNOWN_AGE"
                    age > AplPidInventory.maxAgeMs(pid.id) -> "EXPIRED"
                    else -> sample.quality.name
                }
                append(',').append(sample?.value?.takeIf { it.isFinite() } ?: "")
                append(',').append(quality)
                append(',').append(sample?.sourceToken ?: "")
                append(',').append(age ?: "")
            }
            append("\r\n")
        }
    }

    /** Drain already queued writes; no new submissions after close. */
    fun close() { executor.shutdown() }
}
