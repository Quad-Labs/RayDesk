package com.raydesk.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.raydesk.test.R

/**
 * Modal dialog for 4-digit PIN entry using head-gaze navigation.
 *
 * The dialog displays:
 * - "Enter PIN" title
 * - PIN display showing entered digits: [1] [_] [_] [_]
 * - NumberGridView for digit selection
 * - Hint text at bottom
 *
 * Interaction flow:
 * 1. User looks at desired number (focus follows gaze)
 * 2. Temple tap adds focused digit to PIN
 * 3. After 4 digits, callback fires with PIN string
 * 4. Double-tap removes last digit (backspace) or cancels if empty
 *
 * Design compliance:
 * - Pure black background (#000000)
 * - White text
 * - Blue focus (#1E2DED)
 * - 22sp for digits, 28sp for title
 * - Semi-transparent backdrop for modal effect
 *
 * Usage:
 * ```kotlin
 * val dialog = PinEntryDialog(context)
 * dialog.setOnPinEnteredListener { pin ->
 *     pairingManager.submitPin(pin)
 * }
 * dialog.setOnCancelledListener {
 *     showCancelMessage()
 * }
 * dialog.show()
 * ```
 */
class PinEntryDialog(context: Context) : Dialog(context) {

    /** State manager for PIN entry */
    private val pinState = PinEntryState()

    /** Number grid for digit selection */
    private lateinit var numberGridView: NumberGridView

    /** Display for entered PIN */
    private lateinit var pinDisplayView: TextView

    /** Callback when PIN is complete */
    private var onPinEnteredListener: ((String) -> Unit)? = null

    /** Callback when dialog is cancelled */
    private var onCancelledListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Create layout programmatically for maximum control
        val rootLayout = createLayout()
        setContentView(rootLayout)

        // Configure window for modal display
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor(BACKDROP_COLOR)))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            // Dim background for modal effect
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(BACKDROP_DIM_AMOUNT)
        }

        // Set up digit selection listener
        numberGridView.setOnDigitSelectedListener { digit ->
            onDigitSelected(digit)
        }

        updatePinDisplay()
    }

    /**
     * Create the dialog layout programmatically.
     */
    private fun createLayout(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(PADDING_DP), dpToPx(PADDING_DP), dpToPx(PADDING_DP), dpToPx(PADDING_DP))

            // Dialog content container with black background
            val contentContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.BLACK)
                setPadding(
                    dpToPx(CONTENT_PADDING_DP),
                    dpToPx(CONTENT_PADDING_DP),
                    dpToPx(CONTENT_PADDING_DP),
                    dpToPx(CONTENT_PADDING_DP)
                )

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                // Title
                addView(createTitleView())

                // Divider
                addView(createDivider())

                // PIN display
                pinDisplayView = createPinDisplayView()
                addView(pinDisplayView)

                // Number grid
                numberGridView = NumberGridView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(SECTION_MARGIN_DP)
                    }
                }
                addView(numberGridView)

                // Bottom divider
                addView(createDivider())

                // Hint text
                addView(createHintView())
            }

            addView(contentContainer)
        }
    }

    /**
     * Create the title TextView.
     */
    private fun createTitleView(): TextView {
        return TextView(context).apply {
            text = "Enter PIN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Create the PIN display TextView.
     */
    private fun createPinDisplayView(): TextView {
        return TextView(context).apply {
            text = pinState.displayText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PIN_DISPLAY_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(SECTION_MARGIN_DP)
            }
        }
    }

    /**
     * Create a divider view.
     */
    private fun createDivider(): android.view.View {
        return android.view.View(context).apply {
            setBackgroundColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(SECTION_MARGIN_DP)
            }
        }
    }

    /**
     * Create the hint TextView.
     */
    private fun createHintView(): TextView {
        return TextView(context).apply {
            text = "Look at number \u2022 Tap to select"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_TEXT_SIZE_SP)
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(SECTION_MARGIN_DP)
            }
        }
    }

    /**
     * Handle digit selection from NumberGridView.
     */
    private fun onDigitSelected(digit: Char) {
        if (!pinState.isComplete) {
            pinState.addDigit(digit)
            updatePinDisplay()

            if (pinState.isComplete) {
                // Auto-submit when 4 digits entered
                onPinEnteredListener?.invoke(pinState.getPin())
            }
        }
    }

    /**
     * Update the PIN display to reflect current state.
     */
    private fun updatePinDisplay() {
        if (::pinDisplayView.isInitialized) {
            pinDisplayView.text = pinState.displayText
        }
    }

    /**
     * Set the focus position on the number grid.
     *
     * Call this from head tracking to update focus.
     *
     * @param row Row index (0 or 1)
     * @param col Column index (0-4)
     */
    @MainThread
    fun setFocusPosition(row: Int, col: Int) {
        if (::numberGridView.isInitialized) {
            numberGridView.setFocusPosition(row, col)
        }
    }

    /**
     * Get the currently focused digit.
     */
    fun getFocusedDigit(): Char {
        return if (::numberGridView.isInitialized) {
            numberGridView.getFocusedDigit()
        } else {
            '1' // Default
        }
    }

    /**
     * Select the currently focused digit (temple tap action).
     */
    fun selectFocusedDigit() {
        if (::numberGridView.isInitialized) {
            numberGridView.selectFocusedDigit()
        }
    }

    /**
     * Handle backspace action (temple double-tap).
     *
     * Removes the last digit if any entered, otherwise cancels the dialog.
     */
    fun handleBackspace() {
        if (pinState.isEmpty) {
            // Cancel if nothing to delete
            onCancelledListener?.invoke()
            dismiss()
        } else {
            pinState.removeLastDigit()
            updatePinDisplay()
        }
    }

    /**
     * Set a listener for when a complete PIN is entered.
     *
     * @param listener Callback receiving the 4-digit PIN string
     */
    fun setOnPinEnteredListener(listener: (String) -> Unit) {
        this.onPinEnteredListener = listener
    }

    /**
     * Set a listener for when the dialog is cancelled.
     *
     * Called when user double-taps with no digits entered.
     */
    fun setOnCancelledListener(listener: () -> Unit) {
        this.onCancelledListener = listener
    }

    /**
     * Get the current entered digits count.
     */
    fun getEnteredDigitsCount(): Int = pinState.enteredDigits.length

    /**
     * Check if PIN entry is complete.
     */
    fun isPinComplete(): Boolean = pinState.isComplete

    /**
     * Get the current PIN string (may be incomplete).
     */
    fun getCurrentPin(): String = pinState.getPin()

    /**
     * Reset the PIN entry state.
     */
    fun resetPin() {
        pinState.clear()
        updatePinDisplay()
    }

    /**
     * Convert dp to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        /** Title text size in sp */
        const val TITLE_TEXT_SIZE_SP = 28f

        /** PIN display text size in sp */
        const val PIN_DISPLAY_TEXT_SIZE_SP = 26f

        /** Hint text size in sp */
        const val HINT_TEXT_SIZE_SP = 18f

        /** Padding around the entire dialog in dp */
        const val PADDING_DP = 32

        /** Padding inside content container in dp */
        const val CONTENT_PADDING_DP = 24

        /** Margin between sections in dp */
        const val SECTION_MARGIN_DP = 16

        /** Backdrop color (semi-transparent dark) */
        const val BACKDROP_COLOR = "#80000000"

        /** Background dim amount for modal effect */
        const val BACKDROP_DIM_AMOUNT = 0.7f
    }
}
