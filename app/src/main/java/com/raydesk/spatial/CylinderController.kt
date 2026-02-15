package com.raydesk.spatial

import android.opengl.Matrix
import android.util.Log
import kotlin.math.abs
import kotlin.math.sign

/**
 * Controls the curved cylinder display for immersive AR viewing.
 *
 * Unlike VirtualScreenController which uses scale-based zoom at fixed distance,
 * CylinderController uses distance-based zoom - the cylinder radius changes,
 * making the user feel like they're moving closer/further from the screens.
 *
 * Key behaviors:
 * - Cylinder wraps around user at configurable radius
 * - Zoom changes radius (1.0m - 5.0m), not scale
 * - Head tracking rotates view around cylinder (continuous pan)
 * - Dampened follow prevents micro-jitter
 * - Vertical follow prevents "mail slot" effect (Gemini recommendation)
 */
class CylinderController {

    companion object {
        private const val TAG = "CylinderController"

        // Radius limits for zoom
        const val MIN_RADIUS = 1.0f      // Meters - closest (immersive)
        const val MAX_RADIUS = 5.0f      // Meters - furthest (overview)
        const val DEFAULT_RADIUS = 2.5f  // Meters - comfortable default
        const val ZOOM_STEP = 0.25f      // Meters per zoom gesture

        // Animation
        const val ANIMATION_DURATION = 0.2f  // Seconds for smooth zoom

        // Dampening (same as VirtualScreenController)
        private const val DAMPENING_COEFF = 0.1f
        private const val STICK_TO_VIEW_YAW_THRESHOLD = 20f
        private const val STICK_TO_VIEW_PITCH_THRESHOLD = 12f

        // Vertical follow (Gemini recommendation)
        private const val VERTICAL_FOLLOW_THRESHOLD = 8f  // Degrees before follow kicks in
        private const val VERTICAL_FOLLOW_SPEED = 0.15f   // Lerp coefficient
        private const val MAX_VERTICAL_OFFSET = 0.5f      // Max meters up/down

        // Cursor-centered panning threshold
        // When radius is below this value, edges of the cylinder start clipping
        private const val PANNING_THRESHOLD_RADIUS = 2.0f
    }

    // Radius state (for distance-based zoom)
    private var currentRadius: Float = DEFAULT_RADIUS
    private var targetRadius: Float = DEFAULT_RADIUS
    private var startRadius: Float = DEFAULT_RADIUS
    private var radiusAnimationProgress: Float = 1f  // 1 = complete

    // Flag for immediate radius changes that need mesh update on GL thread
    // Marked @Volatile for thread-safe visibility between UI and GL threads
    @Volatile
    private var pendingRadiusUpdate: Boolean = false

    // Head tracking state
    private var rawHeadYaw: Float = 0f
    private var rawHeadPitch: Float = 0f
    private var baseYaw: Float = 0f
    private var basePitch: Float = 0f

    // Dampened follow state
    private var smoothedYaw: Float = 0f
    private var smoothedPitch: Float = 0f

    // Vertical follow state (Gemini recommendation)
    var verticalFollowEnabled: Boolean = true
    private var cylinderVerticalOffset: Float = 0f
    private var targetVerticalOffset: Float = 0f

    // Pre-allocated matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private var projectionConfigured = false

    // === CURSOR-CENTERED PANNING STATE ===
    // Cursor position in normalized coordinates (0-1)
    private var cursorU: Float = 0.5f
    private var cursorV: Float = 0.5f

    // Cursor tracking enabled - when true and zoomed in, view pans horizontally to follow cursor
    var cursorTrackingEnabled: Boolean = true

    // Desktop dimensions for cursor position normalization
    private var desktopWidth: Int = 1920
    private var desktopHeight: Int = 1080

    // Horizontal panning offset (yaw bias based on cursor position)
    private var cursorYawOffset: Float = 0f

    init {
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.setIdentityM(projectionMatrix, 0)
    }

    // === ZOOM METHODS (Distance-Based) ===

    /**
     * Zoom in - decrease radius (move closer to screens).
     */
    fun zoomIn() {
        startRadius = currentRadius
        targetRadius = (currentRadius - ZOOM_STEP).coerceAtLeast(MIN_RADIUS)
        radiusAnimationProgress = 0f
    }

    /**
     * Zoom out - increase radius (move away from screens).
     */
    fun zoomOut() {
        startRadius = currentRadius
        targetRadius = (currentRadius + ZOOM_STEP).coerceAtMost(MAX_RADIUS)
        radiusAnimationProgress = 0f
    }

    /**
     * Set radius directly to a specific value.
     *
     * @param radius Target radius in meters (clamped to MIN_RADIUS..MAX_RADIUS)
     * @param immediate If true, snap immediately (no animation). Use for real-time
     *                  slider feedback. If false (default), animate smoothly.
     */
    fun setRadius(radius: Float, immediate: Boolean = false) {
        targetRadius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)

        if (immediate) {
            // Snap immediately - no animation
            currentRadius = targetRadius
            startRadius = targetRadius
            radiusAnimationProgress = 1f
            // Set flag so GL thread knows to update the mesh
            pendingRadiusUpdate = true
        } else {
            // Animate smoothly
            startRadius = currentRadius
            radiusAnimationProgress = 0f
        }
    }

    /**
     * Reset zoom to default radius.
     */
    fun resetZoom() {
        startRadius = currentRadius
        targetRadius = DEFAULT_RADIUS
        radiusAnimationProgress = 0f
    }

    /**
     * Update zoom animation. Call every frame.
     *
     * @param deltaTime Time since last frame in seconds
     * @return true if radius changed (mesh needs update)
     */
    fun updateZoomAnimation(deltaTime: Float): Boolean {
        // Check for pending immediate update first (from setRadius with immediate=true)
        if (pendingRadiusUpdate) {
            pendingRadiusUpdate = false
            return true  // Signal that mesh needs update
        }

        if (radiusAnimationProgress >= 1f) return false

        val previousRadius = currentRadius
        radiusAnimationProgress = (radiusAnimationProgress + deltaTime / ANIMATION_DURATION)
            .coerceAtMost(1f)
        currentRadius = startRadius + (targetRadius - startRadius) * radiusAnimationProgress

        return currentRadius != previousRadius
    }

    /**
     * Get current radius for mesh update.
     */
    fun getCurrentRadius(): Float = currentRadius

    /**
     * Get zoom level as 0-1 for UI display.
     * 0 = MAX_RADIUS (furthest), 1 = MIN_RADIUS (closest)
     * Inverted because smaller radius = zoomed in
     */
    fun getZoomLevel(): Float {
        return 1f - (currentRadius - MIN_RADIUS) / (MAX_RADIUS - MIN_RADIUS)
    }

    // === CURSOR-CENTERED PANNING ===

    /**
     * Update desktop dimensions for cursor normalization.
     */
    fun updateDesktopDimensions(width: Int, height: Int) {
        desktopWidth = width
        desktopHeight = height
    }

    /**
     * Update cursor position for horizontal panning.
     *
     * When zoomed in close (radius < PANNING_THRESHOLD_RADIUS) and cursor tracking
     * is enabled, the view pans horizontally to keep the cursor visible.
     *
     * @param cursorX Cursor X position in desktop coordinates
     * @param cursorY Cursor Y position in desktop coordinates
     */
    fun updateCursorPosition(cursorX: Float, cursorY: Float) {
        cursorU = (cursorX / desktopWidth).coerceIn(0f, 1f)
        cursorV = (cursorY / desktopHeight).coerceIn(0f, 1f)

        // Calculate horizontal yaw offset based on cursor position when zoomed in
        if (cursorTrackingEnabled && currentRadius < PANNING_THRESHOLD_RADIUS) {
            // Map cursor X (0-1) to yaw offset (degrees)
            // Cursor at 0 (left) -> positive yaw offset (look left)
            // Cursor at 1 (right) -> negative yaw offset (look right)
            // Cursor at 0.5 (center) -> no offset
            val cursorOffsetFromCenter = cursorU - 0.5f

            // Scale factor: more panning when zoomed in closer
            // At MIN_RADIUS (1.0m), full arc is ~60° so max offset should be ~30°
            // At PANNING_THRESHOLD_RADIUS (2.0m), just starting to clip
            val zoomFactor = (PANNING_THRESHOLD_RADIUS - currentRadius) / (PANNING_THRESHOLD_RADIUS - MIN_RADIUS)
            val maxOffsetDegrees = 30f * zoomFactor.coerceIn(0f, 1f)

            // Apply offset - negative because looking at cursor means rotating view
            // in opposite direction (cursor left -> look left -> rotate view right)
            cursorYawOffset = -cursorOffsetFromCenter * maxOffsetDegrees * 2f
        } else {
            cursorYawOffset = 0f
        }
    }

    /**
     * Check if cursor-centered panning is active.
     * True when zoomed in close and cursor tracking is enabled.
     */
    fun isCursorPanningActive(): Boolean {
        return cursorTrackingEnabled && currentRadius < PANNING_THRESHOLD_RADIUS
    }

    /**
     * Get the cursor yaw offset for horizontal panning.
     */
    fun getCursorYawOffset(): Float = cursorYawOffset

    // === HEAD TRACKING ===

    /**
     * Update head orientation from sensor.
     *
     * @param yaw Yaw in degrees (left/right)
     * @param pitch Pitch in degrees (up/down)
     * @param deltaTime Time since last frame (for vertical follow)
     */
    fun updateHeadPose(yaw: Float, pitch: Float, deltaTime: Float = 0.016f) {
        // Adjust for baseline
        rawHeadYaw = yaw - baseYaw
        rawHeadPitch = pitch - basePitch

        // Stick-to-view for yaw
        val yawDelta = rawHeadYaw - smoothedYaw
        if (abs(yawDelta) > STICK_TO_VIEW_YAW_THRESHOLD) {
            val overshoot = yawDelta - STICK_TO_VIEW_YAW_THRESHOLD * sign(yawDelta)
            smoothedYaw += overshoot * DAMPENING_COEFF * 2f
        }

        // Stick-to-view for pitch
        val pitchDelta = rawHeadPitch - smoothedPitch
        if (abs(pitchDelta) > STICK_TO_VIEW_PITCH_THRESHOLD) {
            val overshoot = pitchDelta - STICK_TO_VIEW_PITCH_THRESHOLD * sign(pitchDelta)
            smoothedPitch += overshoot * DAMPENING_COEFF * 2f
        }

        // Micro-jitter dampening
        smoothedYaw += (rawHeadYaw - smoothedYaw) * DAMPENING_COEFF
        smoothedPitch += (rawHeadPitch - smoothedPitch) * DAMPENING_COEFF

        // Vertical follow (Gemini recommendation)
        updateVerticalFollow(deltaTime)
    }

    /**
     * Update vertical follow to prevent "mail slot" effect.
     */
    private fun updateVerticalFollow(deltaTime: Float) {
        if (!verticalFollowEnabled) {
            targetVerticalOffset = 0f
        } else {
            val pitchOffset = rawHeadPitch
            if (abs(pitchOffset) > VERTICAL_FOLLOW_THRESHOLD) {
                val overshoot = pitchOffset - VERTICAL_FOLLOW_THRESHOLD * sign(pitchOffset)
                val metersPerDegree = 0.02f
                targetVerticalOffset = (overshoot * metersPerDegree)
                    .coerceIn(-MAX_VERTICAL_OFFSET, MAX_VERTICAL_OFFSET)
            } else {
                targetVerticalOffset = 0f
            }
        }

        cylinderVerticalOffset += (targetVerticalOffset - cylinderVerticalOffset) * VERTICAL_FOLLOW_SPEED
    }

    /**
     * Recenter - current head position becomes "forward".
     */
    fun recenter(currentYaw: Float, currentPitch: Float) {
        Log.i(TAG, "recenter: yaw=$currentYaw, pitch=$currentPitch")
        baseYaw = currentYaw
        basePitch = currentPitch
        smoothedYaw = 0f
        smoothedPitch = 0f
        rawHeadYaw = 0f
        rawHeadPitch = 0f
        cylinderVerticalOffset = 0f
        targetVerticalOffset = 0f
    }

    // === MATRICES ===

    /**
     * Configure projection matrix.
     */
    fun configureProjection(fovDegrees: Float, aspect: Float, near: Float = 0.1f, far: Float = 100f) {
        Matrix.perspectiveM(projectionMatrix, 0, fovDegrees, aspect, near, far)
        projectionConfigured = true
    }

    /**
     * Get view matrix for head rotation.
     * The cylinder mesh is at origin - we rotate the view to look around it.
     */
    fun getViewMatrix(): FloatArray {
        Matrix.setIdentityM(viewMatrix, 0)

        // Apply vertical offset (Gemini vertical follow)
        Matrix.translateM(viewMatrix, 0, 0f, -cylinderVerticalOffset, 0f)

        // Rotate view based on head (inverted - view rotates opposite to head)
        // Include cursor yaw offset for cursor-centered panning when zoomed in
        val effectiveYaw = rawHeadYaw + cursorYawOffset
        Matrix.rotateM(viewMatrix, 0, -effectiveYaw, 0f, 1f, 0f)
        Matrix.rotateM(viewMatrix, 0, -rawHeadPitch, 1f, 0f, 0f)

        return viewMatrix
    }

    /**
     * Get MVP matrix for rendering.
     * Note: Model matrix is identity - cylinder mesh is already positioned.
     */
    fun getMVPMatrix(): FloatArray {
        if (!projectionConfigured) {
            Matrix.perspectiveM(projectionMatrix, 0, 30f, 4f / 3f, 0.1f, 100f)
        }

        // MVP = Projection * View (Model is identity)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, getViewMatrix(), 0)
        return mvpMatrix
    }

    // === STEREO RENDERING ===

    // Pre-allocated stereo matrices
    private val leftEyeViewMatrix = FloatArray(16)
    private val rightEyeViewMatrix = FloatArray(16)
    private val leftEyeMvpMatrix = FloatArray(16)
    private val rightEyeMvpMatrix = FloatArray(16)

    // IPD (Inter-Pupillary Distance) - 63mm standard
    var ipd: Float = 0.063f

    /**
     * Get view matrix for left eye (offset by +IPD/2 in X).
     */
    fun getLeftEyeViewMatrix(): FloatArray {
        Matrix.setIdentityM(leftEyeViewMatrix, 0)

        // Apply vertical offset
        Matrix.translateM(leftEyeViewMatrix, 0, 0f, -cylinderVerticalOffset, 0f)

        // Apply head rotation with cursor yaw offset for panning
        val effectiveYaw = rawHeadYaw + cursorYawOffset
        Matrix.rotateM(leftEyeViewMatrix, 0, -effectiveYaw, 0f, 1f, 0f)
        Matrix.rotateM(leftEyeViewMatrix, 0, -rawHeadPitch, 1f, 0f, 0f)

        // Apply IPD offset (left eye is +X from center)
        Matrix.translateM(leftEyeViewMatrix, 0, ipd / 2f, 0f, 0f)

        return leftEyeViewMatrix
    }

    /**
     * Get view matrix for right eye (offset by -IPD/2 in X).
     */
    fun getRightEyeViewMatrix(): FloatArray {
        Matrix.setIdentityM(rightEyeViewMatrix, 0)

        // Apply vertical offset
        Matrix.translateM(rightEyeViewMatrix, 0, 0f, -cylinderVerticalOffset, 0f)

        // Apply head rotation with cursor yaw offset for panning
        val effectiveYaw = rawHeadYaw + cursorYawOffset
        Matrix.rotateM(rightEyeViewMatrix, 0, -effectiveYaw, 0f, 1f, 0f)
        Matrix.rotateM(rightEyeViewMatrix, 0, -rawHeadPitch, 1f, 0f, 0f)

        // Apply IPD offset (right eye is -X from center)
        Matrix.translateM(rightEyeViewMatrix, 0, -ipd / 2f, 0f, 0f)

        return rightEyeViewMatrix
    }

    /**
     * Get MVP matrix for left eye.
     */
    fun getLeftEyeMVPMatrix(): FloatArray {
        if (!projectionConfigured) {
            Matrix.perspectiveM(projectionMatrix, 0, 30f, 4f / 3f, 0.1f, 100f)
        }
        Matrix.multiplyMM(leftEyeMvpMatrix, 0, projectionMatrix, 0, getLeftEyeViewMatrix(), 0)
        return leftEyeMvpMatrix
    }

    /**
     * Get MVP matrix for right eye.
     */
    fun getRightEyeMVPMatrix(): FloatArray {
        if (!projectionConfigured) {
            Matrix.perspectiveM(projectionMatrix, 0, 30f, 4f / 3f, 0.1f, 100f)
        }
        Matrix.multiplyMM(rightEyeMvpMatrix, 0, projectionMatrix, 0, getRightEyeViewMatrix(), 0)
        return rightEyeMvpMatrix
    }

    // === ACCESSORS ===

    fun getRawHeadYaw(): Float = rawHeadYaw
    fun getRawHeadPitch(): Float = rawHeadPitch
    fun getVerticalOffset(): Float = cylinderVerticalOffset
    fun isZoomAnimating(): Boolean = radiusAnimationProgress < 1f

    // Monitor geometry parameters (for frame/dashboard positioning)
    private var monitorArcAngle: Float = (60f * Math.PI / 180f).toFloat()  // 60 degrees default
    private var monitorHeight: Float = 1.0f  // 1 meter default

    fun getArcAngle(): Float = monitorArcAngle
    fun getMonitorHeight(): Float = monitorHeight

    /**
     * Update monitor geometry parameters.
     * Called when stream resolution changes to adjust aspect ratio.
     */
    fun updateMonitorGeometry(arcAngleDegrees: Float, heightMeters: Float) {
        monitorArcAngle = (arcAngleDegrees * Math.PI / 180f).toFloat()
        monitorHeight = heightMeters
    }
}
