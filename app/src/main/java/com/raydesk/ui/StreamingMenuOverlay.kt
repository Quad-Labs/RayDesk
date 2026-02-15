package com.raydesk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread

/**
 * Menu overlay for streaming controls.
 *
 * Displays a quick menu with selectable items for:
 * - Lock View (toggle head tracking pause)
 * - Right Click (send right-click at cursor position)
 * - Exit Streaming (close streaming activity)
 *
 * Navigation:
 * - SlideForward/SlideBackward moves selection up/down
 * - Click executes selected item
 * - DoubleClick cancels (closes menu)
 *
 * Design follows RayNeo AR Design Specification:
 * - Colors: Blue (#1E2DED) for selected, white for normal
 * - Typography: H2 (24sp) title, H3 (20sp) items, H4 (18sp) hint
 * - Selected state: Blue fill + 110% scale
 * - Background: 50% black overlay
 */
class StreamingMenuOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        // RayNeo Design Spec Colors
        private const val COLOR_THEME_BLUE = 0xFF1E2DED.toInt()
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_BACKGROUND = 0x80000000.toInt()  // 50% black
        private const val COLOR_HINT = 0x80FFFFFF.toInt()  // 50% white

        // RayNeo Design Spec Typography (sp)
        private const val FONT_TITLE = 24f    // H2
        private const val FONT_ITEM = 20f     // H3
        private const val FONT_HINT = 18f     // H4

        // RayNeo Design Spec Layout
        private const val PADDING_BOUNDARY = 16  // Reserved boundary in dp
        private const val ITEM_PADDING_H = 24    // Horizontal padding in dp
        private const val ITEM_PADDING_V = 16    // Vertical padding in dp
        private const val ITEM_CORNER_RADIUS = 8f  // Corner radius in dp
        private const val ITEM_MARGIN_V = 8      // Vertical margin between items in dp
        private const val SELECTED_SCALE = 1.02f  // 102% for hover state (per RayNeo spec)
        private const val NORMAL_SCALE = 1.00f

        // Menu container width (fits in left eye FoV)
        private const val MENU_WIDTH_DP = 280
    }

    /**
     * Menu items available in the quick menu.
     *
     * INPUT_MODE: Toggle between Trackpad and Gesture input modes
     * TRACKPAD_SPEED: Cycle through trackpad sensitivity levels
     * ZOOM_SPEED: Cycle through zoom sensitivity levels
     * DISPLAY_MODE: Toggle between Floating Monitor and Keyhole Panning
     * RECENTER: Recenter the virtual screen (fixes 3DOF yaw drift)
     * HEAD_TRACKING: Toggle head-to-cursor movement
     * LOCK_ZOOM: Toggle zoom adjustments (keyhole mode only)
     * RIGHT_CLICK: Send right-click at cursor position
     * EXIT: Close streaming and return to connection screen
     */
    enum class MenuItem(val id: String, val label: String) {
        INPUT_MODE("input_mode", "Switch to Gesture Mode"),
        TRACKPAD_SPEED("trackpad_speed", "Trackpad Speed: Medium"),
        ZOOM_SPEED("zoom_speed", "Zoom Speed: Medium"),
        DISPLAY_MODE("display_mode", "Switch to Keyhole Mode"),
        RECENTER("recenter", "Recenter View"),
        HEAD_TRACKING("head_tracking", "Turn off Head Tracking"),
        LOCK_ZOOM("lock_zoom", "Lock Zoom"),
        RIGHT_CLICK("right_click", "Right Click"),
        EXIT("exit", "Exit Streaming")
    }

    /**
     * Speed levels for trackpad sensitivity.
     */
    enum class SpeedLevel(val label: String, val sensitivityMultiplier: Float) {
        SLOW("Slow", 0.3f),
        MEDIUM("Medium", 0.5f),
        FAST("Fast", 0.8f),
        VERY_FAST("Very Fast", 1.2f)
    }

    /**
     * Speed levels for zoom sensitivity.
     */
    enum class ZoomSpeedLevel(val label: String, val zoomStepMultiplier: Float) {
        SLOW("Slow", 0.5f),
        MEDIUM("Medium", 1.0f),
        FAST("Fast", 1.5f),
        VERY_FAST("Very Fast", 2.0f)
    }

    // State
    private var selectedIndex = 0
    private var isHeadTrackingEnabled = true
    private var isZoomLocked = false
    private var isFloatingMonitorMode = true  // Default display mode
    private var isTrackpadMode = true  // Default to trackpad mode
    private var trackpadSpeedLevel = SpeedLevel.MEDIUM  // Default speed
    private var zoomSpeedLevel = ZoomSpeedLevel.MEDIUM  // Default zoom speed

    // Menu item views for both eyes (left eye items for interaction, right eye for display)
    private val leftMenuItemViews = mutableListOf<View>()
    private val rightMenuItemViews = mutableListOf<View>()

    init {
        // Semi-transparent dark background
        setBackgroundColor(COLOR_BACKGROUND)
        visibility = View.GONE

        // Build binocular menu (duplicated for both eyes)
        buildBinocularMenu()
    }

    /**
     * Build the menu UI for binocular display (1280x480 = 640x480 per eye).
     * Creates identical menu content in both eye regions.
     */
    private fun buildBinocularMenu() {
        // Horizontal container for both eyes
        val binocularContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        // Left eye menu (primary - handles interaction)
        val leftEyeContainer = createEyeContainer()
        val leftMenuContent = createMenuContent(isLeftEye = true)
        leftEyeContainer.addView(leftMenuContent)
        binocularContainer.addView(leftEyeContainer)

        // Right eye menu (mirror - display only)
        val rightEyeContainer = createEyeContainer()
        val rightMenuContent = createMenuContent(isLeftEye = false)
        rightEyeContainer.addView(rightMenuContent)
        binocularContainer.addView(rightEyeContainer)

        addView(binocularContainer)

        // Initialize selection on both eyes
        updateSelection()
    }

    /**
     * Create a container for one eye (640x480).
     */
    private fun createEyeContainer(): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f  // Equal weight for both eyes
            )
        }
    }

    /**
     * Create the menu content for one eye.
     */
    private fun createMenuContent(isLeftEye: Boolean): LinearLayout {
        // Outer container centered in eye region
        val outerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Disable clipping so scaled items and borders are fully visible
            clipChildren = false
            clipToPadding = false
        }

        // Menu container with fixed width
        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val width = dpToPx(MENU_WIDTH_DP)
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dpToPx(PADDING_BOUNDARY), dpToPx(PADDING_BOUNDARY),
                       dpToPx(PADDING_BOUNDARY), dpToPx(PADDING_BOUNDARY))
            // Disable clipping so scaled items and borders are fully visible
            clipChildren = false
            clipToPadding = false
        }

        // Title
        val titleView = TextView(context).apply {
            text = "Quick Menu"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_TITLE)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }
        menuContainer.addView(titleView)

        // Menu items
        val itemViews = if (isLeftEye) leftMenuItemViews else rightMenuItemViews
        MenuItem.values().forEachIndexed { index, item ->
            val itemView = createMenuItemView(item, index)
            itemViews.add(itemView)
            menuContainer.addView(itemView)
        }

        // Footer hint
        val hintView = TextView(context).apply {
            text = "Click select · Double-click cancel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_HINT)
            setTextColor(COLOR_HINT)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
        }
        menuContainer.addView(hintView)

        outerContainer.addView(menuContainer)
        return outerContainer
    }

    /**
     * Create a single menu item view.
     */
    private fun createMenuItemView(item: MenuItem, index: Int): View {
        return TextView(context).apply {
            text = getItemDisplayText(item)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_ITEM)
            setTextColor(COLOR_WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V),
                       dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V))

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (index > 0) dpToPx(ITEM_MARGIN_V) else 0
                // Horizontal margins so borders are visible on sides
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }

            // Initial background (unselected state)
            background = createItemBackground(isSelected = false)
        }
    }

    /**
     * Get display text for a menu item, considering current state.
     */
    private fun getItemDisplayText(item: MenuItem): String {
        return when (item) {
            MenuItem.INPUT_MODE -> if (isTrackpadMode) "Switch to Gesture Mode" else "Switch to Trackpad Mode"
            MenuItem.TRACKPAD_SPEED -> "Trackpad Speed: ${trackpadSpeedLevel.label}"
            MenuItem.ZOOM_SPEED -> "Zoom Speed: ${zoomSpeedLevel.label}"
            MenuItem.DISPLAY_MODE -> if (isFloatingMonitorMode) "Switch to Keyhole Mode" else "Switch to Floating Monitor"
            MenuItem.RECENTER -> "Recenter View"
            MenuItem.HEAD_TRACKING -> if (isHeadTrackingEnabled) "Turn off Head Tracking" else "Turn on Head Tracking"
            MenuItem.LOCK_ZOOM -> if (isZoomLocked) "Unlock Zoom" else "Lock Zoom"
            else -> item.label
        }
    }

    /**
     * Create background drawable for menu item.
     *
     * - Selected: Dark fill (#0D0D0D) + blue outline (2px)
     * - Normal: Transparent + gray outline (1px)
     *
     * @param isSelected Whether the item is currently selected
     */
    private fun createItemBackground(isSelected: Boolean): Drawable {
        return GradientDrawable().apply {
            cornerRadius = dpToPx(ITEM_CORNER_RADIUS.toInt()).toFloat()
            if (isSelected) {
                // Selected: Dark fill with blue outline
                setColor(0xFF0D0D0D.toInt())  // 95% black
                setStroke(dpToPx(2), COLOR_THEME_BLUE)  // Blue outline
            } else {
                // Normal: Transparent with gray outline
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), 0xFF808080.toInt())  // Gray outline
            }
        }
    }

    /**
     * Update the visual selection state of all menu items (both eyes).
     */
    private fun updateSelection() {
        // Update left eye items
        leftMenuItemViews.forEachIndexed { index, view ->
            applySelectionStyle(view, index == selectedIndex)
        }
        // Update right eye items (mirror)
        rightMenuItemViews.forEachIndexed { index, view ->
            applySelectionStyle(view, index == selectedIndex)
        }
    }

    /**
     * Apply selection style to a menu item view.
     */
    private fun applySelectionStyle(view: View, isSelected: Boolean) {
        view.background = createItemBackground(isSelected)
        view.scaleX = if (isSelected) SELECTED_SCALE else NORMAL_SCALE
        view.scaleY = if (isSelected) SELECTED_SCALE else NORMAL_SCALE
    }

    /**
     * Update menu item text to reflect current state (both eyes).
     */
    private fun updateMenuItemText(item: MenuItem) {
        val index = item.ordinal
        val displayText = getItemDisplayText(item)

        // Update left eye
        if (index < leftMenuItemViews.size) {
            (leftMenuItemViews[index] as? TextView)?.text = displayText
        }
        // Update right eye
        if (index < rightMenuItemViews.size) {
            (rightMenuItemViews[index] as? TextView)?.text = displayText
        }
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /**
     * Show the menu overlay.
     * Starts with Recenter selected (most common action for drift correction).
     */
    @MainThread
    fun show() {
        visibility = View.VISIBLE
        selectedIndex = MenuItem.RECENTER.ordinal
        updateSelection()
    }

    /**
     * Hide the menu overlay.
     */
    @MainThread
    fun hide() {
        visibility = View.GONE
    }

    /**
     * Check if the menu is currently visible.
     */
    fun isMenuVisible(): Boolean = visibility == View.VISIBLE

    /**
     * Move selection up (wrap around).
     */
    @MainThread
    fun moveSelectionUp() {
        selectedIndex = (selectedIndex - 1 + MenuItem.values().size) % MenuItem.values().size
        updateSelection()
    }

    /**
     * Move selection down (wrap around).
     */
    @MainThread
    fun moveSelectionDown() {
        selectedIndex = (selectedIndex + 1) % MenuItem.values().size
        updateSelection()
    }

    /**
     * Get the currently selected menu item.
     */
    fun selectCurrent(): MenuItem {
        return MenuItem.values()[selectedIndex]
    }

    /**
     * Update the head tracking state (changes "Turn off Head Tracking" to "Turn on Head Tracking").
     *
     * @param enabled Whether head tracking is currently enabled
     */
    @MainThread
    fun updateHeadTrackingState(enabled: Boolean) {
        isHeadTrackingEnabled = enabled
        updateMenuItemText(MenuItem.HEAD_TRACKING)
    }

    /**
     * Update the zoom lock state (changes "Lock Zoom" to "Unlock Zoom").
     *
     * @param locked Whether zoom is currently locked
     */
    @MainThread
    fun updateZoomLockState(locked: Boolean) {
        isZoomLocked = locked
        updateMenuItemText(MenuItem.LOCK_ZOOM)
    }

    /**
     * Update the display mode state (changes "Switch to Keyhole Mode" to "Switch to Floating Monitor").
     *
     * @param isFloatingMonitor Whether floating monitor mode is currently active
     */
    @MainThread
    fun updateDisplayModeState(isFloatingMonitor: Boolean) {
        isFloatingMonitorMode = isFloatingMonitor
        updateMenuItemText(MenuItem.DISPLAY_MODE)
    }

    /**
     * Update the input mode state (changes "Switch to Gesture Mode" to "Switch to Trackpad Mode").
     *
     * @param isTrackpad Whether trackpad mode is currently active
     */
    @MainThread
    fun updateInputModeState(isTrackpad: Boolean) {
        isTrackpadMode = isTrackpad
        updateMenuItemText(MenuItem.INPUT_MODE)
    }

    /**
     * Cycle to the next trackpad speed level and update the menu.
     * Returns the new speed level for the caller to apply.
     */
    @MainThread
    fun cycleTrackpadSpeed(): SpeedLevel {
        val levels = SpeedLevel.values()
        val currentIndex = levels.indexOf(trackpadSpeedLevel)
        val nextIndex = (currentIndex + 1) % levels.size
        trackpadSpeedLevel = levels[nextIndex]
        updateMenuItemText(MenuItem.TRACKPAD_SPEED)
        return trackpadSpeedLevel
    }

    /**
     * Update the trackpad speed state to a specific level.
     */
    @MainThread
    fun updateTrackpadSpeedState(level: SpeedLevel) {
        trackpadSpeedLevel = level
        updateMenuItemText(MenuItem.TRACKPAD_SPEED)
    }

    /**
     * Get the current trackpad speed level.
     */
    fun getTrackpadSpeedLevel(): SpeedLevel = trackpadSpeedLevel

    /**
     * Cycle to the next zoom speed level and update the menu.
     * Returns the new speed level for the caller to apply.
     */
    @MainThread
    fun cycleZoomSpeed(): ZoomSpeedLevel {
        val levels = ZoomSpeedLevel.values()
        val currentIndex = levels.indexOf(zoomSpeedLevel)
        val nextIndex = (currentIndex + 1) % levels.size
        zoomSpeedLevel = levels[nextIndex]
        updateMenuItemText(MenuItem.ZOOM_SPEED)
        return zoomSpeedLevel
    }

    /**
     * Update the zoom speed state to a specific level.
     */
    @MainThread
    fun updateZoomSpeedState(level: ZoomSpeedLevel) {
        zoomSpeedLevel = level
        updateMenuItemText(MenuItem.ZOOM_SPEED)
    }

    /**
     * Get the current zoom speed level.
     */
    fun getZoomSpeedLevel(): ZoomSpeedLevel = zoomSpeedLevel

    /**
     * Get current selection index (for testing).
     */
    fun getSelectedIndex(): Int = selectedIndex

    // ========================================================================
    // UTILITIES
    // ========================================================================

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
}
