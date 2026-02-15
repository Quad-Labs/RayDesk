package com.raydesk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread

/**
 * Overlay view showing connection status during streaming.
 *
 * Three states:
 * - CONNECTING: "Connecting to SERVER..." (blue dot)
 * - RECONNECTING: "Reconnecting (1/3)..." (yellow dot)
 * - CONNECTED: Shows briefly then auto-hides after 2 seconds (green dot)
 *
 * Design compliance:
 * - Semi-transparent dark background when visible
 * - Centered status text (22sp white)
 * - Status dot color indicates state
 * - Minimal APL (only shown when needed)
 *
 * Status colors from design spec:
 * - Connected: Green (#0FC843)
 * - Reconnecting: Yellow (#F1C426)
 * - Connecting: Blue (#1E2DED)
 *
 * Usage:
 * ```kotlin
 * val overlay = findViewById<ConnectionOverlay>(R.id.connectionOverlay)
 *
 * // Show connecting state
 * overlay.setState(ConnectionOverlay.State.CONNECTING, serverName = "DESKTOP-PC")
 *
 * // Show reconnecting with progress
 * overlay.setState(ConnectionOverlay.State.RECONNECTING)
 * overlay.setReconnectProgress(1, 3)
 *
 * // Show connected (auto-hides after 2 seconds)
 * overlay.setState(ConnectionOverlay.State.CONNECTED, serverName = "DESKTOP-PC")
 *
 * // Manual hide
 * overlay.hide()
 * ```
 */
class ConnectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** State manager */
    private val state = ConnectionOverlayState()

    /** Status dot indicators for both eyes */
    private lateinit var leftStatusDot: View
    private lateinit var rightStatusDot: View

    /** Status text views for both eyes */
    private lateinit var leftStatusText: TextView
    private lateinit var rightStatusText: TextView

    // Convenience accessors for updating both eyes
    private val statusDot: View get() = leftStatusDot
    private val statusText: TextView get() = leftStatusText

    /** Handler for auto-hide timer */
    private val handler = Handler(Looper.getMainLooper())

    /** Auto-hide runnable */
    private val autoHideRunnable = Runnable { hide() }

    init {
        // Semi-transparent dark background
        setBackgroundColor(BACKGROUND_COLOR)
        visibility = View.GONE

        // Build binocular layout (content duplicated for both eyes)
        buildBinocularLayout()
    }

    /**
     * Build binocular layout with identical content in each eye region.
     * Display is 1280x480 (640x480 per eye).
     */
    private fun buildBinocularLayout() {
        // Horizontal container for both eyes
        val binocularContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        // Left eye content
        val (leftDot, leftText) = createEyeContent()
        leftStatusDot = leftDot
        leftStatusText = leftText
        binocularContainer.addView(createEyeContainer(leftDot, leftText))

        // Right eye content (identical copy)
        val (rightDot, rightText) = createEyeContent()
        rightStatusDot = rightDot
        rightStatusText = rightText
        binocularContainer.addView(createEyeContainer(rightDot, rightText))

        addView(binocularContainer)
    }

    /**
     * Create a container for one eye (640px wide).
     */
    private fun createEyeContainer(dot: View, text: TextView): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f  // Equal weight for both eyes
            )

            // Inner container for centered content
            val innerContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            innerContainer.addView(dot)
            innerContainer.addView(text)
            addView(innerContainer)
        }
    }

    /**
     * Create status dot and text for one eye.
     */
    private fun createEyeContent(): Pair<View, TextView> {
        val dot = View(context).apply {
            val size = dpToPx(STATUS_DOT_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dpToPx(STATUS_DOT_MARGIN_DP)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
        }

        val text = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        return Pair(dot, text)
    }

    /**
     * Set the overlay state.
     *
     * @param state The connection state to display
     * @param serverName Optional server name to include in status text
     */
    @MainThread
    fun setState(state: State, serverName: String? = null) {
        // Cancel any pending auto-hide
        handler.removeCallbacks(autoHideRunnable)

        this.state.setState(state.toInternalState(), serverName)
        updateDisplay()

        // Schedule auto-hide for CONNECTED state
        if (this.state.shouldAutoHide) {
            handler.postDelayed(autoHideRunnable, ConnectionOverlayState.AUTO_HIDE_DELAY_MS)
        }
    }

    /**
     * Set the reconnection progress for RECONNECTING state.
     *
     * @param attempt Current attempt number (1-based)
     * @param maxAttempts Maximum number of attempts
     */
    @MainThread
    fun setReconnectProgress(attempt: Int, maxAttempts: Int) {
        state.setReconnectProgress(attempt, maxAttempts)
        updateDisplay()
    }

    /**
     * Show the overlay.
     */
    @MainThread
    fun show() {
        state.show()
        updateDisplay()
    }

    /**
     * Hide the overlay.
     */
    @MainThread
    fun hide() {
        handler.removeCallbacks(autoHideRunnable)
        state.hide()
        updateDisplay()
    }

    /**
     * Update the visual display to match current state (both eyes).
     */
    private fun updateDisplay() {
        visibility = if (state.isVisible) View.VISIBLE else View.GONE

        val text = state.getStatusText()
        val dotColor = state.getStatusDotColor()
        val dotVisibility = if (dotColor != 0) View.VISIBLE else View.GONE

        // Update left eye
        leftStatusText.text = text
        (leftStatusDot.background as? GradientDrawable)?.setColor(dotColor)
        leftStatusDot.visibility = dotVisibility

        // Update right eye
        rightStatusText.text = text
        (rightStatusDot.background as? GradientDrawable)?.setColor(dotColor)
        rightStatusDot.visibility = dotVisibility
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

    /**
     * Connection states for the overlay.
     */
    enum class State {
        HIDDEN,
        CONNECTING,
        RECONNECTING,
        CONNECTED;

        internal fun toInternalState(): ConnectionOverlayState.State {
            return when (this) {
                HIDDEN -> ConnectionOverlayState.State.HIDDEN
                CONNECTING -> ConnectionOverlayState.State.CONNECTING
                RECONNECTING -> ConnectionOverlayState.State.RECONNECTING
                CONNECTED -> ConnectionOverlayState.State.CONNECTED
            }
        }
    }

    companion object {
        /** Background color: semi-transparent dark */
        const val BACKGROUND_COLOR = 0xCC000000.toInt()

        /** Status text size in sp */
        const val STATUS_TEXT_SIZE_SP = 22f

        /** Status dot size in dp */
        const val STATUS_DOT_SIZE_DP = 12

        /** Status dot margin in dp */
        const val STATUS_DOT_MARGIN_DP = 8
    }
}

/**
 * State class for ConnectionOverlay.
 *
 * Manages connection state, visibility, and display text for the overlay.
 * Separated from the View for testability.
 */
class ConnectionOverlayState {
    companion object {
        /** Auto-hide delay for CONNECTED state (2 seconds) */
        const val AUTO_HIDE_DELAY_MS = 2000L

        /** Status dot color for Connected state (green from design spec) */
        const val STATUS_COLOR_CONNECTED = 0xFF0FC843.toInt()

        /** Status dot color for Reconnecting state (yellow from design spec) */
        const val STATUS_COLOR_RECONNECTING = 0xFFF1C426.toInt()

        /** Status dot color for Connecting state (blue from design spec) */
        const val STATUS_COLOR_CONNECTING = 0xFF1E2DED.toInt()
    }

    /**
     * Connection states for the overlay.
     */
    enum class State {
        HIDDEN,
        CONNECTING,
        RECONNECTING,
        CONNECTED
    }

    /** Current connection state */
    var currentState: State = State.HIDDEN
        private set

    /** Whether the overlay is currently visible */
    var isVisible: Boolean = false
        private set

    /** Current server name (if any) */
    private var serverName: String? = null

    /** Current reconnection attempt */
    var currentAttempt: Int = 0
        private set

    /** Maximum reconnection attempts */
    var maxAttempts: Int = 0
        private set

    /** Whether the overlay should auto-hide */
    val shouldAutoHide: Boolean
        get() = currentState == State.CONNECTED

    /**
     * Set the overlay state.
     *
     * Server name behavior:
     * - If serverName is provided (non-null), it updates the server name
     * - If serverName is not provided (null default), the existing server name is preserved
     * - Use [clearServerName] to explicitly clear the server name
     *
     * @param state The connection state to display
     * @param serverName Optional server name to include in status text (preserves existing if null)
     */
    fun setState(state: State, serverName: String? = null) {
        // Clear reconnect progress when leaving RECONNECTING state
        if (currentState == State.RECONNECTING && state != State.RECONNECTING) {
            currentAttempt = 0
            maxAttempts = 0
        }

        currentState = state

        // Only update serverName if explicitly provided
        if (serverName != null) {
            this.serverName = serverName
        }

        isVisible = (state != State.HIDDEN)
    }

    /**
     * Clear the server name.
     */
    fun clearServerName() {
        this.serverName = null
    }

    /**
     * Set the reconnection progress for RECONNECTING state.
     *
     * @param attempt Current attempt number (1-based)
     * @param maxAttempts Maximum number of attempts
     */
    fun setReconnectProgress(attempt: Int, maxAttempts: Int) {
        val clampedMax = maxAttempts.coerceAtLeast(1)
        this.maxAttempts = clampedMax
        this.currentAttempt = attempt.coerceIn(1, clampedMax)
    }

    /**
     * Get the status text for the current state.
     *
     * @return Formatted status text
     */
    fun getStatusText(): String {
        return when (currentState) {
            State.HIDDEN -> ""
            State.CONNECTING -> {
                if (serverName != null) {
                    "Connecting to $serverName..."
                } else {
                    "Connecting..."
                }
            }
            State.RECONNECTING -> {
                if (currentAttempt > 0 && maxAttempts > 0) {
                    "Reconnecting ($currentAttempt/$maxAttempts)..."
                } else {
                    "Reconnecting..."
                }
            }
            State.CONNECTED -> {
                if (serverName != null) {
                    "Connected to $serverName"
                } else {
                    "Connected"
                }
            }
        }
    }

    /**
     * Get the status dot color for the current state.
     *
     * @return Color int, or 0 for HIDDEN state
     */
    fun getStatusDotColor(): Int {
        return when (currentState) {
            State.HIDDEN -> 0
            State.CONNECTING -> STATUS_COLOR_CONNECTING
            State.RECONNECTING -> STATUS_COLOR_RECONNECTING
            State.CONNECTED -> STATUS_COLOR_CONNECTED
        }
    }

    /**
     * Show the overlay.
     */
    fun show() {
        isVisible = true
    }

    /**
     * Hide the overlay.
     */
    fun hide() {
        isVisible = false
    }
}
