package com.raydesk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MainThread

/**
 * Game-style settings menu for AR glasses streaming.
 *
 * Two-panel layout following RayNeo Design Specification:
 * - Left sidebar (40%): Action buttons + Settings categories
 * - Right panel (60%): Options for selected category
 *
 * Navigation (temple touchpad):
 * - Swipe up/down: Navigate items
 * - Click: Select action or toggle option
 * - Swipe right: Enter category options
 * - Swipe left: Go back to sidebar / Close menu
 * - Double-click: Close menu
 *
 * Design spec colors:
 * - Theme blue: #1E2DED (selected/hover)
 * - Normal outline: #808080
 * - Text: #FFFFFF
 * - Background: #000000 80% opacity
 */
class GameStyleMenuOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        // RayNeo Design Spec Colors
        private const val COLOR_THEME_BLUE = 0xFF1E2DED.toInt()
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_GRAY = 0xFF808080.toInt()
        private const val COLOR_BACKGROUND = 0xCC000000.toInt()  // 80% black
        private const val COLOR_CURRENT_VALUE = 0xFF1E2DED.toInt()  // Blue fill for current

        // Typography (sp)
        private const val FONT_TITLE = 24f      // H2 - panel titles
        private const val FONT_ITEM = 20f       // H3 - menu items
        private const val FONT_SECTION = 18f    // H4 - section headers
        private const val FONT_HINT = 16f       // Hint text

        // Layout
        private const val RESERVED_BOUNDARY = 16
        private const val SIDEBAR_WEIGHT = 0.4f
        private const val PANEL_WEIGHT = 0.6f
        private const val ITEM_PADDING_H = 20
        private const val ITEM_PADDING_V = 14
        private const val ITEM_MARGIN_V = 6
        private const val CORNER_RADIUS = 8f
        private const val SELECTED_SCALE = 1.02f
    }

    // ========================================================================
    // DATA STRUCTURES
    // ========================================================================

    /** Types of sidebar items */
    enum class SidebarItemType {
        ACTION,     // Immediate action (Right Click, Exit)
        CATEGORY    // Opens options panel (Display, Input, Zoom)
    }

    /** Sidebar menu items */
    enum class SidebarItem(
        val id: String,
        val label: String,
        val type: SidebarItemType,
        val section: String  // "ACTIONS" or "SETTINGS"
    ) {
        RIGHT_CLICK("right_click", "Right Click", SidebarItemType.ACTION, "ACTIONS"),
        SWITCH_MONITOR("switch_monitor", "Switch Monitor", SidebarItemType.CATEGORY, "ACTIONS"),
        EXIT("exit", "Exit", SidebarItemType.ACTION, "ACTIONS"),
        DISPLAY("display", "Display", SidebarItemType.CATEGORY, "SETTINGS"),
        INPUT("input", "Input", SidebarItemType.CATEGORY, "SETTINGS"),
        ENVIRONMENT("environment", "Environment", SidebarItemType.CATEGORY, "SETTINGS")
    }

    /** Display mode options */
    enum class DisplayModeOption(val label: String) {
        FLOATING("Floating Monitor"),
        KEYHOLE("Keyhole Panning"),
        CURVED("Curved Monitor")
    }

    /** Input mode options */
    enum class InputModeOption(val label: String) {
        TRACKPAD("Trackpad Mode"),
        GESTURE("Gesture Mode")
    }

    /** Speed level options (for trackpad and zoom) */
    enum class SpeedOption(val label: String) {
        SLOW("Slow"),
        MEDIUM("Medium"),
        FAST("Fast"),
        VERY_FAST("Very Fast")
    }

    /** Cursor tracking options - controls viewport panning when zoomed in */
    enum class CursorTrackingOption(val label: String) {
        ON("On"),
        OFF("Off")
    }

    /** Environment enable/disable */
    enum class EnvironmentOption(val label: String) {
        ON("On"),
        OFF("Off")
    }

    /** Environment themes */
    enum class EnvironmentThemeOption(val id: String, val label: String) {
        BLUE("blue_transparent", "Blue Transparent"),
        STARRY("starry_night", "Starry Night"),
        SUNSET("sunset", "Sunset")
    }

    /** Navigation focus state */
    enum class FocusPanel {
        SIDEBAR,
        OPTIONS
    }

    // ========================================================================
    // STATE
    // ========================================================================

    private var focusPanel = FocusPanel.SIDEBAR
    private var sidebarSelectedIndex = 0  // Default to Right Click
    private var optionSelectedIndex = 0

    // Current settings values
    var currentDisplayMode = DisplayModeOption.FLOATING
    var currentInputMode = InputModeOption.TRACKPAD
    var currentTrackpadSpeed = SpeedOption.MEDIUM
    var currentHeadTrackingSpeed = SpeedOption.MEDIUM
    var currentCursorTracking = CursorTrackingOption.ON
    var isZoomLocked = false
    var currentZoomLevel = 1.0f  // 1.0 = 100%, range 0.5 to 3.0
    var currentEnvironmentEnabled = EnvironmentOption.OFF
    var currentEnvironmentTheme = EnvironmentThemeOption.BLUE

    // Zoom adjustment mode state
    private var isZoomAdjustmentMode = false

    // Continuous zoom sensitivity
    private val ZOOM_SWIPE_SENSITIVITY = 0.0008f  // Reduced for smoother control

    // Magnetic detent settings - strong snap at 25% increments
    private val ZOOM_DETENT_INTERVAL = 0.25f      // Detents at 25% increments (0.75, 1.00, 1.25, etc.)
    private val ZOOM_CAPTURE_ZONE = 0.08f         // ±8% capture zone (stronger)
    private val ZOOM_SNAP_THRESHOLD = 0.03f       // Snap when within 3%
    private val ZOOM_DAMPING_FACTOR = 0.08f       // 8% speed in capture zone (much slower)
    private val ZOOM_ESCAPE_THRESHOLD = 120f      // Need more momentum to escape (was 60)
    private var zoomEscapeMomentum = 0f           // Accumulated momentum to escape detent
    private var isZoomAtDetent = false            // Currently stuck at a detent

    // Callbacks
    var onRightClick: (() -> Unit)? = null
    var onMonitorSwitch: ((Int) -> Unit)? = null  // Monitor number (1-4)
    var onExit: (() -> Unit)? = null
    var onDisplayModeChanged: ((DisplayModeOption) -> Unit)? = null
    var onInputModeChanged: ((InputModeOption) -> Unit)? = null
    var onTrackpadSpeedChanged: ((SpeedOption) -> Unit)? = null
    var onHeadTrackingSpeedChanged: ((SpeedOption) -> Unit)? = null
    var onCursorTrackingChanged: ((CursorTrackingOption) -> Unit)? = null
    var onZoomLockChanged: ((Boolean) -> Unit)? = null
    var onRecenter: (() -> Unit)? = null
    var onZoomLevelChanged: ((Float) -> Unit)? = null
    var onZoomLevelConfirmed: ((Float) -> Unit)? = null  // Called on finger lift
    var onEnvironmentEnabledChanged: ((EnvironmentOption) -> Unit)? = null
    var onEnvironmentThemeChanged: ((EnvironmentThemeOption) -> Unit)? = null

    // ========================================================================
    // UI COMPONENTS
    // ========================================================================

    // Binocular containers (left eye, right eye)
    private lateinit var leftEyeContainer: LinearLayout
    private lateinit var rightEyeContainer: LinearLayout

    // Left eye components (primary interaction)
    private val leftSidebarItems = mutableListOf<View>()
    private var leftOptionsPanel: LinearLayout? = null
    private val leftOptionItems = mutableListOf<View>()
    private var leftPanelTitle: TextView? = null

    // Right eye components (mirror)
    private val rightSidebarItems = mutableListOf<View>()
    private var rightOptionsPanel: LinearLayout? = null
    private val rightOptionItems = mutableListOf<View>()
    private var rightPanelTitle: TextView? = null

    // Scroll views for overflow handling
    private var leftSidebarScroll: ScrollView? = null
    private var rightSidebarScroll: ScrollView? = null
    private var leftOptionsScroll: ScrollView? = null
    private var rightOptionsScroll: ScrollView? = null
    private var leftOptionsInner: LinearLayout? = null
    private var rightOptionsInner: LinearLayout? = null

    // Zoom adjustment overlay components
    private var leftZoomOverlay: FrameLayout? = null
    private var rightZoomOverlay: FrameLayout? = null
    private var leftZoomPercentText: TextView? = null
    private var rightZoomPercentText: TextView? = null
    private var leftZoomHintText: TextView? = null
    private var rightZoomHintText: TextView? = null
    private var leftZoomStatusBar: TextView? = null
    private var rightZoomStatusBar: TextView? = null

    // Normal background color for restoration
    private val COLOR_BACKGROUND_NORMAL = COLOR_BACKGROUND
    private val COLOR_BACKGROUND_TRANSPARENT = 0x4D000000.toInt()  // 30% black

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        visibility = View.GONE
        buildBinocularUI()
    }

    private fun buildBinocularUI() {
        // Root horizontal layout for binocular display
        val binocularRoot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Left eye (640x480)
        leftEyeContainer = createEyeLayout(isLeftEye = true)
        binocularRoot.addView(leftEyeContainer)

        // Right eye (640x480)
        rightEyeContainer = createEyeLayout(isLeftEye = false)
        binocularRoot.addView(rightEyeContainer)

        addView(binocularRoot)

        // Add zoom adjustment overlays (initially hidden)
        createZoomAdjustmentOverlays()

        updateSidebarSelection()
    }

    private fun createZoomAdjustmentOverlays() {
        // Left eye zoom overlay
        leftZoomOverlay = createZoomOverlay(isLeftEye = true)
        addView(leftZoomOverlay)

        // Right eye zoom overlay
        rightZoomOverlay = createZoomOverlay(isLeftEye = false)
        addView(rightZoomOverlay)
    }

    private fun createZoomOverlay(isLeftEye: Boolean): FrameLayout {
        // Each eye is 640 pixels wide on the 1280x480 binocular display
        // Note: LayoutParams.MATCH_PARENT / 2 = -1 / 2 = 0 (wrong!)
        val perEyeWidth = 640
        val overlay = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                perEyeWidth,
                LayoutParams.MATCH_PARENT
            ).apply {
                // Position for left or right eye
                gravity = if (isLeftEye) Gravity.START else Gravity.END
            }
            visibility = View.GONE
        }

        // Status bar at top
        val statusBar = TextView(context).apply {
            text = "ADJUSTING ZOOM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_SECTION)
            setTextColor(COLOR_WHITE)
            setBackgroundColor(COLOR_THEME_BLUE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }
        overlay.addView(statusBar)

        // Container for centered content
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Large percentage text
        val percentText = TextView(context).apply {
            text = "100%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTextColor(COLOR_THEME_BLUE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        contentContainer.addView(percentText)

        // Swipe direction hint
        val swipeHint = TextView(context).apply {
            text = "◀ Swipe Left = Zoom Out    Swipe Right = Zoom In ▶"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_HINT)
            setTextColor(COLOR_WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(20)
            }
        }
        contentContainer.addView(swipeHint)

        // Confirm hint
        val hintText = TextView(context).apply {
            text = "Double-click to confirm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_ITEM)
            setTextColor(COLOR_THEME_BLUE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }
        contentContainer.addView(hintText)

        overlay.addView(contentContainer)

        // Store references
        if (isLeftEye) {
            leftZoomPercentText = percentText
            leftZoomHintText = hintText
            leftZoomStatusBar = statusBar
        } else {
            rightZoomPercentText = percentText
            rightZoomHintText = hintText
            rightZoomStatusBar = statusBar
        }

        return overlay
    }

    private fun createEyeLayout(isLeftEye: Boolean): LinearLayout {
        val eyeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(dpToPx(RESERVED_BOUNDARY), dpToPx(RESERVED_BOUNDARY),
                       dpToPx(RESERVED_BOUNDARY), dpToPx(RESERVED_BOUNDARY))
            clipChildren = false
            clipToPadding = false
        }

        // Left sidebar
        val sidebar = createSidebar(isLeftEye)
        eyeContainer.addView(sidebar)

        // Right options panel
        val optionsPanel = createOptionsPanel(isLeftEye)
        eyeContainer.addView(optionsPanel)

        return eyeContainer
    }

    private fun createSidebar(isLeftEye: Boolean): LinearLayout {
        val sidebar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, SIDEBAR_WEIGHT)
            setPadding(0, 0, dpToPx(8), 0)
            clipChildren = false
            clipToPadding = false
        }

        // Non-interactive ScrollView: scrolling is programmatic via scrollToVisible().
        // Must not intercept touch events or Mercury SDK temple gestures break.
        val scrollView = createNonInteractiveScrollView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val itemList = if (isLeftEye) leftSidebarItems else rightSidebarItems
        var currentSection = ""

        SidebarItem.values().forEachIndexed { index, item ->
            if (item.section != currentSection) {
                currentSection = item.section
                val header = createSectionHeader(item.section)
                innerLayout.addView(header)
            }

            val itemView = createSidebarItemView(item, index)
            itemList.add(itemView)
            innerLayout.addView(itemView)
        }

        // Add hint at bottom
        val hint = createHintView()
        val hintParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(16)
        }
        hint.layoutParams = hintParams
        innerLayout.addView(hint)

        scrollView.addView(innerLayout)
        sidebar.addView(scrollView)

        if (isLeftEye) leftSidebarScroll = scrollView
        else rightSidebarScroll = scrollView

        return sidebar
    }

    private fun createSectionHeader(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_SECTION)
            setTextColor(COLOR_GRAY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(4)
            }
            setPadding(dpToPx(8), 0, 0, 0)
        }
    }

    private fun createSidebarItemView(item: SidebarItem, index: Int): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(ITEM_MARGIN_V)
            }
            setPadding(dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V),
                       dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V))
            background = createItemBackground(isSelected = false)

            // Label
            val label = TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_ITEM)
                setTextColor(COLOR_WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            // Chevron for categories
            if (item.type == SidebarItemType.CATEGORY) {
                val chevron = TextView(context).apply {
                    text = "›"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_ITEM + 4)
                    setTextColor(COLOR_GRAY)
                    setPadding(dpToPx(8), 0, 0, 0)
                }
                addView(chevron)
            }

            tag = item  // Store item reference
        }
    }

    private fun createHintView(): TextView {
        return TextView(context).apply {
            text = "⬆⬇ Navigate • Click Select • ⬅ Close"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_HINT)
            setTextColor(COLOR_GRAY)
            gravity = Gravity.CENTER
        }
    }

    private fun createOptionsPanel(isLeftEye: Boolean): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, PANEL_WEIGHT)
            setPadding(dpToPx(16), 0, 0, 0)
            visibility = View.INVISIBLE  // Hidden until category selected
            clipChildren = false
            clipToPadding = false
        }

        // Panel title (fixed, doesn't scroll)
        val title = TextView(context).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_TITLE)
            setTextColor(COLOR_WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }
        panel.addView(title)

        // Non-interactive ScrollView: scrolling is programmatic via scrollToVisible().
        // Must not intercept touch events or Mercury SDK temple gestures break.
        val scrollView = createNonInteractiveScrollView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f  // Take remaining space after title
            )
            clipChildren = false
            clipToPadding = false
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(innerLayout)
        panel.addView(scrollView)

        if (isLeftEye) {
            leftOptionsPanel = panel
            leftPanelTitle = title
            leftOptionsScroll = scrollView
            leftOptionsInner = innerLayout
        } else {
            rightOptionsPanel = panel
            rightPanelTitle = title
            rightOptionsScroll = scrollView
            rightOptionsInner = innerLayout
        }

        return panel
    }

    // ========================================================================
    // OPTIONS PANEL CONTENT
    // ========================================================================

    private fun showOptionsForCategory(category: SidebarItem, preserveSelection: Boolean = false) {
        val previousIndex = optionSelectedIndex

        leftOptionItems.clear()
        rightOptionItems.clear()
        leftOptionsInner?.removeAllViews()
        rightOptionsInner?.removeAllViews()

        val title: String
        val options: List<Pair<String, Boolean>>  // label, isCurrentValue

        when (category) {
            SidebarItem.DISPLAY -> {
                title = "Display Settings"
                options = buildDisplayOptions()
            }
            SidebarItem.INPUT -> {
                title = "Input Settings"
                options = buildInputOptions()
            }
            SidebarItem.ENVIRONMENT -> {
                title = "Environment Settings"
                options = buildEnvironmentOptions()
            }
            SidebarItem.SWITCH_MONITOR -> {
                title = "Switch Monitor"
                options = buildMonitorOptions()
            }
            else -> return
        }

        // Update titles
        leftPanelTitle?.text = title
        rightPanelTitle?.text = title

        // Create option items
        options.forEachIndexed { index, (label, isCurrent) ->
            val leftOption = createOptionItemView(label, isCurrent, index)
            leftOptionItems.add(leftOption)
            leftOptionsInner?.addView(leftOption)

            val rightOption = createOptionItemView(label, isCurrent, index)
            rightOptionItems.add(rightOption)
            rightOptionsInner?.addView(rightOption)
        }

        // Show panels
        leftOptionsPanel?.visibility = View.VISIBLE
        rightOptionsPanel?.visibility = View.VISIBLE

        // Set option selection - preserve if requested, otherwise reset to 0
        optionSelectedIndex = if (preserveSelection) {
            previousIndex.coerceIn(0, options.size - 1)
        } else {
            0
        }
        updateOptionSelection()
    }

    private fun buildDisplayOptions(): List<Pair<String, Boolean>> {
        val options = mutableListOf<Pair<String, Boolean>>()

        // Display mode
        options.add("Mode: ${currentDisplayMode.label}" to false)

        // Zoom level (consolidated from Zoom menu)
        val zoomPercent = (currentZoomLevel * 100).toInt()
        options.add("Manage Zoom Level ($zoomPercent%)" to false)

        return options
    }

    private fun buildInputOptions(): List<Pair<String, Boolean>> {
        val options = mutableListOf<Pair<String, Boolean>>()

        val isKeyhole = (currentDisplayMode == DisplayModeOption.KEYHOLE)
        val isGesture = (currentInputMode == InputModeOption.GESTURE)

        if (isKeyhole) {
            // Keyhole: allow both modes
            options.add("Mode: ${currentInputMode.label}" to false)

            if (isGesture) {
                // Gesture mode: show Zoom Lock + Head Tracking speed, hide Trackpad speed
                options.add("Zoom Lock: ${if (isZoomLocked) "On" else "Off"}" to false)
                options.add("Head Tracking: ${currentHeadTrackingSpeed.label}" to false)
            } else {
                // Trackpad mode: show Trackpad speed, hide Head Tracking
                options.add("Trackpad: ${currentTrackpadSpeed.label}" to false)
            }
        } else {
            // Floating/Curved: show Trackpad active, Gesture greyed out
            options.add("Mode: Trackpad Mode" to false)
            options.add("⚫ Gesture Mode (Keyhole only)" to false)
            options.add("Trackpad: ${currentTrackpadSpeed.label}" to false)
        }

        // Cursor tracking (viewport follows cursor when zoomed in)
        options.add("Cursor Tracking: ${currentCursorTracking.label}" to false)

        // Recenter
        options.add("Recenter View" to false)

        return options
    }

    private fun buildEnvironmentOptions(): List<Pair<String, Boolean>> {
        val options = mutableListOf<Pair<String, Boolean>>()

        // Enable toggle
        options.add("Enable: ${currentEnvironmentEnabled.label}" to false)

        // Theme selector (only show if enabled)
        if (currentEnvironmentEnabled == EnvironmentOption.ON) {
            options.add("Theme: ${currentEnvironmentTheme.label}" to false)
        }

        return options
    }

    private fun buildMonitorOptions(): List<Pair<String, Boolean>> {
        // Show monitors 1-4 (covers 99% of setups)
        // We cannot detect how many monitors the host has, so we show a fixed list
        return listOf(
            "Monitor 1" to false,
            "Monitor 2" to false,
            "Monitor 3" to false,
            "Monitor 4" to false
        )
    }

    private fun createOptionItemView(label: String, isCurrent: Boolean, index: Int): View {
        // Check if this is a disabled/greyed out item (indicated by ⚫ prefix)
        val isDisabled = label.startsWith("⚫")

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(ITEM_MARGIN_V)
            }
            setPadding(dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V),
                       dpToPx(ITEM_PADDING_H), dpToPx(ITEM_PADDING_V))
            background = when {
                isDisabled -> createDisabledItemBackground()
                isCurrent -> createCurrentValueBackground()
                else -> createItemBackground(false)
            }

            // Current value indicator
            if (isCurrent) {
                val bullet = TextView(context).apply {
                    text = "●"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(COLOR_WHITE)
                    setPadding(0, 0, dpToPx(8), 0)
                }
                addView(bullet)
            }

            // Label - grey text for disabled items
            val labelView = TextView(context).apply {
                text = if (isDisabled) label.removePrefix("⚫ ") else label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_ITEM)
                setTextColor(if (isDisabled) COLOR_GRAY else COLOR_WHITE)
            }
            addView(labelView)

            tag = index
        }
    }

    private fun hideOptionsPanel() {
        leftOptionsPanel?.visibility = View.INVISIBLE
        rightOptionsPanel?.visibility = View.INVISIBLE
    }

    // ========================================================================
    // BACKGROUND DRAWABLES
    // ========================================================================

    private fun createItemBackground(isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dpToPx(CORNER_RADIUS.toInt()).toFloat()
            if (isSelected) {
                setColor(0xFF0D0D0D.toInt())
                setStroke(dpToPx(2), COLOR_THEME_BLUE)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), COLOR_GRAY)
            }
        }
    }

    private fun createCurrentValueBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dpToPx(CORNER_RADIUS.toInt()).toFloat()
            setColor(COLOR_CURRENT_VALUE)
            setStroke(dpToPx(2), COLOR_THEME_BLUE)
        }
    }

    private fun createDisabledItemBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dpToPx(CORNER_RADIUS.toInt()).toFloat()
            setColor(0xFF1A1A1A.toInt())  // Slightly darker than normal
            setStroke(dpToPx(1), 0xFF404040.toInt())  // Darker grey stroke
        }
    }

    // ========================================================================
    // SELECTION UPDATES
    // ========================================================================

    private fun updateSidebarSelection() {
        leftSidebarItems.forEachIndexed { index, view ->
            applyItemSelection(view, index == sidebarSelectedIndex && focusPanel == FocusPanel.SIDEBAR)
        }
        rightSidebarItems.forEachIndexed { index, view ->
            applyItemSelection(view, index == sidebarSelectedIndex && focusPanel == FocusPanel.SIDEBAR)
        }
        if (focusPanel == FocusPanel.SIDEBAR && leftSidebarItems.isNotEmpty()) {
            scrollToVisible(leftSidebarScroll, leftSidebarItems[sidebarSelectedIndex])
            scrollToVisible(rightSidebarScroll, rightSidebarItems[sidebarSelectedIndex])
        }
    }

    private fun updateOptionSelection() {
        leftOptionItems.forEachIndexed { index, view ->
            applyItemSelection(view, index == optionSelectedIndex && focusPanel == FocusPanel.OPTIONS)
        }
        rightOptionItems.forEachIndexed { index, view ->
            applyItemSelection(view, index == optionSelectedIndex && focusPanel == FocusPanel.OPTIONS)
        }
        if (focusPanel == FocusPanel.OPTIONS && leftOptionItems.isNotEmpty()) {
            scrollToVisible(leftOptionsScroll, leftOptionItems[optionSelectedIndex])
            scrollToVisible(rightOptionsScroll, rightOptionItems[optionSelectedIndex])
        }
    }

    private fun applyItemSelection(view: View, isSelected: Boolean) {
        view.background = createItemBackground(isSelected)
        view.scaleX = if (isSelected) SELECTED_SCALE else 1f
        view.scaleY = if (isSelected) SELECTED_SCALE else 1f
    }

    // ========================================================================
    // PUBLIC API - SHOW/HIDE
    // ========================================================================

    @MainThread
    fun show() {
        visibility = View.VISIBLE
        focusPanel = FocusPanel.SIDEBAR
        sidebarSelectedIndex = 0  // Default to Right Click
        hideOptionsPanel()
        updateSidebarSelection()
    }

    @MainThread
    fun hide() {
        visibility = View.GONE
    }

    fun isMenuVisible(): Boolean = visibility == View.VISIBLE

    // ========================================================================
    // PUBLIC API - NAVIGATION
    // ========================================================================

    @MainThread
    fun moveUp() {
        when (focusPanel) {
            FocusPanel.SIDEBAR -> {
                sidebarSelectedIndex = (sidebarSelectedIndex - 1 + SidebarItem.values().size) % SidebarItem.values().size
                updateSidebarSelection()
            }
            FocusPanel.OPTIONS -> {
                if (leftOptionItems.isNotEmpty()) {
                    optionSelectedIndex = (optionSelectedIndex - 1 + leftOptionItems.size) % leftOptionItems.size
                    updateOptionSelection()
                }
            }
        }
    }

    @MainThread
    fun moveDown() {
        when (focusPanel) {
            FocusPanel.SIDEBAR -> {
                sidebarSelectedIndex = (sidebarSelectedIndex + 1) % SidebarItem.values().size
                updateSidebarSelection()
            }
            FocusPanel.OPTIONS -> {
                if (leftOptionItems.isNotEmpty()) {
                    optionSelectedIndex = (optionSelectedIndex + 1) % leftOptionItems.size
                    updateOptionSelection()
                }
            }
        }
    }

    @MainThread
    fun moveRight(): Boolean {
        if (focusPanel == FocusPanel.SIDEBAR) {
            val selectedItem = SidebarItem.values()[sidebarSelectedIndex]
            if (selectedItem.type == SidebarItemType.CATEGORY) {
                focusPanel = FocusPanel.OPTIONS
                showOptionsForCategory(selectedItem)
                updateSidebarSelection()
                return true
            }
        }
        return false
    }

    @MainThread
    fun moveLeft(): Boolean {
        if (focusPanel == FocusPanel.OPTIONS) {
            focusPanel = FocusPanel.SIDEBAR
            hideOptionsPanel()
            updateSidebarSelection()
            return true
        }
        return false  // Signal to close menu
    }

    @MainThread
    fun selectCurrent(): SelectionResult {
        when (focusPanel) {
            FocusPanel.SIDEBAR -> {
                val selectedItem = SidebarItem.values()[sidebarSelectedIndex]
                return when (selectedItem) {
                    SidebarItem.RIGHT_CLICK -> {
                        onRightClick?.invoke()
                        SelectionResult.ACTION_RIGHT_CLICK
                    }
                    SidebarItem.EXIT -> {
                        onExit?.invoke()
                        SelectionResult.ACTION_EXIT
                    }
                    else -> {
                        // Category - enter options
                        moveRight()
                        SelectionResult.ENTERED_CATEGORY
                    }
                }
            }
            FocusPanel.OPTIONS -> {
                handleOptionSelection()
                return SelectionResult.OPTION_CHANGED
            }
        }
    }

    private fun handleOptionSelection() {
        val selectedCategory = SidebarItem.values()[sidebarSelectedIndex]

        when (selectedCategory) {
            SidebarItem.DISPLAY -> {
                handleDisplayOptionSelection()
            }
            SidebarItem.INPUT -> {
                handleInputOptionSelection()
            }
            SidebarItem.ENVIRONMENT -> {
                handleEnvironmentOptionSelection()
            }
            SidebarItem.SWITCH_MONITOR -> {
                handleMonitorOptionSelection()
            }
            else -> {}
        }
    }

    private fun handleDisplayOptionSelection() {
        when (optionSelectedIndex) {
            0 -> {
                // Cycle display mode
                val modes = DisplayModeOption.values()
                val nextIndex = (modes.indexOf(currentDisplayMode) + 1) % modes.size
                currentDisplayMode = modes[nextIndex]
                onDisplayModeChanged?.invoke(currentDisplayMode)

                // AUTO-SWITCH: Force Trackpad when leaving Keyhole mode
                // Gesture mode doesn't work well with zoom-based display modes
                if (currentDisplayMode != DisplayModeOption.KEYHOLE &&
                    currentInputMode == InputModeOption.GESTURE) {
                    currentInputMode = InputModeOption.TRACKPAD
                    onInputModeChanged?.invoke(currentInputMode)
                }

                refreshOptionsPanel()
            }
            1 -> {
                // Enter zoom adjustment mode
                enterZoomAdjustmentMode()
            }
        }
    }

    private fun handleInputOptionSelection() {
        // Menu structure depends on display mode and input mode:
        // KEYHOLE + Gesture: Mode, ZoomLock, HeadTracking, CursorTracking, Recenter
        // KEYHOLE + Trackpad: Mode, Trackpad, CursorTracking, Recenter
        // Non-KEYHOLE: Mode (disabled), Gesture (greyed), Trackpad, CursorTracking, Recenter

        val isKeyhole = (currentDisplayMode == DisplayModeOption.KEYHOLE)
        val isGesture = (currentInputMode == InputModeOption.GESTURE)

        // Map option index to action
        val action: String = if (isKeyhole && isGesture) {
            // KEYHOLE + Gesture mode: Mode, ZoomLock, HeadTracking, CursorTracking, Recenter
            when (optionSelectedIndex) {
                0 -> "TOGGLE_MODE"
                1 -> "TOGGLE_ZOOM_LOCK"
                2 -> "CYCLE_HEAD_TRACKING_SPEED"
                3 -> "TOGGLE_CURSOR_TRACKING"
                4 -> "RECENTER"
                else -> "NONE"
            }
        } else if (isKeyhole) {
            // KEYHOLE + Trackpad mode: Mode, Trackpad, CursorTracking, Recenter
            when (optionSelectedIndex) {
                0 -> "TOGGLE_MODE"
                1 -> "CYCLE_TRACKPAD_SPEED"
                2 -> "TOGGLE_CURSOR_TRACKING"
                3 -> "RECENTER"
                else -> "NONE"
            }
        } else {
            // Non-KEYHOLE mode: Mode (disabled), Gesture (greyed), Trackpad, CursorTracking, Recenter
            when (optionSelectedIndex) {
                0 -> "NONE"  // Mode locked to Trackpad
                1 -> "NONE"  // Gesture greyed out
                2 -> "CYCLE_TRACKPAD_SPEED"
                3 -> "TOGGLE_CURSOR_TRACKING"
                4 -> "RECENTER"
                else -> "NONE"
            }
        }

        when (action) {
            "TOGGLE_MODE" -> {
                currentInputMode = if (currentInputMode == InputModeOption.TRACKPAD)
                    InputModeOption.GESTURE else InputModeOption.TRACKPAD
                onInputModeChanged?.invoke(currentInputMode)
            }
            "TOGGLE_ZOOM_LOCK" -> {
                isZoomLocked = !isZoomLocked
                onZoomLockChanged?.invoke(isZoomLocked)
            }
            "CYCLE_TRACKPAD_SPEED" -> {
                val speeds = SpeedOption.values()
                val nextIndex = (speeds.indexOf(currentTrackpadSpeed) + 1) % speeds.size
                currentTrackpadSpeed = speeds[nextIndex]
                onTrackpadSpeedChanged?.invoke(currentTrackpadSpeed)
            }
            "CYCLE_HEAD_TRACKING_SPEED" -> {
                val speeds = SpeedOption.values()
                val nextIndex = (speeds.indexOf(currentHeadTrackingSpeed) + 1) % speeds.size
                currentHeadTrackingSpeed = speeds[nextIndex]
                onHeadTrackingSpeedChanged?.invoke(currentHeadTrackingSpeed)
            }
            "TOGGLE_CURSOR_TRACKING" -> {
                currentCursorTracking = if (currentCursorTracking == CursorTrackingOption.ON)
                    CursorTrackingOption.OFF else CursorTrackingOption.ON
                onCursorTrackingChanged?.invoke(currentCursorTracking)
            }
            "RECENTER" -> {
                onRecenter?.invoke()
            }
            "NONE" -> {
                // No action for disabled items
            }
        }
        refreshOptionsPanel()
    }

    private fun handleEnvironmentOptionSelection() {
        when (optionSelectedIndex) {
            0 -> {
                // Toggle environment
                currentEnvironmentEnabled = if (currentEnvironmentEnabled == EnvironmentOption.ON)
                    EnvironmentOption.OFF else EnvironmentOption.ON
                onEnvironmentEnabledChanged?.invoke(currentEnvironmentEnabled)
            }
            1 -> {
                // Cycle theme (only visible when enabled)
                val themes = EnvironmentThemeOption.values()
                val nextIndex = (themes.indexOf(currentEnvironmentTheme) + 1) % themes.size
                currentEnvironmentTheme = themes[nextIndex]
                onEnvironmentThemeChanged?.invoke(currentEnvironmentTheme)
            }
        }
        refreshOptionsPanel()
    }

    private fun handleMonitorOptionSelection() {
        // Monitor number is option index + 1 (Monitor 1 = index 0, etc.)
        val monitorNumber = optionSelectedIndex + 1
        onMonitorSwitch?.invoke(monitorNumber)
        // Note: Menu will be closed by the callback in StreamingActivity
    }

    private fun refreshOptionsPanel() {
        val selectedCategory = SidebarItem.values()[sidebarSelectedIndex]
        showOptionsForCategory(selectedCategory, preserveSelection = true)
    }

    enum class SelectionResult {
        ACTION_RIGHT_CLICK,
        ACTION_EXIT,
        ENTERED_CATEGORY,
        OPTION_CHANGED
    }

    // ========================================================================
    // STATE UPDATES FROM EXTERNAL CHANGES
    // ========================================================================

    @MainThread
    fun updateDisplayMode(mode: DisplayModeOption) {
        currentDisplayMode = mode
        if (focusPanel == FocusPanel.OPTIONS &&
            SidebarItem.values()[sidebarSelectedIndex] == SidebarItem.DISPLAY) {
            refreshOptionsPanel()
        }
    }

    @MainThread
    fun updateInputMode(mode: InputModeOption) {
        currentInputMode = mode
    }

    @MainThread
    fun updateTrackpadSpeed(speed: SpeedOption) {
        currentTrackpadSpeed = speed
    }

    @MainThread
    fun updateHeadTrackingSpeed(speed: SpeedOption) {
        currentHeadTrackingSpeed = speed
    }

    @MainThread
    fun updateCursorTracking(enabled: Boolean) {
        val newValue = if (enabled) CursorTrackingOption.ON else CursorTrackingOption.OFF
        currentCursorTracking = newValue
    }

    @MainThread
    fun updateZoomLock(locked: Boolean) {
        isZoomLocked = locked
    }

    @MainThread
    fun updateZoomLevel(level: Float) {
        currentZoomLevel = level.coerceIn(0.5f, 3.0f)
        updateZoomPercentDisplay()
        // Refresh options panel if in display category (zoom is now part of display)
        if (focusPanel == FocusPanel.OPTIONS &&
            SidebarItem.values()[sidebarSelectedIndex] == SidebarItem.DISPLAY) {
            refreshOptionsPanel()
        }
    }

    @MainThread
    fun updateEnvironmentEnabled(enabled: Boolean) {
        currentEnvironmentEnabled = if (enabled) EnvironmentOption.ON else EnvironmentOption.OFF
        if (focusPanel == FocusPanel.OPTIONS &&
            SidebarItem.values()[sidebarSelectedIndex] == SidebarItem.ENVIRONMENT) {
            refreshOptionsPanel()
        }
    }

    @MainThread
    fun updateEnvironmentTheme(themeId: String) {
        currentEnvironmentTheme = EnvironmentThemeOption.values().find { it.id == themeId }
            ?: EnvironmentThemeOption.BLUE
    }

    // ========================================================================
    // ZOOM ADJUSTMENT MODE
    // ========================================================================

    /**
     * Enter zoom adjustment mode.
     * Makes menu semi-transparent and shows large percentage display.
     */
    @MainThread
    private fun enterZoomAdjustmentMode() {
        isZoomAdjustmentMode = true
        zoomEscapeMomentum = 0f
        isZoomAtDetent = false

        // Make menu semi-transparent
        setBackgroundColor(COLOR_BACKGROUND_TRANSPARENT)
        leftEyeContainer.alpha = 0.3f
        rightEyeContainer.alpha = 0.3f

        // Show zoom overlays
        leftZoomOverlay?.visibility = View.VISIBLE
        rightZoomOverlay?.visibility = View.VISIBLE

        // Update percentage display
        updateZoomPercentDisplay()
    }

    /**
     * Exit zoom adjustment mode.
     * Restores menu opacity and saves zoom level.
     */
    @MainThread
    fun exitZoomAdjustmentMode() {
        if (!isZoomAdjustmentMode) return

        isZoomAdjustmentMode = false

        // Restore menu opacity
        setBackgroundColor(COLOR_BACKGROUND_NORMAL)
        leftEyeContainer.alpha = 1.0f
        rightEyeContainer.alpha = 1.0f

        // Hide zoom overlays
        leftZoomOverlay?.visibility = View.GONE
        rightZoomOverlay?.visibility = View.GONE

        // Notify that zoom level is confirmed (for saving)
        onZoomLevelConfirmed?.invoke(currentZoomLevel)

        // Refresh options panel to show updated percentage
        refreshOptionsPanel()
    }

    /**
     * Handle horizontal swipe during zoom adjustment mode.
     * Implements magnetic detent stops at 25% increments with strong snap.
     *
     * @param delta Swipe delta (positive = right = zoom in, negative = left = zoom out)
     */
    @MainThread
    fun handleZoomAdjustmentSwipe(delta: Float) {
        if (!isZoomAdjustmentMode) return

        // Find nearest detent point
        val nearestDetent = findNearestDetent(currentZoomLevel)
        val distanceToDetent = kotlin.math.abs(currentZoomLevel - nearestDetent)

        // Check if we're in the capture zone
        val inCaptureZone = distanceToDetent < ZOOM_CAPTURE_ZONE

        var newZoomLevel: Float

        if (isZoomAtDetent) {
            // We're stuck at a detent - accumulate escape momentum
            zoomEscapeMomentum += delta

            if (kotlin.math.abs(zoomEscapeMomentum) >= ZOOM_ESCAPE_THRESHOLD) {
                // Escaped! Move just past the detent
                val escapeDirection = if (zoomEscapeMomentum > 0) 1f else -1f
                newZoomLevel = (nearestDetent + escapeDirection * (ZOOM_SNAP_THRESHOLD + 0.01f)).coerceIn(0.5f, 3.0f)
                isZoomAtDetent = false
                zoomEscapeMomentum = 0f
            } else {
                // Still stuck - don't move
                return
            }
        } else if (inCaptureZone) {
            // In capture zone - apply heavy damping
            val dampedDelta = delta * ZOOM_DAMPING_FACTOR
            val zoomChange = dampedDelta * ZOOM_SWIPE_SENSITIVITY
            newZoomLevel = (currentZoomLevel + zoomChange).coerceIn(0.5f, 3.0f)

            // Check if we should snap to detent
            val newDistanceToDetent = kotlin.math.abs(newZoomLevel - nearestDetent)
            if (newDistanceToDetent < ZOOM_SNAP_THRESHOLD) {
                // Snap to detent and enter stuck state
                newZoomLevel = nearestDetent
                isZoomAtDetent = true
                zoomEscapeMomentum = 0f
            }
        } else {
            // Outside capture zone - normal movement
            val zoomChange = delta * ZOOM_SWIPE_SENSITIVITY
            newZoomLevel = (currentZoomLevel + zoomChange).coerceIn(0.5f, 3.0f)
        }

        // Update if changed
        if (kotlin.math.abs(newZoomLevel - currentZoomLevel) > 0.001f) {
            currentZoomLevel = newZoomLevel
            updateZoomPercentDisplay()
            onZoomLevelChanged?.invoke(currentZoomLevel)
        }
    }

    /**
     * Find the nearest detent point for a given zoom level.
     * Detents are at 0.50, 0.75, 1.00, 1.25, 1.50, 1.75, 2.00, 2.25, 2.50, 2.75, 3.00
     */
    private fun findNearestDetent(zoomLevel: Float): Float {
        val adjusted = zoomLevel - 0.5f
        val intervals = kotlin.math.round(adjusted / ZOOM_DETENT_INTERVAL)
        return (0.5f + intervals * ZOOM_DETENT_INTERVAL).coerceIn(0.5f, 3.0f)
    }

    /**
     * Check if currently in zoom adjustment mode.
     */
    fun isInZoomAdjustmentMode(): Boolean = isZoomAdjustmentMode

    /**
     * Adjust zoom by a discrete step (25%).
     * Used by SlideForward/SlideBackward discrete swipe events.
     *
     * @param direction Positive = zoom in, negative = zoom out
     */
    @MainThread
    fun adjustZoomByStep(direction: Int) {
        if (!isZoomAdjustmentMode) return

        // Move to next/previous detent
        val nearestDetent = findNearestDetent(currentZoomLevel)
        val newZoomLevel = if (direction > 0) {
            (nearestDetent + ZOOM_DETENT_INTERVAL).coerceAtMost(3.0f)
        } else {
            (nearestDetent - ZOOM_DETENT_INTERVAL).coerceAtLeast(0.5f)
        }

        if (kotlin.math.abs(newZoomLevel - currentZoomLevel) > 0.001f) {
            currentZoomLevel = newZoomLevel
            isZoomAtDetent = true  // Land on detent
            zoomEscapeMomentum = 0f
            updateZoomPercentDisplay()
            onZoomLevelChanged?.invoke(currentZoomLevel)
        }
    }

    /**
     * Update the zoom percentage display text.
     */
    private fun updateZoomPercentDisplay() {
        val zoomPercent = (currentZoomLevel * 100).toInt()
        val percentText = "$zoomPercent%"
        leftZoomPercentText?.text = percentText
        rightZoomPercentText?.text = percentText
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    /**
     * Create a ScrollView that never intercepts or handles touch events.
     * All scrolling is driven programmatically via scrollToVisible().
     * This prevents the ScrollView from stealing touch events that the
     * Mercury SDK needs for temple gesture recognition (SlideContinuous, Click, etc.).
     */
    private fun createNonInteractiveScrollView(): ScrollView {
        return object : ScrollView(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent?) = false
            override fun onTouchEvent(ev: MotionEvent?) = false
        }.apply {
            isVerticalScrollBarEnabled = false
        }
    }

    private fun scrollToVisible(scrollView: ScrollView?, target: View) {
        scrollView ?: return
        scrollView.post {
            val scrollViewHeight = scrollView.height
            val targetTop = target.top
            val targetBottom = targetTop + target.height
            val scrollY = scrollView.scrollY
            val visibleBottom = scrollY + scrollViewHeight

            when {
                targetTop < scrollY -> scrollView.smoothScrollTo(0, targetTop)
                targetBottom > visibleBottom -> scrollView.smoothScrollTo(0, targetBottom - scrollViewHeight)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
