package com.aatorque.prefs

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.aatorque.stats.AaViewport
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Measures the real dashboard at the car's virtual viewport, then transforms
 * the whole view (including overlays and touch coordinates) to fit the phone.
 * The phone remains portrait; the dashboard is rotated as in the old preview.
 */
class PreviewViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private var virtualWidth = AaViewport.DEFAULT_WIDTH
    private var virtualHeight = AaViewport.DEFAULT_HEIGHT

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        val viewport = AaViewport.read(context)
        // Convert car pixels to equivalent phone pixels for matching dp geometry.
        // Old captures without density retain the original pixel dimensions.
        val densityRatio = if (AaViewport.validDensity(viewport.densityDpi))
            resources.displayMetrics.densityDpi.toFloat() / viewport.densityDpi
        else 1f
        // Bound the virtual drawing surface, preserving its aspect ratio.
        val ratio = min(densityRatio, 4096f / maxOf(viewport.width, viewport.height))
        virtualWidth = (viewport.width * ratio).roundToInt().coerceAtLeast(1)
        virtualHeight = (viewport.height * ratio).roundToInt().coerceAtLeast(1)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                View.MeasureSpec.makeMeasureSpec(virtualHeight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(virtualWidth, View.MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val scale = min(width.toFloat() / virtualHeight, height.toFloat() / virtualWidth)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // The existing RotateLayout performs the rotation and child layout.
            // Scale only its outer portrait bounds, not the fragment directly.
            val x = (width - virtualHeight) / 2
            val y = (height - virtualWidth) / 2
            child.layout(x, y, x + virtualHeight, y + virtualWidth)
            child.pivotX = virtualHeight / 2f
            child.pivotY = virtualWidth / 2f
            child.scaleX = scale
            child.scaleY = scale
        }
    }
}
