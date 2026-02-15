package com.raydesk.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.raydesk.test.R

/**
 * Custom view for displaying a 2-row number grid with head-gaze focus navigation.
 *
 * Layout:
 *   Row 0: 1 2 3 4 5
 *   Row 1: 6 7 8 9 0
 *
 * The grid supports head-gaze focus navigation where:
 * - Focus follows gaze position (mapped to row and column)
 * - Focused digit shows blue highlight (#1E2DED)
 * - Temple tap selects the focused digit
 *
 * Design compliance:
 * - Pure black background (from parent)
 * - White text, blue focus
 * - 22sp for digits
 * - Focus indicator symbols: filled circle / empty circle
 *
 * Usage:
 * ```kotlin
 * val numberGrid = findViewById<NumberGridView>(R.id.numberGrid)
 * numberGrid.setFocusPosition(row, col) // From head tracking
 * numberGrid.setOnDigitSelectedListener { digit ->
 *     handleDigitSelection(digit)
 * }
 *
 * // On temple tap:
 * val digit = numberGrid.getFocusedDigit()
 * ```
 */
class NumberGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** State manager for grid and focus */
    private val state = NumberGridState()

    /** Callback for digit selection */
    private var onDigitSelectedListener: ((Char) -> Unit)? = null

    /** Text views for each digit */
    private val digitViews = Array(2) { arrayOfNulls<TextView>(5) }

    /** Text views for focus indicators */
    private val indicatorViews = Array(2) { arrayOfNulls<TextView>(5) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        buildGrid()
    }

    /**
     * Build the grid layout with digits and indicators.
     */
    private fun buildGrid() {
        for (row in 0 until state.rowCount) {
            // Container for digits row
            val digitRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            }

            // Container for indicators row
            val indicatorRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(INDICATOR_MARGIN_DP)
                }
            }

            for (col in 0 until state.columnCount) {
                // Digit text view
                val digitView = createDigitTextView(row, col)
                digitViews[row][col] = digitView
                digitRow.addView(digitView)

                // Focus indicator text view
                val indicatorView = createIndicatorTextView(row, col)
                indicatorViews[row][col] = indicatorView
                indicatorRow.addView(indicatorView)
            }

            addView(digitRow)
            addView(indicatorRow)
        }

        updateFocusDisplay()
    }

    /**
     * Create a TextView for displaying a digit.
     */
    private fun createDigitTextView(row: Int, col: Int): TextView {
        return TextView(context).apply {
            text = state.getDigitAt(row, col).toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DIGIT_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dpToPx(CELL_WIDTH_DP)
            layoutParams = LayoutParams(
                dpToPx(CELL_WIDTH_DP),
                LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val digit = state.getDigitAt(row, col)
                onDigitSelectedListener?.invoke(digit)
            }
        }
    }

    /**
     * Create a TextView for displaying a focus indicator.
     */
    private fun createIndicatorTextView(row: Int, col: Int): TextView {
        return TextView(context).apply {
            text = state.getFocusIndicator(row, col)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, INDICATOR_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dpToPx(CELL_WIDTH_DP)
            layoutParams = LayoutParams(
                dpToPx(CELL_WIDTH_DP),
                LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Set the focus position from head-gaze tracking.
     *
     * Maps head orientation to grid position:
     * - Row 0 for upper portion of gaze range
     * - Row 1 for lower portion
     * - Columns 0-4 from left to right
     *
     * Must be called from the main thread.
     *
     * @param row Row index (0 or 1)
     * @param col Column index (0-4)
     */
    @MainThread
    fun setFocusPosition(row: Int, col: Int) {
        val prevRow = state.focusRow
        val prevCol = state.focusColumn

        state.setFocusPosition(row, col)

        // Only update if position changed
        if (prevRow != state.focusRow || prevCol != state.focusColumn) {
            updateFocusDisplay()
        }
    }

    /**
     * Get the currently focused digit.
     *
     * @return The digit character at the current focus position
     */
    fun getFocusedDigit(): Char {
        return state.getFocusedDigit()
    }

    /**
     * Set a listener for digit selection events.
     *
     * Called when a digit is selected (e.g., via temple tap or direct click).
     *
     * @param listener Callback receiving the selected digit character
     */
    fun setOnDigitSelectedListener(listener: (Char) -> Unit) {
        this.onDigitSelectedListener = listener
    }

    /**
     * Select the currently focused digit.
     *
     * Call this when user performs temple tap.
     */
    fun selectFocusedDigit() {
        val digit = state.getFocusedDigit()
        onDigitSelectedListener?.invoke(digit)
    }

    /**
     * Get the current focus row.
     */
    fun getFocusRow(): Int = state.focusRow

    /**
     * Get the current focus column.
     */
    fun getFocusColumn(): Int = state.focusColumn

    /**
     * Update the visual display to reflect current focus state.
     */
    private fun updateFocusDisplay() {
        for (row in 0 until state.rowCount) {
            for (col in 0 until state.columnCount) {
                val isFocused = state.isFocused(row, col)

                // Update digit text color
                digitViews[row][col]?.setTextColor(
                    if (isFocused) {
                        ContextCompat.getColor(context, R.color.focus_blue)
                    } else {
                        Color.WHITE
                    }
                )

                // Update indicator symbol and color
                indicatorViews[row][col]?.apply {
                    text = state.getFocusIndicator(row, col)
                    setTextColor(
                        if (isFocused) {
                            ContextCompat.getColor(context, R.color.focus_blue)
                        } else {
                            Color.WHITE
                        }
                    )
                }
            }
        }
    }

    /**
     * Convert dp to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        /** Text size for digits in sp */
        const val DIGIT_TEXT_SIZE_SP = 22f

        /** Text size for focus indicators in sp */
        const val INDICATOR_TEXT_SIZE_SP = 18f

        /** Width of each cell in dp */
        const val CELL_WIDTH_DP = 48

        /** Margin below indicator row in dp */
        const val INDICATOR_MARGIN_DP = 8
    }
}

/**
 * State class for NumberGridView.
 *
 * Manages the digit grid layout and focus position for head-gaze navigation.
 * Grid layout:
 *   Row 0: 1 2 3 4 5
 *   Row 1: 6 7 8 9 0
 */
class NumberGridState {
    companion object {
        const val FOCUSED_INDICATOR = "\u25C9"   // Filled circle
        const val UNFOCUSED_INDICATOR = "\u25CB" // Empty circle

        private val GRID = arrayOf(
            charArrayOf('1', '2', '3', '4', '5'),
            charArrayOf('6', '7', '8', '9', '0')
        )
    }

    val rowCount: Int = 2
    val columnCount: Int = 5

    var focusRow: Int = 0
        private set
    var focusColumn: Int = 0
        private set

    /**
     * Set the focus position from head-gaze tracking.
     *
     * @param row Row index (0 or 1)
     * @param col Column index (0-4)
     */
    fun setFocusPosition(row: Int, col: Int) {
        focusRow = row.coerceIn(0, rowCount - 1)
        focusColumn = col.coerceIn(0, columnCount - 1)
    }

    /**
     * Get the digit at the current focus position.
     */
    fun getFocusedDigit(): Char {
        return getDigitAt(focusRow, focusColumn)
    }

    /**
     * Get the digit at a specific grid position.
     *
     * @return The digit character, or null character if out of bounds
     */
    fun getDigitAt(row: Int, col: Int): Char {
        return if (row in 0 until rowCount && col in 0 until columnCount) {
            GRID[row][col]
        } else {
            '\u0000'
        }
    }

    /**
     * Check if a specific cell is currently focused.
     */
    fun isFocused(row: Int, col: Int): Boolean {
        return row == focusRow && col == focusColumn
    }

    /**
     * Get the focus indicator symbol for a cell.
     */
    fun getFocusIndicator(row: Int, col: Int): String {
        return if (isFocused(row, col)) FOCUSED_INDICATOR else UNFOCUSED_INDICATOR
    }

    /**
     * Format a row for display with digits and focus indicators.
     */
    fun formatRow(row: Int): String {
        return (0 until columnCount).joinToString("   ") { col ->
            getDigitAt(row, col).toString()
        }
    }
}

/**
 * State class for PIN entry.
 *
 * Manages the 4-digit PIN entry with display formatting.
 */
class PinEntryState {
    companion object {
        const val PIN_LENGTH = 4
        private const val EMPTY_SLOT = "[_]"
    }

    private val digits = StringBuilder()

    /**
     * The currently entered digits as a string.
     */
    val enteredDigits: String
        get() = digits.toString()

    /**
     * Display text showing entered digits and empty slots.
     * Format: "[1] [2] [_] [_]"
     */
    val displayText: String
        get() {
            return (0 until PIN_LENGTH).joinToString(" ") { index ->
                if (index < digits.length) {
                    "[${digits[index]}]"
                } else {
                    EMPTY_SLOT
                }
            }
        }

    /**
     * Whether the PIN entry is complete (4 digits entered).
     */
    val isComplete: Boolean
        get() = digits.length == PIN_LENGTH

    /**
     * Whether no digits have been entered.
     */
    val isEmpty: Boolean
        get() = digits.isEmpty()

    /**
     * Add a digit to the PIN.
     * Does nothing if PIN is already complete.
     */
    fun addDigit(digit: Char) {
        if (digits.length < PIN_LENGTH) {
            digits.append(digit)
        }
    }

    /**
     * Remove the last entered digit (backspace).
     * Does nothing if PIN is empty.
     */
    fun removeLastDigit() {
        if (digits.isNotEmpty()) {
            digits.deleteCharAt(digits.length - 1)
        }
    }

    /**
     * Get the complete PIN string.
     */
    fun getPin(): String = digits.toString()

    /**
     * Clear all entered digits.
     */
    fun clear() {
        digits.clear()
    }
}
