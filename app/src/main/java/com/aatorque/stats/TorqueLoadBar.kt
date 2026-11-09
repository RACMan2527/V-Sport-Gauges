package com.aatorque.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.math.max
import kotlin.math.roundToInt

/** Configurable horizontal bar for a single Torque PID and user-defined range. */
class TorqueLoadBar : Fragment() {
    private lateinit var gauge: EngineLoadBarView
    private lateinit var titleText: TextView
    private lateinit var valueText: TextView
    private var minimum = 0.0
    private var maximum = 100.0
    private var useWholeNumbers = true
    private var displayUnit = ""
    private var allTimePid = ""
    private var allTimeEnabled = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_bar, container, false)
        gauge = view.findViewById(R.id.engineLoadGauge)
        titleText = view.findViewById(R.id.loadBarTitle)
        valueText = view.findViewById(R.id.engineLoadValue)
        return view
    }

    fun setupBar(data: TorqueData, minValue: Double, maxValue: Double, title: String, showLabel: Boolean, wholeNumbers: Boolean, showTicks: Boolean, showPeak: Boolean, unit: String) {
        minimum = minValue
        maximum = if (maxValue > minValue) maxValue else minValue + 1.0
        titleText.text = if (showLabel) title else ""
        useWholeNumbers = wholeNumbers
        displayUnit = unit.trim()
        gauge.showTicks = showTicks
        allTimePid = data.display.pid
        allTimeEnabled = showPeak
        gauge.showAllTimePeak = showPeak
        gauge.setAllTimePeak(if (showPeak) AllTimePeakStore.get(requireContext(), data.display)?.toFloat() else null)
        gauge.showPeak = true
        gauge.setRange(minimum.toFloat(), maximum.toFloat())
        gauge.setProgress(data.lastData.toFloat())
        gauge.setDailyPeak(DailyPeakStore.get(requireContext(), data.display)?.toFloat())
        recordAllTimePeak(data.lastData)
        valueText.text = formatReading(data.lastData)
        data.notifyUpdate = {
            val value = it.lastData
            gauge.animateProgress(value.toFloat(), TorqueRefresher.REFRESH_INTERVAL)
            gauge.setDailyPeak(DailyPeakStore.get(requireContext(), it.display)?.toFloat())
            recordAllTimePeak(value)
            valueText.text = formatReading(value)
        }
    }

    private fun recordAllTimePeak(value: Double) {
        if (!value.isFinite() || allTimePid.isBlank()) return
        val saved = AllTimePeakStore.get(requireContext(), allTimePid)
        if (saved == null || value > saved) {
            AllTimePeakStore.set(requireContext(), allTimePid, value)
            if (allTimeEnabled) gauge.setAllTimePeak(value.toFloat())
        }
    }

    private fun formatReading(value: Double): String {
        val number = if (!value.isFinite()) "--" else if (useWholeNumbers) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
        return if (displayUnit.isBlank() || !value.isFinite()) number else "$number$displayUnit"
    }
}

/** Fixed-thickness bar with six tick labels spanning the configured min/max range. */
class EngineLoadBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4B4B4B.toInt()
        style = Paint.Style.FILL
    }
    private val maxMarkerColor: Int = context.theme.obtainStyledAttributes(
        intArrayOf(R.attr.themedNeedleColor)
    ).let { attributes ->
        try {
            attributes.getColor(0, android.graphics.Color.RED)
        } finally {
            attributes.recycle()
        }
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maxMarkerColor
        style = Paint.Style.FILL
    }
    private val maxMarkerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maxMarkerColor
        style = Paint.Style.FILL
        setShadowLayer(5f * density, 0f, 0f, maxMarkerColor)
    }
    private val maxMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maxMarkerColor
        style = Paint.Style.FILL
    }
    private val allTimeMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        strokeWidth = max(2f, 2f * density)
        style = Paint.Style.STROKE
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        strokeWidth = max(1f, 1f * density)
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
    }
    private val cornerRadius = 2f * density
    private val maxMarkerWidth = 13f * density
    private val maxMarkerHeight = 10f * density
    var showTicks = true
    var showPeak = true
    var showAllTimePeak = false
    private var allTimePeak = Float.NaN

    fun setAllTimePeak(value: Float?) {
        allTimePeak = value?.takeIf { it.isFinite() } ?: Float.NaN
        invalidate()
    }
    private var reading = 0f
    private var peak = Float.NEGATIVE_INFINITY
    private var minimum = 0f
    private var maximum = 100f

    fun setRange(minValue: Float, maxValue: Float) {
        minimum = minValue
        maximum = if (maxValue > minValue) maxValue else minValue + 1f
        reading = reading.coerceIn(minimum, maximum)
        peak = Float.NEGATIVE_INFINITY
        invalidate()
    }

    fun setProgress(value: Float) {
        reading = if (value.isFinite()) value.coerceIn(minimum, maximum) else minimum
        invalidate()
    }

    private var progressAnimator: ValueAnimator? = null

    /**
     * Interpolate each new Torque sample over the same refresh window used by
     * the round gauges. This avoids a visible step every time the PID updates.
     */
    fun animateProgress(value: Float, durationMs: Long) {
        if (!value.isFinite()) return
        val target = value.coerceIn(minimum, maximum)
        progressAnimator?.cancel()
        if (durationMs <= 0L || reading == target) {
            setProgress(target)
            return
        }
        progressAnimator = ValueAnimator.ofFloat(reading, target).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                reading = (animator.animatedValue as Float).coerceIn(minimum, maximum)
                invalidate()
            }
            start()
        }
    }

    fun setDailyPeak(value: Float?) {
        peak = value?.takeIf { it.isFinite() }?.coerceIn(minimum, maximum)
            ?: Float.NEGATIVE_INFINITY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Keep the active range 3% clear of both horizontal edges so end ticks
        // and rounded corners remain visible on the dashboard.
        val horizontalInset = w * 0.03f
        val usableWidth = w - horizontalInset * 2f
        val track = RectF(horizontalInset, 0f, w - horizontalInset, h)
        canvas.drawRoundRect(track, cornerRadius, cornerRadius, trackPaint)

        val fraction = ((reading - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        val filledWidth = usableWidth * fraction
        if (filledWidth > 0f) {
            val fill = RectF(horizontalInset, 0f, horizontalInset + filledWidth, h)
            canvas.save()
            canvas.clipRect(horizontalInset, 0f, horizontalInset + filledWidth, h)
            canvas.drawRoundRect(fill, cornerRadius, cornerRadius, progressPaint)
            canvas.restore()
        }

        // Mark the highest reading received with a soft glow and a crisp triangle.
        // The progress fill continues to follow the current reading.
        val peakFraction = (((peak.takeIf { it.isFinite() } ?: reading) - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        val markerX = horizontalInset + usableWidth * peakFraction
        val markerTop = 1f * density
        val markerBottom = minOf(h - 1f * density, markerTop + maxMarkerHeight)
        val markerPath = Path().apply {
            moveTo(markerX - maxMarkerWidth / 2f, markerTop)
            lineTo(markerX + maxMarkerWidth / 2f, markerTop)
            lineTo(markerX, markerBottom)
            close()
        }
        if (showPeak && peak.isFinite()) {
            canvas.drawPath(markerPath, maxMarkerGlowPaint)
            canvas.drawPath(markerPath, maxMarkerPaint)
        }
        if (showAllTimePeak && allTimePeak.isFinite()) {
            val allTimeFraction = ((allTimePeak - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            val allTimeX = horizontalInset + usableWidth * allTimeFraction
            val allTimePath = Path().apply {
                moveTo(allTimeX - maxMarkerWidth / 2f, markerTop)
                lineTo(allTimeX + maxMarkerWidth / 2f, markerTop)
                lineTo(allTimeX, markerBottom)
                close()
            }
            canvas.drawPath(allTimePath, allTimeMarkerPaint)
        }

        // Draw ticks after the active fill so the tick marks sit visibly on top
        // of the red portion, as well as on the unfilled track.
        val tickTop = h * 0.12f
        // Major ticks stop at the bar midpoint, clear of the labels below.
        val tickBottom = h * 0.50f
        val labelY = minOf(h - 2f * density, h * 0.94f)
        // Four short minor ticks divide each interval between the six primary ticks.
        val minorTickTop = h * 0.28f
        val minorTickBottom = h * 0.58f
        for (interval in 0 until 5) {
            for (minor in 1..4) {
                val fractionAtTick = (interval + minor / 5f) / 5f
                val x = horizontalInset + usableWidth * fractionAtTick
                canvas.drawLine(x, minorTickTop, x, minorTickBottom, minorTickPaint)
            }
        }

        for (index in 0..5) {
            val fractionAtTick = index / 5f
            val x = horizontalInset + usableWidth * fractionAtTick
            val tickValue = minimum + (maximum - minimum) * fractionAtTick
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            if (showTicks) canvas.drawText(formatTick(tickValue), x, labelY, labelPaint)
        }
    }

    private fun formatTick(value: Float): String {
        return if (value.isFinite()) value.roundToInt().toString() else "--"
    }
}
