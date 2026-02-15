package com.raydesk.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseEventActivity
import com.raydesk.data.CursorSettings
import com.raydesk.data.EnvironmentThemes
import com.raydesk.data.SavedServer
import com.raydesk.data.ServerRepository
import com.raydesk.data.StreamingSettings
import com.raydesk.input.CursorPosition
import com.raydesk.gl.DisplayMode
import com.raydesk.gl.StreamRenderer
import com.raydesk.input.HeadGazeCursor
import com.raydesk.spatial.EdgeGlint
import com.raydesk.spatial.KeyholeViewport
import com.raydesk.test.databinding.ActivityStreamingBinding
import com.raydesk.streaming.MoonlightBridge
import com.raydesk.streaming.ReconnectionManager
import com.raydesk.streaming.StreamConfig
import com.raydesk.streaming.StreamResolutionListener
import com.raydesk.video.FrameSlot
import com.raydesk.video.GLTextureRenderer
import com.raydesk.video.TestPatternGenerator
import com.raydesk.video.VideoTextureProvider
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.nvstream.input.MouseButtonPacket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main streaming activity for RayDesk with Mercury SDK binocular display.
 *
 * Uses TextureView + MirroringView pattern for proper binocular rendering:
 * - Left eye: TextureView receives OpenGL rendered video via GLTextureRenderer
 * - Right eye: MirroringView mirrors the TextureView content
 *
 * Integrates all Phase 2 components:
 * - VideoTextureProvider: Video frames from Moonlight
 * - KeyholeViewport: Head-tracked viewport panning
 * - EdgeGlint: Visual indicator when cursor is outside viewport
 * - StreamRenderer: OpenGL rendering with keyhole shader
 * - ReconnectionManager: Auto-reconnect on brief disconnects
 * - ConnectionOverlay: Visual status during connect/reconnect
 * - GameStyleMenuOverlay: Game-style settings menu with two-panel layout
 *
 * Temple gesture handling (normal mode):
 * - Click: Left click at cursor position
 * - Double-click: Recenter head tracking + PC cursor
 * - Triple-click: Open quick menu
 * - Long-press: Toggle overview mode
 * - Swipe forward: Zoom out (show more of desktop)
 * - Swipe backward: Zoom in (closer view)
 * - Swipe up: Scroll up
 * - Swipe down: Scroll down
 *
 * Temple gesture handling (menu mode):
 * - Click: Execute selected menu item
 * - Double-click: Cancel (close menu)
 * - Triple-click: Close menu
 * - Swipe up: Navigate selection up
 * - Swipe down: Navigate selection down
 *
 * Menu items:
 * - Turn off Head Tracking: Toggle head-to-cursor movement (cursor stays fixed when off)
 * - Lock Zoom: Toggle zoom adjustments (zoom level stays fixed when locked)
 * - Right Click: Send right-click at current cursor position
 * - Exit Streaming: Close streaming and return to connection screen
 *
 * Launch with createIntent() from ConnectionActivity with server parameters.
 */
class StreamingActivity : BaseEventActivity(), SensorEventListener, StreamResolutionListener {

    companion object {
        private const val TAG = "Streaming"

        // Intent extras for server parameters
        const val EXTRA_SERVER_UUID = "server_uuid"
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_DISPLAY_MODE = "display_mode"

        // Default desktop resolution - used until actual resolution is detected
        // Sunshine will send its native resolution; we adapt to whatever we receive
        private const val DEFAULT_DESKTOP_WIDTH = 1920
        private const val DEFAULT_DESKTOP_HEIGHT = 1080
        // Gemini recommendation: 20 Mbps minimum for text clarity
        private const val DEFAULT_BITRATE = 20_000  // 20 Mbps in kbps

        // AR viewport resolution (RayNeo X3 Pro per eye)
        private const val VIEWPORT_WIDTH = 640
        private const val VIEWPORT_HEIGHT = 480

        // Head tracking config
        private const val FOV_DEGREES = 30f
        private const val CURSOR_SENSITIVITY = 15f
        private const val CURSOR_DEADZONE = 0.2f

        // Zoom control config
        private const val DEFAULT_ZOOM_STEP = 0.25f  // Step size for trackpad zoom gestures (4 swipes for full range)
        private const val SLIDE_EVENTS_PER_ZOOM_STEP = 5  // Number of SlideContinuous events to trigger one zoom step

        // Click-freeze config - prevents head wobble during tap from affecting click position
        private const val CLICK_FREEZE_MS = 200L

        // Vertical scroll threshold for SlideContinuous (since SlideUpwards/SlideDownwards may not fire)
        private const val VERTICAL_SCROLL_THRESHOLD = 50f  // Delta threshold to trigger a scroll event

        // Menu navigation threshold - higher to prevent rapid selection changes
        private const val MENU_NAVIGATION_THRESHOLD = 150f  // Delta threshold to move menu selection

        // Trackpad sensitivity - multiplier for GestureDetector pixel deltas
        // GestureDetector gives raw pixel deltas (smaller than Mercury SDK's delta values)
        // 1.0 = 1:1 mapping, TapLink uses 0.45f
        // Using 0.8f for responsive but controllable movement
        private const val DEFAULT_TRACKPAD_SENSITIVITY = 0.8f

        // Discrete trackpad step - pixels moved per discrete slide event (SlideForward/Backward/Up/Down)
        // These fire once per deliberate swipe gesture, scaled to match sensitivity
        private const val DEFAULT_TRACKPAD_DISCRETE_STEP = 35f

        /**
         * Creates an intent to launch StreamingActivity with server parameters.
         *
         * Resolution is auto-detected from the stream - we no longer request specific dimensions.
         * The preset determines bitrate and display mode hints.
         *
         * @param context Context for creating intent
         * @param server The saved server to connect to
         * @param preset Display preset for bitrate and display mode (default: AUTO_DETECT)
         * @return Intent configured with server parameters
         */
        fun createIntent(context: Context, server: SavedServer, preset: DisplayPreset = DisplayPreset.AUTO_DETECT): Intent {
            return Intent(context, StreamingActivity::class.java).apply {
                putExtra(EXTRA_SERVER_UUID, server.uuid)
                putExtra(EXTRA_SERVER_ADDRESS, server.address)
                putExtra(EXTRA_SERVER_NAME, server.name)
                putExtra(EXTRA_BITRATE, preset.bitrate)
                putExtra(EXTRA_DISPLAY_MODE, preset.name)
            }
        }
    }

    /**
     * Input mode for temple touchpad.
     * TRACKPAD: Swipes move cursor like a laptop trackpad (default)
     * GESTURE: Swipes control zoom (horizontal) and scroll (vertical)
     */
    enum class InputMode {
        TRACKPAD,
        GESTURE
    }

    // View binding (direct, not mBindingPair)
    private lateinit var binding: ActivityStreamingBinding

    // Server parameters from intent
    private var serverUuid: String? = null
    private var serverAddress: String? = null
    private var serverName: String? = null

    // Desktop resolution - dynamically set from intent extras
    // These are marked @Volatile because they may be updated from callbacks
    @Volatile private var desktopWidth = DEFAULT_DESKTOP_WIDTH
    @Volatile private var desktopHeight = DEFAULT_DESKTOP_HEIGHT
    private var streamBitrate = DEFAULT_BITRATE

    // Persistence
    private lateinit var serverRepository: ServerRepository

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var gameRotationSensor: Sensor? = null

    // Phase 2 components
    private var videoProvider: VideoTextureProvider? = null
    private var frameSlot: FrameSlot? = null  // Decoupled rendering buffer
    private var keyholeViewport: KeyholeViewport? = null
    private var edgeGlint: EdgeGlint? = null
    private var streamRenderer: StreamRenderer? = null
    private var headGazeCursor: HeadGazeCursor? = null
    private var testPatternGenerator: TestPatternGenerator? = null

    // GLTextureRenderer for TextureView-based rendering (binocular support)
    private var glTextureRenderer: GLTextureRenderer? = null

    // Test mode flag - Canvas-based patterns don't work with SurfaceTexture
    // For testing, use Moonlight directly or enable OpenGL-based test pattern
    private var useTestPattern = false

    // Moonlight streaming integration
    private var moonlightBridge: MoonlightBridge? = null

    // Reconnection management
    private lateinit var reconnectionManager: ReconnectionManager
    private var reconnectJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    // Head tracking state
    private var currentYaw = 0f
    private var currentPitch = 0f
    private var headTimestampNs = 0L
    private var needsRecenter = true  // Recenter on first sensor reading

    // Debug/status
    private var showStatus = false
    private var isStreaming = false

    // Slide gesture accumulators (for SlideContinuous fallback when discrete events don't fire)
    private var slideAccumulator = 0
    private var verticalSlideAccumulator = 0f  // Accumulates vertical delta for scroll triggering

    // Click-freeze state - prevents head wobble from moving cursor during tap
    private var clickFreezePosition: CursorPosition? = null
    private var clickFreezeUntil: Long = 0L

    // Connection state tracking to prevent reconnection race conditions
    private var hasEverConnectedSuccessfully = false
    private var isConnecting = false

    // Connection quality tracking
    @Volatile private var isConnectionPoor = false
    private val qualityUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isStreaming || isFinishing) return
            val rttInfo = MoonBridge.getEstimatedRttInfo()
            val rttMs = (rttInfo shr 32).toInt()  // Top 32 bits = RTT in ms
            val quality = when {
                isConnectionPoor -> 1
                rttMs >= 100 -> 2
                rttMs >= 50 -> 3
                rttMs >= 20 -> 4
                else -> 5
            }
            streamRenderer?.setHudConnectionQuality(quality)
            handler.postDelayed(this, 2000)
        }
    }

    // Menu state
    private var isMenuVisible = false

    // Cursor tracking state - controls whether viewport follows cursor when zoomed in
    // When ON: viewport pans to keep cursor centered (soft-edge panning)
    // When OFF: viewport stays fixed, cursor moves freely within it
    private var isCursorTrackingEnabled = true
    private var lastCursorPosition: CursorPosition? = null  // Stores cursor position when cursor tracking disabled

    // Zoom lock state - prevents zoom adjustments when locked
    private var isZoomLocked = false

    // Input mode state - controls how swipes are interpreted
    private var inputMode = InputMode.TRACKPAD  // Default to trackpad mode

    // Trackpad cursor state - stores cursor position when using trackpad mode
    private var trackpadCursorX = DEFAULT_DESKTOP_WIDTH / 2f
    private var trackpadCursorY = DEFAULT_DESKTOP_HEIGHT / 2f

    // Adjustable sensitivity settings (can be changed via settings menu)
    private var trackpadSensitivity = DEFAULT_TRACKPAD_SENSITIVITY
    private var trackpadDiscreteStep = DEFAULT_TRACKPAD_DISCRETE_STEP
    private var zoomStep = DEFAULT_ZOOM_STEP
    private var headTrackingSensitivity = CURSOR_SENSITIVITY

    // Persistent streaming settings
    private lateinit var streamingSettings: StreamingSettings

    // Raw gesture detector for direct trackpad input (bypasses Mercury SDK latency)
    private var trackpadGestureDetector: GestureDetector? = null

    // Menu swipe guard - ensures exactly one selection change per swipe gesture
    private var hasMovedMenuThisSwipe = false

    // Menu horizontal swipe guard - ensures exactly one horizontal action per swipe gesture
    private var hasMovedMenuHorizontallyThisSwipe = false

    // Connection listener for Moonlight events
    private val connectionListener = object : NvConnectionListener {
        override fun stageStarting(stage: String) {
            runOnUiThread {
                showStatusMessage("Stage: $stage")
            }
        }

        override fun stageComplete(stage: String) {
        }

        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
            Log.e(TAG, "[CONNECT] Stage failed: $stage (port=$portFlags, error=$errorCode)")
            runOnUiThread {
                showStatusMessage("Failed: $stage")

                // Only trigger reconnection if we've previously connected successfully.
                // Initial connection failures should just show the error, not trigger reconnect loop.
                if (hasEverConnectedSuccessfully) {
                    handleDisconnect(errorCode)
                } else {
                    Log.w(TAG, "[CONNECT] Initial connection failed - not triggering reconnection")
                    // For initial connection failure, wait a bit then navigate back
                    handler.postDelayed({ navigateToConnectionActivity() }, 3000)
                }
            }
        }

        override fun connectionStarted() {
            Log.i(TAG, "[CONNECT] Connection started - streaming active")
            isStreaming = true
            isConnecting = false
            hasEverConnectedSuccessfully = true  // Mark that we've had at least one successful connection
            runOnUiThread {
                // Update overlay to connected state (will auto-hide)
                updateConnectionOverlay(ConnectionOverlay.State.CONNECTED)

                // Disable test pattern now that real video is available
                streamRenderer?.testPatternEnabled = false

                // Notify reconnection manager of success
                reconnectionManager.onReconnectSuccess()

                // Initialize trackpad mode (default)
                inputMode = InputMode.TRACKPAD
                isCursorTrackingEnabled = false  // Trackpad mode disables head tracking
                trackpadCursorX = desktopWidth / 2f
                trackpadCursorY = desktopHeight / 2f

                // Move PC cursor to center
                moonlightBridge?.sendAbsolutePosition(
                    trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                    desktopWidth, desktopHeight
                )

                // Restore saved monitor selection (if not monitor 1)
                val savedMonitor = streamingSettings.monitorNumber
                if (savedMonitor > 1) {
                    handler.postDelayed({
                        Log.i(TAG, "[MONITOR] Restoring saved monitor $savedMonitor via Sunshine hotkey")
                        moonlightBridge?.switchToMonitor(savedMonitor)
                    }, 2000) // Delay to ensure Sunshine is ready to receive hotkeys
                }

                // Auto-recenter on connection start
                when (streamRenderer?.displayMode) {
                    DisplayMode.FLOATING_MONITOR -> {
                        streamRenderer?.recenterVirtualScreen()
                        Log.i(TAG, "[CONNECT] Virtual screen recentered")
                    }
                    DisplayMode.CURVED_MONITOR -> {
                        streamRenderer?.recenterCurvedMonitor()
                        Log.i(TAG, "[CONNECT] Curved monitor recentered")
                    }
                    DisplayMode.KEYHOLE_PANNING -> {
                        headGazeCursor?.recenter()
                        streamRenderer?.recenterKeyhole()  // Use current head orientation
                        moonlightBridge?.centerCursor(desktopWidth, desktopHeight)
                        Log.i(TAG, "[CONNECT] Viewport recentered")
                    }
                    else -> {}
                }

                // Start connection quality polling
                isConnectionPoor = false
                handler.removeCallbacks(qualityUpdateRunnable)
                handler.post(qualityUpdateRunnable)

                Log.i(TAG, "[CONNECT] Initialized in TRACKPAD mode with cursor at center")
            }
        }

        override fun connectionTerminated(errorCode: Int) {
            Log.w(TAG, "[CONNECT] Connection terminated (error=$errorCode)")
            isStreaming = false
            handler.removeCallbacks(qualityUpdateRunnable)
            streamRenderer?.setHudConnectionQuality(0)

            // CRITICAL: Only trigger reconnection if we've previously connected successfully.
            // During initial connection, temporary errors (like -102 during video establishment)
            // are normal and the connection may still succeed. Don't reconnect in that case.
            if (!hasEverConnectedSuccessfully) {
                return
            }

            runOnUiThread {
                handleDisconnect(errorCode)
            }
        }

        override fun connectionStatusUpdate(connectionStatus: Int) {
            isConnectionPoor = (connectionStatus == MoonBridge.CONN_STATUS_POOR)
        }

        override fun displayMessage(message: String) {
            Log.i(TAG, "[MOONLIGHT] $message")
            runOnUiThread {
                showStatusMessage(message)
            }
        }

        override fun displayTransientMessage(message: String) {
        }

        override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
            // Not implemented for AR glasses
        }

        override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
            // Not implemented for AR glasses
        }

        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        }

        override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
            // Not implemented for AR glasses
        }

        override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
            // Not implemented for AR glasses
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Inflate binding directly (not using BaseMirrorActivity's mBindingPair)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Extract server parameters from intent
        extractServerParameters()

        // Load persistent settings
        loadStreamingSettings()

        // Initialize dependencies
        serverRepository = ServerRepository(this)

        initReconnectionManager()
        initSensors()
        initComponents()
        initBinocularDisplay()
        initTempleGestures()
        initTrackpadGestureDetector()
        initGameMenu()

        // Show connecting overlay immediately
        showConnectingOverlay()
    }

    /**
     * Extract server parameters from launch intent.
     *
     * Resolution is now auto-detected from the stream. We start with defaults
     * and update when the actual stream resolution is received via
     * onStreamResolutionChanged().
     */
    private fun extractServerParameters() {
        serverUuid = intent.getStringExtra(EXTRA_SERVER_UUID)
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)

        // Extract bitrate (use default if not provided - 20 Mbps per Gemini recommendation)
        streamBitrate = intent.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE)

        // Display mode hint (informational only, not used to request resolution)
        val displayModeName = intent.getStringExtra(EXTRA_DISPLAY_MODE) ?: DisplayPreset.AUTO_DETECT.name

        // Start with default desktop resolution - will be updated when stream starts
        desktopWidth = DEFAULT_DESKTOP_WIDTH
        desktopHeight = DEFAULT_DESKTOP_HEIGHT

        Log.i(TAG, "[INIT] Server: $serverName @ $serverAddress (uuid=$serverUuid)")
        Log.i(TAG, "[INIT] Initial: ${desktopWidth}x${desktopHeight} @ ${streamBitrate}kbps (auto-detect enabled)")

        if (serverUuid == null || serverAddress == null) {
            Log.e(TAG, "[INIT] Missing server parameters - cannot stream")
            showStatusMessage("Missing server parameters")
            // Finish after a brief delay to show the error
            handler.postDelayed({ finish() }, 2000)
            return
        }
    }

    /**
     * Load persistent streaming settings and apply them.
     */
    private fun loadStreamingSettings() {
        streamingSettings = StreamingSettings.load(this)

        // Apply settings to local state
        inputMode = if (streamingSettings.inputMode == "TRACKPAD") InputMode.TRACKPAD else InputMode.GESTURE
        isCursorTrackingEnabled = streamingSettings.isCursorTrackingEnabled
        isZoomLocked = streamingSettings.isZoomLocked

        // Enforce Trackpad for non-Keyhole modes (Gesture doesn't work well with zoom)
        // HeadGazeCursor maps head rotation to full desktop coordinates, which breaks
        // when zoom changes the visible portion of the screen
        if (streamingSettings.displayMode != "KEYHOLE" &&
            streamingSettings.inputMode == "GESTURE") {
            inputMode = InputMode.TRACKPAD
            isCursorTrackingEnabled = false
            // Note: Menu overlay sync happens in initGameMenu()
        }

        // Apply speed settings
        applySpeedSetting(streamingSettings.trackpadSpeed, isTrackpad = true)
        applySpeedSetting(streamingSettings.zoomSpeed, isTrackpad = false)
        applyHeadTrackingSpeedSetting(streamingSettings.headTrackingSpeed)
    }

    /**
     * Apply a speed setting to the appropriate variables.
     */
    private fun applySpeedSetting(speedName: String, isTrackpad: Boolean) {
        val multiplier = when (speedName) {
            "SLOW" -> 0.5f
            "MEDIUM" -> 1.0f
            "FAST" -> 1.5f
            "VERY_FAST" -> 2.0f
            else -> 1.0f
        }
        if (isTrackpad) {
            trackpadSensitivity = DEFAULT_TRACKPAD_SENSITIVITY * multiplier
            trackpadDiscreteStep = DEFAULT_TRACKPAD_DISCRETE_STEP * multiplier
        } else {
            zoomStep = DEFAULT_ZOOM_STEP * multiplier
        }
    }

    /**
     * Apply head tracking speed setting to the cursor sensitivity.
     */
    private fun applyHeadTrackingSpeedSetting(speedName: String) {
        val multiplier = when (speedName) {
            "SLOW" -> 0.5f
            "MEDIUM" -> 1.0f
            "FAST" -> 1.5f
            "VERY_FAST" -> 2.0f
            else -> 1.0f
        }
        headTrackingSensitivity = CURSOR_SENSITIVITY * multiplier
        headGazeCursor?.sensitivity = headTrackingSensitivity
    }

    /**
     * Initialize the reconnection manager with callbacks.
     */
    private fun initReconnectionManager() {
        reconnectionManager = ReconnectionManager(
            onAutoReconnect = {
                Log.i(TAG, "[RECONNECT] Auto-reconnecting...")
                attemptReconnect()
            },
            onNavigateToConnection = {
                Log.i(TAG, "[RECONNECT] Navigating back to ConnectionActivity")
                navigateToConnectionActivity()
            },
            onReconnectAttempt = { attempt, maxAttempts ->
                Log.i(TAG, "[RECONNECT] Attempt $attempt/$maxAttempts")
                runOnUiThread {
                    updateConnectionOverlay(ConnectionOverlay.State.RECONNECTING)
                    binding.connectionOverlay.setReconnectProgress(attempt, maxAttempts)
                }
            }
        )
    }

    /**
     * Show the connecting overlay when activity starts.
     */
    private fun showConnectingOverlay() {
        binding.connectionOverlay.setState(ConnectionOverlay.State.CONNECTING, serverName)
    }

    /**
     * Update the connection overlay state.
     */
    private fun updateConnectionOverlay(state: ConnectionOverlay.State) {
        binding.connectionOverlay.setState(state, serverName)
    }

    /**
     * Initialize raw gesture detector for direct trackpad input.
     *
     * Bypasses Mercury SDK's TempleAction for lower latency cursor control.
     * Based on TapLink X3's approach which uses GestureDetector directly.
     */
    private fun initTrackpadGestureDetector() {
        trackpadGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Must return true to receive subsequent events (onScroll, etc.)
                return inputMode == InputMode.TRACKPAD && !isMenuVisible
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (inputMode != InputMode.TRACKPAD || isMenuVisible) return false

                // Direct cursor control - distanceX/Y are incremental deltas in pixels
                // Note: GestureDetector's distanceX is positive when swiping LEFT, so we negate
                trackpadCursorX = (trackpadCursorX - distanceX * trackpadSensitivity)
                    .coerceIn(0f, desktopWidth.toFloat())
                trackpadCursorY = (trackpadCursorY - distanceY * trackpadSensitivity)
                    .coerceIn(0f, desktopHeight.toFloat())

                // Send to PC
                moonlightBridge?.sendAbsolutePosition(
                    trackpadCursorX.toInt(),
                    trackpadCursorY.toInt(),
                    desktopWidth,
                    desktopHeight
                )

                // Update renderer for cursor-centered panning (soft-edge behavior)
                streamRenderer?.apply {
                    cursorX = trackpadCursorX
                    cursorY = trackpadCursorY
                }

                return true
            }
        })
    }

    /**
     * Initialize the game-style settings menu with callbacks.
     */
    private fun initGameMenu() {
        binding.gameMenuOverlay.apply {
            // Sync initial state from saved settings
            updateDisplayMode(
                when (streamingSettings.displayMode) {
                    "FLOATING" -> GameStyleMenuOverlay.DisplayModeOption.FLOATING
                    "KEYHOLE" -> GameStyleMenuOverlay.DisplayModeOption.KEYHOLE
                    "CURVED" -> GameStyleMenuOverlay.DisplayModeOption.CURVED
                    else -> GameStyleMenuOverlay.DisplayModeOption.FLOATING
                }
            )
            updateInputMode(
                if (inputMode == InputMode.TRACKPAD) GameStyleMenuOverlay.InputModeOption.TRACKPAD
                else GameStyleMenuOverlay.InputModeOption.GESTURE
            )
            updateCursorTracking(isCursorTrackingEnabled)
            updateZoomLock(isZoomLocked)
            updateZoomLevel(streamingSettings.zoomLevel)

            // Sync speed settings
            updateTrackpadSpeed(
                when (streamingSettings.trackpadSpeed) {
                    "SLOW" -> GameStyleMenuOverlay.SpeedOption.SLOW
                    "MEDIUM" -> GameStyleMenuOverlay.SpeedOption.MEDIUM
                    "FAST" -> GameStyleMenuOverlay.SpeedOption.FAST
                    "VERY_FAST" -> GameStyleMenuOverlay.SpeedOption.VERY_FAST
                    else -> GameStyleMenuOverlay.SpeedOption.MEDIUM
                }
            )
            updateHeadTrackingSpeed(
                when (streamingSettings.headTrackingSpeed) {
                    "SLOW" -> GameStyleMenuOverlay.SpeedOption.SLOW
                    "MEDIUM" -> GameStyleMenuOverlay.SpeedOption.MEDIUM
                    "FAST" -> GameStyleMenuOverlay.SpeedOption.FAST
                    "VERY_FAST" -> GameStyleMenuOverlay.SpeedOption.VERY_FAST
                    else -> GameStyleMenuOverlay.SpeedOption.MEDIUM
                }
            )

            // Sync environment settings
            currentEnvironmentEnabled = if (streamingSettings.isEnvironmentEnabled)
                GameStyleMenuOverlay.EnvironmentOption.ON else GameStyleMenuOverlay.EnvironmentOption.OFF
            currentEnvironmentTheme = GameStyleMenuOverlay.EnvironmentThemeOption.values()
                .find { it.id == streamingSettings.environmentThemeId } ?: GameStyleMenuOverlay.EnvironmentThemeOption.BLUE

            // Wire up callbacks
            onRightClick = {
                sendRightClickAtCursor()
                hide()
                isMenuVisible = false
            }

            onMonitorSwitch = { monitorNumber ->
                Log.i(TAG, "[MONITOR] Switching to monitor $monitorNumber via Sunshine hotkey")
                moonlightBridge?.switchToMonitor(monitorNumber)
                streamingSettings = streamingSettings.withMonitorNumber(this@StreamingActivity, monitorNumber)
                showStatusMessage("Switched to Monitor $monitorNumber")
                hide()
                isMenuVisible = false
            }

            onExit = {
                Log.i(TAG, "[MENU] Exit selected - finishing activity")
                hide()
                isMenuVisible = false
                finish()
            }

            onDisplayModeChanged = { mode ->
                val newDisplayMode = when (mode) {
                    GameStyleMenuOverlay.DisplayModeOption.FLOATING -> DisplayMode.FLOATING_MONITOR
                    GameStyleMenuOverlay.DisplayModeOption.KEYHOLE -> DisplayMode.KEYHOLE_PANNING
                    GameStyleMenuOverlay.DisplayModeOption.CURVED -> DisplayMode.CURVED_MONITOR
                }
                streamRenderer?.displayMode = newDisplayMode
                streamingSettings = streamingSettings.withDisplayMode(this@StreamingActivity, mode.name)
                Log.i(TAG, "[MENU] Display mode changed to: $newDisplayMode (saved)")

                // Apply current zoom level to the new display mode
                applyZoomLevel(streamingSettings.zoomLevel)

                // Auto-recenter view for the new display mode
                recenterView()

                showStatusMessage("Mode: ${mode.label}")
            }

            onInputModeChanged = { mode ->
                when (mode) {
                    GameStyleMenuOverlay.InputModeOption.TRACKPAD -> {
                        inputMode = InputMode.TRACKPAD
                        isCursorTrackingEnabled = false
                        trackpadCursorX = desktopWidth / 2f
                        trackpadCursorY = desktopHeight / 2f
                        moonlightBridge?.sendAbsolutePosition(
                            trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                            desktopWidth, desktopHeight
                        )
                        Log.i(TAG, "[MENU] Input mode: TRACKPAD")
                        showStatusMessage("Trackpad Mode")
                    }
                    GameStyleMenuOverlay.InputModeOption.GESTURE -> {
                        inputMode = InputMode.GESTURE
                        isCursorTrackingEnabled = true
                        lastCursorPosition = null
                        Log.i(TAG, "[MENU] Input mode: GESTURE")
                        showStatusMessage("Gesture Mode")
                    }
                }
                // Update renderer with new cursor tracking state (changes with input mode)
                streamRenderer?.setCursorTrackingEnabled(isCursorTrackingEnabled)
                // Update menu's visual state to match (for consistency when viewing Input settings)
                binding.gameMenuOverlay.updateCursorTracking(isCursorTrackingEnabled)
                // Update HUD with new input mode
                streamRenderer?.setHudInputMode(mode.name)
                streamingSettings = streamingSettings.withInputMode(this@StreamingActivity, mode.name)
                streamingSettings = streamingSettings.withCursorTracking(this@StreamingActivity, isCursorTrackingEnabled)
            }

            onTrackpadSpeedChanged = { speed ->
                val multiplier = when (speed) {
                    GameStyleMenuOverlay.SpeedOption.SLOW -> 0.5f
                    GameStyleMenuOverlay.SpeedOption.MEDIUM -> 1.0f
                    GameStyleMenuOverlay.SpeedOption.FAST -> 1.5f
                    GameStyleMenuOverlay.SpeedOption.VERY_FAST -> 2.0f
                }
                trackpadSensitivity = DEFAULT_TRACKPAD_SENSITIVITY * multiplier
                trackpadDiscreteStep = DEFAULT_TRACKPAD_DISCRETE_STEP * multiplier
                streamingSettings = streamingSettings.withTrackpadSpeed(this@StreamingActivity, speed.name)
                Log.i(TAG, "[MENU] Trackpad speed: ${speed.label} (saved)")
                showStatusMessage("Trackpad: ${speed.label}")
            }

            onHeadTrackingSpeedChanged = { speed ->
                val multiplier = when (speed) {
                    GameStyleMenuOverlay.SpeedOption.SLOW -> 0.5f
                    GameStyleMenuOverlay.SpeedOption.MEDIUM -> 1.0f
                    GameStyleMenuOverlay.SpeedOption.FAST -> 1.5f
                    GameStyleMenuOverlay.SpeedOption.VERY_FAST -> 2.0f
                }
                headTrackingSensitivity = CURSOR_SENSITIVITY * multiplier
                headGazeCursor?.sensitivity = headTrackingSensitivity
                streamingSettings = streamingSettings.withHeadTrackingSpeed(this@StreamingActivity, speed.name)
                Log.i(TAG, "[MENU] Head tracking speed: ${speed.label} (saved)")
                showStatusMessage("Head Tracking: ${speed.label}")
            }

            onCursorTrackingChanged = { tracking ->
                isCursorTrackingEnabled = (tracking == GameStyleMenuOverlay.CursorTrackingOption.ON)
                if (!isCursorTrackingEnabled) {
                    lastCursorPosition = headGazeCursor?.getCursorPosition()
                } else {
                    lastCursorPosition = null
                }
                // Update renderer for cursor-centered panning
                streamRenderer?.setCursorTrackingEnabled(isCursorTrackingEnabled)
                streamingSettings = streamingSettings.withCursorTracking(this@StreamingActivity, isCursorTrackingEnabled)
                Log.i(TAG, "[MENU] Cursor tracking: ${tracking.label} (saved to prefs)")
                showStatusMessage("Cursor Tracking: ${tracking.label}")
            }

            onZoomLockChanged = { locked ->
                isZoomLocked = locked
                streamingSettings = streamingSettings.withZoomLocked(this@StreamingActivity, locked)
                Log.i(TAG, "[MENU] Zoom lock: $locked (saved)")
                showStatusMessage(if (locked) "Zoom Locked" else "Zoom Unlocked")
            }

            onRecenter = {
                recenterView()
            }

            // Zoom level adjustment callbacks
            onZoomLevelChanged = { level ->
                // Real-time zoom preview during adjustment
                applyZoomLevel(level)
            }

            onZoomLevelConfirmed = { level ->
                // Save zoom level when adjustment is confirmed (finger lifted)
                streamingSettings = streamingSettings.withZoomLevel(this@StreamingActivity, level)
                Log.i(TAG, "[MENU] Zoom level confirmed: ${(level * 100).toInt()}% (saved)")
                showStatusMessage("Zoom: ${(level * 100).toInt()}%")
            }

            onEnvironmentEnabledChanged = { option ->
                val enabled = option == GameStyleMenuOverlay.EnvironmentOption.ON
                streamingSettings = streamingSettings.withEnvironmentEnabled(this@StreamingActivity, enabled)
                streamRenderer?.setEnvironmentEnabled(enabled)
                Log.i(TAG, "[MENU] Environment enabled: $enabled (saved)")
                showStatusMessage(if (enabled) "Environment On" else "Environment Off")
            }

            onEnvironmentThemeChanged = { option ->
                streamingSettings = streamingSettings.withEnvironmentTheme(this@StreamingActivity, option.id)
                streamRenderer?.setEnvironmentTheme(EnvironmentThemes.getById(option.id))
                Log.i(TAG, "[MENU] Environment theme: ${option.label} (saved)")
                showStatusMessage("Theme: ${option.label}")
            }
        }

        // Apply saved display mode to renderer
        streamRenderer?.displayMode = when (streamingSettings.displayMode) {
            "FLOATING" -> DisplayMode.FLOATING_MONITOR
            "KEYHOLE" -> DisplayMode.KEYHOLE_PANNING
            "CURVED" -> DisplayMode.CURVED_MONITOR
            else -> DisplayMode.FLOATING_MONITOR
        }

        // Apply saved zoom level
        applyZoomLevel(streamingSettings.zoomLevel)

        // Apply saved environment settings to renderer
        streamRenderer?.setEnvironmentEnabled(streamingSettings.isEnvironmentEnabled)
        streamRenderer?.setEnvironmentTheme(EnvironmentThemes.getById(streamingSettings.environmentThemeId))

        // Apply cursor tracking setting (use local var which may have been modified during enforcement)
        streamRenderer?.setCursorTrackingEnabled(isCursorTrackingEnabled)

        // Apply input mode to HUD (use local var which may have been modified during enforcement)
        streamRenderer?.setHudInputMode(inputMode.name)

    }

    /**
     * Intercept touch events before Mercury SDK for direct trackpad control.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // In trackpad mode, try raw gesture detector first for lower latency
        if (inputMode == InputMode.TRACKPAD && !isMenuVisible) {
            trackpadGestureDetector?.onTouchEvent(ev)
            // Note: We still call super to let Mercury SDK handle taps/clicks
            // The GestureDetector handles scroll/drag, Mercury handles discrete events
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Handle disconnect event from Moonlight.
     *
     * @param errorCode The Moonlight error code (logged for debugging, not currently used)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleDisconnect(errorCode: Int) {
        // Reset connecting flag since we're now disconnected
        isConnecting = false

        // Show reconnecting state
        updateConnectionOverlay(ConnectionOverlay.State.RECONNECTING)

        // Let reconnection manager decide what to do
        // (tracks first disconnect time internally for timeout calculations)
        reconnectionManager.onDisconnect()
    }

    /**
     * Attempt to reconnect to the server.
     *
     * Uses job tracking to allow cancellation when activity goes to background.
     * Checks lifecycle state before actually reconnecting to prevent race conditions.
     */
    private fun attemptReconnect() {
        // Prevent duplicate reconnection attempts
        if (isConnecting) {
            return
        }

        val uuid = serverUuid ?: return
        val server = serverRepository.getServer(uuid)

        if (server == null) {
            Log.e(TAG, "[RECONNECT] Server not found in repository")
            reconnectionManager.onReconnectFailed()
            navigateToConnectionActivity()
            return
        }

        // Cancel any existing reconnect job to prevent races
        reconnectJob?.cancel()

        // Mark as connecting to prevent race conditions
        isConnecting = true

        // Get retry delay from manager
        val delayMs = reconnectionManager.getCurrentRetryDelay()

        // Schedule reconnection with backoff delay
        reconnectJob = lifecycleScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }

            // Check if activity is still in foreground before reconnecting
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                isConnecting = false
                return@launch
            }

            Log.i(TAG, "[RECONNECT] Attempting to reconnect to ${server.name}")
            moonlightBridge?.disconnect()
            moonlightBridge?.connectToSavedServer(server)
        }
    }

    /**
     * Navigate back to ConnectionActivity.
     */
    private fun navigateToConnectionActivity() {
        // Clear the back stack and return to connection screen
        val intent = Intent(this, ConnectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (gameRotationSensor != null) {
            Log.i(TAG, "[SENSOR] TYPE_GAME_ROTATION_VECTOR available")
        } else {
            Log.e(TAG, "[SENSOR] TYPE_GAME_ROTATION_VECTOR not available!")
        }
    }

    private fun initComponents() {
        // Initialize keyhole viewport for head-tracked panning
        keyholeViewport = KeyholeViewport(
            desktopWidth = desktopWidth,
            desktopHeight = desktopHeight,
            viewportWidth = VIEWPORT_WIDTH,
            viewportHeight = VIEWPORT_HEIGHT,
            fovDegrees = FOV_DEGREES
        )

        // Initialize edge glint for off-screen cursor indication
        edgeGlint = EdgeGlint()

        // Load cursor settings from preferences
        val cursorSettings = CursorSettings.load(this)

        // Initialize head-gaze cursor with settings
        headGazeCursor = HeadGazeCursor(
            screenWidth = desktopWidth,
            screenHeight = desktopHeight,
            sensitivity = headTrackingSensitivity,
            deadzone = CURSOR_DEADZONE,
            settings = cursorSettings
        )

        Log.i(TAG, "[INIT] Components initialized (desktop: ${desktopWidth}x${desktopHeight}, cursor settings: baseSensitivity=${cursorSettings.baseSensitivity})")
    }

    /**
     * Initialize the binocular display with TextureView + MirroringView pattern.
     * This is the correct way to render to both eyes on RayNeo X3 Pro.
     */
    private fun initBinocularDisplay() {
        // Create renderer (video provider is set after GL surface is ready)
        streamRenderer = StreamRenderer(this).apply {
            keyholeViewport = this@StreamingActivity.keyholeViewport
            // Start with test pattern enabled to verify pipeline
            testPatternEnabled = true

            // Video stall detection - navigate back when video stops
            // (Mercury OS doesn't call onStop when headset is removed)
            onVideoStallDetected = {
                Log.e(TAG, "[VIDEO] Stall detected - navigating to ConnectionActivity")
                runOnUiThread {
                    navigateToConnectionActivity()
                }
            }

            // Callback when GL surface is ready
            onSurfaceCreatedCallback = {
                initVideoProvider()
            }
        }

        // Create GLTextureRenderer for rendering to TextureView
        glTextureRenderer = GLTextureRenderer(
            binding.videoTextureView,
            streamRenderer!!  // StreamRenderer implements GLTextureRenderer.Renderer
        )

        // Set up MirroringView to mirror the TextureView to the right eye
        binding.mirrorView.setSource(binding.videoTextureView)
        binding.mirrorView.startMirroring()

        // Start the render loop
        glTextureRenderer?.start()

        Log.i(TAG, "[GL] Binocular display initialized: TextureView + MirroringView")
    }

    private fun initVideoProvider() {
        // Create FrameSlot for decoupled rendering (60Hz render, ~30fps video)
        frameSlot = FrameSlot()
        Log.i(TAG, "[VIDEO] FrameSlot created for decoupled rendering")

        // Initialize on GL thread
        videoProvider = VideoTextureProvider().apply {
            initialize()
            // Connect FrameSlot - VideoTextureProvider will publish frames to it
            setFrameSlot(frameSlot)
        }

        // Connect to renderer
        streamRenderer?.videoProvider = videoProvider
        streamRenderer?.frameSlot = frameSlot
        streamRenderer?.keyholeViewport = keyholeViewport

        Log.i(TAG, "[VIDEO] VideoTextureProvider initialized with FrameSlot")
        Log.i(TAG, "[VIDEO] KeyholeViewport connected to renderer: ${keyholeViewport != null}, renderer has viewport: ${streamRenderer?.keyholeViewport != null}")

        // Start video source
        if (useTestPattern) {
            startTestPattern()
        } else {
            // Initialize MoonlightBridge for streaming
            initMoonlightBridge()
        }
    }

    /**
     * Initialize the Moonlight streaming bridge and connect to server.
     */
    private fun initMoonlightBridge() {
        val provider = videoProvider ?: run {
            Log.e(TAG, "[MOONLIGHT] VideoTextureProvider not initialized")
            return
        }

        // Create MoonlightBridge with our video provider
        moonlightBridge = MoonlightBridge(this, provider).apply {
            // Configure stream settings for AR glasses using dynamic dimensions
            setStreamConfig(StreamConfig(
                width = desktopWidth,
                height = desktopHeight,
                fps = 60,                 // 60fps to match X3 Pro and standard Moonlight
                bitrate = streamBitrate   // Use preset-specified bitrate (kbps)
            ))

            // Set our connection listener
            setConnectionListener(connectionListener)

            // Set resolution listener to handle resolution mismatches
            setResolutionListener(this@StreamingActivity)

            // Initialize the decoder (creates MediaCodecDecoderRenderer)
            // This wires VideoSurfaceHolder to the decoder via setRenderTarget()
            initializeDecoder(
                meteredConnection = false,
                hdrEnabled = false,
                glRenderer = ""
            )
        }

        val hevc = moonlightBridge?.isHevcSupported() ?: false
        val av1 = moonlightBridge?.isAv1Supported() ?: false

        Log.i(TAG, "[MOONLIGHT] Bridge initialized - decoder ready")
        Log.i(TAG, "[MOONLIGHT] HEVC supported: $hevc")
        Log.i(TAG, "[MOONLIGHT] AV1 supported: $av1")

        // Connect to the saved server if we have server parameters
        connectToServer()
    }

    /**
     * Connect to the server using saved credentials.
     */
    private fun connectToServer() {
        val uuid = serverUuid
        if (uuid == null) {
            Log.e(TAG, "[CONNECT] No server UUID provided")
            showStatusMessage("No server configured")
            return
        }

        // Load server from repository
        val server = serverRepository.getServer(uuid)
        if (server == null) {
            Log.e(TAG, "[CONNECT] Server not found in repository: $uuid")
            showStatusMessage("Server not found")
            navigateToConnectionActivity()
            return
        }

        Log.i(TAG, "[CONNECT] Connecting to ${server.name} @ ${server.address}")

        // Mark that we're connecting (prevents premature reconnection)
        isConnecting = true

        // Update last connected timestamp
        serverRepository.updateLastConnected(uuid)

        // CRITICAL: Quit any existing session first to clear stale server state
        // This prevents the server from sending video packets to a previous client's
        // UDP destination, which causes "no video traffic" errors when connecting
        // after standard Moonlight was used.
        lifecycleScope.launch {
            Log.i(TAG, "[CONNECT] Quitting any existing session first...")
            val quitSuccess = moonlightBridge?.quitExistingSession(server) ?: false
            Log.i(TAG, "[CONNECT] Quit result: $quitSuccess")

            // Wait for server to fully process the quit
            // Sunshine may need time to release video encoder resources
            kotlinx.coroutines.delay(2000)

            // Now connect via MoonlightBridge
            moonlightBridge?.connectToSavedServer(server)
        }
    }

    /**
     * Start test pattern generator for pipeline verification.
     * This proves the Surface -> SurfaceTexture -> OpenGL pipeline works.
     */
    private fun startTestPattern() {
        val surface = videoProvider?.getSurface()
        if (surface == null) {
            Log.e(TAG, "[VIDEO] Cannot start test pattern - no surface")
            return
        }

        testPatternGenerator = TestPatternGenerator().apply {
            start(
                surface = surface,
                width = desktopWidth,
                height = desktopHeight,
                pattern = TestPatternGenerator.Pattern.MOVING_GRADIENT,
                fps = 30
            )
        }

        Log.i(TAG, "[VIDEO] Test pattern started - verify video pipeline")
        showStatusMessage("Test Pattern Active")
    }

    private fun initTempleGestures() {
        Log.i(TAG, "[INIT] Setting up temple gesture collection...")
        lifecycleScope.launch {
            Log.i(TAG, "[INIT] Temple gesture coroutine started")
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                Log.i(TAG, "[INIT] Temple gesture collection started (RESUMED state)")
                templeActionViewModel.state.collect { action ->
                    handleTempleAction(action)
                }
            }
        }
    }

    /**
     * Handle menu navigation from vertical swipes.
     * Used by both trackpad and gesture modes when menu is open.
     */
    private fun handleMenuNavigation(isVertical: Boolean, delta: Float) {
        if (!isVertical) return

        if (!hasMovedMenuThisSwipe) {
            verticalSlideAccumulator += delta
            if (kotlin.math.abs(verticalSlideAccumulator) >= MENU_NAVIGATION_THRESHOLD) {
                hasMovedMenuThisSwipe = true
                // Swipe down (positive delta) = selection moves down (natural direction)
                if (verticalSlideAccumulator > 0) {
                    binding.gameMenuOverlay.moveDown()
                    Log.i(TAG, "[MENU] Selection down (vertical swipe)")
                } else {
                    binding.gameMenuOverlay.moveUp()
                    Log.i(TAG, "[MENU] Selection up (vertical swipe)")
                }
                verticalSlideAccumulator = 0f
            }
        }
    }

    /**
     * Handle menu horizontal navigation (enter/exit category options).
     */
    private fun handleMenuHorizontalNavigation(delta: Float) {
        if (!hasMovedMenuHorizontallyThisSwipe) {
            if (kotlin.math.abs(delta) >= MENU_NAVIGATION_THRESHOLD / 2) {
                hasMovedMenuHorizontallyThisSwipe = true
                if (delta > 0) {
                    // Swipe forward = right = enter options
                    binding.gameMenuOverlay.moveRight()
                    Log.i(TAG, "[MENU] Move right (horizontal swipe)")
                } else {
                    // Swipe backward = left = go back or close
                    val wentBack = binding.gameMenuOverlay.moveLeft()
                    if (!wentBack) {
                        // Was already at sidebar, close menu
                        binding.gameMenuOverlay.hide()
                        isMenuVisible = false
                        Log.i(TAG, "[MENU] Closed (swipe left)")
                    } else {
                        Log.i(TAG, "[MENU] Back to sidebar (horizontal swipe)")
                    }
                }
            }
        }
    }

    private fun handleTempleAction(action: TempleAction) {
        Log.i(TAG, "[INPUT] Temple action received: ${action::class.simpleName} (menuVisible=$isMenuVisible)")
        when (action) {
            is TempleAction.Click -> {
                if (isMenuVisible) {
                    // Menu is open: select current item
                    val result = binding.gameMenuOverlay.selectCurrent()
                    Log.i(TAG, "[MENU] Selected: $result")
                    // Actions trigger callbacks automatically, categories navigate
                    // Only close menu for action results
                    when (result) {
                        GameStyleMenuOverlay.SelectionResult.ACTION_RIGHT_CLICK,
                        GameStyleMenuOverlay.SelectionResult.ACTION_EXIT -> {
                            // Menu auto-closed by callback
                        }
                        GameStyleMenuOverlay.SelectionResult.ENTERED_CATEGORY,
                        GameStyleMenuOverlay.SelectionResult.OPTION_CHANGED -> {
                            // Stay in menu
                        }
                    }
                } else {
                    // Get cursor position based on input mode
                    val (clickX, clickY) = when (inputMode) {
                        InputMode.TRACKPAD -> Pair(trackpadCursorX.toInt(), trackpadCursorY.toInt())
                        InputMode.GESTURE -> {
                            val pos = getCurrentCursorPosition()
                            if (pos != null) {
                                // Apply click-freeze for head tracking mode
                                clickFreezePosition = pos
                                clickFreezeUntil = System.currentTimeMillis() + CLICK_FREEZE_MS
                                Pair(pos.x.toInt(), pos.y.toInt())
                            } else {
                                return  // No valid position
                            }
                        }
                    }

                    Log.i(TAG, "[INPUT] Click at ($clickX, $clickY) in ${inputMode.name} mode")
                    moonlightBridge?.sendAbsolutePosition(clickX, clickY, desktopWidth, desktopHeight)
                    moonlightBridge?.sendMouseClick()
                }
            }

            is TempleAction.DoubleClick -> {
                // Zoom adjustment mode: double-click confirms and exits
                if (binding.gameMenuOverlay.isInZoomAdjustmentMode()) {
                    Log.i(TAG, "[MENU] Zoom adjustment confirmed via double-click")
                    binding.gameMenuOverlay.exitZoomAdjustmentMode()
                    return
                }

                if (isMenuVisible) {
                    // Menu is open: close without action
                    Log.i(TAG, "[MENU] Cancelled")
                    binding.gameMenuOverlay.hide()
                    isMenuVisible = false
                } else {
                    // Recenter based on current display mode
                    when (streamRenderer?.displayMode) {
                        DisplayMode.FLOATING_MONITOR -> {
                            // Floating monitor: recenter the virtual screen position
                            Log.i(TAG, "[INPUT] Recentering FLOATING_MONITOR mode")
                            streamRenderer?.recenterVirtualScreen()
                            showStatusMessage("View Recentered")
                        }
                        DisplayMode.CURVED_MONITOR -> {
                            // Curved monitor: recenter the cylinder view
                            Log.i(TAG, "[INPUT] Recentering CURVED_MONITOR mode")
                            streamRenderer?.recenterCurvedMonitor()
                            showStatusMessage("View Recentered")
                        }
                        DisplayMode.KEYHOLE_PANNING, null -> {
                            // Keyhole mode: recenter head tracking AND move PC cursor to center
                            headGazeCursor?.recenter()
                            streamRenderer?.recenterKeyhole()  // Use current head orientation
                            moonlightBridge?.centerCursor(desktopWidth, desktopHeight)

                            // Clear any click-freeze state
                            clickFreezePosition = null
                            clickFreezeUntil = 0L

                            Log.i(TAG, "[INPUT] Cursor and viewport recentered to center")
                            showStatusMessage("Recentered")
                        }
                    }
                }
            }

            is TempleAction.TripleClick -> {
                // Toggle menu visibility
                if (isMenuVisible) {
                    Log.i(TAG, "[MENU] Closing menu (triple-tap toggle)")
                    binding.gameMenuOverlay.hide()
                    isMenuVisible = false
                } else {
                    Log.i(TAG, "[MENU] Opening menu")
                    // Sync menu state before showing
                    binding.gameMenuOverlay.apply {
                        updateDisplayMode(
                            when (streamRenderer?.displayMode) {
                                DisplayMode.FLOATING_MONITOR -> GameStyleMenuOverlay.DisplayModeOption.FLOATING
                                DisplayMode.KEYHOLE_PANNING -> GameStyleMenuOverlay.DisplayModeOption.KEYHOLE
                                DisplayMode.CURVED_MONITOR -> GameStyleMenuOverlay.DisplayModeOption.CURVED
                                null -> GameStyleMenuOverlay.DisplayModeOption.FLOATING
                            }
                        )
                        updateInputMode(
                            if (inputMode == InputMode.TRACKPAD) GameStyleMenuOverlay.InputModeOption.TRACKPAD
                            else GameStyleMenuOverlay.InputModeOption.GESTURE
                        )
                        Log.i(TAG, "[MENU-OPEN] Syncing menu on open: isCursorTrackingEnabled=$isCursorTrackingEnabled")
                        updateCursorTracking(isCursorTrackingEnabled)
                        updateZoomLock(isZoomLocked)
                        show()
                    }
                    isMenuVisible = true
                }
            }

            is TempleAction.LongClick -> {
                // Toggle overview mode (system-reserved, works regardless of menu)
                val viewport = keyholeViewport ?: return
                val newMode = if (viewport.getMode() == KeyholeViewport.Mode.KEYHOLE) {
                    KeyholeViewport.Mode.OVERVIEW
                } else {
                    KeyholeViewport.Mode.KEYHOLE
                }
                viewport.setMode(newMode)
                Log.i(TAG, "[INPUT] Mode changed to: $newMode")
                showStatusMessage("Mode: $newMode")
            }

            is TempleAction.SlideForward -> {
                // Zoom adjustment mode takes priority
                if (binding.gameMenuOverlay.isInZoomAdjustmentMode()) {
                    Log.i(TAG, "[ZOOM] SlideForward in zoom adjustment mode")
                    binding.gameMenuOverlay.adjustZoomByStep(1)  // Forward = zoom in
                    return
                }
                if (isMenuVisible) {
                    // Menu handles navigation via SlideContinuous
                } else if (inputMode == InputMode.TRACKPAD) {
                    // Trackpad mode: move cursor right (larger step for discrete event)
                    val maxX = desktopWidth.toFloat()
                    trackpadCursorX = (trackpadCursorX + trackpadDiscreteStep).coerceIn(0f, maxX)
                    moonlightBridge?.sendAbsolutePosition(
                        trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                        desktopWidth, desktopHeight
                    )
                    Log.i(TAG, "[INPUT] Trackpad: cursor right to ${trackpadCursorX.toInt()}")
                } else {
                    // Gesture mode: zoom out
                    handleZoomGesture(zoomIn = false)
                }
            }

            is TempleAction.SlideBackward -> {
                // Zoom adjustment mode takes priority
                if (binding.gameMenuOverlay.isInZoomAdjustmentMode()) {
                    Log.i(TAG, "[ZOOM] SlideBackward in zoom adjustment mode")
                    binding.gameMenuOverlay.adjustZoomByStep(-1)  // Backward = zoom out
                    return
                }
                if (isMenuVisible) {
                    // Menu handles navigation via SlideContinuous
                } else if (inputMode == InputMode.TRACKPAD) {
                    // Trackpad mode: move cursor left (larger step for discrete event)
                    val maxX = desktopWidth.toFloat()
                    trackpadCursorX = (trackpadCursorX - trackpadDiscreteStep).coerceIn(0f, maxX)
                    moonlightBridge?.sendAbsolutePosition(
                        trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                        desktopWidth, desktopHeight
                    )
                    Log.i(TAG, "[INPUT] Trackpad: cursor left to ${trackpadCursorX.toInt()}")
                } else {
                    // Gesture mode: zoom in
                    handleZoomGesture(zoomIn = true)
                }
            }

            is TempleAction.SlideUpwards -> {
                if (isMenuVisible) {
                    // Menu navigation handled by SlideContinuous
                } else if (inputMode == InputMode.TRACKPAD) {
                    // Trackpad mode: move cursor up
                    val maxY = desktopHeight.toFloat()
                    trackpadCursorY = (trackpadCursorY - trackpadDiscreteStep).coerceIn(0f, maxY)
                    moonlightBridge?.sendAbsolutePosition(
                        trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                        desktopWidth, desktopHeight
                    )
                    Log.i(TAG, "[INPUT] Trackpad: cursor up to ${trackpadCursorY.toInt()}")
                } else {
                    // Gesture mode: scroll up
                    Log.i(TAG, "[INPUT] Scroll UP")
                    moonlightBridge?.sendMouseScroll(1)
                    showStatusMessage("↑")
                }
            }

            is TempleAction.SlideDownwards -> {
                if (isMenuVisible) {
                    // Menu navigation handled by SlideContinuous
                } else if (inputMode == InputMode.TRACKPAD) {
                    // Trackpad mode: move cursor down
                    val maxY = desktopHeight.toFloat()
                    trackpadCursorY = (trackpadCursorY + trackpadDiscreteStep).coerceIn(0f, maxY)
                    moonlightBridge?.sendAbsolutePosition(
                        trackpadCursorX.toInt(), trackpadCursorY.toInt(),
                        desktopWidth, desktopHeight
                    )
                    Log.i(TAG, "[INPUT] Trackpad: cursor down to ${trackpadCursorY.toInt()}")
                } else {
                    // Gesture mode: scroll down
                    Log.i(TAG, "[INPUT] Scroll DOWN")
                    moonlightBridge?.sendMouseScroll(-1)
                    showStatusMessage("↓")
                }
            }

            is TempleAction.SlideContinuous -> {
                val isVertical = action.vertical
                val delta = action.delta
                val inZoomMode = binding.gameMenuOverlay.isInZoomAdjustmentMode()

                // Zoom adjustment mode takes priority - horizontal swipes adjust zoom
                if (inZoomMode) {
                    if (!isVertical) {
                        binding.gameMenuOverlay.handleZoomAdjustmentSwipe(delta)
                    }
                    return
                }

                // Menu navigation takes priority when visible
                if (isMenuVisible) {
                    if (isVertical) {
                        handleMenuNavigation(isVertical, delta)
                    } else {
                        // Horizontal swipes navigate in/out of category options
                        handleMenuHorizontalNavigation(delta)
                    }
                    return
                }

                // Handle based on current input mode
                when (inputMode) {
                    InputMode.TRACKPAD -> {
                        // Trackpad mode: GestureDetector handles cursor movement (lower latency)
                        // Mercury SDK SlideContinuous doesn't need to do anything here
                        // Note: Cursor movement is handled by trackpadGestureDetector.onScroll()
                        // in dispatchTouchEvent() - don't duplicate here
                    }
                    InputMode.GESTURE -> {
                        // Gesture mode: original zoom/scroll behavior
                        if (isVertical) {
                            // Vertical swipes scroll content
                            verticalSlideAccumulator += delta
                            if (kotlin.math.abs(verticalSlideAccumulator) >= VERTICAL_SCROLL_THRESHOLD) {
                                val scrollDirection = if (verticalSlideAccumulator < 0) -1 else 1
                                Log.i(TAG, "[INPUT] Vertical scroll: direction=$scrollDirection")
                                moonlightBridge?.sendMouseScroll(scrollDirection)
                                showStatusMessage(if (scrollDirection > 0) "↑" else "↓")
                                verticalSlideAccumulator = 0f
                            }
                        } else {
                            // Horizontal swipe: zoom
                            slideAccumulator++
                            if (slideAccumulator >= SLIDE_EVENTS_PER_ZOOM_STEP) {
                                slideAccumulator = 0
                                val zoomIn = delta < 0
                                handleZoomGesture(zoomIn)
                            }
                        }
                    }
                }
            }

            is TempleAction.ActionUp -> {
                // Reset slide accumulators when slide ends
                Log.i(TAG, "[INPUT] ActionUp - resetting slide accumulators")
                slideAccumulator = 0
                verticalSlideAccumulator = 0f
                hasMovedMenuThisSwipe = false  // Reset for next swipe gesture
                hasMovedMenuHorizontallyThisSwipe = false  // Reset horizontal guard
                // Note: Zoom adjustment mode requires double-click to confirm (not finger lift)
            }

            else -> {
                Log.i(TAG, "[INPUT] Unhandled gesture: ${action::class.simpleName}")
            }
        }
    }

    /**
     * Get the current cursor position, considering head tracking state and click-freeze.
     */
    private fun getCurrentCursorPosition(): CursorPosition? {
        return when {
            !isCursorTrackingEnabled -> lastCursorPosition
            System.currentTimeMillis() < clickFreezeUntil -> clickFreezePosition
            else -> headGazeCursor?.getCursorPosition()
        }
    }

    /**
     * Recenter the view - current head position becomes "forward".
     *
     * This fixes 3DOF yaw drift by setting the current head orientation
     * as the new "forward" direction.
     */
    private fun recenterView() {
        val renderer = streamRenderer

        Log.i(TAG, "[VIEW] recenterView called - renderer=${renderer != null}, displayMode=${renderer?.displayMode}, cursorTracking=$isCursorTrackingEnabled, inputMode=$inputMode")

        // 1. Move cursor to desktop center (both trackpad and head gaze)
        val centerX = desktopWidth / 2f
        val centerY = desktopHeight / 2f
        trackpadCursorX = centerX
        trackpadCursorY = centerY
        headGazeCursor?.recenter()

        // 2. Update renderer with centered cursor position
        renderer?.apply {
            cursorX = centerX
            cursorY = centerY
        }

        // 3. Send cursor position to PC
        moonlightBridge?.centerCursor(desktopWidth, desktopHeight)

        // 4. Reset head orientation baseline based on display mode
        when (renderer?.displayMode) {
            DisplayMode.FLOATING_MONITOR -> {
                // Recenter the virtual screen (head orientation baseline)
                Log.i(TAG, "[VIEW] Recentering floating monitor - yaw=${renderer.headYawDegrees}, pitch=${renderer.headPitchDegrees}")
                renderer.recenterVirtualScreen()
                Log.i(TAG, "[VIEW] Virtual screen recentered, cursor at ($centerX, $centerY)")
                showStatusMessage("View Recentered")
            }
            DisplayMode.CURVED_MONITOR -> {
                // Recenter the curved cylinder view (head orientation baseline)
                Log.i(TAG, "[VIEW] Recentering curved monitor - yaw=${renderer.headYawDegrees}, pitch=${renderer.headPitchDegrees}")
                renderer.recenterCurvedMonitor()
                Log.i(TAG, "[VIEW] Curved monitor recentered, cursor at ($centerX, $centerY)")
                showStatusMessage("View Recentered")
            }
            DisplayMode.KEYHOLE_PANNING -> {
                // Recenter keyhole viewport using current head orientation
                renderer.recenterKeyhole()
                Log.i(TAG, "[VIEW] Keyhole viewport recentered, cursor at ($centerX, $centerY)")
                showStatusMessage("View Recentered")
            }
            null -> {
                Log.w(TAG, "[VIEW] Recenter failed - renderer is null")
            }
        }

        // Clear any click-freeze state
        clickFreezePosition = null
        clickFreezeUntil = 0L
    }

    /**
     * Apply a specific zoom level to the current display mode.
     *
     * @param level Zoom level (0.5 = 50%, 1.0 = 100%, 3.0 = 300%)
     */
    private fun applyZoomLevel(level: Float) {
        val renderer = streamRenderer ?: return
        val clampedLevel = level.coerceIn(0.5f, 3.0f)
        when (renderer.displayMode) {
            DisplayMode.FLOATING_MONITOR -> {
                renderer.setVirtualScreenScale(clampedLevel)
            }
            DisplayMode.CURVED_MONITOR -> {
                // Convert scale to radius: larger scale = closer = smaller radius
                // Scale 1.0 = radius 2.5m (default), scale 0.5 = radius 5.0m, scale 3.0 = radius 0.83m
                val radius = (2.5f / clampedLevel).coerceIn(0.5f, 5.0f)
                renderer.setCurvedMonitorRadius(radius)
            }
            DisplayMode.KEYHOLE_PANNING -> {
                val keyholeZoom = ((3.0f - clampedLevel) / 2.5f).coerceIn(0f, 1f)
                keyholeViewport?.setZoomLevel(keyholeZoom)
            }
        }
    }

    /**
     * Handle zoom gestures for both display modes.
     *
     * In FLOATING_MONITOR mode: Uses scale-based zoom via VirtualScreenController
     * In KEYHOLE_PANNING mode: Uses viewport zoom via KeyholeViewport
     *
     * @param zoomIn true for zoom in (closer view), false for zoom out (show more)
     */
    private fun handleZoomGesture(zoomIn: Boolean) {
        if (isZoomLocked) {
            showStatusMessage("Zoom Locked")
            return
        }

        val renderer = streamRenderer ?: return

        when (renderer.displayMode) {
            DisplayMode.FLOATING_MONITOR -> {
                // Scale-based zoom (Gemini recommendation)
                val vsc = renderer.virtualScreenController
                if (vsc != null) {
                    if (zoomIn) {
                        vsc.zoomIn()
                    } else {
                        vsc.zoomOut()
                    }
                    showZoomFeedback()
                }
            }
            DisplayMode.CURVED_MONITOR -> {
                // Distance-based zoom for curved monitor (changes cylinder radius)
                val cc = renderer.cylinderController
                if (cc != null) {
                    if (zoomIn) {
                        cc.zoomIn()
                    } else {
                        cc.zoomOut()
                    }
                    showZoomFeedback()
                }
            }
            DisplayMode.KEYHOLE_PANNING -> {
                // Existing keyhole zoom logic
                val viewport = keyholeViewport
                if (viewport != null) {
                    val zoomDelta = if (zoomIn) -zoomStep else zoomStep
                    viewport.adjustZoom(zoomDelta)
                    val magnification = 1f + (1f - viewport.getZoomLevel()) * 2f
                    Log.i(TAG, "[INPUT] Zoom ${if (zoomIn) "IN" else "OUT"}: ${"%.1f".format(magnification)}x")
                    showStatusMessage("Zoom: ${"%.1f".format(magnification)}x")
                }
            }
        }
    }

    /**
     * Show zoom feedback for floating monitor mode.
     *
     * Displays the current scale as a percentage (100% = default).
     */
    private fun showZoomFeedback() {
        val vsc = streamRenderer?.virtualScreenController ?: return
        val scale = vsc.getCurrentScale()
        val zoomPercent = (scale * 100).toInt()
        Log.i(TAG, "[INPUT] Floating monitor zoom: ${zoomPercent}%")
        showStatusMessage("Zoom: ${zoomPercent}%")
    }

    /**
     * Send right-click at the current cursor position.
     */
    private fun sendRightClickAtCursor() {
        // Use same logic as left-click: check inputMode for correct cursor source
        val (clickX, clickY) = when (inputMode) {
            InputMode.TRACKPAD -> Pair(trackpadCursorX.toInt(), trackpadCursorY.toInt())
            InputMode.GESTURE -> {
                val pos = getCurrentCursorPosition()
                if (pos != null) {
                    Pair(pos.x.toInt(), pos.y.toInt())
                } else {
                    Log.w(TAG, "[INPUT] Right click failed - no cursor position")
                    return
                }
            }
        }

        Log.i(TAG, "[INPUT] Right click at ($clickX, $clickY) in ${inputMode.name} mode")
        moonlightBridge?.sendAbsolutePosition(clickX, clickY, desktopWidth, desktopHeight)
        moonlightBridge?.sendMouseClick(MouseButtonPacket.BUTTON_RIGHT)
    }

    // SensorEventListener implementation
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            headTimestampNs = event.timestamp

            // Extract yaw/pitch from quaternion for floating monitor mode
            // For AR glasses on head:
            // - Yaw (look left/right) = rotation around world Z-axis (up)
            // - Pitch (look up/down) = rotation around world X-axis (right)
            val qx = event.values[0]
            val qy = event.values[1]
            val qz = event.values[2]
            val qw = if (event.values.size > 3) event.values[3]
                     else kotlin.math.sqrt(1f - qx*qx - qy*qy - qz*qz)

            // Azimuth/Yaw: rotation around Z-axis (look left = positive, look right = negative)
            val yawRadians = kotlin.math.atan2(
                2.0 * (qw * qz + qx * qy),
                1.0 - 2.0 * (qy * qy + qz * qz)
            )
            // Pitch: rotation around X-axis (look up = negative, look down = positive)
            val pitchRadians = kotlin.math.asin(
                (2.0 * (qw * qy - qz * qx)).coerceIn(-1.0, 1.0)
            )

            val yawDegrees = Math.toDegrees(yawRadians).toFloat()
            val pitchDegrees = Math.toDegrees(pitchRadians).toFloat()

            // Update current values for renderer (floating monitor mode)
            currentYaw = yawDegrees
            currentPitch = pitchDegrees

            // If head tracking is disabled, cursor position stays fixed but we still
            // need to update viewport panning based on head movement
            if (!isCursorTrackingEnabled) {
                // Update renderer with last known cursor position
                val lastPos = lastCursorPosition
                if (lastPos != null) {
                    streamRenderer?.apply {
                        this.headTimestampNs = headTimestampNs
                        headYawDegrees = yawDegrees
                        headPitchDegrees = pitchDegrees
                        cursorX = lastPos.x
                        cursorY = lastPos.y
                    }
                }
                // Continue to process head orientation for viewport panning
                // (cursor position is just not updated)
            }

            // Use quaternion-based tracking (gimbal-lock free)
            headGazeCursor?.updateFromQuaternion(event.values, headTimestampNs)

            // Recenter on first sensor reading to establish "forward" direction
            if (needsRecenter) {
                Log.i(TAG, "[SENSOR] First reading - recentering cursor")
                headGazeCursor?.recenter()
                keyholeViewport?.recenter(0f, 0f) // Reset viewport too
                moonlightBridge?.centerCursor(desktopWidth, desktopHeight)   // Start with PC cursor at center
                needsRecenter = false
            }

            // Determine cursor position based on input mode and cursor tracking state:
            // - TRACKPAD mode: Always use trackpadCursorX/Y (updated by gesture detector)
            // - GESTURE mode: Use head gaze cursor (unless tracking disabled)
            val cursorPos: CursorPosition? = when (inputMode) {
                InputMode.TRACKPAD -> {
                    // Trackpad mode: use trackpad cursor position
                    // The gesture detector updates trackpadCursorX/Y and sends to PC
                    CursorPosition(trackpadCursorX, trackpadCursorY)
                }
                InputMode.GESTURE -> {
                    // Gesture mode: use head gaze cursor based on cursor tracking state
                    when {
                        !isCursorTrackingEnabled -> lastCursorPosition
                        System.currentTimeMillis() < clickFreezeUntil -> clickFreezePosition
                        else -> {
                            // Clear freeze state and use current head position
                            if (clickFreezePosition != null) {
                                clickFreezePosition = null
                            }
                            headGazeCursor?.getCursorPosition()
                        }
                    }
                }
            }

            // Pass cursor position and head orientation to renderer
            // Head orientation always updates (for view matrix), cursor depends on input mode
            streamRenderer?.apply {
                this.headTimestampNs = headTimestampNs
                headYawDegrees = yawDegrees
                headPitchDegrees = pitchDegrees
                if (cursorPos != null) {
                    cursorX = cursorPos.x
                    cursorY = cursorPos.y
                }
            }

            // Send cursor position to Moonlight for PC cursor movement (absolute positioning)
            // Only update in GESTURE mode (trackpad mode sends via gesture detector)
            if (cursorPos != null && inputMode == InputMode.GESTURE && isCursorTrackingEnabled) {
                moonlightBridge?.sendAbsolutePosition(cursorPos.x.toInt(), cursorPos.y.toInt(), desktopWidth, desktopHeight)
            }

            // Edge glint only makes sense when streaming desktop (disabled until Phase 4)
            // updateEdgeGlint()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun updateEdgeGlint() {
        val cursor = headGazeCursor?.getCursorPosition() ?: return
        val viewport = keyholeViewport ?: return
        val glint = edgeGlint ?: return

        // Skip edge glint in overview mode (entire desktop visible)
        if (viewport.getMode() == KeyholeViewport.Mode.OVERVIEW) {
            hideAllGlints()
            return
        }

        // Calculate viewport rect in desktop coordinates
        val textureRect = viewport.update(currentYaw, currentPitch, headTimestampNs)
        val viewportRect = EdgeGlint.Rect(
            left = (textureRect.u0 * desktopWidth).toInt(),
            top = (textureRect.v0 * desktopHeight).toInt(),
            right = (textureRect.u1 * desktopWidth).toInt(),
            bottom = (textureRect.v1 * desktopHeight).toInt()
        )

        // Calculate glint state
        val glintState = glint.calculate(cursor.x.toInt(), cursor.y.toInt(), viewportRect)

        // Update UI on main thread
        runOnUiThread {
            updateGlintViews(glintState)
        }
    }

    private fun updateGlintViews(state: EdgeGlint.GlintState) {
        // Edge glint views removed from layout for binocular display
    }

    private fun hideAllGlints() {
        // Edge glint views removed from layout for binocular display
    }

    private fun showStatusMessage(message: String) {
        runOnUiThread {
            // Update both eyes
            binding.tvStatus.text = message
            binding.tvStatusRight.text = message
            binding.statusContainer.visibility = View.VISIBLE

            // Auto-hide after 2 seconds
            binding.statusContainer.postDelayed({
                binding.statusContainer.visibility = View.GONE
            }, 2000)
        }
    }

    // Input methods - connected to Moonlight via MoonlightBridge
    private fun sendClick(x: Int, y: Int) {
        Log.i(TAG, "[INPUT] Click at ($x, $y)")
        moonlightBridge?.sendAbsolutePosition(x, y, desktopWidth, desktopHeight)
        moonlightBridge?.sendMouseClick()
    }

    private fun sendRightClick(x: Int, y: Int) {
        Log.i(TAG, "[INPUT] Right click at ($x, $y)")
        moonlightBridge?.sendAbsolutePosition(x, y, desktopWidth, desktopHeight)
        moonlightBridge?.sendMouseClick(com.limelight.nvstream.input.MouseButtonPacket.BUTTON_RIGHT)
    }

    private fun sendScroll(direction: Int) {
        Log.i(TAG, "[INPUT] Scroll: $direction")
        moonlightBridge?.sendMouseScroll(direction)
    }

    // ========================================================================
    // StreamResolutionListener implementation
    // ========================================================================

    /**
     * Called when the actual stream resolution is detected.
     *
     * This is the core of auto-detection: RayDesk adapts to whatever
     * resolution Sunshine sends, rather than requesting specific dimensions.
     *
     * Updates:
     * - Keyhole viewport dimensions
     * - Head gaze cursor bounds
     * - StreamRenderer (mesh aspect ratio, virtual screen controller)
     *
     * @param actualWidth The actual width of the video stream
     * @param actualHeight The actual height of the video stream
     */
    override fun onStreamResolutionChanged(actualWidth: Int, actualHeight: Int) {
        Log.i(TAG, "[RESOLUTION] Stream resolution detected: ${actualWidth}x${actualHeight}")

        // Update our stored dimensions
        desktopWidth = actualWidth
        desktopHeight = actualHeight

        // Update keyhole viewport
        keyholeViewport?.updateDesktopDimensions(actualWidth, actualHeight)

        // Update head gaze cursor
        headGazeCursor?.updateScreenDimensions(actualWidth, actualHeight)

        // Update renderer (mesh aspect ratio + virtual screen controller)
        // This must be done on GL thread
        glTextureRenderer?.runOnRenderThread {
            streamRenderer?.updateStreamResolution(actualWidth, actualHeight)
        }

        runOnUiThread {
            showStatusMessage("Resolution: ${actualWidth}x${actualHeight}")
        }
    }

    override fun onResume() {
        super.onResume()

        // Register sensor listener
        gameRotationSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "[SENSOR] Listener registered")
        }

        // Resume GL rendering
        glTextureRenderer?.resume()
    }

    override fun onPause() {
        super.onPause()

        // Unregister sensor listener
        sensorManager.unregisterListener(this)
        Log.i(TAG, "[SENSOR] Listener unregistered")

        // Cancel pending reconnection to prevent background connection attempts
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectionManager.cancelReconnection()
        Log.i(TAG, "[LIFECYCLE] Cancelled pending reconnections in onPause")

        // Pause GL rendering
        glTextureRenderer?.pause()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "[LIFECYCLE] onStop - disconnecting streaming")

        // Disconnect Moonlight to prevent background drain and stale server state
        // When user puts headset back on, ConnectionActivity will be shown
        moonlightBridge?.disconnect()

        // Reset connection state
        isConnecting = false
        isStreaming = false
        hasEverConnectedSuccessfully = false

        // Reset reconnection manager state so next session starts fresh
        reconnectionManager.onReconnectSuccess()  // This resets internal state

        Log.i(TAG, "[LIFECYCLE] Streaming disconnected in onStop")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop connection quality polling
        handler.removeCallbacks(qualityUpdateRunnable)

        // Cancel any pending reconnection
        reconnectionManager.cancelReconnection()

        // Stop mirroring
        binding.mirrorView.stopMirroring()

        // Stop Moonlight streaming
        moonlightBridge?.release()
        moonlightBridge = null

        // Stop test pattern generator
        testPatternGenerator?.stop()
        testPatternGenerator = null

        // Stop GL rendering and release resources
        glTextureRenderer?.runOnRenderThread {
            streamRenderer?.release()
            videoProvider?.release()
        }
        glTextureRenderer?.stop()
        glTextureRenderer = null

        Log.i(TAG, "[CLEANUP] StreamingActivity destroyed")
    }
}
