package com.raydesk.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.SeekBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.raydesk.data.CursorSettings
import com.raydesk.test.R
import com.raydesk.test.databinding.ActivityCursorSettingsBinding
import kotlinx.coroutines.launch

/**
 * Settings screen for cursor sensitivity configuration.
 *
 * Accessible from ConnectionActivity via settings icon.
 * Uses temple gestures for navigation:
 * - Swipe: Adjust selected setting value
 * - Tap: Move to next setting / confirm
 * - Double-tap: Save and exit
 */
class CursorSettingsActivity : BaseMirrorActivity<ActivityCursorSettingsBinding>() {

    companion object {
        private const val TAG = "CursorSettings"
    }

    private var settings = CursorSettings.default()
    private var focusedControl = 0 // 0=sensitivity, 1=precision, 2=comfort, 3=test, 4=reset, 5=done

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.i(TAG, "[INIT] CursorSettingsActivity created")

        // Load current settings
        settings = CursorSettings.load(this)

        initUI()
        initTempleGestures()
        updateUI()
    }

    private fun initUI() {
        mBindingPair.updateView {
            // Set up seekbar listeners
            seekBaseSensitivity.setOnSeekBarChangeListener(createSeekBarListener { progress ->
                settings = settings.copy(baseSensitivity = 0.5f + (progress / 100f) * 1.5f)
                updateUI()
            })

            seekPrecisionZone.setOnSeekBarChangeListener(createSeekBarListener { progress ->
                settings = settings.copy(precisionZone = 2f + (progress / 100f) * 8f)
                updateUI()
            })

            seekComfortRange.setOnSeekBarChangeListener(createSeekBarListener { progress ->
                settings = settings.copy(comfortRange = 15f + (progress / 100f) * 25f)
                updateUI()
            })

            // Button click listeners
            btnTest.setOnClickListener { launchTestMode() }
            btnReset.setOnClickListener { resetToDefaults() }
            btnDone.setOnClickListener { saveAndExit() }
        }
    }

    private fun initTempleGestures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    handleTempleAction(action)
                }
            }
        }
    }

    private fun handleTempleAction(action: TempleAction) {
        when (action) {
            is TempleAction.SlideForward -> {
                if (focusedControl < 3) {
                    adjustCurrentSetting(10)
                } else {
                    focusedControl = (focusedControl + 1).coerceAtMost(5)
                }
                updateUI()
            }

            is TempleAction.SlideBackward -> {
                if (focusedControl < 3) {
                    adjustCurrentSetting(-10)
                } else {
                    focusedControl = (focusedControl - 1).coerceAtLeast(0)
                }
                updateUI()
            }

            is TempleAction.Click -> {
                when (focusedControl) {
                    3 -> launchTestMode()
                    4 -> resetToDefaults()
                    5 -> saveAndExit()
                    else -> {
                        focusedControl = (focusedControl + 1).coerceAtMost(5)
                        updateUI()
                    }
                }
            }

            is TempleAction.DoubleClick -> {
                saveAndExit()
            }

            else -> {
            }
        }
    }

    private fun adjustCurrentSetting(delta: Int) {
        mBindingPair.updateView {
            when (focusedControl) {
                0 -> {
                    val newProgress = (seekBaseSensitivity.progress + delta).coerceIn(0, 100)
                    seekBaseSensitivity.progress = newProgress
                    settings = settings.copy(baseSensitivity = 0.5f + (newProgress / 100f) * 1.5f)
                }
                1 -> {
                    val newProgress = (seekPrecisionZone.progress + delta).coerceIn(0, 100)
                    seekPrecisionZone.progress = newProgress
                    settings = settings.copy(precisionZone = 2f + (newProgress / 100f) * 8f)
                }
                2 -> {
                    val newProgress = (seekComfortRange.progress + delta).coerceIn(0, 100)
                    seekComfortRange.progress = newProgress
                    settings = settings.copy(comfortRange = 15f + (newProgress / 100f) * 25f)
                }
            }
        }
    }

    private fun updateUI() {
        mBindingPair.updateView {
            // Update seekbar positions
            seekBaseSensitivity.progress = ((settings.baseSensitivity - 0.5f) / 1.5f * 100).toInt()
            seekPrecisionZone.progress = ((settings.precisionZone - 2f) / 8f * 100).toInt()
            seekComfortRange.progress = ((settings.comfortRange - 15f) / 25f * 100).toInt()

            // Update value labels
            tvBaseSensitivityValue.text = String.format("%.1fx", settings.baseSensitivity)
            tvPrecisionZoneValue.text = String.format("%.0f", settings.precisionZone) + "\u00B0"
            tvComfortRangeValue.text = String.format("%.0f", settings.comfortRange) + "\u00B0"

            // Update focus indicators (highlight current control)
            val focusColor = resources.getColor(R.color.focus_blue, null)
            val normalColor = resources.getColor(android.R.color.white, null)

            btnTest.setTextColor(if (focusedControl == 3) focusColor else normalColor)
            btnReset.setTextColor(if (focusedControl == 4) focusColor else normalColor)
            btnDone.setTextColor(if (focusedControl == 5) focusColor else normalColor)
        }
    }

    private fun launchTestMode() {
    }

    private fun resetToDefaults() {
        Log.i(TAG, "[RESET] Resetting to default settings")
        settings = CursorSettings.default()
        updateUI()
    }

    private fun saveAndExit() {
        Log.i(TAG, "[SAVE] Saving settings: $settings")
        settings.save(this)
        finish()
    }

    private fun createSeekBarListener(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }
}
