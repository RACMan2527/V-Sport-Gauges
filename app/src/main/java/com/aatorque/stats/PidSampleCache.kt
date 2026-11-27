package com.aatorque.stats

/** Passive, bounded process-wide cache. No Torque calls or disk I/O.
 * The APL lifecycle owner opens/closes a generation on ECU connect/disconnect.
 * Existing pollers capture that generation before reading and publish afterwards. */
object PidSampleCache {
    data class Sample(
        val pid: String, val value: Double?, val sourceToken: Long,
        val observedAtMs: Long, val firstSeenAtMs: Long,
        val quality: PidValidator.Quality, val generation: Long,
        val sourceAgeAtObservationMs: Long?, val changedTokenObserved: Boolean
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
    private var counter = 0L
    @Volatile private var active: Long? = null

    fun generation(): Long? = active
    fun beginSession(): Long = synchronized(lock) {
        latest.clear()
        lastUsable.clear()
        (++counter).also { active = it }
    }
    fun endSession(generation: Long) = synchronized(lock) {
        if (active == generation) {
            active = null
            latest.clear()
            lastUsable.clear()
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
                value != null && value.isFinite() && kotlin.math.abs(value) < Float.MAX_VALUE.toDouble()
            if (usable && previous != null && token < previous.sourceToken &&
                token >= 1_000_000_000_000L && previous.sourceToken >= 1_000_000_000_000L) return
            val same = previous?.sourceToken == token
            val firstSeen = if (same) previous!!.firstSeenAtMs else observedAtMs
            val changed = if (same) previous!!.changedTokenObserved else previous != null && usable
            val age = if (token >= 1_000_000_000_000L && token <= wallMs) wallMs - token else null
            val classified = when {
                !usable -> PidValidator.Quality.SUSPECT
                same -> PidValidator.Quality.STALE
                else -> PidValidator.Quality.VALID
            }
            val sample = Sample(pid, value, token, observedAtMs, firstSeen,
                classified, generation, age, changed)
            latest[pid] = sample
            if (usable) lastUsable[pid] = sample
        }
    }

    fun snapshot(generation: Long): Map<String, Sample> = synchronized(lock) {
        if (active == generation) HashMap(latest) else emptyMap()
    }
}
