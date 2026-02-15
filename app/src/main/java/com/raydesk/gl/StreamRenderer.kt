// app/src/main/java/com/raydesk/gl/StreamRenderer.kt
package com.raydesk.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.raydesk.test.R
import com.raydesk.spatial.KeyholeViewport
import com.raydesk.spatial.TextureRect
import com.raydesk.spatial.ViewportResult
import com.raydesk.spatial.VirtualScreenController
import com.raydesk.spatial.CylinderController
import com.raydesk.video.FrameSlot
import com.raydesk.video.GLTextureRenderer
import com.raydesk.video.VideoTextureProvider
import com.raydesk.data.EnvironmentTheme
import com.raydesk.data.EnvironmentThemes
import com.raydesk.gl.environment.EnvironmentRenderer
import com.raydesk.gl.environment.ScreenSpaceHudRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Display mode for video rendering.
 *
 * - FLOATING_MONITOR: Screen fixed in world space, head rotation moves camera
 * - KEYHOLE_PANNING: UV-based panning across desktop texture (original mode)
 */
enum class DisplayMode {
    FLOATING_MONITOR,  // Default - screen in 3D space (flat quad)
    KEYHOLE_PANNING,   // Legacy - UV panning across desktop
    CURVED_MONITOR     // Phase 3 - curved cylindrical display
}

/**
 * OpenGL renderer for video streaming with multiple display modes.
 *
 * This is the core rendering component that ties together:
 * - VideoTextureProvider: Supplies video frames as OES texture
 * - KeyholeViewport: Maps head rotation to UV coordinates (keyhole mode)
 * - VirtualScreenController: 3D screen positioning (floating monitor mode)
 * - FlatQuadMesh: Renders the video quad
 * - GLSL shaders: Apply texture and keyhole/3D transformation
 *
 * Display Modes:
 * - FLOATING_MONITOR (default): Desktop appears as a screen floating in 3D space.
 *   Head rotation moves the camera. Uses dampened follow to prevent micro-jitter.
 * - KEYHOLE_PANNING: Traditional UV-based panning across the desktop texture.
 *   Head rotation changes which portion of the desktop is visible.
 *
 * Rendering flow (60Hz via Choreographer):
 * 1. Update video texture from SurfaceTexture
 * 2. Calculate transforms (3D matrices or keyhole UV rect)
 * 3. Bind shader program and set uniforms
 * 4. Draw mesh with video texture
 *
 * Implements both GLSurfaceView.Renderer (legacy) and GLTextureRenderer.Renderer
 * for binocular display support with MirroringView.
 */
class StreamRenderer(
    private val context: Context
) : GLSurfaceView.Renderer, GLTextureRenderer.Renderer {

    companion object {
        private const val TAG = "StreamRenderer"
    }

    // Display mode selection (written by UI thread, read by GL thread)
    @Volatile var displayMode: DisplayMode = DisplayMode.FLOATING_MONITOR

    // Dependencies (set externally before rendering starts)
    var videoProvider: VideoTextureProvider? = null
    var keyholeViewport: KeyholeViewport? = null
    var virtualScreenController: VirtualScreenController? = null
    var frameSlot: FrameSlot? = null  // For decoupled rendering

    // Head tracking input (written by sensor thread, read by GL thread)
    @Volatile var headYawDegrees: Float = 0f
    @Volatile var headPitchDegrees: Float = 0f
    @Volatile var headTimestampNs: Long = 0L

    // Cursor position for cursor-centered zoom mode (written by UI thread, read by GL thread)
    @Volatile var cursorX: Float = 960f   // Center of 1920
    @Volatile var cursorY: Float = 540f   // Center of 1080

    // Stored cursor tracking state - applied to controllers when they're created
    // (Controllers are created lazily in onSurfaceCreated, after setCursorTrackingEnabled may be called)
    private var storedCursorTrackingEnabled: Boolean = true

    // Stream resolution (updated via onStreamResolutionChanged)
    private var streamWidth: Int = 1920
    private var streamHeight: Int = 1080

    // Test pattern mode (written by UI thread, read by GL thread)
    @Volatile var testPatternEnabled: Boolean = true  // Default to test pattern until video is connected
    private var startTimeMs: Long = 0
    private var framesReceivedCount: Int = 0  // Track video frames to auto-disable test pattern

    // CAS sharpening (Phase 4)
    var casSharpening: Float = 0.5f  // 0.0 = off, 0.5 = default, 1.0 = max

    // GL objects
    private var shaderProgram: Int = 0
    private var mesh: FlatQuadMesh? = null

    // Curved monitor mode (Phase 3)
    private var cylinderMesh: CylinderMesh? = null
    var cylinderController: CylinderController? = null

    // Environment rendering (Phase 1)
    private var environmentRenderer: EnvironmentRenderer? = null
    private var environmentEnabled: Boolean = false
    private var currentTheme: EnvironmentTheme = EnvironmentThemes.DEFAULT
    @Volatile private var pendingTheme: EnvironmentTheme? = null  // Thread-safe deferred theme change

    // Screen-space HUD (fixed to glasses viewport, not world-space)
    private var hudRenderer: ScreenSpaceHudRenderer? = null
    var hudEnabled: Boolean = true  // HUD always on by default

    // Uniform locations
    private var uMVPMatrix: Int = -1
    private var uKeyholeRect: Int = -1
    private var uSTMatrix: Int = -1
    private var uVideoTexture: Int = -1
    private var uTestPatternEnabled: Int = -1
    private var uTime: Int = -1
    private var uCursorPos: Int = -1
    private var uCursorEnabled: Int = -1
    private var uZoomLevel: Int = -1
    private var uDisplayMode: Int = -1
    private var uCasSharpening: Int = -1
    private var uTexelSize: Int = -1

    // Bezel uniforms
    private var uBezelEnabled: Int = -1
    private var uBezelColor: Int = -1
    private var uBezelWidth: Int = -1
    private var uAspectRatio: Int = -1

    // Cursor visibility control - disabled since we use PC's native cursor via Moonlight
    var cursorEnabled: Boolean = false

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16)

    // Keyhole mode uses orthographic projection (2D texture mapping, no 3D depth)
    private val keyholeOrthoMatrix = FloatArray(16)

    // Stereo rendering (for depth perception)
    // DISABLED: Mercury SDK already handles binocular display duplication
    // Manual stereo causes viewport issues - needs investigation
    var stereoEnabled: Boolean = false
    private var viewportWidth: Int = 1280
    private var viewportHeight: Int = 480

    // Stereo projection matrix (separate from main projection for correct aspect)
    private val stereoProjectionMatrix = FloatArray(16)

    // Callbacks
    var onSurfaceCreatedCallback: (() -> Unit)? = null

    // Frame timing for animations
    private var lastFrameTimeNs: Long = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {

        // Black background (transparent in AR - waveguide shows black as transparent)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // Load and compile shaders
        val vertexSource = ShaderUtils.loadShaderFromResource(context, R.raw.video_vertex)
        val fragmentSource = ShaderUtils.loadShaderFromResource(context, R.raw.video_fragment)
        shaderProgram = ShaderUtils.createProgram(vertexSource, fragmentSource)

        if (shaderProgram == 0) {
            throw RuntimeException("Shader compilation failed")
        }

        // Get uniform locations
        uMVPMatrix = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        uKeyholeRect = GLES30.glGetUniformLocation(shaderProgram, "uKeyholeRect")
        uSTMatrix = GLES30.glGetUniformLocation(shaderProgram, "uSTMatrix")
        uVideoTexture = GLES30.glGetUniformLocation(shaderProgram, "uVideoTexture")
        uTestPatternEnabled = GLES30.glGetUniformLocation(shaderProgram, "uTestPatternEnabled")
        uTime = GLES30.glGetUniformLocation(shaderProgram, "uTime")
        uCursorPos = GLES30.glGetUniformLocation(shaderProgram, "uCursorPos")
        uCursorEnabled = GLES30.glGetUniformLocation(shaderProgram, "uCursorEnabled")
        uZoomLevel = GLES30.glGetUniformLocation(shaderProgram, "uZoomLevel")
        uDisplayMode = GLES30.glGetUniformLocation(shaderProgram, "uDisplayMode")
        uCasSharpening = GLES30.glGetUniformLocation(shaderProgram, "uCasSharpening")
        uTexelSize = GLES30.glGetUniformLocation(shaderProgram, "uTexelSize")

        // Bezel uniforms
        uBezelEnabled = GLES30.glGetUniformLocation(shaderProgram, "uBezelEnabled")
        uBezelColor = GLES30.glGetUniformLocation(shaderProgram, "uBezelColor")
        uBezelWidth = GLES30.glGetUniformLocation(shaderProgram, "uBezelWidth")
        uAspectRatio = GLES30.glGetUniformLocation(shaderProgram, "uAspectRatio")

        // Initialize start time for animation
        startTimeMs = System.currentTimeMillis()

        // Initialize identity matrix for fallback
        Matrix.setIdentityM(identityMatrix, 0)

        // Create mesh with dynamic aspect ratio support
        mesh = FlatQuadMesh()
        mesh?.initialize()

        // Create cylinder mesh for curved monitor mode
        cylinderMesh = CylinderMesh()
        cylinderMesh?.initialize()

        // Initialize CylinderController if not set externally
        if (cylinderController == null) {
            cylinderController = CylinderController()
            // Apply stored cursor tracking state (may have been set before controller was created)
            cylinderController?.cursorTrackingEnabled = storedCursorTrackingEnabled
        }

        // Initialize environment renderer (always create to avoid GL thread issues)
        environmentRenderer = EnvironmentRenderer(context, currentTheme)
        environmentRenderer?.initialize()

        // Initialize screen-space HUD (fixed to glasses viewport)
        hudRenderer = ScreenSpaceHudRenderer(context)
        hudRenderer?.initialize()

        // Initialize view matrix (camera at origin, looking at -Z)
        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 0f, 0f,    // Eye position
            0f, 0f, -1f,   // Look at
            0f, 1f, 0f     // Up vector
        )

        // Initialize VirtualScreenController for floating monitor mode
        if (virtualScreenController == null) {
            virtualScreenController = VirtualScreenController()
            // Apply stored cursor tracking state (may have been set before controller was created)
            virtualScreenController?.cursorTrackingEnabled = storedCursorTrackingEnabled
        }

        // Notify that surface is ready (for VideoTextureProvider initialization)
        onSurfaceCreatedCallback?.invoke()

        ShaderUtils.checkGlError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {

        // Save viewport dimensions for stereo rendering
        viewportWidth = width
        viewportHeight = height

        GLES30.glViewport(0, 0, width, height)

        // For stereo rendering, each eye sees half the width
        // RayNeo X3 Pro: 1280x480 total, 640x480 per eye
        val eyeWidth = if (stereoEnabled && displayMode == DisplayMode.CURVED_MONITOR) width / 2 else width
        val aspect = eyeWidth.toFloat() / height
        Matrix.perspectiveM(projectionMatrix, 0, 30f, aspect, 0.1f, 100f)

        // Configure VirtualScreenController projection (uses full width aspect)
        virtualScreenController?.configureProjection(30f, width.toFloat() / height, 0.1f, 100f)

        // Configure CylinderController projection (uses eye aspect for stereo)
        cylinderController?.configureProjection(30f, aspect, 0.1f, 100f)

        // Compute MVP for keyhole mode (static) - DEPRECATED, use keyholeOrthoMatrix
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Keyhole mode: orthographic projection that fills viewport
        // Mesh is unit quad from (-0.5,-0.5) to (0.5,0.5) at Z=0
        // Ortho maps this directly to clip space (-1,-1) to (1,1)
        Matrix.orthoM(keyholeOrthoMatrix, 0, -0.5f, 0.5f, -0.5f, 0.5f, -1f, 1f)

        // Update HUD viewport dimensions
        hudRenderer?.updateViewport(width, height)

        ShaderUtils.checkGlError("onSurfaceChanged")
    }

    /**
     * Update stream resolution when detected from actual stream.
     *
     * @param width Stream width in pixels
     * @param height Stream height in pixels
     */
    fun updateStreamResolution(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        streamWidth = width
        streamHeight = height

        Log.i(TAG, "Stream resolution: ${width}x${height}")

        // Update mesh aspect ratio (for flat quad)
        mesh?.updateFromResolution(width, height)

        // Update virtual screen controller aspect ratio
        virtualScreenController?.updateAspectRatio(width, height)

        // Update keyhole viewport if available
        keyholeViewport?.updateDesktopDimensions(width, height)

        // Update cylinder controller for cursor panning
        cylinderController?.updateDesktopDimensions(width, height)

        // Update cylinder mesh for single monitor
        cylinderMesh?.updateFromResolution(width, height)
    }

    // Debug counters for render loop
    private var drawFrameCount = 0
    private var lastDrawFrameLogTime = 0L

    // FPS tracking for dashboard display
    private var currentFps = 0

    // Debug counter for video frame consumption
    private var videoFramesConsumedCount = 0

    // Video stall detection (Mercury OS doesn't call onStop when headset removed)
    private var lastVideoFrameTimestampMs = 0L
    private var videoStallTimeoutMs = 5000L  // 5 seconds
    private var stallDetectionActive = false
    var onVideoStallDetected: (() -> Unit)? = null

    override fun onDrawFrame(gl: GL10?) {
        // Calculate delta time for animations
        val currentTimeNs = System.nanoTime()
        val deltaTime = (currentTimeNs - lastFrameTimeNs) / 1_000_000_000f
        lastFrameTimeNs = currentTimeNs

        // Update zoom animation (for floating monitor mode)
        virtualScreenController?.updateZoomAnimation(deltaTime)

        val provider = videoProvider ?: return
        val slot = frameSlot

        // Apply pending theme change on GL thread (thread-safe)
        pendingTheme?.let { theme ->
            pendingTheme = null
            environmentRenderer?.setTheme(theme)
        }

        // Track render loop for stall detection
        drawFrameCount++
        val now = System.currentTimeMillis()
        if (now - lastDrawFrameLogTime >= 1000) {

            // Video stall detection - Mercury OS doesn't call onStop when headset removed,
            // so video frames just stop arriving while the activity stays alive
            if (videoFramesConsumedCount > 0) {
                lastVideoFrameTimestampMs = now
                stallDetectionActive = true
            } else if (stallDetectionActive && lastVideoFrameTimestampMs > 0) {
                val stallDuration = now - lastVideoFrameTimestampMs
                if (stallDuration >= videoStallTimeoutMs) {
                    Log.e(TAG, "VIDEO STALL: No frames for ${stallDuration}ms")
                    stallDetectionActive = false  // Prevent repeated callbacks
                    onVideoStallDetected?.invoke()
                }
            }

            // Update FPS for dashboard
            currentFps = drawFrameCount
            environmentRenderer?.setFps(currentFps)

            drawFrameCount = 0
            videoFramesConsumedCount = 0
            lastDrawFrameLogTime = now
        }

        // DECOUPLED RENDERING:
        // - Check FrameSlot for new video frames (non-blocking)
        // - Only update texture when new frame available
        // - Always render for 60Hz head tracking updates
        val hasNewFrame: Boolean
        if (slot != null && slot.hasNewFrame()) {
            // New frame in FrameSlot - update texture and mark consumed
            provider.updateTexture()
            val frame = slot.consume()
            if (frame != null) {
                slot.markConsumed(frame.frameNumber)
                videoFramesConsumedCount++
            }
            hasNewFrame = true
        } else if (slot == null) {
            // No FrameSlot configured - use legacy direct update
            hasNewFrame = provider.updateTexture()
        } else {
            // FrameSlot present but no new frame - skip texture update
            hasNewFrame = false
        }
        val stMatrix = provider.getTransformMatrix()

        // Auto-disable test pattern after receiving video frames
        if (hasNewFrame && testPatternEnabled) {
            framesReceivedCount++
            if (framesReceivedCount >= 3) {
                Log.i(TAG, "Received $framesReceivedCount video frames - disabling test pattern")
                testPatternEnabled = false
            }
        }

        // Stereo rendering for curved monitor mode
        if (stereoEnabled && displayMode == DisplayMode.CURVED_MONITOR) {
            val cc = cylinderController ?: return

            // Update head pose and zoom
            cc.updateHeadPose(headYawDegrees, headPitchDegrees, deltaTime)
            if (cc.updateZoomAnimation(deltaTime)) {
                cylinderMesh?.updateRadius(cc.getCurrentRadius())
            }

            // Update environment params
            if (environmentEnabled) {
                environmentRenderer?.apply {
                    updateMonitorParams(cc.getCurrentRadius(), cc.getArcAngle(), cc.getMonitorHeight())
                    setZoom(cc.getZoomLevel())
                }
            }

            val eyeWidth = viewportWidth / 2

            // Calculate stereo projection with correct per-eye aspect ratio
            val stereoAspect = eyeWidth.toFloat() / viewportHeight.toFloat()
            Matrix.perspectiveM(stereoProjectionMatrix, 0, 30f, stereoAspect, 0.1f, 100f)

            // Update CylinderController with correct stereo projection
            cc.configureProjection(30f, stereoAspect, 0.1f, 100f)

            // === LEFT EYE (viewport 0 to eyeWidth) ===
            GLES30.glViewport(0, 0, eyeWidth, viewportHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            renderEyeStereo(cc.getLeftEyeViewMatrix(), cc.getLeftEyeMVPMatrix(), stMatrix, deltaTime, provider, stereoProjectionMatrix)

            // === RIGHT EYE (viewport eyeWidth to end) ===
            GLES30.glViewport(eyeWidth, 0, eyeWidth, viewportHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            renderEyeStereo(cc.getRightEyeViewMatrix(), cc.getRightEyeMVPMatrix(), stMatrix, deltaTime, provider, stereoProjectionMatrix)

            // Reset viewport for next frame
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        } else {
            // Non-stereo rendering (original path)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

            // Draw environment background (skybox) - supports curved and floating monitor modes
            if (environmentEnabled && displayMode != DisplayMode.KEYHOLE_PANNING) {
                when (displayMode) {
                    DisplayMode.CURVED_MONITOR -> {
                        val cc = cylinderController
                        if (cc != null) {
                            environmentRenderer?.apply {
                                updateMonitorParams(cc.getCurrentRadius(), cc.getArcAngle(), cc.getMonitorHeight())
                                setZoom(cc.getZoomLevel())
                                drawBackground(cc.getViewMatrix(), projectionMatrix)
                            }
                        }
                    }
                    DisplayMode.FLOATING_MONITOR -> {
                        val vsc = virtualScreenController
                        if (vsc != null) {
                            environmentRenderer?.apply {
                                // Scale radius inversely with screen scale for consistent ring positioning
                                // Fixed screen distance is 2.5m, so radius = 2.5 / scale
                                val scale = vsc.getCurrentScale()
                                val effectiveRadius = 2.5f / scale
                                updateMonitorParams(effectiveRadius, 60f, 1.0f)
                                setZoom(vsc.getZoomLevel())
                                drawBackground(vsc.getHeadViewMatrix(), projectionMatrix)
                            }
                        }
                    }
                    else -> {}
                }
            }

            // Use shader program
            GLES30.glUseProgram(shaderProgram)

            // Ensure proper GL state for video rendering
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glFrontFace(GLES30.GL_CCW)
            GLES30.glDisable(GLES30.GL_BLEND)

            // Set uniforms based on display mode
            when (displayMode) {
                DisplayMode.FLOATING_MONITOR -> renderFloatingMonitorMode(stMatrix)
                DisplayMode.KEYHOLE_PANNING -> renderKeyholeMode(stMatrix)
                DisplayMode.CURVED_MONITOR -> renderCurvedMonitorMode(stMatrix, deltaTime)
            }

            // Bind video texture
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, provider.getTextureId())
            GLES30.glUniform1i(uVideoTexture, 0)

            // Draw appropriate mesh based on display mode
            when (displayMode) {
                DisplayMode.CURVED_MONITOR -> cylinderMesh?.draw()
                else -> mesh?.draw()
            }

            // Draw environment frame and foreground (after monitor content)
            if (environmentEnabled && displayMode != DisplayMode.KEYHOLE_PANNING) {
                when (displayMode) {
                    DisplayMode.CURVED_MONITOR -> {
                        val cc = cylinderController
                        if (cc != null) {
                            environmentRenderer?.apply {
                                drawFrame(cc.getViewMatrix(), projectionMatrix)
                                drawForeground(cc.getViewMatrix(), projectionMatrix)
                            }
                        }
                    }
                    DisplayMode.FLOATING_MONITOR -> {
                        val vsc = virtualScreenController
                        if (vsc != null) {
                            environmentRenderer?.apply {
                                drawFrame(vsc.getHeadViewMatrix(), projectionMatrix)
                                drawForeground(vsc.getHeadViewMatrix(), projectionMatrix)
                            }
                        }
                    }
                    else -> {}
                }
            }

            // Draw screen-space HUD (fixed to glasses viewport, after all 3D content)
            if (hudEnabled) {
                // Update HUD with current stats
                hudRenderer?.setFps(currentFps)
                hudRenderer?.draw()
            }
        }

        ShaderUtils.checkGlError("onDrawFrame")
    }

    /**
     * Get the current zoom level as a display value (1.0 = 100%).
     */
    private fun getDisplayZoomLevel(): Float {
        return when (displayMode) {
            DisplayMode.FLOATING_MONITOR -> virtualScreenController?.getCurrentScale() ?: 1.0f
            DisplayMode.CURVED_MONITOR -> {
                // Convert radius to zoom: smaller radius = more zoomed in
                val cc = cylinderController ?: return 1.0f
                val radius = cc.getCurrentRadius()
                // Radius 2.5 = 1.0x, radius 1.25 = 2.0x, radius 5.0 = 0.5x
                2.5f / radius
            }
            DisplayMode.KEYHOLE_PANNING -> {
                // Keyhole zoom is 0-1, convert to scale factor
                val keyholeZoom = keyholeViewport?.getZoomLevel() ?: 0.5f
                // 0 = zoomed in (3x), 1 = zoomed out (0.5x)
                3.0f - (keyholeZoom * 2.5f)
            }
        }
    }

    /**
     * Render one eye for stereo mode.
     * Contains the full render pipeline: skybox → monitor → frame → dashboard
     *
     * @param stereoProjection Projection matrix with correct per-eye aspect ratio
     */
    private fun renderEyeStereo(
        eyeViewMatrix: FloatArray,
        eyeMvpMatrix: FloatArray,
        stMatrix: FloatArray,
        deltaTime: Float,
        provider: VideoTextureProvider,
        stereoProjection: FloatArray
    ) {
        // Phase 1: Draw skybox (behind everything)
        if (environmentEnabled) {
            environmentRenderer?.drawBackground(eyeViewMatrix, stereoProjection)
        }

        // Phase 2: Draw monitor content
        GLES30.glUseProgram(shaderProgram)

        // Set GL state for video rendering
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Set MVP and other uniforms for curved monitor
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, eyeMvpMatrix, 0)

        val fullTextureRect = floatArrayOf(0f, 0f, 1f, 1f)
        GLES30.glUniform4fv(uKeyholeRect, 1, fullTextureRect, 0)
        GLES30.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)
        GLES30.glUniform1f(uDisplayMode, 2f)

        GLES30.glUniform1f(uTestPatternEnabled, if (testPatternEnabled) 1f else 0f)
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000f
        GLES30.glUniform1f(uTime, elapsedSeconds)

        GLES30.glUniform2f(uCursorPos, cursorX / streamWidth, cursorY / streamHeight)
        GLES30.glUniform1f(uCursorEnabled, if (cursorEnabled) 1f else 0f)
        GLES30.glUniform1f(uZoomLevel, cylinderController?.getZoomLevel() ?: 0f)

        GLES30.glUniform1f(uCasSharpening, casSharpening)
        GLES30.glUniform2f(uTexelSize, 1f / streamWidth, 1f / streamHeight)

        // Bezel uniforms
        if (environmentEnabled) {
            GLES30.glUniform1f(uBezelEnabled, 1f)
            val bezelColor = currentTheme.bezelColorFloat()
            GLES30.glUniform3f(uBezelColor, bezelColor[0], bezelColor[1], bezelColor[2])
            GLES30.glUniform1f(uBezelWidth, 0.04f)
            GLES30.glUniform1f(uAspectRatio, streamWidth.toFloat() / streamHeight)
        } else {
            GLES30.glUniform1f(uBezelEnabled, 0f)
        }

        // Bind video texture and draw
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, provider.getTextureId())
        GLES30.glUniform1i(uVideoTexture, 0)
        cylinderMesh?.draw()

        // Phase 3: Draw physical frame
        if (environmentEnabled) {
            environmentRenderer?.drawFrame(eyeViewMatrix, stereoProjection)
        }

        // Phase 4: Draw dashboard HUD
        if (environmentEnabled) {
            environmentRenderer?.drawForeground(eyeViewMatrix, stereoProjection)
        }
    }

    /**
     * Render in floating monitor mode.
     *
     * The desktop appears as a screen floating in 3D space. Head rotation
     * moves the camera viewing the stationary screen (with dampened follow
     * to prevent micro-jitter amplification).
     *
     * Cursor-centered panning: When zoomed in (scale > 1.0) and cursor tracking
     * is enabled, the viewport pans to keep the cursor centered (soft-edge).
     */
    private fun renderFloatingMonitorMode(stMatrix: FloatArray) {
        val vsc = virtualScreenController ?: return

        // Update head pose from current tracking values
        vsc.updateHeadPose(headYawDegrees, headPitchDegrees)

        // Update cursor position for cursor-centered panning
        vsc.updateCursorPosition(cursorX, cursorY)

        // Get combined MVP matrix (includes model, view, projection)
        val mvp = vsc.getMVPMatrix()

        // Set MVP matrix
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvp, 0)

        // Get UV rect for cursor-centered panning (when zoomed in)
        // When zoomed out (scale <= 1.0), returns full texture (0,0,1,1)
        val uvRect = vsc.getViewportUVRect()
        val textureRect = floatArrayOf(uvRect.u0, uvRect.v0, uvRect.u1, uvRect.v1)
        GLES30.glUniform4fv(uKeyholeRect, 1, textureRect, 0)

        // SurfaceTexture transform matrix
        GLES30.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)

        // Display mode uniform (1.0 = floating monitor)
        GLES30.glUniform1f(uDisplayMode, 1f)

        // Test pattern and time uniforms
        GLES30.glUniform1f(uTestPatternEnabled, if (testPatternEnabled) 1f else 0f)
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000f
        GLES30.glUniform1f(uTime, elapsedSeconds)

        // Cursor uniforms - when panning is active, calculate cursor's position within the UV rect
        if (vsc.isCursorPanningActive()) {
            // Cursor position relative to visible viewport
            val cursorNormX = cursorX / streamWidth
            val cursorNormY = cursorY / streamHeight
            val cursorInViewportX = ((cursorNormX - uvRect.u0) / (uvRect.u1 - uvRect.u0)).coerceIn(0f, 1f)
            val cursorInViewportY = ((cursorNormY - uvRect.v0) / (uvRect.v1 - uvRect.v0)).coerceIn(0f, 1f)
            GLES30.glUniform2f(uCursorPos, cursorInViewportX, cursorInViewportY)
        } else {
            GLES30.glUniform2f(uCursorPos, cursorX / streamWidth, cursorY / streamHeight)
        }
        GLES30.glUniform1f(uCursorEnabled, if (cursorEnabled) 1f else 0f)

        // Pass zoom level for cursor scaling (same as keyhole mode)
        val zoomLevel = vsc.getZoomLevel()
        GLES30.glUniform1f(uZoomLevel, zoomLevel)

        // CAS sharpening uniforms
        GLES30.glUniform1f(uCasSharpening, casSharpening)
        GLES30.glUniform2f(uTexelSize, 1f / streamWidth, 1f / streamHeight)

        // Bezel enabled when environment is on (provides glowing border)
        if (environmentEnabled) {
            GLES30.glUniform1f(uBezelEnabled, 1f)
            val bezelColor = currentTheme.bezelColorFloat()
            GLES30.glUniform3f(uBezelColor, bezelColor[0], bezelColor[1], bezelColor[2])
            GLES30.glUniform1f(uBezelWidth, 0.04f)
            GLES30.glUniform1f(uAspectRatio, streamWidth.toFloat() / streamHeight)
        } else {
            GLES30.glUniform1f(uBezelEnabled, 0f)
        }
    }

    /**
     * Render in keyhole panning mode.
     *
     * Traditional UV-based panning across the desktop texture. Head rotation
     * changes which portion of the desktop is visible through the "keyhole".
     */
    private fun renderKeyholeMode(stMatrix: FloatArray) {
        // Calculate viewport rect and cursor screen position (soft-edge cursor mode)
        val result: ViewportResult = keyholeViewport?.updateWithCursor(
            cursorX,
            cursorY,
            headTimestampNs
        ) ?: ViewportResult(TextureRect(0f, 0f, 1f, 1f), 0.5f, 0.5f)

        val keyholeRect = result.textureRect

        // Set MVP matrix (orthographic projection fills viewport with quad)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, keyholeOrthoMatrix, 0)

        // Keyhole UV rect
        GLES30.glUniform4fv(uKeyholeRect, 1, keyholeRect.toFloatArray(), 0)

        // SurfaceTexture transform matrix
        GLES30.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)

        // Display mode uniform (0.0 = keyhole panning)
        GLES30.glUniform1f(uDisplayMode, 0f)

        // Test pattern and time uniforms
        GLES30.glUniform1f(uTestPatternEnabled, if (testPatternEnabled) 1f else 0f)
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000f
        GLES30.glUniform1f(uTime, elapsedSeconds)

        // Cursor uniforms (soft-edge cursor position from ViewportResult)
        GLES30.glUniform2f(uCursorPos, result.cursorScreenX, result.cursorScreenY)
        GLES30.glUniform1f(uCursorEnabled, if (cursorEnabled) 1f else 0f)

        // Zoom level for cursor scaling
        val zoomLevel = keyholeViewport?.getZoomLevel() ?: 0f
        GLES30.glUniform1f(uZoomLevel, zoomLevel)

        // CAS sharpening uniforms
        GLES30.glUniform1f(uCasSharpening, casSharpening)
        GLES30.glUniform2f(uTexelSize, 1f / streamWidth, 1f / streamHeight)

        // Bezel disabled in this mode
        GLES30.glUniform1f(uBezelEnabled, 0f)
    }

    /**
     * Render in curved monitor mode.
     *
     * The desktop appears on a cylindrical surface wrapping around the user.
     * Distance-based zoom changes the cylinder radius.
     */
    private fun renderCurvedMonitorMode(stMatrix: FloatArray, deltaTime: Float) {
        val cc = cylinderController ?: return
        val cm = cylinderMesh ?: return

        // Update head pose
        cc.updateHeadPose(headYawDegrees, headPitchDegrees, deltaTime)

        // Update cursor position for cursor-centered horizontal panning
        cc.updateCursorPosition(cursorX, cursorY)

        // Update zoom animation and mesh radius if needed
        val needsMeshUpdate = cc.updateZoomAnimation(deltaTime)
        if (needsMeshUpdate) {
            val newRadius = cc.getCurrentRadius()
            cm.updateRadius(newRadius)
        }

        // Get MVP matrix
        val mvp = cc.getMVPMatrix()

        // Set MVP matrix
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvp, 0)

        // For curved monitor mode, use full texture (no keyhole cropping)
        val fullTextureRect = floatArrayOf(0f, 0f, 1f, 1f)
        GLES30.glUniform4fv(uKeyholeRect, 1, fullTextureRect, 0)

        // SurfaceTexture transform matrix
        GLES30.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)

        // Display mode uniform (2.0 = curved monitor)
        GLES30.glUniform1f(uDisplayMode, 2f)

        // Test pattern and time uniforms
        GLES30.glUniform1f(uTestPatternEnabled, if (testPatternEnabled) 1f else 0f)
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000f
        GLES30.glUniform1f(uTime, elapsedSeconds)

        // Cursor uniforms
        GLES30.glUniform2f(uCursorPos, cursorX / streamWidth, cursorY / streamHeight)
        GLES30.glUniform1f(uCursorEnabled, if (cursorEnabled) 1f else 0f)

        // Zoom level for UI (inverted - smaller radius = zoomed in)
        GLES30.glUniform1f(uZoomLevel, cc.getZoomLevel())

        // CAS sharpening uniforms
        GLES30.glUniform1f(uCasSharpening, casSharpening)
        GLES30.glUniform2f(uTexelSize, 1f / streamWidth, 1f / streamHeight)

        // Bezel uniforms
        if (environmentEnabled) {
            GLES30.glUniform1f(uBezelEnabled, 1f)
            val bezelColor = currentTheme.bezelColorFloat()
            GLES30.glUniform3f(uBezelColor, bezelColor[0], bezelColor[1], bezelColor[2])
            GLES30.glUniform1f(uBezelWidth, 0.04f)  // Thicker for visibility
            GLES30.glUniform1f(uAspectRatio, streamWidth.toFloat() / streamHeight)
        } else {
            GLES30.glUniform1f(uBezelEnabled, 0f)
        }
    }

    /**
     * Recenter the virtual screen to current head position.
     */
    fun recenterVirtualScreen() {
        virtualScreenController?.recenter(headYawDegrees, headPitchDegrees)
    }

    /**
     * Recenter the curved cylinder view to current head position.
     */
    fun recenterCurvedMonitor() {
        cylinderController?.recenter(headYawDegrees, headPitchDegrees)
    }

    /**
     * Recenter the keyhole viewport to current head position.
     */
    fun recenterKeyhole() {
        keyholeViewport?.recenter(headYawDegrees, headPitchDegrees)
    }

    /**
     * Set the virtual screen scale for floating monitor mode.
     *
     * @param scale Scale factor (0.5 to 3.0)
     */
    fun setVirtualScreenScale(scale: Float) {
        virtualScreenController?.setScale(scale)
    }

    /**
     * Set cursor tracking enabled for viewport panning.
     *
     * When enabled and zoomed in, the viewport will pan to keep
     * the cursor centered (soft-edge panning).
     *
     * @param enabled True to enable cursor-centered panning
     */
    fun setCursorTrackingEnabled(enabled: Boolean) {
        // Store the value for later application when controllers are created
        storedCursorTrackingEnabled = enabled
        // Apply to any existing controllers
        virtualScreenController?.cursorTrackingEnabled = enabled
        cylinderController?.cursorTrackingEnabled = enabled
        keyholeViewport?.cursorTrackingEnabled = enabled
    }

    /**
     * Set the curved monitor radius.
     *
     * IMPORTANT: This method only updates the CylinderController's target state.
     * The actual mesh update happens on the GL thread in renderCurvedMonitorMode()
     * when updateZoomAnimation() returns true. This pattern prevents GL threading
     * violations - OpenGL commands must only be called from the GL thread.
     *
     * @param radius Radius in meters (1.0 to 5.0)
     */
    fun setCurvedMonitorRadius(radius: Float) {

        // Ensure controller exists (may be called before onSurfaceCreated)
        if (cylinderController == null) {
            cylinderController = CylinderController()
            // Apply stored cursor tracking state (may have been set before controller was created)
            cylinderController?.cursorTrackingEnabled = storedCursorTrackingEnabled
        }
        val cc = cylinderController ?: run {
            return
        }
        // ONLY update the controller state (thread-safe float assignment).
        // DO NOT call cylinderMesh.updateRadius() here - it would cause a GL
        // threading violation since this method is called from the UI thread.
        // The render loop (renderCurvedMonitorMode) will see the change and
        // update the mesh on the GL thread.
        //
        // Use immediate=true to skip animation - provides instant feedback when
        // adjusting the zoom slider. The mesh update still happens on GL thread.
        cc.setRadius(radius, immediate = true)

    }

    /**
     * Enable/disable environment rendering.
     */
    fun setEnvironmentEnabled(enabled: Boolean) {
        environmentEnabled = enabled
    }

    /**
     * Set environment theme.
     * Theme is applied on the GL thread during next drawFrame() call
     * because skybox texture regeneration requires GL context.
     */
    fun setEnvironmentTheme(theme: EnvironmentTheme) {
        currentTheme = theme
        pendingTheme = theme  // Apply on GL thread
    }

    /**
     * Release OpenGL resources.
     * Must be called on GL thread.
     */
    fun release() {
        mesh?.release()
        mesh = null

        cylinderMesh?.release()
        cylinderMesh = null

        environmentRenderer?.release()
        environmentRenderer = null

        hudRenderer?.release()
        hudRenderer = null

        if (shaderProgram > 0) {
            GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
    }

    /**
     * Set the connection quality displayed on the HUD (0-5 bars).
     */
    fun setHudConnectionQuality(quality: Int) {
        hudRenderer?.setConnectionQuality(quality)
    }

    /**
     * Set the input mode displayed on the HUD (affects control hints).
     */
    fun setHudInputMode(mode: String) {
        hudRenderer?.setInputMode(mode)
    }

    // ==================== GLTextureRenderer.Renderer implementation ====================

    /**
     * GLTextureRenderer.Renderer implementation - delegates to GLSurfaceView version.
     */
    override fun onSurfaceCreated() {
        onSurfaceCreated(null, null)
    }

    /**
     * GLTextureRenderer.Renderer implementation - delegates to GLSurfaceView version.
     */
    override fun onSurfaceChanged(width: Int, height: Int) {
        onSurfaceChanged(null, width, height)
    }

    /**
     * GLTextureRenderer.Renderer implementation - delegates to GLSurfaceView version.
     */
    override fun onDrawFrame() {
        onDrawFrame(null)
    }
}
