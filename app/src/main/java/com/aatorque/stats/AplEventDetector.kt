package com.aatorque.stats

/** Pure event state machine. All times are elapsed realtime, not wall clock. */
class AplEventDetector {
    enum class Action { NONE, START, FINISH }
    data class Input(val rpm: Double, val pedal: Double, val load: Double,
                     val rpmToken: Long, val pedalToken: Long, val loadToken: Long)
    private var triggerAt: Long? = null
    private var triggerInput: Input? = null
    private var releaseAt: Long? = null
    private var releaseInput: Input? = null
    private var postAt: Long? = null
    private var activeAt: Long? = null
    private var requireRelease = false
    val recording: Boolean get() = activeAt != null

    private fun qualifies(input: Input) = input.rpm > 300 && input.pedal >= 60 && input.load >= 75
    private fun released(input: Input) = input.rpm <= 300 || input.pedal < 35 || input.load < 50
    private fun distinct(a: Input, b: Input) =
        a.rpmToken != b.rpmToken && a.pedalToken != b.pedalToken && a.loadToken != b.loadToken

    fun update(now: Long, input: Input?, ready: Boolean): Action {
        val active = activeAt
        if (active != null) {
            if (now - active >= 120_000L) {
                clearEvent()
                requireRelease = true
                return Action.FINISH
            }
            if (input != null && qualifies(input)) {
                if (postAt != null) postAt = null
                releaseAt = null
                releaseInput = null
            } else if (postAt == null) {
                if (input != null && released(input)) {
                    val first = releaseInput
                    if (first == null) {
                        releaseAt = now
                        releaseInput = input
                    } else if (now - releaseAt!! >= 1000L &&
                        ((input.pedal < 35 && input.pedalToken != first.pedalToken) ||
                         (input.load < 50 && input.loadToken != first.loadToken) ||
                         (input.rpm <= 300 && input.rpmToken != first.rpmToken))) {
                        postAt = now
                    }
                } else {
                    releaseAt = null
                    releaseInput = null
                }
            }
            if (postAt?.let { now - it >= 5000L } == true) {
                clearEvent()
                return Action.FINISH
            }
            return Action.NONE
        }

        if (requireRelease) {
            if (input != null && released(input)) requireRelease = false
            return Action.NONE
        }
        if (!ready || input == null || !qualifies(input)) {
            triggerAt = null
            triggerInput = null
            return Action.NONE
        }
        val first = triggerInput
        if (first == null) {
            triggerAt = now
            triggerInput = input
        } else if (now - triggerAt!! >= 400L && distinct(first, input)) {
            activeAt = now
            triggerAt = null
            triggerInput = null
            return Action.START
        }
        return Action.NONE
    }

    private fun clearEvent() {
        activeAt = null
        triggerAt = null
        triggerInput = null
        releaseAt = null
        releaseInput = null
        postAt = null
    }

    fun reset() {
        clearEvent()
        requireRelease = false
    }
}
