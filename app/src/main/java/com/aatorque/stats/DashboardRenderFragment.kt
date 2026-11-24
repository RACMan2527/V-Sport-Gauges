package com.aatorque.stats

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import com.aatorque.prefs.DashboardPreviewFragment

/**
 * The host remains the real activity. Only the opt-in phone preview subtree
 * uses the car's resource configuration, including nested fragment inflaters
 * and programmatic dimensions read through requireContext()/resources.
 */
abstract class DashboardRenderFragment : Fragment() {
    protected fun previewRenderContext(): Context? {
        var parent = parentFragment
        while (parent != null) {
            if (parent is DashboardPreviewFragment) return parent.virtualRenderContext
            parent = parent.parentFragment
        }
        return null
    }

    override fun getContext(): Context? {
        val host = super.getContext() ?: return null
        return previewRenderContext() ?: host
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        val render = previewRenderContext() ?: return inflater
        // Preserve the child FragmentManager's inflater factory.
        return inflater.cloneInContext(render)
    }
}
