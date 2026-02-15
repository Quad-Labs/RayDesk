package com.raydesk.streaming

import android.app.Activity
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import com.raydesk.data.SavedServer
import com.raydesk.video.SurfaceProvider
import com.raydesk.video.VideoSurfaceHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Listener for stream resolution changes.
 *
 * Called when the actual stream resolution differs from the requested resolution.
 * This happens when the host rejects the requested resolution and falls back to
 * its native display resolution.
 *
 * IMPORTANT: Implementations should update their viewport and cursor dimensions
 * to match the actual resolution to avoid distortion.
 */
interface StreamResolutionListener {
    /**
     * Called when the actual stream resolution is determined.
     *
     * @param actualWidth The actual width of the video stream in pixels
     * @param actualHeight The actual height of the video stream in pixels
     */
    fun onStreamResolutionChanged(actualWidth: Int, actualHeight: Int)
}

/**
 * Bridge class for integrating Moonlight streaming with RayDesk video pipeline.
 *
 * This handles:
 * - Creating MediaCodecDecoderRenderer with proper configuration
 * - Wiring VideoSurfaceHolder to the decoder
 * - Managing NvConnection lifecycle
 *
 * Usage:
 * ```
 * val bridge = MoonlightBridge(activity, surfaceProvider)
 * bridge.setConnectionListener(listener)
 * bridge.connect(hostAddress, app, serverCert, cryptoProvider)
 * // ... when done:
 * bridge.disconnect()
 * ```
 */
class MoonlightBridge(
    private val activity: Activity,
    private val surfaceProvider: SurfaceProvider
) {
    // Moonlight components
    private var decoderRenderer: MediaCodecDecoderRenderer? = null
    private var connection: NvConnection? = null
    private var videoSurfaceHolder: VideoSurfaceHolder? = null

    // Configuration
    private var streamConfig: StreamConfig = StreamConfig()
    private var connectionListener: NvConnectionListener? = null
    private var resolutionListener: StreamResolutionListener? = null
    private var crashCount = 0

    // Track whether we've notified about resolution mismatch
    private var hasNotifiedResolution = false

    /**
     * Creates and configures the video decoder.
     * Must be called before connect().
     *
     * @return MediaCodecDecoderRenderer for capability queries
     */
    fun initializeDecoder(
        meteredConnection: Boolean = false,
        hdrEnabled: Boolean = false,
        glRenderer: String = ""
    ): MediaCodecDecoderRenderer {
        Log.i(TAG, "Initializing decoder (metered=$meteredConnection, hdr=$hdrEnabled)")

        // CRITICAL: Initialize MediaCodecHelper before creating decoder
        // This populates decoder lists and must be called once before use
        MediaCodecHelper.initialize(activity, glRenderer)
        Log.i(TAG, "MediaCodecHelper initialized")

        // Create preference configuration for decoder
        val prefs = PreferenceConfiguration.createForStreaming(
            streamConfig.width,
            streamConfig.height,
            streamConfig.fps,
            streamConfig.bitrate
        )

        // Create crash listener
        val crashListener = CrashListener { e ->
            Log.e(TAG, "Decoder crashed", e)
            crashCount++
        }

        // Create performance overlay listener (no-op for now)
        val perfListener = object : PerfOverlayListener {
            override fun onPerfUpdate(text: String) {
            }
        }

        // Create decoder renderer
        decoderRenderer = MediaCodecDecoderRenderer(
            activity,
            prefs,
            crashListener,
            crashCount,
            meteredConnection,
            hdrEnabled,
            glRenderer,
            perfListener
        )

        // Create VideoSurfaceHolder adapter
        videoSurfaceHolder = VideoSurfaceHolder(surfaceProvider)

        // Wire the surface holder to the decoder
        decoderRenderer?.setRenderTarget(videoSurfaceHolder)

        Log.i(TAG, "Decoder initialized and render target set")

        return decoderRenderer!!
    }

    /**
     * Set the streaming configuration.
     */
    fun setStreamConfig(config: StreamConfig) {
        this.streamConfig = config
    }

    /**
     * Set the connection listener for state updates.
     */
    fun setConnectionListener(listener: NvConnectionListener) {
        this.connectionListener = listener
    }

    /**
     * Set the resolution listener for stream resolution changes.
     *
     * This will be called if the actual stream resolution differs from the requested
     * resolution (e.g., host rejected the resolution and fell back to native).
     */
    fun setResolutionListener(listener: StreamResolutionListener) {
        this.resolutionListener = listener
    }

    /**
     * Called to check and notify about actual stream resolution.
     *
     * This should be called when the first video frame is received or when
     * the stream resolution can be determined. If the actual resolution differs
     * from the requested resolution, the resolution listener will be notified.
     *
     * @param actualWidth The actual width of the video stream
     * @param actualHeight The actual height of the video stream
     */
    fun notifyActualResolution(actualWidth: Int, actualHeight: Int) {
        if (hasNotifiedResolution) return

        val requestedWidth = streamConfig.width
        val requestedHeight = streamConfig.height

        if (actualWidth != requestedWidth || actualHeight != requestedHeight) {
            Log.w(TAG, "Resolution mismatch! Requested: ${requestedWidth}x${requestedHeight}, " +
                    "Actual: ${actualWidth}x${actualHeight}")
            resolutionListener?.onStreamResolutionChanged(actualWidth, actualHeight)
        } else {
            Log.i(TAG, "Stream resolution matches request: ${actualWidth}x${actualHeight}")
        }

        hasNotifiedResolution = true
    }

    /**
     * Get supported video formats from the decoder.
     * Call after initializeDecoder().
     */
    fun getSupportedVideoFormats(): Int {
        val decoder = decoderRenderer ?: return MoonBridge.VIDEO_FORMAT_H264

        var formats = MoonBridge.VIDEO_FORMAT_H264
        if (decoder.isHevcSupported) {
            formats = formats or MoonBridge.VIDEO_FORMAT_H265
        }
        if (decoder.isAv1Supported) {
            formats = formats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
        }

        Log.i(TAG, "Supported video formats: $formats")
        return formats
    }

    /**
     * Connect to a streaming server.
     *
     * @param hostAddress Server address (IP or hostname)
     * @param port Server port (default: NvHTTP.DEFAULT_HTTP_PORT = 47989)
     * @param httpsPort HTTPS port (default: 0 = auto-discover)
     * @param app The app to stream (use NvApp for desktop)
     * @param serverCert Server certificate from pairing
     * @param cryptoProvider Crypto provider with client keys
     * @param uniqueId Unique client ID
     */
    fun connect(
        hostAddress: String,
        port: Int = NvHTTP.DEFAULT_HTTP_PORT,
        httpsPort: Int = 0,  // 0 = auto-discover HTTPS port
        app: NvApp,
        serverCert: X509Certificate,
        cryptoProvider: LimelightCryptoProvider,
        uniqueId: String = MOONLIGHT_UNIQUE_ID
    ) {
        Log.i(TAG, "Connecting to $hostAddress:$port")

        val decoder = decoderRenderer
        if (decoder == null) {
            Log.e(TAG, "Decoder not initialized - call initializeDecoder() first")
            connectionListener?.displayMessage("Decoder not initialized")
            return
        }

        val listener = connectionListener
        if (listener == null) {
            Log.e(TAG, "Connection listener not set")
            return
        }

        // Create stream configuration with ALL required fields
        // Missing fields like clientRefreshRateX100 can cause 0x80030023 (TOPOLOGY_CHANGED) errors
        // because the server might try to switch to 0Hz refresh rate
        val colorSpace = decoder.preferredColorSpace
        val colorRange = decoder.preferredColorRange
        val videoFormats = getSupportedVideoFormats()

        Log.i(TAG, "StreamConfig: ${streamConfig.width}x${streamConfig.height}@${streamConfig.fps}fps, " +
                "bitrate=${streamConfig.bitrate}, colorSpace=$colorSpace, colorRange=$colorRange, " +
                "videoFormats=$videoFormats, clientRefreshRateX100=${streamConfig.fps * 100}, launchRefreshRate=${streamConfig.fps}")

        val streamConfiguration = StreamConfiguration.Builder()
            .setResolution(streamConfig.width, streamConfig.height)
            .setRefreshRate(streamConfig.fps)
            .setLaunchRefreshRate(streamConfig.fps)  // Must match fps - 0 causes no video to be sent!
            .setClientRefreshRateX100(streamConfig.fps * 100)  // CRITICAL: 60fps = 6000, tells server our display rate
            .setBitrate(streamConfig.bitrate)  // StreamConfig.bitrate is in kbps (matches Moonlight API)
            .setApp(app)
            .setSupportedVideoFormats(videoFormats)
            .setMaxPacketSize(1392)  // Standard Moonlight packet size
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)  // Let Moonlight detect LAN/VPN
            .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO)  // Stereo audio
            .setColorSpace(colorSpace)  // Use decoder's preferred color space
            .setColorRange(colorRange)  // Use decoder's preferred color range (limited/full)
            .setEnableSops(true)  // Stream optimization settings
            .build()

        // Create address tuple
        val addressTuple = ComputerDetails.AddressTuple(hostAddress, port)

        // Create NvConnection
        connection = NvConnection(
            activity.applicationContext,
            addressTuple,
            httpsPort,
            uniqueId,
            streamConfiguration,
            cryptoProvider,
            serverCert
        )

        // Create audio renderer (full implementation with low-latency settings)
        val audioRenderer = createAudioRenderer(enableAudioFx = false)

        // Log Surface state before starting
        val surface = videoSurfaceHolder?.surface
        Log.i(TAG, "Surface state before start: surface=$surface, isValid=${surface?.isValid}")

        // Start streaming
        Log.i(TAG, "Calling connection.start() NOW")
        connection?.start(audioRenderer, decoder, listener)

        Log.i(TAG, "Streaming started")
    }

    /**
     * Quit any existing streaming session on the server.
     *
     * This should be called before connecting to ensure the server clears
     * any stale session state that might cause video packets to be routed
     * to a previous client.
     *
     * @return true if session was quit or no session was running, false on error
     */
    suspend fun quitExistingSession(
        server: SavedServer,
        port: Int = NvHTTP.DEFAULT_HTTP_PORT,
        httpsPort: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Quitting any existing session on ${server.name}")

        try {
            // Decode server certificate
            val serverCert = decodeCertificate(server.serverCert)
            val clientCert = decodeCertificate(server.clientCert)
            val clientKey = decodePrivateKey(server.clientKey)
            val cryptoProvider = SavedServerCryptoProvider(clientCert, clientKey)

            // Create HTTP connection
            val addressTuple = ComputerDetails.AddressTuple(server.address, port)
            val nvHttp = NvHTTP(
                addressTuple,
                httpsPort,
                MOONLIGHT_UNIQUE_ID,
                serverCert,
                cryptoProvider
            )

            // Check if a game is running
            val serverInfo = nvHttp.getServerInfo(true)
            val currentGame = nvHttp.getCurrentGame(serverInfo)
            if (currentGame == 0) {
                Log.i(TAG, "No game currently running")
                return@withContext true
            }

            Log.i(TAG, "Quitting running game (app ID: $currentGame)")

            // Quit the running app
            val success = nvHttp.quitApp()
            if (success) {
                Log.i(TAG, "Successfully quit existing session")
            } else {
                Log.w(TAG, "Failed to quit session - may have been started by another client")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting session: ${e.message}")
            false
        }
    }

    /**
     * Connect to a saved server using stored credentials.
     *
     * This is a convenience method that decodes the Base64-encoded certificates
     * from a SavedServer and creates the appropriate crypto provider.
     *
     * IMPORTANT: This is a suspend function that performs network I/O.
     * Must be called from a coroutine context.
     *
     * @param server The saved server with stored credentials
     * @param app The app to stream (defaults to Desktop streaming)
     * @param port Server port (default: NvHTTP.DEFAULT_HTTP_PORT = 47989)
     * @param httpsPort HTTPS port (default: 0 = auto-discover)
     */
    suspend fun connectToSavedServer(
        server: SavedServer,
        appName: String = "Desktop",  // App name to look up on server
        port: Int = NvHTTP.DEFAULT_HTTP_PORT,
        httpsPort: Int = 0  // 0 = auto-discover HTTPS port
    ) {
        Log.i(TAG, "Connecting to saved server: ${server.name} @ ${server.address}")

        // Decode certificates from Base64
        val serverCert = decodeCertificate(server.serverCert)
        val clientCert = decodeCertificate(server.clientCert)
        val clientKey = decodePrivateKey(server.clientKey)

        // Create crypto provider with stored keys
        val cryptoProvider = SavedServerCryptoProvider(clientCert, clientKey)

        // Look up the app ID from the server before connecting
        // This fixes a bug in NvConnection.launchNotRunningApp() that uses the original
        // app ID (0) instead of the looked-up app ID when launching a fresh session
        // CRITICAL: Network I/O must run on Dispatchers.IO to avoid NetworkOnMainThreadException
        val lookedUpApp = withContext(Dispatchers.IO) {
            try {
                val nvHttp = NvHTTP(
                    ComputerDetails.AddressTuple(server.address, port),
                    httpsPort,
                    MOONLIGHT_UNIQUE_ID,
                    serverCert,
                    cryptoProvider
                )
                val app = nvHttp.getAppByName(appName)
                if (app != null) {
                    Log.i(TAG, "Looked up app: ${app.appName} (ID: ${app.appId})")
                    // Create properly initialized NvApp with the correct ID
                    NvApp(app.appName, app.appId, app.isHdrSupported)
                } else {
                    Log.e(TAG, "App '$appName' not found on server")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to look up app: ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }

        if (lookedUpApp == null) {
            connectionListener?.displayMessage("Failed to find app '$appName' on server")
            return
        }

        // Call existing connect with decoded credentials and proper app ID
        // CRITICAL: Use MOONLIGHT_UNIQUE_ID (same as pairing) to maintain session identity
        connect(
            hostAddress = server.address,
            port = port,
            httpsPort = httpsPort,
            app = lookedUpApp,
            serverCert = serverCert,
            cryptoProvider = cryptoProvider,
            uniqueId = MOONLIGHT_UNIQUE_ID  // Must match the ID used during pairing
        )
    }

    /**
     * Decode a Base64-encoded X.509 certificate.
     *
     * @throws IllegalArgumentException if the Base64 string is malformed or the certificate is invalid
     */
    private fun decodeCertificate(base64: String): X509Certificate {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Failed to decode Base64 certificate: ${e.message}", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse X.509 certificate: ${e.message}", e)
        }
    }

    /**
     * Decode a Base64-encoded PKCS8 private key.
     *
     * @throws IllegalArgumentException if the Base64 string is malformed or the key is invalid
     */
    private fun decodePrivateKey(base64: String): PrivateKey {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val spec = PKCS8EncodedKeySpec(bytes)
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(spec)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Failed to decode Base64 private key: ${e.message}", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse PKCS8 private key: ${e.message}", e)
        }
    }

    /**
     * Creates the audio renderer using Moonlight's AndroidAudioRenderer.
     *
     * @param enableAudioFx Whether to enable audio effects (equalizers, etc.).
     *                      Set to false for lower latency.
     */
    private fun createAudioRenderer(enableAudioFx: Boolean = false): AudioRenderer {
        return AndroidAudioRenderer(activity, enableAudioFx)
    }

    /**
     * Disconnect from the streaming server.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting")
        connection?.stop()
        connection = null
        hasNotifiedResolution = false  // Reset for next connection
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        Log.i(TAG, "Releasing resources")
        disconnect()
        decoderRenderer = null
        videoSurfaceHolder = null
    }

    /**
     * Check if decoder supports HEVC.
     */
    fun isHevcSupported(): Boolean = decoderRenderer?.isHevcSupported == true

    /**
     * Check if decoder supports AV1.
     */
    fun isAv1Supported(): Boolean = decoderRenderer?.isAv1Supported == true

    /**
     * Check if currently streaming.
     */
    fun isStreaming(): Boolean = connection != null

    // ========================================================================
    // INPUT METHODS - for head-gaze cursor and temple gestures
    // ========================================================================

    // Throttle logging for mouse input (to avoid spamming at 219Hz)
    private var lastPositionLogTime = 0L
    private var positionCallCount = 0

    /**
     * Send absolute cursor position to PC.
     * The glasses are the source of truth - PC cursor warps to this position.
     *
     * This fixes drift issues that occur with relative mouse movement (sendMouseMove),
     * as absolute positioning doesn't accumulate integration errors from noisy sensor data.
     *
     * @param x Current X coordinate in desktop space
     * @param y Current Y coordinate in desktop space
     * @param refWidth Reference width for coordinate system (default: 1920)
     * @param refHeight Reference height for coordinate system (default: 1080)
     */
    fun sendAbsolutePosition(x: Int, y: Int, refWidth: Int = 1920, refHeight: Int = 1080) {
        val conn = connection ?: return

        // Direct absolute positioning - no drift possible
        conn.sendMousePosition(
            x.toShort(),
            y.toShort(),
            refWidth.toShort(),
            refHeight.toShort()
        )

        // Throttled logging to verify calls without spamming
        positionCallCount++
        val now = System.currentTimeMillis()
        if (now - lastPositionLogTime >= 2000) {
            Log.i(TAG, "[INPUT] sendAbsolutePosition: ($x, $y) in ${refWidth}x${refHeight}, $positionCallCount calls")
            lastPositionLogTime = now
            positionCallCount = 0
        }
    }

    /**
     * Move cursor to center of screen.
     * Call this on recenter to synchronize glasses and PC cursor positions.
     *
     * @param refWidth Reference width for coordinate system (default: 1920)
     * @param refHeight Reference height for coordinate system (default: 1080)
     */
    fun centerCursor(refWidth: Int = 1920, refHeight: Int = 1080) {
        val centerX = refWidth / 2
        val centerY = refHeight / 2
        Log.i(TAG, "[INPUT] Centering cursor to ($centerX, $centerY)")
        sendAbsolutePosition(centerX, centerY, refWidth, refHeight)
    }

    /**
     * Send mouse click (button down + button up).
     *
     * @param button Mouse button (use MouseButtonPacket constants)
     */
    fun sendMouseClick(button: Byte = MouseButtonPacket.BUTTON_LEFT) {
        connection?.sendMouseButtonDown(button)
        connection?.sendMouseButtonUp(button)
    }

    /**
     * Send mouse scroll.
     *
     * @param direction Positive for scroll up, negative for scroll down
     */
    fun sendMouseScroll(direction: Int) {
        val scrollClicks = if (direction > 0) 1.toByte() else (-1).toByte()
        connection?.sendMouseScroll(scrollClicks)
    }

    /**
     * Send a keyboard shortcut (key down + key up with modifiers).
     *
     * Sends modifier key-down events first, then the main key, then releases all.
     * This matches how Sunshine expects to receive hotkey combos.
     *
     * @param vkCode Windows virtual key code (e.g., 0x70 for F1)
     * @param modifiers Modifier flags (combination of MODIFIER_CTRL, MODIFIER_ALT, MODIFIER_SHIFT)
     */
    fun sendKeyboardShortcut(vkCode: Int, modifiers: Byte) {
        val conn = connection ?: return
        val modInt = modifiers.toInt() and 0xFF

        // Press modifier keys first
        if (modInt and KeyboardPacket.MODIFIER_CTRL.toInt() != 0) {
            conn.sendKeyboardInput(0xA2.toShort(), KeyboardPacket.KEY_DOWN, modifiers, 0) // VK_LCONTROL
        }
        if (modInt and KeyboardPacket.MODIFIER_ALT.toInt() != 0) {
            conn.sendKeyboardInput(0xA4.toShort(), KeyboardPacket.KEY_DOWN, modifiers, 0) // VK_LMENU
        }
        if (modInt and KeyboardPacket.MODIFIER_SHIFT.toInt() != 0) {
            conn.sendKeyboardInput(0xA0.toShort(), KeyboardPacket.KEY_DOWN, modifiers, 0) // VK_LSHIFT
        }

        // Press and release the main key
        val keyMap = vkCode.toShort()
        conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, modifiers, 0)
        conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, modifiers, 0)

        // Release modifier keys
        val noMod: Byte = 0
        if (modInt and KeyboardPacket.MODIFIER_SHIFT.toInt() != 0) {
            conn.sendKeyboardInput(0xA0.toShort(), KeyboardPacket.KEY_UP, noMod, 0)
        }
        if (modInt and KeyboardPacket.MODIFIER_ALT.toInt() != 0) {
            conn.sendKeyboardInput(0xA4.toShort(), KeyboardPacket.KEY_UP, noMod, 0)
        }
        if (modInt and KeyboardPacket.MODIFIER_CTRL.toInt() != 0) {
            conn.sendKeyboardInput(0xA2.toShort(), KeyboardPacket.KEY_UP, noMod, 0)
        }
    }

    /**
     * Switch to a specific monitor on the Sunshine host.
     *
     * Sends CTRL+ALT+SHIFT+F1 through F12 to switch monitors.
     * This is a Sunshine built-in hotkey that must be sent during an active stream.
     *
     * @param monitorNumber Monitor number (1-12)
     */
    fun switchToMonitor(monitorNumber: Int) {
        require(monitorNumber in 1..12) { "Monitor number must be between 1 and 12" }
        // VK_F1=0x70, VK_F2=0x71, ..., VK_F12=0x7B
        val vkCode = 0x6F + monitorNumber  // 0x70 for monitor 1, 0x71 for monitor 2, etc.
        val modifiers = (KeyboardPacket.MODIFIER_CTRL.toInt() or
                KeyboardPacket.MODIFIER_ALT.toInt() or
                KeyboardPacket.MODIFIER_SHIFT.toInt()).toByte()
        sendKeyboardShortcut(vkCode, modifiers)
    }

    // ========================================================================
    // PAIRING SUPPORT
    // ========================================================================

    /**
     * Generate a random 4-digit PIN for pairing.
     */
    fun generatePairingPin(): String {
        return PairingManager.generatePinString()
    }

    /**
     * Pair with a new server.
     *
     * This initiates the pairing handshake with a Sunshine/GFE server.
     * The PIN should be displayed to the user and entered on the server.
     *
     * @param address Server IP address or hostname
     * @param pin 4-digit PIN (use generatePairingPin() to create one)
     * @return PairingResult indicating success or failure
     */
    suspend fun pairWithServer(
        address: String,
        pin: String
    ): PairingResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting pairing with $address")

        try {
            // Get the platform crypto provider (will generate client cert/key if needed)
            val cryptoProvider = AndroidCryptoProvider(activity)

            // Create HTTP connection for pairing (no server cert yet)
            val addressTuple = ComputerDetails.AddressTuple(address, NvHTTP.DEFAULT_HTTP_PORT)
            val http = NvHTTP(
                addressTuple,
                0,  // httpsPort - will be discovered
                MOONLIGHT_UNIQUE_ID,
                null,  // No server cert yet
                cryptoProvider
            )

            // Get server info to initiate pairing
            val serverInfo = http.getServerInfo(true)

            // Create pairing manager and execute pairing
            val pm = PairingManager(http, cryptoProvider)
            val pairState = pm.pair(serverInfo, pin)

            when (pairState) {
                PairingManager.PairState.PAIRED -> {
                    Log.i(TAG, "Pairing successful!")

                    // Get the server certificate from pairing manager
                    val serverCert = pm.pairedCert

                    // Get computer details for the server name and UUID
                    val computerDetails = http.getComputerDetails(serverInfo)

                    PairingResult.Success(
                        serverCert = serverCert,
                        clientCert = cryptoProvider.clientCertificate,
                        clientKey = cryptoProvider.clientPrivateKey,
                        serverUuid = computerDetails.uuid,
                        serverName = computerDetails.name
                    )
                }
                PairingManager.PairState.PIN_WRONG -> {
                    Log.w(TAG, "Pairing failed: wrong PIN")
                    PairingResult.WrongPin
                }
                PairingManager.PairState.ALREADY_IN_PROGRESS -> {
                    Log.w(TAG, "Pairing failed: already in progress on server")
                    PairingResult.Failed("Another device is currently pairing with this server")
                }
                else -> {
                    Log.e(TAG, "Pairing failed: $pairState")
                    PairingResult.Failed(pairState.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing exception", e)
            PairingResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Create a SavedServer from successful pairing result.
     *
     * @param address The server address used for pairing
     * @param result The successful pairing result
     * @return SavedServer ready for storage
     */
    fun createSavedServer(address: String, result: PairingResult.Success): SavedServer {
        return SavedServer(
            uuid = result.serverUuid,
            name = result.serverName,
            address = address,
            lastConnected = System.currentTimeMillis(),
            serverCert = encodeCertificate(result.serverCert),
            clientCert = encodeCertificate(result.clientCert),
            clientKey = encodePrivateKey(result.clientKey)
        )
    }

    /**
     * Encode an X.509 certificate to Base64.
     */
    private fun encodeCertificate(cert: X509Certificate): String {
        return Base64.encodeToString(cert.encoded, Base64.DEFAULT)
    }

    /**
     * Encode a private key to Base64 (PKCS8 format).
     */
    private fun encodePrivateKey(key: PrivateKey): String {
        return Base64.encodeToString(key.encoded, Base64.DEFAULT)
    }

    companion object {
        private const val TAG = "MoonlightBridge"

        // Default streaming configuration for AR glasses
        // NOTE: These are suggestions to Sunshine. Actual resolution is auto-detected.
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FPS = 60
        // Gemini recommendation: 20 Mbps minimum for text clarity
        const val DEFAULT_BITRATE_KBPS = 20_000  // 20 Mbps

        // Use the same unique ID as Moonlight for compatibility
        // This allows quitting games started by other Moonlight clients
        private const val MOONLIGHT_UNIQUE_ID = "0123456789ABCDEF"
    }
}

/**
 * Result of a pairing attempt.
 */
sealed class PairingResult {
    /**
     * Pairing succeeded. Contains credentials needed to connect.
     */
    data class Success(
        val serverCert: X509Certificate,
        val clientCert: X509Certificate,
        val clientKey: PrivateKey,
        val serverUuid: String,
        val serverName: String
    ) : PairingResult()

    /**
     * The entered PIN was incorrect.
     */
    object WrongPin : PairingResult()

    /**
     * Pairing failed for another reason.
     */
    data class Failed(val reason: String) : PairingResult()
}

/**
 * Crypto provider that uses pre-stored certificates.
 *
 * Unlike AndroidCryptoProvider which loads from files, this uses
 * certificates that were decoded from a SavedServer.
 */
private class SavedServerCryptoProvider(
    private val clientCert: X509Certificate,
    private val clientKey: PrivateKey
) : LimelightCryptoProvider {

    override fun getClientCertificate(): X509Certificate = clientCert

    override fun getClientPrivateKey(): PrivateKey = clientKey

    override fun getPemEncodedClientCertificate(): ByteArray {
        // Convert certificate to PEM format
        val base64 = Base64.encodeToString(clientCert.encoded, Base64.DEFAULT)
        return ("-----BEGIN CERTIFICATE-----\n" +
                base64 +
                "-----END CERTIFICATE-----\n").toByteArray(Charsets.UTF_8)
    }

    override fun encodeBase64String(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
}
