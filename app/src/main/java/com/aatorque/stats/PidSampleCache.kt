package com.aatorque.stats

/** Passive, bounded process-wide cache. No Torque calls or disk I/O.
 * The APL lifecycle owner opens/closes a generation on ECU connect/disconnect.
 * Existing pollers capture that generation before reading and publish afterwards. */
object PidSampleCache {
    data class Sample(
        val pid: String, val value: Double?, val sourceToken: Long,
        val observedAtMs: Long, val firstSeenAtMs: Long,
        val quality: PidValidator.Quality, val generation: Long,
        val sourceAgeAtObservationMs: Long?, val changedTokenObserved: Boolean,
        val sourceIntervalMs: Long? = null, val freshnessLimitMs: Long = AplPidInventory.maxAgeMs(pid)
    ) {
        fun ageMs(nowMs: Long): Long? {
            val elapsed = (nowMs - observedAtMs).coerceAtLeast(0L)
            return sourceAgeAtObservationMs?.plus(elapsed)
                ?: if (changedTokenObserved) (nowMs - firstSeenAtMs).coerceAtLeast(0L) else null
        }
    }

    private val lock = Any()
    private val latest = HashMap<String, Sample>()
    private val lastUsable = HashMap<String, Sample>()
    private val regularAt = HashMap<String, Long>()
    private val intervals = HashMap<String, ArrayDeque<Long>>()
    private data class Counts(var gauge: Long = 0, var supplemental: Long = 0,
        var fuel: Long = 0, var minimum: Long = 0, var changes: Long = 0,
        var repeated: Long = 0, var suspect: Long = 0, var duration: Long = 0, var completed: Long = 0)
    private val counts = HashMap<String, Counts>()

    fun request(generation: Long?, pid: String, producer: String, now: Long) = synchronized(lock) {
        if (generation == null || active != generation || pid !in AplPidInventory.ids) return@synchronized
        val c = counts.getOrPut(pid) { Counts() }
        when (producer) {
            "gauge" -> { c.gauge++; regularAt[pid] = now }
            "apl" -> c.supplemental++
            "fuel" -> c.fuel++
            "minimum" -> c.minimum++
        }
    }
    fun completed(generation: Long?, pid: String, durationMs: Long) = synchronized(lock) {
        if (generation == null || active != generation || pid !in AplPidInventory.ids) return@synchronized
        val c = counts.getOrPut(pid) { Counts() }
        c.duration += durationMs.coerceAtLeast(0L)
        c.completed++
    }
    fun regularPolling(generation: Long, pid: String, now: Long, period: Long): Boolean = synchronized(lock) {
        if (active != generation) return@synchronized false
        regularAt[pid]?.let { now - it <= maxOf(350L, period * 3) } ?: false
    }

    /** Distinct source timestamps, never cached repeat frequency. */
    private fun learnInterval(pid: String, previous: Sample?, token: Long): Long? {
        if (previous != null && token > previous.sourceToken && previous.sourceToken >= 1_000_000_000_000L &&
            token >= 1_000_000_000_000L) {
            val delta = token - previous.sourceToken
            if (delta in 1L..5000L) {
                val history = intervals.getOrPut(pid) { ArrayDeque() }
                if (history.size >= 12) history.removeFirst()
                history.addLast(delta)
            }
        }
        val history = intervals[pid] ?: return null
        if (history.size < 3) return null
        val sorted = history.sorted()
        return sorted[sorted.lastIndex * 3 / 4]
    }
    fun triggerAgeLimit(pid: String, interval: Long?): Long {
        if (pid !in AplPidInventory.triggerIds) return AplPidInventory.maxAgeMs(pid)
        return if (interval == null) 400L else (interval + 200L).coerceIn(400L, 2000L)
    }
    fun drainMetrics(generation: Long, now: Long, elapsed: Long): List<String> = synchronized(lock) {
        if (active != generation) return@synchronized emptyList()
        val result = AplPidInventory.requirements.map { item ->
            val c = counts[item.id] ?: Counts()
            val sample = latest[item.id]
            val seconds = elapsed.coerceAtLeast(1L) / 1000.0
            val mean = if (c.completed > 0) c.duration.toDouble() / c.completed else null
            "pid=${item.key} gauge=${c.gauge} apl=${c.supplemental} fuel=${c.fuel} minimum=${c.minimum}" +
                " distinct=${c.changes} repeated=${c.repeated} suspect=${c.suspect}" +
                " observedHz=${c.changes / seconds} intervalMs=${sample?.sourceIntervalMs}" +
                " ageMs=${sample?.ageMs(now)} limitMs=${sample?.freshnessLimitMs} meanRequestMs=$mean"
        }
        counts.clear()
        result
    }
    private var counter = 0L
    @Volatile private var active: Long? = null

    fun generation(): Long? = active
    fun beginSession(): Long = synchronized(lock) {
        latest.clear()
        lastUsable.clear(); regularAt.clear(); intervals.clear(); counts.clear()
        (++counter).also { active = it }
    }
    fun endSession(generation: Long) = synchronized(lock) {
        if (active == generation) {
            active = null
            latest.clear()
            lastUsable.clear(); regularAt.clear(); intervals.clear(); counts.clear()
        }
    }

    fun publish(generation: Long?, pid: String, value: Double?, token: Long,
                quality: PidValidator.Quality,
                observedAtMs: Long = android.os.SystemClock.elapsedRealtime(),
                wallMs: Long = System.currentTimeMillis()) {
        if (generation == null || pid !in AplPidInventory.ids) return
        synchronized(lock) {
            if (active != generation) return
            val previous = lastUsable[pid]
            if ((latest[pid]?.observedAtMs ?: Long.MIN_VALUE) > observedAtMs) return
            val usable = quality != PidValidator.Quality.SUSPECT && token > 0 &&
                value != null && value.isFinite() && kotlin.math.abs(value) < Float.MAX_VALUE.toDouble() &&
                (token < 1_000_000_000_000L || token <= wallMs)
            if (usable && previous != null && token < previous.sourceToken &&
                token >= 1_000_000_000_000L && previous.sourceToken >= 1_000_000_000_000L) return
            val same = previous?.sourceToken == token
            val firstSeen = if (same) previous!!.firstSeenAtMs else observedAtMs
            val changed = if (same) previous!!.changedTokenObserved else previous != null && usable
            val epochAge = if (token >= 1_000_000_000_000L && token <= wallMs) wallMs - token else null
            val age = if (same) previous!!.ageMs(observedAtMs)?.let { old -> maxOf(old, epochAge ?: old) } ?: epochAge else epochAge
            val classified = when {
                !usable -> PidValidator.Quality.SUSPECT
                same -> PidValidator.Quality.STALE
                else -> PidValidator.Quality.VALID
            }
            val c = counts.getOrPut(pid) { Counts() }
            if (!usable) c.suspect++ else if (same) c.repeated++ else c.changes++
            val interval = if (usable) learnInterval(pid, previous, token) else previous?.sourceIntervalMs
            val sample = Sample(pid, value, token, observedAtMs, firstSeen,
                classified, generation, age, changed, interval, triggerAgeLimit(pid, interval))
            latest[pid] = sample
            if (usable) lastUsable[pid] = sample
        }
    }

    fun snapshot(generation: Long): Map<String, Sample> = synchronized(lock) {
        if (active == generation) HashMap(latest) else emptyMap()
    }
}
