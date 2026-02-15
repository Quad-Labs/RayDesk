package com.raydesk.ui

import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.raydesk.data.ServerRepository
import com.raydesk.streaming.MoonlightBridge
import com.raydesk.streaming.PairingResult
import com.raydesk.streaming.ServerDiscoveryManager
import com.raydesk.test.R
import com.raydesk.test.databinding.ActivityConnectionBinding
import com.raydesk.video.NoopSurfaceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Display mode configurations for different monitor setups.
 *
 * IMPORTANT: These presets do NOT request specific resolutions from Sunshine.
 * RayDesk auto-detects the actual stream resolution and adapts accordingly.
 *
 * The preset informs RayDesk about the user's monitor setup so it can:
 * - Use appropriate aspect ratio handling
 * - Set optimal bitrate for text clarity
 * - Configure multi-monitor layout (future)
 *
 * Bitrate guidelines (from Gemini blind spot analysis):
 * - Desktop UI has high-frequency detail (text, thin lines) that suffers from compression
 * - In AR, virtual screen is effectively 100 inches -> artifacts magnified
 * - Minimum 20 Mbps recommended for text clarity (10 Mbps too low)
 * - Scale up for higher resolutions and multi-monitor
 */
enum class DisplayPreset(
    val label: String,
    val description: String,
    val bitrate: Int,           // Recommended bitrate in kbps
    val expectedAspect: Float   // Expected aspect ratio for validation
) {
    AUTO_DETECT("Auto Detect", "Accept any resolution from Sunshine", 20_000, 16f/9f),
    SINGLE_16_9("Single 16:9", "Standard monitor (1080p/1440p/4K)", 20_000, 16f/9f),
    ULTRAWIDE_21_9("Ultrawide 21:9", "Ultrawide monitor (2560x1080, 3440x1440)", 25_000, 21f/9f),
    DUAL_MONITORS("Dual Monitors", "Side-by-side monitors", 30_000, 32f/9f),
    TRIPLE_MONITORS("Triple Monitors", "Three monitors side-by-side", 40_000, 48f/9f);

    companion object {
        fun fromLabel(label: String): DisplayPreset {
            return values().find { it.label == label } ?: AUTO_DETECT
        }

        fun labels(): Array<String> = values().map { it.label }.toTypedArray()
    }
}

/**
 * Server selection activity for RayDesk (Standard Edition).
 *
 * Displays saved servers and discovered servers on the network.
 * Users navigate with temple gestures:
 * - Swipe front->back: Previous item
 * - Swipe back->front: Next item
 * - Single tap: Select/connect to focused item
 * - Double tap: Exit activity
 *
 * On server selection:
 * - If paired: Launch StreamingActivity directly
 * - If not paired: Start pairing flow (PIN entry)
 *
 * Standard Edition: No Bluetooth pairing - goes straight to streaming.
 */
class ConnectionActivity : BaseMirrorActivity<ActivityConnectionBinding>(), SensorEventListener {

    companion object {
        private const val TAG = "ConnectionActivity"

        // Head tracking config for PIN entry
        private const val PIN_GRID_WIDTH = 5  // 5 columns (1-5, 6-0)
        private const val PIN_GRID_HEIGHT = 2 // 2 rows

        // Trackpad navigation thresholds (matches StreamingActivity)
        private const val NAVIGATION_THRESHOLD = 150f  // Delta threshold to move selection
    }

    // Dependencies
    private lateinit var serverRepository: ServerRepository
    private lateinit var discoveryManager: ServerDiscoveryManager
    private lateinit var moonlightBridge: MoonlightBridge

    // Sensors for head tracking (PIN entry)
    private lateinit var sensorManager: SensorManager
    private var gameRotationSensor: Sensor? = null

    // State
    private var savedServerItems = listOf<ServerListItem>()
    private var discoveredServerItems = listOf<ServerListItem>()
    private lateinit var focusNavigator: FocusNavigator

    // Pairing state
    private var pinEntryDialog: PinEntryDialog? = null
    private var currentPairingServer: ServerListItem? = null
    private var pairingJob: Job? = null
    private var isPairingMode = false

    // Delete confirmation state
    private var isDeleteConfirmationMode = false
    private var serverPendingDeletion: ServerListItem? = null

    // Head tracking state for PIN entry
    private var baseYaw = 0f
    private var basePitch = 0f
    private var needsHeadCalibration = true

    // Display preset selection (default: auto-detect)
    private var selectedPreset: DisplayPreset = DisplayPreset.AUTO_DETECT

    // Trackpad navigation state (for SlideContinuous)
    private var verticalSlideAccumulator = 0f
    private var hasMovedThisSwipe = false

    // Jobs
    private var discoveryJob: Job? = null
    private var hideStatusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.i(TAG, "[INIT] ConnectionActivity created (Standard Edition)")

        initDependencies()
        initSensors()
        initFocusNavigator()
        initServerListViews()
        initResolutionSpinner()
        initTempleGestures()
    }

    /**
     * Initialize the resolution preset selector (tap-to-cycle).
     *
     * AR glasses don't support Spinner dropdowns with temple gestures.
     * Instead, users navigate to the resolution row with swipe gestures
     * and tap to cycle through presets.
     */
    private fun initResolutionSpinner() {
        mBindingPair.updateView {
            // Initial display will be set by updateUI() call
            tvResolution.setOnClickListener {
                cycleResolutionPreset()
            }
        }
        Log.i(TAG, "[INIT] Resolution selector initialized (tap-to-cycle)")
    }

    /**
     * Cycle to the next display mode preset.
     */
    private fun cycleResolutionPreset() {
        val presets = DisplayPreset.values()
        val currentIdx = presets.indexOf(selectedPreset)
        val nextIdx = (currentIdx + 1) % presets.size
        selectedPreset = presets[nextIdx]

        Log.i(TAG, "[UI] Display preset changed: ${selectedPreset.label} (bitrate=${selectedPreset.bitrate}kbps)")

        // Update display with focus state
        updateUI()
    }

    /**
     * Trigger a manual server search/refresh.
     */
    private fun triggerServerSearch() {
        Log.i(TAG, "[SEARCH] Manual server search triggered")

        // Show spinner
        mBindingPair.updateView {
            spinnerSearch.visibility = View.VISIBLE
        }

        // Restart discovery
        stopDiscovery()
        startDiscovery()

        // Hide spinner after a delay
        lifecycleScope.launch {
            delay(3000)
            mBindingPair.updateView {
                spinnerSearch.visibility = View.GONE
            }
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (gameRotationSensor != null) {
            Log.i(TAG, "[SENSOR] TYPE_GAME_ROTATION_VECTOR available for PIN entry")
        } else {
            Log.w(TAG, "[SENSOR] TYPE_GAME_ROTATION_VECTOR not available - PIN entry will use gestures only")
        }
    }

    private fun initServerListViews() {
        // Set up click listeners for ServerListViews
        mBindingPair.updateView {
            slvSavedServers.setOnItemClickListener { index, item ->
                Log.i(TAG, "[CLICK] Saved server clicked: ${item.name}")
                handleServerItemClick(index, item)
            }

            slvDiscoveredServers.setOnItemClickListener { index, item ->
                Log.i(TAG, "[CLICK] Discovered server clicked: ${item.name}")
                handleServerItemClick(savedServerItems.size + index, item)
            }

            btnSettings.setOnClickListener {
                Log.i(TAG, "[UI] Settings button clicked")
                launchSettings()
            }

            tvSearchServer.setOnClickListener {
                Log.i(TAG, "[CLICK] Search server clicked")
                triggerServerSearch()
            }
        }
    }

    private fun launchSettings() {
        val intent = Intent(this, CursorSettingsActivity::class.java)
        startActivity(intent)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleServerItemClick(absoluteIndex: Int, item: ServerListItem) {
        when (item.type) {
            ServerListItem.Type.SAVED -> {
                Log.i(TAG, "[SELECT] Saved server: ${item.name}")
                launchStreaming(item.uuid, item.address, item.name)
            }
            ServerListItem.Type.DISCOVERED -> {
                Log.i(TAG, "[SELECT] Discovered server: ${item.name}")
                if (item.isPaired) {
                    launchStreaming(item.uuid, item.address, item.name)
                } else {
                    startPairingFlow(item)
                }
            }
            ServerListItem.Type.MANUAL_ENTRY -> {
                Log.i(TAG, "[SELECT] Manual IP entry")
                showStatus("Manual IP entry...")
                Log.w(TAG, "[SELECT] Manual IP entry not implemented yet")
            }
        }
    }

    private fun initDependencies() {
        serverRepository = ServerRepository(this)
        discoveryManager = ServerDiscoveryManager(this)

        // Create MoonlightBridge for pairing (no video needed, use NoopSurfaceProvider)
        moonlightBridge = MoonlightBridge(this, NoopSurfaceProvider())
    }

    private fun initFocusNavigator() {
        focusNavigator = FocusNavigator(
            savedCount = 0,
            discoveredCount = 0,
            hasManualEntry = true,
            hasBluetoothPairing = false  // Standard Edition: No Bluetooth
        )
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
        // Priority 1: PIN dialog
        if (isPairingMode && pinEntryDialog != null) {
            handlePinDialogGesture(action)
            return
        }

        // Priority 2: Delete confirmation
        if (isDeleteConfirmationMode) {
            handleDeleteConfirmationGesture(action)
            return
        }

        // Priority 3: Normal navigation
        when (action) {
            is TempleAction.SlideContinuous -> {
                val isVertical = action.vertical
                val delta = action.delta

                if (isVertical && !hasMovedThisSwipe) {
                    verticalSlideAccumulator += delta
                    if (kotlin.math.abs(verticalSlideAccumulator) >= NAVIGATION_THRESHOLD) {
                        hasMovedThisSwipe = true
                        // Swipe down (positive delta) = selection moves down (natural direction)
                        if (verticalSlideAccumulator > 0) {
                            focusNavigator.moveNext()
                        } else {
                            focusNavigator.movePrevious()
                        }
                        verticalSlideAccumulator = 0f
                        updateUI()
                    }
                }
            }

            is TempleAction.ActionUp -> {
                // Reset accumulator when finger lifts off trackpad
                verticalSlideAccumulator = 0f
                hasMovedThisSwipe = false
            }

            is TempleAction.SlideBackward -> {
                focusNavigator.movePrevious()
                updateUI()
            }

            is TempleAction.SlideForward -> {
                focusNavigator.moveNext()
                updateUI()
            }

            is TempleAction.Click -> {
                handleSelection()
            }

            is TempleAction.DoubleClick -> {
                finish()
            }

            is TempleAction.TripleClick -> {
                handleTripleClickDelete()
            }

            else -> {
            }
        }
    }

    private fun handlePinDialogGesture(action: TempleAction) {
        val dialog = pinEntryDialog ?: return

        when (action) {
            is TempleAction.Click -> {
                dialog.selectFocusedDigit()
            }

            is TempleAction.DoubleClick -> {
                dialog.handleBackspace()
            }

            else -> {
            }
        }
    }

    private fun handleSelection() {
        when (focusNavigator.getCurrentItemType()) {
            FocusNavigator.ItemType.RESOLUTION_PRESET -> {
                Log.i(TAG, "[SELECT] Resolution preset - cycling to next")
                cycleResolutionPreset()
            }

            FocusNavigator.ItemType.SEARCH_SERVERS -> {
                Log.i(TAG, "[SELECT] Search servers")
                triggerServerSearch()
            }

            FocusNavigator.ItemType.SAVED -> {
                val index = focusNavigator.getSavedServerIndex() ?: return
                val item = savedServerItems.getOrNull(index) ?: return
                Log.i(TAG, "[SELECT] Saved server: ${item.name}")
                launchStreaming(item.uuid, item.address, item.name)
            }

            FocusNavigator.ItemType.DISCOVERED -> {
                val index = focusNavigator.getDiscoveredServerIndex() ?: return
                val item = discoveredServerItems.getOrNull(index) ?: return
                Log.i(TAG, "[SELECT] Discovered server: ${item.name}")

                if (item.isPaired) {
                    launchStreaming(item.uuid, item.address, item.name)
                } else {
                    startPairingFlow(item)
                }
            }

            FocusNavigator.ItemType.MANUAL_ENTRY -> {
                Log.i(TAG, "[SELECT] Manual IP entry")
                showStatus("Manual IP entry...")
                Log.w(TAG, "[SELECT] Manual IP entry not implemented yet")
            }

            FocusNavigator.ItemType.BLUETOOTH_PAIRING -> {
                // Standard Edition: Bluetooth removed - this case shouldn't occur
                Log.w(TAG, "[SELECT] Bluetooth pairing not available in Standard Edition")
            }
        }
    }

    // ==================== Delete Server Flow ====================

    /**
     * Handle triple-click to initiate delete for focused saved server.
     */
    private fun handleTripleClickDelete() {
        if (focusNavigator.getCurrentItemType() != FocusNavigator.ItemType.SAVED) {
            return
        }

        val index = focusNavigator.getSavedServerIndex() ?: return
        val item = savedServerItems.getOrNull(index) ?: return

        Log.i(TAG, "[DELETE] Initiating delete for: ${item.name}")
        showDeleteConfirmation(item)
    }

    /**
     * Show delete confirmation UI.
     */
    private fun showDeleteConfirmation(server: ServerListItem) {
        serverPendingDeletion = server
        isDeleteConfirmationMode = true

        mBindingPair.updateView {
            // Hide normal UI
            slvSavedServers.visibility = View.GONE
            slvDiscoveredServers.visibility = View.GONE
            tvSavedHeader.visibility = View.GONE
            tvDiscoveredHeader.visibility = View.GONE
            tvManualEntry.visibility = View.GONE
            tvResolution.visibility = View.GONE
            tvSourceLabel.visibility = View.GONE
            tvSearchServer.visibility = View.GONE
            spinnerSearch.visibility = View.GONE
            tvHint.visibility = View.GONE

            // Show confirmation
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Delete \"${server.name}\"?\n\nTap to confirm\nDouble-tap to cancel"
            tvStatus.textSize = 20f
        }

    }

    /**
     * Handle gestures during delete confirmation.
     */
    private fun handleDeleteConfirmationGesture(action: TempleAction) {
        when (action) {
            is TempleAction.Click -> {
                confirmDeleteServer()
            }

            is TempleAction.DoubleClick -> {
                cancelDeleteConfirmation()
            }

            else -> {
            }
        }
    }

    /**
     * Confirm and execute server deletion.
     */
    private fun confirmDeleteServer() {
        val server = serverPendingDeletion ?: return

        Log.i(TAG, "[DELETE] Deleting server: ${server.name} (${server.uuid})")
        serverRepository.deleteServer(server.uuid)

        showStatus("Deleted \"${server.name}\"")
        cleanupDeleteConfirmation()

        // Reload server list
        loadSavedServers()
    }

    /**
     * Cancel the delete confirmation.
     */
    private fun cancelDeleteConfirmation() {
        showStatus("Delete cancelled")
        cleanupDeleteConfirmation()
    }

    /**
     * Clean up delete confirmation state and restore normal UI.
     */
    private fun cleanupDeleteConfirmation() {
        isDeleteConfirmationMode = false
        serverPendingDeletion = null

        mBindingPair.updateView {
            // Restore normal UI
            tvSavedHeader.visibility = View.VISIBLE
            tvDiscoveredHeader.visibility = View.VISIBLE
            tvManualEntry.visibility = View.VISIBLE
            tvResolution.visibility = View.VISIBLE
            tvSourceLabel.visibility = View.VISIBLE
            tvSearchServer.visibility = View.VISIBLE
            tvHint.visibility = View.VISIBLE
            slvSavedServers.visibility = View.VISIBLE
            slvDiscoveredServers.visibility = View.VISIBLE

            tvStatus.textSize = 18f
            tvStatus.visibility = View.GONE
        }

        updateUI()
    }

    /**
     * Launch streaming directly - Standard Edition goes straight to streaming.
     */
    private fun launchStreaming(uuid: String, address: String, name: String) {
        Log.i(TAG, "[LAUNCH] Starting streaming to $name @ $address (preset: ${selectedPreset.label})")

        // Load full server from repository to use createIntent()
        val server = serverRepository.getServer(uuid)
        if (server == null) {
            Log.e(TAG, "[LAUNCH] Server not found in repository: $uuid")
            showStatus("Server not found")
            return
        }

        // Use StreamingActivity.createIntent() for type-safe intent creation with preset
        val intent = StreamingActivity.createIntent(this, server, selectedPreset)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()

        // Load saved servers
        loadSavedServers()

        // Start discovery
        startDiscovery()
    }

    private fun loadSavedServers() {
        val savedServers = serverRepository.getSavedServers()
        savedServerItems = savedServers.map { server ->
            ServerListItem.fromSaved(server, isOnline = true)
        }
        updateFocusNavigator()
        updateUI()
    }

    private fun startDiscovery() {
        discoveryJob = lifecycleScope.launch {
            discoveryManager.startDiscovery().collectLatest { servers ->
                val savedUuids = savedServerItems.map { it.uuid }.toSet()
                discoveredServerItems = servers
                    .filter { discovered ->
                        discovered.uuid !in savedUuids
                    }
                    .map { server ->
                        ServerListItem.fromDiscovered(server)
                    }
                updateFocusNavigator()
                updateUI()
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoveryManager.stopDiscovery()
    }

    private fun updateFocusNavigator() {
        focusNavigator.updateCounts(
            saved = savedServerItems.size,
            discovered = discoveredServerItems.size
        )
    }

    private fun updateUI() {
        mBindingPair.updateView {
            // Update resolution selector focus
            updateResolutionSelectorFocus(tvResolution, tvSourceLabel)

            // Update search server button focus
            updateSearchServerFocus(tvSearchServer)

            // Update saved servers section
            updateSavedServersSection(slvSavedServers, tvSavedHeader)

            // Update discovered servers section
            updateDiscoveredServersSection(slvDiscoveredServers, tvDiscoveredHeader)

            // Update manual entry option
            updateManualEntryOption(tvManualEntry)
        }
    }

    private fun updateResolutionSelectorFocus(textView: TextView, labelView: TextView) {
        val isFocused = focusNavigator.getCurrentItemType() == FocusNavigator.ItemType.RESOLUTION_PRESET
        val indicator = if (isFocused) FocusIndicator.FOCUSED else FocusIndicator.UNFOCUSED

        labelView.text = "${indicator.symbol}  Source:"
        textView.text = "\u25C0 ${selectedPreset.label} \u25B6"

        val color = if (isFocused) {
            ContextCompat.getColor(this, R.color.focus_blue)
        } else {
            Color.WHITE
        }
        textView.setTextColor(color)
        labelView.setTextColor(color)
    }

    private fun updateSearchServerFocus(textView: TextView) {
        val isFocused = focusNavigator.getCurrentItemType() == FocusNavigator.ItemType.SEARCH_SERVERS
        val indicator = if (isFocused) FocusIndicator.FOCUSED else FocusIndicator.UNFOCUSED

        textView.text = "${indicator.symbol}  Search servers"
        textView.setTextColor(if (isFocused) {
            ContextCompat.getColor(this, R.color.focus_blue)
        } else {
            Color.WHITE
        })
    }

    private fun updateSavedServersSection(serverListView: ServerListView, header: TextView) {
        if (savedServerItems.isEmpty()) {
            header.visibility = View.GONE
            serverListView.setItems(emptyList())
            return
        }

        header.visibility = View.VISIBLE
        serverListView.setItems(savedServerItems)

        // Use FocusNavigator's helper method which handles index offset
        val focusedIndex = focusNavigator.getSavedServerIndex() ?: -1
        serverListView.setFocusedIndex(focusedIndex)
    }

    private fun updateDiscoveredServersSection(serverListView: ServerListView, header: TextView) {
        if (discoveredServerItems.isEmpty()) {
            header.visibility = View.GONE
            serverListView.setItems(emptyList())
            return
        }

        header.visibility = View.VISIBLE
        serverListView.setItems(discoveredServerItems)

        // Use FocusNavigator's helper method which handles index offset
        val focusedIndex = focusNavigator.getDiscoveredServerIndex() ?: -1
        serverListView.setFocusedIndex(focusedIndex)
    }

    private fun updateManualEntryOption(textView: TextView) {
        val isFocused = focusNavigator.getCurrentItemType() == FocusNavigator.ItemType.MANUAL_ENTRY
        val indicator = if (isFocused) FocusIndicator.FOCUSED else FocusIndicator.UNFOCUSED

        textView.text = "${indicator.symbol}  + Enter IP manually"
        textView.setTextColor(if (isFocused) {
            ContextCompat.getColor(this, R.color.focus_blue)
        } else {
            Color.WHITE
        })
    }

    private fun showStatus(message: String) {
        mBindingPair.updateView {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = message
        }
        hideStatusJob?.cancel()
        hideStatusJob = lifecycleScope.launch {
            delay(2000)
            mBindingPair.updateView {
                tvStatus.visibility = View.GONE
            }
        }
    }

    // ==================== Pairing Flow ====================

    private fun startPairingFlow(server: ServerListItem) {
        Log.i(TAG, "[PAIRING] Starting pairing flow for ${server.name} @ ${server.address}")

        currentPairingServer = server
        isPairingMode = true

        // Generate random 4-digit PIN
        val pin = (1000..9999).random().toString()
        Log.i(TAG, "[PAIRING] Generated PIN: $pin")

        showPairingPin(pin, server.name)

        pairingJob = lifecycleScope.launch {
            try {
                val result = moonlightBridge.pairWithServer(server.address, pin)

                withContext(Dispatchers.Main) {
                    handlePairingResult(server, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PAIRING] Exception during pairing", e)
                withContext(Dispatchers.Main) {
                    showStatus("Pairing failed: ${e.message}")
                    cleanupPairingState()
                }
            }
        }
    }

    private fun showPairingPin(pin: String, serverName: String) {
        mBindingPair.updateView {
            slvSavedServers.visibility = View.GONE
            slvDiscoveredServers.visibility = View.GONE
            tvSavedHeader.visibility = View.GONE
            tvDiscoveredHeader.visibility = View.GONE
            tvManualEntry.visibility = View.GONE
            tvResolution.visibility = View.GONE
            tvSourceLabel.visibility = View.GONE
            tvSearchServer.visibility = View.GONE
            spinnerSearch.visibility = View.GONE
            tvHint.visibility = View.GONE

            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "PIN: $pin\n\nEnter in Sunshine\n($serverName)"
            tvStatus.textSize = 24f
        }
    }

    private fun handlePairingResult(server: ServerListItem, result: PairingResult) {
        when (result) {
            is PairingResult.Success -> {
                Log.i(TAG, "[PAIRING] Success! Server: ${result.serverName}")

                val savedServer = moonlightBridge.createSavedServer(server.address, result)
                serverRepository.saveServer(savedServer)
                Log.i(TAG, "[PAIRING] Server saved to repository: ${savedServer.name}")

                pinEntryDialog?.dismiss()
                cleanupPairingState()

                showStatus("Paired with ${savedServer.name}!")

                loadSavedServers()

                // Standard Edition: Go straight to streaming
                launchStreaming(savedServer.uuid, savedServer.address, savedServer.name)
            }

            is PairingResult.WrongPin -> {
                Log.w(TAG, "[PAIRING] Wrong PIN")
                showStatus("Wrong PIN - try again")
                pinEntryDialog?.resetPin()
            }

            is PairingResult.Failed -> {
                Log.e(TAG, "[PAIRING] Failed: ${result.reason}")
                showStatus("Pairing failed: ${result.reason}")
                pinEntryDialog?.dismiss()
                cleanupPairingState()
            }
        }
    }

    private fun cleanupPairingState() {
        isPairingMode = false
        currentPairingServer = null
        pinEntryDialog = null
        pairingJob?.cancel()
        pairingJob = null

        sensorManager.unregisterListener(this)

        mBindingPair.updateView {
            tvSavedHeader.visibility = View.VISIBLE
            tvDiscoveredHeader.visibility = View.VISIBLE
            tvManualEntry.visibility = View.VISIBLE
            tvResolution.visibility = View.VISIBLE
            tvSourceLabel.visibility = View.VISIBLE
            tvSearchServer.visibility = View.VISIBLE
            tvHint.visibility = View.VISIBLE
            slvSavedServers.visibility = View.VISIBLE
            slvDiscoveredServers.visibility = View.VISIBLE

            tvStatus.textSize = 18f
            tvStatus.visibility = View.GONE
        }

        updateUI()
    }

    // ==================== SensorEventListener ====================

    override fun onSensorChanged(event: SensorEvent) {
        if (!isPairingMode || pinEntryDialog == null) return

        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val qx = event.values[0]
            val qy = event.values[1]
            val qz = event.values[2]
            val qw = if (event.values.size > 3) event.values[3] else {
                val normSq = qx * qx + qy * qy + qz * qz
                if (normSq < 1f) kotlin.math.sqrt(1f - normSq) else 0f
            }

            val yaw = Math.toDegrees(
                atan2(
                    2.0 * (qw * qy + qx * qz),
                    1.0 - 2.0 * (qy * qy + qz * qz)
                )
            ).toFloat()

            val pitch = Math.toDegrees(
                asin((2.0 * (qw * qx - qz * qy)).coerceIn(-1.0, 1.0))
            ).toFloat()

            if (needsHeadCalibration) {
                baseYaw = yaw
                basePitch = pitch
                needsHeadCalibration = false
                return
            }

            val deltaYaw = yaw - baseYaw
            val deltaPitch = pitch - basePitch

            val col = ((deltaYaw / 8f) + 2).toInt().coerceIn(0, PIN_GRID_WIDTH - 1)
            val row = ((-deltaPitch / 10f) + 0.5f).toInt().coerceIn(0, PIN_GRID_HEIGHT - 1)

            pinEntryDialog?.setFocusPosition(row, col)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    // ==================== HID Keyboard Input ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isExternalKeyboard = (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        val deviceName = event.device?.name ?: "Unknown"

        val keyName = KeyEvent.keyCodeToString(keyCode)
        Log.i(TAG, "[KEYBOARD] Key DOWN: $keyName (code=$keyCode) external=$isExternalKeyboard device=$deviceName")

        showStatus("Key: $keyName ($deviceName)")

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> {
                focusNavigator.movePrevious()
                updateUI()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> {
                focusNavigator.moveNext()
                updateUI()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (isDeleteConfirmationMode) {
                    confirmDeleteServer()
                } else {
                    handleSelection()
                }
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (isDeleteConfirmationMode) {
                    cancelDeleteConfirmation()
                } else {
                    handleTripleClickDelete()
                }
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (isDeleteConfirmationMode) {
                    cancelDeleteConfirmation()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        return super.onKeyUp(keyCode, event)
    }

    // ==================== Lifecycle ====================

    override fun onPause() {
        super.onPause()

        stopDiscovery()

        if (isPairingMode) {
            pinEntryDialog?.dismiss()
            cleanupPairingState()
        }

        if (isDeleteConfirmationMode) {
            cleanupDeleteConfirmation()
        }
    }
}
