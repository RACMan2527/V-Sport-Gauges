package com.aatorque.stats

import android.os.SystemClock
import android.icu.math.BigDecimal
import android.icu.text.NumberFormat
import androidx.lifecycle.MutableLiveData
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Operation
import com.ezylang.evalex.BaseException
import com.ezylang.evalex.Expression
import com.ezylang.evalex.data.EvaluationValue
import timber.log.Timber
import java.util.concurrent.ScheduledFuture
import java.util.ArrayDeque

class TorqueData(var display: Display) {
    companion object {
        const val PREFIX = "torque_"
        val drawableRegex = Regex("res/drawable/(?<name>.+)\\.[a-z]+")
        val twoPlaces: NumberFormat = NumberFormat.getNumberInstance()
        val intPlaces: NumberFormat = NumberFormat.getIntegerInstance()
    }

    init {
        twoPlaces.maximumFractionDigits = 2
        twoPlaces.minimumFractionDigits = 0
        twoPlaces.isGroupingUsed = true
        intPlaces.isGroupingUsed = true
        twoPlaces.roundingMode = BigDecimal.ROUND_HALF_UP
        twoPlaces.roundingMode = BigDecimal.ROUND_HALF_UP
    }

    var pid: String? = null
        get() {
            return if (display.disabled) null else field
        }
    var minValue: Double = Double.POSITIVE_INFINITY
    var maxValue: Double = Double.NEGATIVE_INFINITY
    private var expression: Expression? = null
    var lastDataStr: String = "-"
    var refreshTimer: ScheduledFuture<*>? = null
    var hasReceivedNonZero = false
    var hasReceivedReading = false
    var allowInitialZeroUpdates = false
    var currentAlarm = MutableLiveData<Coloring?>()

    var notifyUpdate: ((TorqueData) -> Unit)? = null
        set(value) {
            field = value
            value?.let { it(this) }
        }

    var lastData: Double = 0.0
        private set

    // Stage 1 of the statistical modifiers: apply Trend AFTER unit/script conversion.
    // A zero window preserves the original gauge behavior and existing saved settings.
    private data class TrendSample(val elapsedMs: Long, val convertedValue: Double)
    private val trendSamples = ArrayDeque<TrendSample>()
    private var trendWindowMs: Long = 0L
    var trendWindowComplete: Boolean = true
        private set

    @Synchronized
    fun configureTrendWindow(windowSeconds: Int) {
        require(windowSeconds >= 0) { "Trend window must be non-negative" }
        val newWindow = windowSeconds.toLong() * 1000L
        if (trendWindowMs != newWindow) {
            trendWindowMs = newWindow
            trendSamples.clear()
            trendWindowComplete = newWindow == 0L
        }
    }

    @Synchronized
    fun resetTrendHistory() {
        trendSamples.clear()
        trendWindowComplete = trendWindowMs == 0L
    }

    // A sliding difference, not a rate: current converted reading minus the
    // oldest converted reading at the requested time boundary.
    // Never treat a polling interruption as elapsed observed history.
    private fun trendValue(converted: Double, nowMs: Long): Double {
        if (trendWindowMs == 0L) return converted
        val previous = trendSamples.peekLast()
        val gapLimitMs = maxOf(2000L, minOf(10000L, trendWindowMs / 4L))
        if (previous != null && (nowMs <= previous.elapsedMs ||
                    nowMs - previous.elapsedMs > gapLimitMs)) {
            trendSamples.clear()
        }
        trendSamples.addLast(TrendSample(nowMs, converted))
        val cutoff = nowMs - trendWindowMs
        while (trendSamples.size > 1) {
            val first = trendSamples.peekFirst()
            val second = trendSamples.elementAt(1)
            if (second.elapsedMs > cutoff) break
            trendSamples.removeFirst()
        }
        val first = trendSamples.peekFirst()
        trendWindowComplete = first.elapsedMs <= cutoff
        val baseline = if (trendWindowComplete && trendSamples.size > 1) {
            val next = trendSamples.elementAt(1)
            val span = (next.elapsedMs - first.elapsedMs).toDouble()
            if (span > 0.0) {
                first.convertedValue + (next.convertedValue - first.convertedValue) *
                    ((cutoff - first.elapsedMs).toDouble() / span)
            } else first.convertedValue
        } else first.convertedValue
        return converted - baseline
    }

    // Accept only finite raw and converted readings. Rejected samples leave the
    // last valid display value, min/max, and alarm state unchanged.
    fun updateReading(value: Double): Boolean {
        if (!value.isFinite()) {
            Timber.w("Rejected non-finite PID reading $value for ${display.label}")
            return false
        }

        val converted = convertValue(value)
        if (!converted.first.isFinite() || !converted.first.toFloat().isFinite() ||
            converted.second == "Error") {
            Timber.w("Rejected invalid converted PID reading $value for ${display.label}")
            return false
        }

        // Reject shutdown sentinels and readings beyond 200% of the configured
        // display range before updating the gauge, session extrema, or peaks.
        val reading = converted.first
        val upperLimit = display.maxValue * 2.0
        // Negative readings remain valid; the gauge itself clamps them to its
        // display minimum, as it did before this shutdown-spike guard.
        if (reading == Int.MAX_VALUE.toDouble() || reading == Int.MIN_VALUE.toDouble() ||
            (upperLimit.isFinite() && reading > upperLimit)) {
            return false
        }

        val output = synchronized(this) {
            trendValue(converted.first, SystemClock.elapsedRealtime())
        }
        lastData = output
        lastDataStr = if (trendWindowMs > 0L) {
            (if (display.wholeNumbers) intPlaces else twoPlaces).format(output)
        } else converted.second
        if (lastData > maxValue) maxValue = lastData
        if (lastData < minValue) minValue = lastData
        updateAlarm(lastData)
        return true
    }

    private fun updateAlarm(field: Double) {
        if (!hasReceivedNonZero) {
            return
        }
        val newValue = display.alarmsList.firstOrNull {
            when (it.operation) {
                Operation.GT -> field > it.value
                Operation.GTE -> field >= it.value
                Operation.EQ -> field == it.value
                Operation.LT -> field < it.value
                Operation.LTE -> field <= it.value
                else -> false
            }
        }
        if (currentAlarm.value?.color != newValue?.color) {
            currentAlarm.postValue(newValue)
        }
    }


    init {
        val value = display.pid
        if (value.startsWith(PREFIX)) {
            pid = value.substring(PREFIX.length)
        } else if (VirtualPid.find(value) != null) {
            pid = value
            allowInitialZeroUpdates = true
        }
    }

    private fun convertValue(value: Double): Pair<Double, String> {
        val numberFormatter = if (display.wholeNumbers) intPlaces else twoPlaces
        if (!display.enableScript || display.customScript == "") {
            return Pair(value, try{
                numberFormatter.format(value)
            } catch (ex: IllegalArgumentException) {
                Timber.e("Exception formatting unconverted value $value", ex)
                value.toString()
            })
        }
        if (expression == null) {
            val strExp = display.customScript.replace("[x×]".toRegex(), "*")
            Timber.i("Attempting to make expression: $strExp")
            expression = Expression(strExp)
        }
        return try {
            val result = expression!!.with("a", value).evaluate()
            val asString = if (result.dataType == EvaluationValue.DataType.STRING) {
                result.stringValue
            } else {
                numberFormatter.format(result.numberValue)
            }
            Pair(result.numberValue.toDouble(), asString)
        } catch (ex: Exception) {
            when(ex) {
                is BaseException, is NoSuchElementException, is NumberFormatException -> {
                    Timber.e("Unable to parse", ex)
                    ex.printStackTrace()
                    Pair(0.0, "Error")
                }
                else -> throw ex
            }
        }
    }

    fun getDrawableName(): String? {
        val match = drawableRegex.matchEntire(display.icon)
        if (match != null) {
            return match.groups["name"]!!.value
        }
        return display.icon
    }

    fun sendNotifyUpdate() {
        // Background polling intentionally has no attached gauge view.
        // Keep polling and peak recording active without emitting an error per sample.
        notifyUpdate?.invoke(this)
    }

    fun stopRefreshing(isDestroying: Boolean = false) {
        refreshTimer?.cancel(true)
        refreshTimer = null
        if (isDestroying) {
            notifyUpdate = null
        }
    }

}