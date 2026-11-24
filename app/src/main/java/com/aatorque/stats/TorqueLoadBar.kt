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
import com.aatorque.datastore.MaxControl
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
    private var allTimeDisplay: com.aatorque.datastore.Display? = null
    private var allTimeEnabled = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_bar, container, false)
        val frameAccent = view.context.theme.obtainStyledAttributes(intArrayOf(R.attr.themedHorizontalAccentColor))
        try {
            if (frameAccent.hasValue(0)) view.setBackgroundResource(R.drawable.bg_engine_load_frame_cadillac)
        } finally {
            frameAccent.recycle()
        }
        gauge = view.findViewById(R.id.engineLoadGauge)
        titleText = view.findViewById(R.id.loadBarTitle)
        titleText.setTextColor(requireContext().theme.obtainStyledAttributes(intArrayOf(R.attr.themedGaugeLabelColor)).let { attrs ->
            try { attrs.getColor(0, android.graphics.Color.WHITE) } finally { attrs.recycle() }
        })
        valueText = view.findViewById(R.id.engineLoadValue)
        applyGaugeTypography()
        return view
    }

    fun applyGaugeTypography() {
        if (!::gauge.isInitialized) return
        val prefs = requireContext().getSharedPreferences("gauge_typography", Context.MODE_PRIVATE)
        gauge.setLabelSizeSp(intArrayOf(16, 20, 24)[prefs.getInt("horizontal_font", 1).coerceIn(0, 2)])
    }

    fun setupBar(data: TorqueData, minValue: Double, maxValue: Double, title: String, showLabel: Boolean, wholeNumbers: Boolean, showTicks: Boolean, showPeak: Boolean, unit: String) {
        minimum = minValue
        maximum = if (maxValue > minValue) maxValue else minValue + 1.0
        titleText.text = if (showLabel) title else ""
        useWholeNumbers = wholeNumbers
        displayUnit = data.formattedUnit
        gauge.showTicks = showTicks
        allTimeDisplay = data.display
        allTimeEnabled = showPeak
        gauge.showAllTimePeak = showPeak
        gauge.setAllTimePeak(if (showPeak) AllTimePeakStore.get(requireContext(), data.display)?.toFloat() else null)
        val mode = data.display.maxMarksActive
        val legacyMax = mode == MaxControl.OFF &&
            (!data.display.hasHorizontalDailyMaxEnabled() || data.display.horizontalDailyMaxEnabled)
        gauge.showPeak = mode == MaxControl.MAX || mode == MaxControl.BOTH || legacyMax
        gauge.showDailyMin = mode == MaxControl.MIN || mode == MaxControl.BOTH
        gauge.setRange(minimum.toFloat(), maximum.toFloat())
        gauge.setProgress(if (data.hasReceivedReading) data.lastData.toFloat() else minimum.toFloat())
        gauge.setDailyPeak(DailyPeakStore.get(requireContext(), data.display)?.toFloat())
        gauge.setDailyMinimum(DailyPeakStore.get(requireContext(), data.display, true)?.toFloat())
        recordAllTimePeak(data)
        valueText.text = if (data.hasReceivedReading) formatReading(data.lastData) else "--"
        updateModifierValueColor(data)
        data.notifyUpdate = {
            val value = it.lastData
            gauge.animateProgress(value.toFloat(), TorqueRefresher.REFRESH_INTERVAL)
            gauge.setDailyPeak(DailyPeakStore.get(requireContext(), it.display)?.toFloat())
            gauge.setDailyMinimum(DailyPeakStore.get(requireContext(), it.display, true)?.toFloat())
            recordAllTimePeak(it)
            valueText.text = formatReading(value)
            updateModifierValueColor(it)
        }
    }

    private fun updateModifierValueColor(data: TorqueData) {
        valueText.setTextColor(
            if (data.currentAlarm.value != null) data.currentAlarm.value!!.color else
                if (data.modifierWindowIncomplete) android.graphics.Color.rgb(0, 223, 255) else android.graphics.Color.WHITE
        )
    }

    private fun recordAllTimePeak(data: TorqueData) {
        val value = data.lastData
        val display = allTimeDisplay ?: return
        if (!data.hasReceivedReading || !value.isFinite() || display.pid.isBlank() || data.modifierWindowIncomplete) return
        val saved = AllTimePeakStore.get(requireContext(), display)
        if (saved == null || value > saved) {
            AllTimePeakStore.set(requireContext(), display, value)
            if (allTimeEnabled) gauge.setAllTimePeak(value.toFloat())
        }
    }

    private fun formatReading(value: Double): String {
        val number = if (!value.isFinite()) "--" else if (useWholeNumbers) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
        return if (displayUnit.isBlank() || !value.isFinite()) number else "$number $displayUnit"
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
    // Optional theme-specific horizontal border/tick accent; other themes keep legacy styling.
    private val horizontalAccentColor: Int? = context.theme.obtainStyledAttributes(
        intArrayOf(R.attr.themedHorizontalAccentColor)
    ).let { attributes ->
        try {
            if (attributes.hasValue(0)) attributes.getColor(0, android.graphics.Color.WHITE) else null
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
        textSize = 24f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    fun setLabelSizeSp(sizeSp: Int) {
        labelPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        invalidate()
    }
    init {
        horizontalAccentColor?.let { accent ->
            // Cadillac: blue tick lines, white numeric labels.
            tickPaint.color = accent
            minorTickPaint.color = accent
        }
    }
    private val cornerRadius = 2f * density
    private val maxMarkerWidth = 13f * density
    private val maxMarkerHeight = 10f * density
    var showTicks = true
    var showDailyMin = false
    private var dailyMinimum = Float.NaN
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

    fun setDailyMinimum(value: Float?) {
        dailyMinimum = value?.takeIf { it.isFinite() }?.coerceIn(minimum, maximum) ?: Float.NaN
        invalidate()
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

        // For ranges crossing zero (including a maximum of zero), fill outward
        // from zero in either direction. Other ranges retain minimum-origin fill.
        val fraction = ((reading - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        if (minimum < 0f && maximum >= 0f) {
            val zeroFraction = ((0f - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            val zeroX = horizontalInset + usableWidth * zeroFraction
            val readingX = horizontalInset + usableWidth * fraction
            val left = minOf(zeroX, readingX)
            val right = maxOf(zeroX, readingX)
            if (right > left) {
                canvas.save()
                val trackPath = Path().apply {
                    addRoundRect(track, cornerRadius, cornerRadius, Path.Direction.CW)
                }
                canvas.clipPath(trackPath)
                canvas.drawRect(left, 0f, right, h, progressPaint)
                canvas.restore()
            }
        } else {
            val filledWidth = usableWidth * fraction
            if (filledWidth > 0f) {
                val fill = RectF(horizontalInset, 0f, horizontalInset + filledWidth, h)
                canvas.save()
                canvas.clipRect(horizontalInset, 0f, horizontalInset + filledWidth, h)
                canvas.drawRoundRect(fill, cornerRadius, cornerRadius, progressPaint)
                canvas.restore()
            }
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
        if (showDailyMin && dailyMinimum.isFinite()) {
            val minFraction = ((dailyMinimum - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            val minX = horizontalInset + usableWidth * minFraction
            val minPath = Path().apply {
                moveTo(minX - maxMarkerWidth / 2f, markerTop)
                lineTo(minX + maxMarkerWidth / 2f, markerTop)
                lineTo(minX, markerBottom)
                close()
            }
            val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.CYAN
                style = Paint.Style.FILL
            }
            canvas.drawPath(minPath, cyanPaint)
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
        val tickBottom = h * (if (horizontalAccentColor != null) 0.36f else 0.50f)
        val labelY = minOf(h - 2f * density, h * 0.94f)
        val minorTickTop = h * 0.28f
        val minorTickBottom = h * (if (horizontalAccentColor != null) 0.46f else 0.58f)
        val bidirectional = minimum < 0f && maximum >= 0f
        if (bidirectional) {
            // Anchor the tick grid at zero. Use one value step in both directions,
            // rather than inserting zero into the old six-position grid.
            val extent = maxOf(-minimum.toDouble(), maximum.toDouble())
            val desiredStep = extent / 3.0
            val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(desiredStep)))
            val normalized = desiredStep / magnitude
            val step = (when {
                normalized <= 1.0 -> 1.0
                normalized <= 2.0 -> 2.0
                normalized <= 5.0 -> 5.0
                else -> 10.0
            }) * magnitude
            val negativeCount = kotlin.math.floor(-minimum.toDouble() / step + 1e-9).toInt()
            val positiveCount = kotlin.math.floor(maximum.toDouble() / step + 1e-9).toInt()
            fun xFor(value: Double): Float =
                horizontalInset + usableWidth * ((value - minimum) / (maximum - minimum)).toFloat()
            for (index in -negativeCount..positiveCount) {
                val value = index * step
                val x = xFor(value)
                if (showTicks) {
                    canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
                    val label = formatTick(value.toFloat())
                    canvas.drawText(label, x, labelY, labelPaint)
                }
                if (index < positiveCount) {
                    for (minor in 1..1) {
                        val minorX = xFor(value + step / 2.0)
                        canvas.drawLine(minorX, minorTickTop, minorX, minorTickBottom, minorTickPaint)
                    }
                }
            }
        } else {
            // Preserve six major labels with one midpoint minor tick per interval.
            for (interval in 0 until 5) {
                for (minor in 1..1) {
                    val fractionAtTick = (interval + 0.5f) / 5f
                    val x = horizontalInset + usableWidth * fractionAtTick
                    canvas.drawLine(x, minorTickTop, x, minorTickBottom, minorTickPaint)
                }
            }
            for (index in 0..5) {
                val fractionAtTick = index / 5f
                val x = horizontalInset + usableWidth * fractionAtTick
                val tickValue = minimum + (maximum - minimum) * fractionAtTick
                if (showTicks) {
                    canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
                    canvas.drawText(formatTick(tickValue), x, labelY, labelPaint)
                }
            }
        }
    }

    private fun formatTick(value: Float): String {
        return if (value.isFinite()) value.roundToInt().toString() else "--"
    }
}
