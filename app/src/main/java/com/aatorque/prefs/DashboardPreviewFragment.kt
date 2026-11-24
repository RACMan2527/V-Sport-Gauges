package com.aatorque.prefs

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.aatorque.stats.AaViewport
import com.aatorque.stats.DiagnosticLog
import kotlin.math.roundToInt
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.aatorque.stats.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking


class DashboardPreviewFragment: Fragment() {
    val handler = Handler(Looper.getMainLooper())
    internal var virtualRenderContext: Context? = null
        private set
    companion object {
        val hideBars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.navigationBars()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = requireContext()
        val data = runBlocking { context.dataStore.data.first() }
        val theme = mapTheme(context, data.selectedTheme)
        val view = if (AaViewport.useVirtualPreview(context)) {
            val viewport = AaViewport.read(context)
            val dpi = viewport.renderDensityDpi
            val config = Configuration().apply {
                densityDpi = dpi
                fontScale = viewport.renderFontScale
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = (viewport.width * 160f / dpi).roundToInt()
                screenHeightDp = (viewport.height * 160f / dpi).roundToInt()
                smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
            }
            val render = ContextThemeWrapper(context.createConfigurationContext(config), theme)
            virtualRenderContext = render
            val metrics = render.resources.displayMetrics
            DiagnosticLog.event("TEST_SCREEN_METRICS",
                "widthPx=${viewport.width} heightPx=${viewport.height} " +
                    "capturedDpi=${viewport.densityDpi} capturedFontScale=${viewport.fontScale} " +
                    "capturedScaledDensity=${viewport.scaledDensity} " +
                    "renderDpi=${metrics.densityDpi} renderScaledDensity=${metrics.scaledDensity} " +
                    "renderFontScale=${render.resources.configuration.fontScale}")
            (inflater.cloneInContext(render)
                .inflate(R.layout.fragment_dashboard_preview_aa, container, false)
                as PreviewViewportLayout).apply { configureViewport(viewport) }
        } else {
            virtualRenderContext = null
            // Keep the original phone preview and theme sequence unchanged.
            inflater.inflate(R.layout.fragment_dashboard_preview, container, false).also {
                inflater.context.setTheme(theme)
            }
        }
        forceRotate(true)
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        virtualRenderContext = null
    }

    fun forceRotate(isOn: Boolean) {
        requireActivity().requestedOrientation = if(isOn) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onResume() {
        super.onResume()
        // Activate only after the phone test dashboard is displayed.
        val prefs = requireContext().getSharedPreferences("background_fuel_activation", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("test_dashboard_viewed", false)) {
            if (prefs.edit().putBoolean("test_dashboard_viewed", true).commit()) {
                com.aatorque.stats.DiagnosticLog.event("BACKGROUND_ACTIVATED_BY_TEST_DASHBOARD")
            }
        }
        (activity as? SettingsActivity)?.startBackgroundFuelWhenReady()
        forceRotate(true)

        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(hideBars)
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onPause() {
        super.onPause()
        val window = requireActivity().window
        forceRotate(false)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(hideBars)
    }

}

@StyleRes
fun mapTheme(context: Context, theme: String?):  Int {
    val findIndex = context.resources.getStringArray(R.array.Themes).indexOf(theme)
    if (findIndex != -1) {
        val arr = context.resources.obtainTypedArray(R.array.ThemeList)
        try{
            return arr.getResourceId(findIndex, R.style.AppTheme_AudiVC)
        } finally {
            arr.recycle()
        }
    }
    return R.style.AppTheme_AudiVC
}