package com.aatorque.stats

import android.content.Context

/** Shared capture and preview settings. Never changes the car's actual layout. */
object AaViewport {
    const val PREFS = "aa_content_area"
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 555
    data class Size(val width: Int, val height: Int, val densityDpi: Int)

    fun validSize(width: Int, height: Int): Boolean =
        width in 200..4096 && height in 100..2160 && width >= height

    fun validDensity(dpi: Int): Boolean = dpi in 80..1000

    fun read(context: Context): Size {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val width = prefs.getInt("width_px", 0)
        val height = prefs.getInt("height_px", 0)
        val density = prefs.getInt("density_dpi", 0)
        return if (validSize(width, height))
            Size(width, height, if (validDensity(density)) density else 0)
        else Size(DEFAULT_WIDTH, DEFAULT_HEIGHT, 0)
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("width_px").remove("height_px").remove("density_dpi")
            .putBoolean("reset_pending_capture", true).apply()
        AppStateStore.changed(context.applicationContext)
        DiagnosticLog.event("TEST_SCREEN_VIEWPORT_RESET")
    }
}
