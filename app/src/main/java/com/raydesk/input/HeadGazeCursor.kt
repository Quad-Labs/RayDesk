package com.raydesk.input

import com.raydesk.data.CursorSettings
import com.raydesk.spatial.OneEuroFilter
import com.raydesk.spatial.Quaternion
import kotlin.math.abs

/**
 * Cursor position without Android dependencies.
 */
data class CursorPosition(val x: Float, val y: Float)

/**
 * HeadGazeCursor maps head rotation to cursor position.
 *
 * - Yaw (look left/right) → Cursor X
 * - Pitch (look up/down) → Cursor Y
 * - Includes deadzone to prevent micro-jitter when still
 * - Uses OneEuroFilter for smoothing
 *
 * Reference: openspec/changes/raydesk-mvp/specs/head-tracking.md
 *
 * @param screenWidth Display width in pixels
 * @param screenHeight Display height in pixels
 * @param sensitivity Pixels per degree of head rotation
 * @param deadzone Minimum rotation (degrees) before cursor moves
 */
class HeadGazeCursor(
    private var screenWidth: Int = 1920,
    private var screenHeight: Int = 1080,
    var sensitivity: Float = 15f,
    private val deadzone: Float = 0.2f,
    private val settings: CursorSettings = CursorSettings.default()
) {
    // Input angle filters (used for deadzone calculation)
    private val yawFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)
    private val pitchFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)

    // Output coordinate filters (applied to final X/Y position)
    // This is more effective than filtering input angles because it smooths
    // the actual cursor movement rather than the angular input
    private val xFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)
    private val yFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f)

    private var centerYaw = 0f
    private var centerPitch = 0f

    private var lastStableYaw = 0f
    private var lastStablePitch = 0f

    private var filteredYaw = 0f
    private var filteredPitch = 0f

    // Store latest timestamp for output filtering
    private var lastTimestampNs: Long = 0L

    // Quaternion tracking fields for gimbal-lock-free rotation
    private var referenceQuat: Quaternion = Quaternion.identity()
    private var currentQuat: Quaternion = Quaternion.identity()
    private var hasReference: Boolean = false

    /**
     * Update head orientation from sensor data.
     *
     * @param yaw Horizontal rotation in degrees (positive = right)
     * @param pitch Vertical rotation in degrees (positive = down)
     * @param timestamp Timestamp in nanoseconds
     */
    fun updateHeadOrientation(yaw: Float, pitch: Float, timestamp: Long) {
        // Apply One-Euro filter for smoothing
        filteredYaw = yawFilter.filter(yaw, timestamp)
        filteredPitch = pitchFilter.filter(pitch, timestamp)

        // Apply deadzone to prevent micro-jitter
        lastStableYaw = applyDeadzone(filteredYaw - centerYaw, lastStableYaw)
        lastStablePitch = applyDeadzone(filteredPitch - centerPitch, lastStablePitch)
    }

    /**
     * Update head orientation from quaternion sensor values.
     *
     * This method avoids gimbal lock by tracking relative rotation from a reference
     * quaternion. The first call sets the reference; subsequent calls compute
     * the relative rotation.
     *
     * @param sensorValues Quaternion sensor values [x, y, z] or [x, y, z, w]
     * @param timestamp Timestamp in nanoseconds
     */
    fun updateFromQuaternion(sensorValues: FloatArray, timestamp: Long) {
        currentQuat = Quaternion.fromSensorValues(sensorValues)
        lastTimestampNs = timestamp  // Store for output filtering

        if (!hasReference) {
            referenceQuat = currentQuat
            hasReference = true
            return
        }

        val relative = referenceQuat.relativeTo(currentQuat)
        val (yaw, pitch) = relative.toYawPitch()

        filteredYaw = yawFilter.filter(yaw, timestamp)
        filteredPitch = pitchFilter.filter(pitch, timestamp)

        lastStableYaw = applyDeadzone(filteredYaw, lastStableYaw)
        lastStablePitch = applyDeadzone(filteredPitch, lastStablePitch)
    }

    /**
     * Get current cursor position.
     *
     * Output coordinates are filtered for smoother cursor movement.
     * Per Gemini's recommendation: "Keep your One-Euro filter, but apply it to
     * the Output Coordinates (X, Y) rather than the input Euler angles."
     *
     * @return Cursor position in screen coordinates
     */
    fun getCursorPosition(): CursorPosition {
        // Use adaptive sensitivity from settings
        // Negate X so look-right moves cursor right (matches natural expectation)
        val offsetX = -settings.calculateCursorOffset(lastStableYaw) * sensitivity
        val offsetY = -settings.calculateCursorOffset(lastStablePitch) * sensitivity  // Invert Y (look up = cursor up)

        // Calculate raw position
        var rawX = (screenWidth / 2f) + offsetX
        var rawY = (screenHeight / 2f) + offsetY

        // Clamp to screen bounds before filtering
        rawX = rawX.coerceIn(0f, screenWidth.toFloat())
        rawY = rawY.coerceIn(0f, screenHeight.toFloat())

        // Apply output coordinate filtering for smoother cursor movement
        // Only filter if we have a valid timestamp (avoids issues on first call)
        val x: Float
        val y: Float
        if (lastTimestampNs > 0) {
            x = xFilter.filter(rawX, lastTimestampNs)
            y = yFilter.filter(rawY, lastTimestampNs)
        } else {
            x = rawX
            y = rawY
        }

        return CursorPosition(x, y)
    }

    /**
     * Recenter the cursor to the current head position.
     * Call this when user wants to reset the "forward" direction.
     */
    fun recenter() {
        centerYaw = filteredYaw
        centerPitch = filteredPitch
        lastStableYaw = 0f
        lastStablePitch = 0f

        // Reset quaternion reference to current orientation
        referenceQuat = currentQuat
        // Reset all filters for clean slate
        yawFilter.reset()
        pitchFilter.reset()
        xFilter.reset()
        yFilter.reset()
    }

    /**
     * Apply deadzone to prevent micro-jitter.
     * If the change is within the deadzone, keep the previous stable value.
     */
    private fun applyDeadzone(delta: Float, previousStable: Float): Float {
        return if (abs(delta - previousStable) < deadzone && deadzone > 0f) {
            previousStable
        } else {
            delta
        }
    }

    /**
     * Reset the cursor state.
     */
    fun reset() {
        // Reset all filters
        yawFilter.reset()
        pitchFilter.reset()
        xFilter.reset()
        yFilter.reset()

        centerYaw = 0f
        centerPitch = 0f
        lastStableYaw = 0f
        lastStablePitch = 0f
        filteredYaw = 0f
        filteredPitch = 0f
        lastTimestampNs = 0L

        // Reset quaternion state
        referenceQuat = Quaternion.identity()
        currentQuat = Quaternion.identity()
        hasReference = false
    }

    /**
     * Update screen dimensions dynamically.
     *
     * This is used when the actual stream resolution differs from the requested resolution.
     * For example, if we request 3840x1080 (dual monitors) but the host falls back to
     * 1920x1080, we need to update our cursor bounds.
     *
     * @param newWidth New screen width in pixels
     * @param newHeight New screen height in pixels
     */
    fun updateScreenDimensions(newWidth: Int, newHeight: Int) {
        screenWidth = newWidth
        screenHeight = newHeight
    }
}
