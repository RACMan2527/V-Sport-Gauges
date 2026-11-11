package com.aatorque.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toRect
import com.aatorque.datastore.Coloring
import com.aatorque.utils.OpenCloseAnimator
import com.github.anastr.speedviewlib.ImageSpeedometer

class TorqueSpeedometer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageSpeedometer(context, attrs, defStyleAttr) {

    var unitOverlay: GaugeUnitOverlay? = null
    var overlayUnitColor: Int = Color.WHITE
    var icon: Drawable? = null
    var alarmPaint: Paint? = null
    val alarmBottomOffset = dpTOpx(3f)
    val alarmAnimator = OpenCloseAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener {
            invalidate()
        }
        duration = 300
    }

    fun setAlarm(coloring: Coloring?) {
        alarmAnimator.setState(coloring != null)
        coloring?.let {
            alarmPaint = Paint().apply {
                color = it.color
                style = Paint.Style.STROKE
                strokeWidth = alarmBottomOffset
                setShadowLayer(
                    dpTOpx(3f),
                    0f,
                    0f,
                    Color(0f, 0f, 0f, 0.3f).toArgb()
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawIcon(canvas)
        // The SpeedView numeric text is animated; keep only the separate unit layer aligned.
        unitOverlay?.invalidate()
        alarmPaint?.let{
            var start = 45f
            var distance = 90f
            val percent = alarmAnimator.animatedValue as Float
            start += start - (start * percent)
            distance *= percent
            canvas.drawArc(
                RectF(
                    0f + alarmBottomOffset,
                    0f + alarmBottomOffset,
                    size.toFloat() - alarmBottomOffset,
                    size.toFloat() - alarmBottomOffset
                ),
                start,
                distance,
                false,
                it,
            )
        }
    }

    val iconSize = dpTOpx(35f)
    val halfIcon = (iconSize * 0.5f)
    val squeezeTop = dpTOpx(7f)
    val squeezeBottom = dpTOpx(3f)

    override fun getSpeedUnitTextBounds(): RectF {
        return super.getSpeedUnitTextBounds().apply {
            if (icon != null) {
                top -= halfIcon
                bottom -= halfIcon
                top += squeezeTop
                bottom += squeezeTop
            }
        }
    }

    /** Mirror the pinned SpeedView Gauge.updateSpeedUnitTextBitmap() placement. */
    fun drawUnitOverlay(canvas: Canvas) {
        if (unit.isEmpty()) return
        val bounds = getSpeedUnitTextBounds()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlayUnitColor
            textSize = unitTextSize
            typeface = speedTextTypeface
            textAlign = Paint.Align.CENTER
        }
        if (unitUnderSpeedText) {
            // SpeedView draws the unit at bitmap center + text size + half interval.
            canvas.drawText(unit, bounds.centerX(),
                bounds.centerY() + unitTextSize + unitSpeedInterval * .5f, paint)
        } else {
            val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = speedTextSize
                typeface = speedTextTypeface
            }
            val speedWidth = speedPaint.measureText(getSpeedText().toString())
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(unit, bounds.left + speedWidth + unitSpeedInterval,
                bounds.bottom, paint)
        }
    }

    private fun getIconBounds(): RectF {
        val bounds = getSpeedUnitTextBounds()
        val minPoint = (bounds.left + ((bounds.right - bounds.left) * 0.5f))

        return RectF(
            minPoint - halfIcon,
            bounds.bottom - squeezeBottom,
            minPoint + halfIcon,
            bounds.bottom + iconSize - squeezeBottom,
        )
    }

    private fun drawIcon(canvas: Canvas) {
        icon?.let{
            it.bounds = getIconBounds().toRect()
            it.draw(canvas)
        }
    }
}

/** Transparent sibling of the dial: alpha changes never invalidate the dial itself. */
class GaugeUnitOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var dial: TorqueSpeedometer? = null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dial?.drawUnitOverlay(canvas)
    }
}
