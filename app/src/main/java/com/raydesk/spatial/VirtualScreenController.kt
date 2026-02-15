package com.raydesk.spatial

import android.opengl.Matrix
import android.util.Log
import kotlin.math.abs
import kotlin.math.sign

/**
 * Controls the position and orientation of the virtual screen in 3D space.
 *
 * The floating virtual monitor appears as a screen floating at a comfortable
 * viewing distance. Head rotation moves the camera viewing the stationary screen.
 *
 * Key behaviors (from Gemini blind spot analysis):
 * - Screen is NOT 100% world-locked (causes micro-jitter amplification from waveguide wobble)
 * - Uses "dampened follow" (0.1 coefficient) to hide waveguide/nose micro-wobble
 * - Implements "Stick-to-View" lazy follow when head turns past FOV limits
 * - Manual recenter via triple-tap temple gesture corrects 3DOF yaw drift
 *
 * Zoom behavior (scale-based, not distance-based):
 * - Fixed distance at 2.5m (waveguide focal plane sweet spot)
 * - Zoom changes screen SCALE, not distance (avoids VAC conflict)
 * - Multiplicative zoom (1.25x in, 0.8x out) feels perceptually linear
 * - Smooth Lerp animation over 0.2s prevents jarring jumps
 *
 * @param screenDistance Legacy param - ignored, uses FIXED_DISTANCE (2.5m)
 * @param screenHeight Virtual height of screen in meters
 * @param initialAspect Initial aspect ratio (updated from stream)
 */
class VirtualScreenController(
    private var screenDistance: Float = 2.5f,  // Legacy - uses FIXED_DISTANCE constant (2.5m)
    private var screenHeight: Float = 0.8f,    // Virtual height in meters (fits within 30° FOV)
    private var screenAspect: Float = 16f / 9f // Updated from stream
) {
    companion object {
        private const val TAG = "VirtualScreen"

        // Dampening coefficient (0.0 = rigid world-lock, 1.0 = follows head completely)
        // Gemini recommendation: 0.1 to hide micro-jitters without adding latency
        private const val DAMPENING_COEFF = 0.1f

        // Stick-to-View thresholds (degrees beyond FOV before screen follows)
        // RayNeo X3 Pro has ~30° diagonal FOV, so ~25° horizontal
        private const val STICK_TO_VIEW_YAW_THRESHOLD = 20f   // Degrees before lazy follow for yaw
        private const val STICK_TO_VIEW_PITCH_THRESHOLD = 12f // Pitch threshold is lower (vertical neck movement is harder)

        // Screen distance limits (legacy - kept for API compatibility)
        private const val MIN_SCREEN_DISTANCE = 1.5f
        private const val MAX_SCREEN_DISTANCE = 5.0f

        // === SCALE-BASED ZOOM CONSTANTS ===
        // Fixed distance at waveguide focal plane sweet spot (avoids VAC conflict)
        const val FIXED_DISTANCE = 2.5f    // Meters - waveguide sweet spot (replaces variable distance)

        // Scale limits for zoom (multiplicative, not additive)
        const val MIN_SCALE = 0.5f         // 50% = zoomed out max
        const val MAX_SCALE = 3.0f         // 300% = zoomed in max
        const val DEFAULT_SCALE = 1.0f

        // Multiplicative zoom factors (perceptually linear)
        const val ZOOM_IN_FACTOR = 1.25f   // Multiplicative zoom in
        const val ZOOM_OUT_FACTOR = 0.8f   // Multiplicative zoom out

        // Animation duration
        const val ZOOM_LERP_DURATION = 0.2f // Seconds for smooth animation
    }

    // Screen dimensions calculated from aspect ratio
    private var screenWidth: Float = screenHeight * screenAspect

    // Head tracking state (raw sensor input, adjusted for baseline)
    private var rawHeadYaw: Float = 0f
    private var rawHeadPitch: Float = 0f

    // Smoothed screen position (for dampened follow - NOT 100% world-locked)
    private var smoothedScreenYaw: Float = 0f
    private var smoothedScreenPitch: Float = 0f

    // Baseline for recentering (corrects 3DOF yaw drift)
    private var baseYaw: Float = 0f
    private var basePitch: Float = 0f

    // === SCALE-BASED ZOOM STATE ===
    // Current and target scale for Lerp animation
    private var currentScale: Float = DEFAULT_SCALE
    private var targetScale: Float = DEFAULT_SCALE
    private var startScale: Float = DEFAULT_SCALE  // Captures scale at animation start for correct Lerp
    private var scaleAnimationProgress: Float = 1f  // 1 = complete (no animation in progress)

    // === CURSOR-CENTERED PANNING STATE ===
    // Cursor position in normalized coordinates (0-1)
    private var cursorU: Float = 0.5f
    private var cursorV: Float = 0.5f

    // Cursor tracking enabled - when true and zoomed in, viewport follows cursor
    var cursorTrackingEnabled: Boolean = true

    // Desktop dimensions for cursor position normalization
    private var desktopWidth: Int = 1920
    private var desktopHeight: Int = 1080

    // Pre-allocated matrices for efficiency
    private val screenModelMatrix = FloatArray(16)
    private val headViewMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // Projection matrix (shared, configured once)
    private val projectionMatrix = FloatArray(16)
    private var projectionConfigured = false

    init {
        // Initialize identity matrices
        Matrix.setIdentityM(screenModelMatrix, 0)
        Matrix.setIdentityM(headViewMatrix, 0)
    }

    // Debug: track last logged time to avoid log spam
    private var lastHeadPoseLogTime = 0L

    /**
     * Update head orientation from sensor.
     *
     * Applies dampened follow to prevent micro-jitter amplification from
     * waveguide optics wobble on nose.
     *
     * @param yaw Yaw in degrees (left/right rotation)
     * @param pitch Pitch in degrees (up/down rotation)
     */
    fun updateHeadPose(yaw: Float, pitch: Float) {
        // Adjust for baseline (allows recentering)
        rawHeadYaw = yaw - baseYaw
        rawHeadPitch = pitch - basePitch

        // Debug: log every second to trace head pose updates
        val now = System.currentTimeMillis()
        if (now - lastHeadPoseLogTime > 1000) {
            lastHeadPoseLogTime = now
        }

        // Apply "Stick-to-View" lazy follow
        // If head turns beyond threshold, screen gently follows to prevent user losing it
        val yawDelta = rawHeadYaw - smoothedScreenYaw
        val pitchDelta = rawHeadPitch - smoothedScreenPitch

        // Horizontal (yaw) stick-to-view
        if (abs(yawDelta) > STICK_TO_VIEW_YAW_THRESHOLD) {
            // Head turned too far - drag screen along
            val overshoot = yawDelta - STICK_TO_VIEW_YAW_THRESHOLD * sign(yawDelta)
            smoothedScreenYaw += overshoot * DAMPENING_COEFF * 2f  // Faster catch-up when beyond threshold
        }

        // Vertical (pitch) stick-to-view
        if (abs(pitchDelta) > STICK_TO_VIEW_PITCH_THRESHOLD) {
            val overshoot = pitchDelta - STICK_TO_VIEW_PITCH_THRESHOLD * sign(pitchDelta)
            smoothedScreenPitch += overshoot * DAMPENING_COEFF * 2f
        }

        // Apply micro-jitter dampening (screen lags slightly behind head)
        // This hides the waveguide optics micro-wobble on nose without adding perceived latency
        smoothedScreenYaw += (rawHeadYaw - smoothedScreenYaw) * DAMPENING_COEFF
        smoothedScreenPitch += (rawHeadPitch - smoothedScreenPitch) * DAMPENING_COEFF
    }

    /**
     * Recenter the view - current head position becomes "forward".
     *
     * This is the fix for inevitable 3DOF yaw drift (TYPE_GAME_ROTATION_VECTOR
     * without magnetometer drifts over 5-10 minutes).
     *
     * @param currentYaw Current raw yaw from sensor
     * @param currentPitch Current raw pitch from sensor
     */
    fun recenter(currentYaw: Float, currentPitch: Float) {
        baseYaw = currentYaw
        basePitch = currentPitch
        smoothedScreenYaw = 0f
        smoothedScreenPitch = 0f
        rawHeadYaw = 0f
        rawHeadPitch = 0f
    }

    /**
     * Update aspect ratio when stream resolution is detected.
     *
     * @param width Stream width in pixels
     * @param height Stream height in pixels
     */
    fun updateAspectRatio(width: Int, height: Int) {
        if (height > 0) {
            screenAspect = width.toFloat() / height
            screenWidth = screenHeight * screenAspect
            desktopWidth = width
            desktopHeight = height
        }
    }

    /**
     * Update cursor position for cursor-centered panning.
     *
     * When zoomed in (scale > 1.0) and cursor tracking is enabled,
     * the viewport will pan to keep the cursor centered (soft-edge).
     *
     * @param cursorX Cursor X position in desktop coordinates
     * @param cursorY Cursor Y position in desktop coordinates
     */
    fun updateCursorPosition(cursorX: Float, cursorY: Float) {
        cursorU = (cursorX / desktopWidth).coerceIn(0f, 1f)
        cursorV = (cursorY / desktopHeight).coerceIn(0f, 1f)
    }

    /**
     * Calculate the UV rect for cursor-centered panning when zoomed in.
     *
     * When scale > 1.0 (zoomed in), we're showing less than the full desktop.
     * The viewport pans to keep the cursor centered, with soft-edge clamping
     * at desktop boundaries.
     *
     * Algorithm:
     * - visibleRatio = 1.0 / scale (e.g., scale=2.0 → see 50% of desktop)
     * - Center viewport on cursor position
     * - Clamp to desktop bounds (0,0 to 1,1)
     *
     * @return TextureRect with UV coordinates for the visible portion
     */
    fun getViewportUVRect(): TextureRect {
        // If cursor tracking disabled or not zoomed in, show full texture
        if (!cursorTrackingEnabled || currentScale <= 1.0f) {
            return TextureRect(0f, 0f, 1f, 1f)
        }

        // Calculate visible portion of desktop
        val visibleRatioX = 1.0f / currentScale
        val visibleRatioY = 1.0f / currentScale

        // Center viewport on cursor, then clamp to bounds
        var viewportU = cursorU - visibleRatioX / 2f
        var viewportV = cursorV - visibleRatioY / 2f

        // Soft-edge clamping at desktop boundaries
        viewportU = viewportU.coerceIn(0f, 1f - visibleRatioX)
        viewportV = viewportV.coerceIn(0f, 1f - visibleRatioY)

        return TextureRect(
            viewportU,
            viewportV,
            viewportU + visibleRatioX,
            viewportV + visibleRatioY
        )
    }

    /**
     * Check if cursor-centered panning is active.
     * This is true when zoomed in and cursor tracking is enabled.
     */
    fun isCursorPanningActive(): Boolean {
        return cursorTrackingEnabled && currentScale > 1.0f
    }

    /**
     * Set the virtual screen distance from the user.
     *
     * DEPRECATED: This method is kept for API compatibility but has NO EFFECT on zoom.
     * The screen is always rendered at FIXED_DISTANCE (2.5m) to avoid VAC conflict.
     * Use zoomIn()/zoomOut() for scale-based zoom instead.
     *
     * @param distance Distance in meters (ignored - always uses FIXED_DISTANCE)
     */
    @Deprecated("Use zoomIn()/zoomOut() for scale-based zoom. Distance is fixed at 2.5m to avoid VAC conflict.")
    fun setScreenDistance(distance: Float) {
        // Legacy: update instance variable but it's not used in getScreenModelMatrix()
        screenDistance = distance.coerceIn(MIN_SCREEN_DISTANCE, MAX_SCREEN_DISTANCE)
    }

    /**
     * Set the virtual screen height.
     *
     * @param height Height in meters
     */
    fun setScreenHeight(height: Float) {
        screenHeight = height.coerceAtLeast(0.5f)
        screenWidth = screenHeight * screenAspect
    }

    // === SCALE-BASED ZOOM METHODS ===

    /**
     * Zoom in by multiplicative factor (scale up).
     *
     * Uses multiplicative scaling (1.25x) which feels perceptually linear,
     * unlike additive steps which feel inconsistent at different zoom levels.
     */
    fun zoomIn() {
        startScale = currentScale
        targetScale = (currentScale * ZOOM_IN_FACTOR).coerceAtMost(MAX_SCALE)
        scaleAnimationProgress = 0f
    }

    /**
     * Zoom out by multiplicative factor (scale down).
     *
     * Uses multiplicative scaling (0.8x) which feels perceptually linear,
     * unlike additive steps which feel inconsistent at different zoom levels.
     */
    fun zoomOut() {
        startScale = currentScale
        targetScale = (currentScale * ZOOM_OUT_FACTOR).coerceAtLeast(MIN_SCALE)
        scaleAnimationProgress = 0f
    }

    /**
     * Reset zoom to default scale (1.0x).
     */
    fun resetZoom() {
        startScale = currentScale
        targetScale = DEFAULT_SCALE
        scaleAnimationProgress = 0f
    }

    /**
     * Set scale directly to a specific value.
     * Animates smoothly to the target scale.
     *
     * @param scale Target scale (clamped to MIN_SCALE..MAX_SCALE)
     */
    fun setScale(scale: Float) {
        startScale = currentScale
        targetScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        scaleAnimationProgress = 0f
    }

    /**
     * Update scale animation (call every frame).
     *
     * Smoothly interpolates from startScale to targetScale over ZOOM_LERP_DURATION.
     * This prevents jarring zoom jumps that cause discomfort in AR.
     *
     * Uses correct linear interpolation: start + (end - start) * t
     * where t goes from 0 to 1 over the animation duration.
     *
     * @param deltaTime Time since last frame in seconds
     */
    fun updateZoomAnimation(deltaTime: Float) {
        if (scaleAnimationProgress < 1f) {
            scaleAnimationProgress += deltaTime / ZOOM_LERP_DURATION
            scaleAnimationProgress = scaleAnimationProgress.coerceAtMost(1f)
            // Correct Lerp: start + (end - start) * t
            currentScale = startScale + (targetScale - startScale) * scaleAnimationProgress
        }
    }

    /**
     * Get zoom level as 0-1 for UI display.
     *
     * @return 0 = MIN_SCALE (50%), 1 = MAX_SCALE (200%)
     */
    fun getZoomLevel(): Float {
        return (currentScale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE)
    }

    /**
     * Get the current scale factor.
     *
     * @return Current scale (0.5 to 2.0)
     */
    fun getCurrentScale(): Float = currentScale

    /**
     * Check if zoom animation is currently in progress.
     *
     * @return true if animating
     */
    fun isZoomAnimating(): Boolean = scaleAnimationProgress < 1f

    /**
     * Get the model matrix for positioning the virtual screen.
     *
     * The screen position incorporates the smoothed follow offset, so it's not
     * 100% world-locked (which would amplify micro-jitter).
     *
     * ZOOM: Uses FIXED_DISTANCE (2.5m) and applies currentScale to dimensions.
     * This avoids VAC conflict - waveguide focal plane is fixed, so changing
     * distance causes vergence-accommodation mismatch. Scale-based zoom is safe.
     *
     * @return 4x4 model matrix in column-major order
     */
    fun getScreenModelMatrix(): FloatArray {
        Matrix.setIdentityM(screenModelMatrix, 0)

        // Fixed distance (2.5m) - never changes (avoids VAC conflict)
        Matrix.translateM(screenModelMatrix, 0, 0f, 0f, -FIXED_DISTANCE)

        // Apply smoothed screen offset (dampened follow)
        // Rotate around Y-axis (yaw) and X-axis (pitch)
        Matrix.rotateM(screenModelMatrix, 0, -smoothedScreenYaw, 0f, 1f, 0f)
        Matrix.rotateM(screenModelMatrix, 0, -smoothedScreenPitch, 1f, 0f, 0f)

        // Apply zoom scale to screen dimensions
        val scaledWidth = screenWidth * currentScale
        val scaledHeight = screenHeight * currentScale
        Matrix.scaleM(screenModelMatrix, 0, scaledWidth, scaledHeight, 1f)

        return screenModelMatrix
    }

    /**
     * Get the view matrix based on head rotation.
     *
     * This rotates the camera (what the user sees) based on raw head orientation.
     * The screen's dampened follow creates a subtle disconnect that hides micro-jitter.
     *
     * COORDINATE SYSTEM (Android TYPE_GAME_ROTATION_VECTOR):
     * - Yaw: rotation around Y-axis (looking left/right)
     * - Pitch: rotation around X-axis (looking up/down)
     *
     * For VIEW matrix, we apply INVERSE rotations (world rotates opposite to head):
     * - Head looks RIGHT (+yaw) → world rotates LEFT (-yaw around Y)
     * - Head looks UP (+pitch) → world rotates DOWN (-pitch around X)
     *
     * Order: Yaw first, then Pitch (opposite of model matrix convention)
     *
     * @return 4x4 view matrix in column-major order
     */
    fun getHeadViewMatrix(): FloatArray {
        Matrix.setIdentityM(headViewMatrix, 0)

        // Apply INVERSE rotations for view matrix
        // Yaw first (left/right), then pitch (up/down)
        // Negated because view matrix rotates world opposite to head direction
        Matrix.rotateM(headViewMatrix, 0, -rawHeadYaw, 0f, 1f, 0f)   // Yaw around Y
        Matrix.rotateM(headViewMatrix, 0, -rawHeadPitch, 1f, 0f, 0f) // Pitch around X

        return headViewMatrix
    }

    /**
     * Configure the projection matrix for the AR glasses.
     *
     * @param fovDegrees Vertical field of view in degrees (~30° for X3 Pro)
     * @param aspect Viewport aspect ratio
     * @param near Near clipping plane
     * @param far Far clipping plane
     */
    fun configureProjection(fovDegrees: Float, aspect: Float, near: Float = 0.1f, far: Float = 100f) {
        Matrix.perspectiveM(projectionMatrix, 0, fovDegrees, aspect, near, far)
        projectionConfigured = true
    }

    /**
     * Get the projection matrix.
     *
     * @return 4x4 projection matrix in column-major order
     */
    fun getProjectionMatrix(): FloatArray {
        if (!projectionConfigured) {
            // Default: 30° FOV, 4:3 aspect
            Matrix.perspectiveM(projectionMatrix, 0, 30f, 4f / 3f, 0.1f, 100f)
        }
        return projectionMatrix
    }

    /**
     * Get the combined MVP (Model-View-Projection) matrix.
     *
     * @return 4x4 MVP matrix in column-major order
     */
    fun getMVPMatrix(): FloatArray {
        val mvp = FloatArray(16)

        // MVP = Projection * View * Model
        Matrix.multiplyMM(tempMatrix, 0, getHeadViewMatrix(), 0, getScreenModelMatrix(), 0)
        Matrix.multiplyMM(mvp, 0, getProjectionMatrix(), 0, tempMatrix, 0)

        return mvp
    }

    // Accessors for current state
    @Deprecated("Screen is always at FIXED_DISTANCE (2.5m). Use getCurrentScale() for zoom level.")
    fun getScreenDistance(): Float = FIXED_DISTANCE
    fun getScreenHeight(): Float = screenHeight
    fun getScreenWidth(): Float = screenWidth
    fun getScreenAspect(): Float = screenAspect
    fun getRawHeadYaw(): Float = rawHeadYaw
    fun getRawHeadPitch(): Float = rawHeadPitch
}
