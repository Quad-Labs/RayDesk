package com.raydesk.spatial

/**
 * KeyholeViewport maps head rotation to texture UV coordinates for the
 * "keyhole" display concept.
 *
 * The desktop (e.g., 1920x1080) is larger than the AR viewport (e.g., 640x480).
 * Head movement pans a "keyhole" window across the desktop, providing
 * 1:1 pixel mapping for text readability while allowing access to the
 * full desktop through head tracking.
 *
 * Modes:
 * - KEYHOLE: Default. Head movement pans viewport across desktop.
 * - OVERVIEW: Shows entire desktop scaled to fit viewport.
 *
 * Zoom levels (0.0 to 1.0):
 * - 0.0 = Maximum zoom IN (keyhole size: 640x480 view of 1920x1080)
 * - 1.0 = Maximum zoom OUT (full desktop view)
 *
 * @param desktopWidth Width of the remote desktop in pixels
 * @param desktopHeight Height of the remote desktop in pixels
 * @param viewportWidth Width of the AR display viewport in pixels
 * @param viewportHeight Height of the AR display viewport in pixels
 * @param fovDegrees Field of view in degrees that maps to viewport width
 */
class KeyholeViewport(
    private var desktopWidth: Int = 1920,
    private var desktopHeight: Int = 1080,
    private val viewportWidth: Int = 640,
    private val viewportHeight: Int = 480,
    private val fovDegrees: Float = 30f,
    private val deadzone: Float = 0.2f  // Degrees of head movement that won't pan (prevents micro-jitter)
) {
    /**
     * Display mode for the viewport.
     */
    enum class Mode {
        /** Head movement pans viewport across desktop (1:1 pixel mapping) */
        KEYHOLE,
        /** Shows entire desktop scaled to fit viewport */
        OVERVIEW
    }

    private var mode: Mode = Mode.KEYHOLE

    // Pixels per degree of head rotation
    private val pixelsPerDegree = viewportWidth / fovDegrees

    // Maximum pan distance from center (in pixels)
    // These are var because they change when desktop dimensions are updated
    private var maxPanX = (desktopWidth - viewportWidth) / 2f
    private var maxPanY = (desktopHeight - viewportHeight) / 2f

    // Half viewport size in normalized UV coordinates
    // These are var because they change when desktop dimensions are updated
    private var halfViewportU = viewportWidth.toFloat() / desktopWidth / 2f
    private var halfViewportV = viewportHeight.toFloat() / desktopHeight / 2f

    // Baseline orientation for recentering
    private var baselineYaw = 0f
    private var baselinePitch = 0f

    // One-Euro filters for smooth head tracking
    private val yawFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)
    private val pitchFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)

    // Deadzone state tracking - prevents micro-jitter when holding still
    private var lastStableYaw = 0f
    private var lastStablePitch = 0f

    // ====== Zoom state (cursor-centered mode) ======
    // zoomLevel: 0.0 = closest (keyhole size), 1.0 = full overview
    private var zoomLevel: Float = 0f
    // Note: Zoom filtering removed - zoom should be immediate since it's user-initiated
    // and the One-Euro filter requires accurate timestamps which aren't always available

    // Cursor tracking enabled - when true, viewport pans to follow cursor
    // When false, viewport stays centered and cursor moves freely within it
    private var _cursorTrackingEnabled: Boolean = true
    var cursorTrackingEnabled: Boolean
        get() = _cursorTrackingEnabled
        set(value) {
            _cursorTrackingEnabled = value
        }

    // Minimum viewport ratio (keyhole size relative to desktop)
    // These are var because they change when desktop dimensions are updated
    private var minRatioU = viewportWidth.toFloat() / desktopWidth   // ~0.333
    private var minRatioV = viewportHeight.toFloat() / desktopHeight // ~0.444

    /**
     * Update viewport position based on head orientation.
     *
     * @param yawDegrees Head yaw (left/right rotation) in degrees
     * @param pitchDegrees Head pitch (up/down rotation) in degrees
     * @param timestampNs Timestamp in nanoseconds for filter calculations
     * @return TextureRect with normalized UV coordinates for the viewport
     */
    fun update(yawDegrees: Float, pitchDegrees: Float, timestampNs: Long): TextureRect {
        // Overview mode returns full texture
        if (mode == Mode.OVERVIEW) {
            return TextureRect(0f, 0f, 1f, 1f)
        }

        // Adjust for baseline (recentering support)
        val adjustedYaw = yawDegrees - baselineYaw
        val adjustedPitch = pitchDegrees - baselinePitch

        // Apply One-Euro filter for smooth tracking
        val filteredYaw = yawFilter.filter(adjustedYaw, timestampNs)
        val filteredPitch = pitchFilter.filter(adjustedPitch, timestampNs)

        // Apply deadzone to prevent micro-jitter when holding still
        lastStableYaw = applyDeadzone(filteredYaw, lastStableYaw)
        lastStablePitch = applyDeadzone(filteredPitch, lastStablePitch)

        // Convert degrees to pixel pan distance (using stable values)
        var panX = lastStableYaw * pixelsPerDegree
        var panY = lastStablePitch * pixelsPerDegree

        // Clamp to maximum pan range
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)

        // Convert pan to normalized UV center position
        // Positive yaw (right) -> positive panX -> higher U (right side of texture)
        // Positive pitch (up) -> positive panY -> lower V (top of texture)
        val centerU = 0.5f + (panX / desktopWidth)
        val centerV = 0.5f - (panY / desktopHeight)

        // Calculate viewport bounds from center
        var u0 = centerU - halfViewportU
        var v0 = centerV - halfViewportV

        // Clamp to texture bounds (0-1)
        u0 = u0.coerceIn(0f, 1f - 2 * halfViewportU)
        v0 = v0.coerceIn(0f, 1f - 2 * halfViewportV)

        val u1 = (u0 + 2 * halfViewportU).coerceAtMost(1f)
        val v1 = (v0 + 2 * halfViewportV).coerceAtMost(1f)

        return TextureRect(u0, v0, u1, v1)
    }

    /**
     * Recenter the viewport to the current head position.
     *
     * After calling this, the current head orientation will be treated
     * as the "center" position, and the viewport will be centered on
     * the desktop.
     *
     * @param currentYaw Current head yaw in degrees
     * @param currentPitch Current head pitch in degrees
     */
    fun recenter(currentYaw: Float, currentPitch: Float) {
        baselineYaw = currentYaw
        baselinePitch = currentPitch
        yawFilter.reset()
        pitchFilter.reset()
        lastStableYaw = 0f
        lastStablePitch = 0f
    }

    /**
     * Set the display mode.
     *
     * @param newMode The new mode to use
     */
    fun setMode(newMode: Mode) {
        if (mode == Mode.OVERVIEW && newMode == Mode.KEYHOLE) {
            yawFilter.reset()
            pitchFilter.reset()
            lastStableYaw = 0f
            lastStablePitch = 0f
        }
        mode = newMode
    }

    /**
     * Get the current display mode.
     *
     * @return Current mode
     */
    fun getMode(): Mode = mode

    /**
     * Apply deadzone to prevent micro-jitter.
     * If the change is within the deadzone threshold, keep the previous stable value.
     *
     * @param value Current filtered value
     * @param previousStable Previous stable value
     * @return Stable value (either current or previous depending on deadzone)
     */
    private fun applyDeadzone(value: Float, previousStable: Float): Float {
        return if (kotlin.math.abs(value - previousStable) < deadzone && deadzone > 0f) {
            previousStable
        } else {
            value
        }
    }

    // ====== Zoom methods (cursor-centered mode) ======

    /**
     * Set the zoom level directly.
     *
     * @param level Zoom level (0.0 = keyhole/closest, 1.0 = full overview)
     */
    fun setZoomLevel(level: Float) {
        zoomLevel = level.coerceIn(0f, 1f)
    }

    /**
     * Adjust zoom level by a delta amount.
     *
     * @param delta Amount to add to zoom level (positive = zoom out, negative = zoom in)
     */
    fun adjustZoom(delta: Float) {
        zoomLevel = (zoomLevel + delta).coerceIn(0f, 1f)
    }

    /**
     * Get the current zoom level.
     *
     * @return Current zoom level (0.0 = closest, 1.0 = full overview)
     */
    fun getZoomLevel(): Float = zoomLevel

    /**
     * Update desktop dimensions dynamically.
     *
     * This is used when the actual stream resolution differs from the requested resolution.
     * For example, if we request 3840x1080 (dual monitors) but the host falls back to
     * 1920x1080, we need to update our calculations.
     *
     * @param newWidth New desktop width in pixels
     * @param newHeight New desktop height in pixels
     */
    fun updateDesktopDimensions(newWidth: Int, newHeight: Int) {
        // Validate inputs
        require(newWidth > 0) { "Desktop width must be positive" }
        require(newHeight > 0) { "Desktop height must be positive" }

        desktopWidth = newWidth
        desktopHeight = newHeight

        // Recalculate derived values
        maxPanX = (desktopWidth - viewportWidth) / 2f
        maxPanY = (desktopHeight - viewportHeight) / 2f
        halfViewportU = viewportWidth.toFloat() / desktopWidth / 2f
        halfViewportV = viewportHeight.toFloat() / desktopHeight / 2f
        minRatioU = viewportWidth.toFloat() / desktopWidth
        minRatioV = viewportHeight.toFloat() / desktopHeight
    }

    /**
     * Update viewport position centered on cursor with current zoom level.
     *
     * This method implements "soft-edge" cursor behavior:
     * - The viewport tries to center on the cursor position
     * - When viewport would exceed desktop bounds, it clamps
     * - The cursor position on screen reflects where cursor falls within clamped viewport
     * - Result: cursor stays centered until hitting edges, then moves within viewport
     *
     * @param cursorX Cursor X position in desktop coordinates (0 to desktopWidth)
     * @param cursorY Cursor Y position in desktop coordinates (0 to desktopHeight)
     * @param timestampNs Timestamp in nanoseconds for filter calculations
     * @return ViewportResult with texture rect and cursor screen position
     */
    fun updateWithCursor(cursorX: Float, cursorY: Float, timestampNs: Long): ViewportResult {
        // Overview mode returns full texture with cursor at its relative position
        if (mode == Mode.OVERVIEW) {
            val cursorU = (cursorX / desktopWidth).coerceIn(0f, 1f)
            val cursorV = (cursorY / desktopHeight).coerceIn(0f, 1f)
            return ViewportResult(
                textureRect = TextureRect(0f, 0f, 1f, 1f),
                cursorScreenX = cursorU,
                cursorScreenY = cursorV
            )
        }

        // Use zoom level directly (no smoothing - zoom should be immediate, not lagged)
        // The One-Euro filter requires accurate timestamps which aren't always available
        val effectiveZoom = zoomLevel

        // Calculate viewport size based on zoom level
        // At zoom 0: viewport = keyhole size (minRatio)
        // At zoom 1: viewport = full desktop (1.0)
        val viewportRatioU = minRatioU + effectiveZoom * (1f - minRatioU)
        val viewportRatioV = minRatioV + effectiveZoom * (1f - minRatioV)

        // Half viewport size in UV space
        val halfU = viewportRatioU / 2f
        val halfV = viewportRatioV / 2f

        // Convert cursor from desktop coordinates to UV (0-1)
        val cursorU = (cursorX / desktopWidth).coerceIn(0f, 1f)
        // Invert V to compensate for SurfaceTexture transform matrix Y-flip
        // This ensures viewport pans in same direction as cursor
        val cursorV = 1f - (cursorY / desktopHeight).coerceIn(0f, 1f)

        // Determine viewport center based on cursor tracking setting
        val (viewportCenterU, viewportCenterV) = if (cursorTrackingEnabled) {
            // Cursor tracking ON: viewport follows cursor
            Pair(cursorU, cursorV)
        } else {
            // Cursor tracking OFF: viewport stays fixed at center
            Pair(0.5f, 0.5f)
        }

        // Center viewport (before clamping)
        var u0 = viewportCenterU - halfU
        var v0 = viewportCenterV - halfV

        // Clamp to texture bounds
        u0 = u0.coerceIn(0f, 1f - viewportRatioU)
        v0 = v0.coerceIn(0f, 1f - viewportRatioV)

        val u1 = (u0 + viewportRatioU).coerceAtMost(1f)
        val v1 = (v0 + viewportRatioV).coerceAtMost(1f)

        // Calculate cursor position ON SCREEN (soft-edge behavior)
        // cursorU/V is cursor in desktop UV space (0-1)
        // u0,v0,u1,v1 is viewport in desktop UV space
        // Cursor screen position = where cursor falls within viewport (0-1)
        val cursorScreenX = if (viewportRatioU > 0f) {
            ((cursorU - u0) / viewportRatioU).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val cursorScreenY = if (viewportRatioV > 0f) {
            ((cursorV - v0) / viewportRatioV).coerceIn(0f, 1f)
        } else {
            0.5f
        }

        return ViewportResult(
            textureRect = TextureRect(u0, v0, u1, v1),
            cursorScreenX = cursorScreenX,
            cursorScreenY = cursorScreenY
        )
    }
}
