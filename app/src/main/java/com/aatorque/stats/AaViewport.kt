package com.aatorque.stats

import android.content.Context

/** Shared capture and preview settings. Never changes the car's actual layout. */
object AaViewport {
    const val PREFS = "aa_content_area"
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 555
    const val DEFAULT_DENSITY_DPI = 160
    data class Size(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val fontScale: Float = 0f,
        val scaledDensity: Float = 0f
    ) {
        val renderDensityDpi: Int
            get() = if (validDensity(densityDpi)) densityDpi else DEFAULT_DENSITY_DPI
        val renderFontScale: Float
            get() = if (validFontScale(fontScale)) fontScale
                else if (validDensity(densityDpi) && validScaledDensity(scaledDensity) &&
                    validFontScale(scaledDensity / (densityDpi / 160f)))
                    scaledDensity / (densityDpi / 160f)
                else 1f
    }

    fun validSize(width: Int, height: Int): Boolean =
        width in 200..4096 && height in 100..2160 && width >= height

    fun validDensity(dpi: Int): Boolean = dpi in 80..1000
    fun validFontScale(scale: Float): Boolean = scale.isFinite() && scale in 0.5f..3f
    fun validScaledDensity(value: Float): Boolean = value.isFinite() && value in 0.25f..20f

    fun read(context: Context): Size {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val width = prefs.getInt("width_px", 0)
        val height = prefs.getInt("height_px", 0)
        val density = prefs.getInt("density_dpi", 0)
        return if (validSize(width, height))
            Size(width, height, if (validDensity(density)) density else 0,
                prefs.getFloat("font_scale", 0f), prefs.getFloat("scaled_density", 0f))
        else Size(DEFAULT_WIDTH, DEFAULT_HEIGHT, 0)
    }

    // Independent of capture: a new car measurement must never re-enable
    // a rendering mode the owner disabled for recovery.
    fun useVirtualPreview(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("virtual_preview_enabled", false)

    fun enableVirtualPreview(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("virtual_preview_enabled", true).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("width_px").remove("height_px").remove("density_dpi")
            .remove("font_scale").remove("scaled_density")
            .putBoolean("reset_pending_capture", true)
            .putBoolean("virtual_preview_enabled", false).apply()
        AppStateStore.changed(context.applicationContext)
        DiagnosticLog.event("TEST_SCREEN_VIEWPORT_RESET")
    }
}
