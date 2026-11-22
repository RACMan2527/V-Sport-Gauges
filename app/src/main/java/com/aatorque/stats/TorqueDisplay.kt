package com.aatorque.stats

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.aatorque.datastore.Coloring
import com.aatorque.prefs.SettingsViewModel
import com.aatorque.stats.databinding.FragmentDisplayBinding
import com.aatorque.utils.AwareObserver
import timber.log.Timber


class TorqueDisplay : Fragment() {
    lateinit var rootView: View
    private var unit = ""

    private val bottomBacking = MutableLiveData(false)
    var isBottomDisplay
        get() = bottomBacking.value
        set(value) {
            bottomBacking.value = value
        }
    private lateinit var binding: FragmentDisplayBinding
    lateinit var settingsViewModel: SettingsViewModel

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settingsViewModel = ViewModelProvider(requireParentFragment())[SettingsViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Timber.i("onCreateView")
        binding = FragmentDisplayBinding.inflate(inflater, container, false)
        settingsViewModel.typefaceLiveData.observe(viewLifecycleOwner, this::setupTypeface)
        rootView = binding.root
        bottomBacking.observeForever {
            binding.showBottom = it
        }
        binding.showBottom = isBottomDisplay
        settingsViewModel.chartVisible.observe(viewLifecycleOwner) {
            binding.showSide = it
        }
        return binding.root
    }
    // this sets all the labels/values in an initial state, depending on the chosen options
    fun setupElement(data: TorqueData) {
        unit = data.formattedUnit

        data.notifyUpdate = this::onUpdate

        var icon = data.getDrawableName() ?: "ic_none"
        binding.iconText = ""
        binding.value = ""
        binding.icon = null

        if (data.pid != null) {
            if (data.display.showLabel || data.display.icon == "" || data.display.icon == "ic_none") {
                binding.iconText = data.display.label
                binding.icon = R.drawable.ic_none
            } else {
                binding.iconText = ""
                binding.icon = (
                    try {
                        resources.getIdentifier(
                            icon,
                            "drawable",
                            requireContext().packageName,
                        )
                    } catch (e: Resources.NotFoundException) {
                        R.drawable.ic_none
                    }
                )
            }
            binding.value = "-"
        }

        alarmObserver.bind(viewLifecycleOwner, data.currentAlarm)
    }

    fun setupTypeface(typeface: Typeface) {
        binding.font = typeface
    }

    @SuppressLint("SetTextI18n")
    fun onUpdate(data: TorqueData) {
        currentDisplayData = data
        binding.value = data.lastDataStr + unit
        binding.valueElement.setTextColor(
            if (data.currentAlarm.value != null) data.currentAlarm.value!!.color else
                if (data.modifierWindowIncomplete) android.graphics.Color.rgb(0, 223, 255) else android.graphics.Color.WHITE
        )
    }

    private var trip3ResetCallback: (() -> Unit)? = null

    fun configureTrip3Reset(onResetRequested: () -> Unit) {
        trip3ResetCallback = onResetRequested
        if (::rootView.isInitialized) attachTrip3ResetListener(onResetRequested)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trip3ResetCallback?.let(::attachTrip3ResetListener)
    }

    private fun attachTrip3ResetListener(onResetRequested: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        rootView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = Runnable {
                        val selected = currentDisplayData?.display?.pid
                        if (selected != null &&
                            VirtualPid.find(selected)?.id?.startsWith("VS_TRIP3_") == true) {
                            onResetRequested()
                        }
                    }.also { handler.postDelayed(it, 3_000L) }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                }
            }
            true
        }
    }

    private var currentDisplayData: TorqueData? = null

    val alarmObserver = object : AwareObserver<Coloring>() {
        override fun onChanged(value: Coloring?) {
            binding.alarm = value?.color
            binding.valueElement.setTextColor(value?.color ?: if (currentDisplayData?.modifierWindowIncomplete == true) android.graphics.Color.rgb(0, 223, 255) else android.graphics.Color.WHITE)
        }
    }
}