package com.raydesk.spatial

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro Filter - Adaptive low-pass filter for smoothing noisy signals.
 *
 * Reduces jitter when stationary but allows fast movements to pass through.
 * Used for head tracking to eliminate micro-jitter while maintaining responsiveness.
 *
 * Reference: openspec/changes/raydesk-mvp/specs/head-tracking.md
 *
 * @param minCutoff Minimum cutoff frequency (Hz). Lower = smoother but laggier.
 * @param beta Speed coefficient. Higher = more responsive to fast movement.
 * @param dCutoff Derivative cutoff frequency (Hz).
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.5f,
    private val dCutoff: Float = 1.0f
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long = 0

    /**
     * Filter a new value.
     *
     * @param value The raw input value
     * @param timestamp Timestamp in nanoseconds
     * @return Filtered value
     */
    fun filter(value: Float, timestamp: Long): Float {
        // First value passes through unchanged
        if (xPrev == null) {
            xPrev = value
            tPrev = timestamp
            return value
        }

        // Calculate time delta in seconds
        val dt = (timestamp - tPrev) / 1_000_000_000f
        tPrev = timestamp

        // Avoid division by zero
        if (dt <= 0f) {
            return xPrev!!
        }

        // Derivative estimation (rate of change)
        val dx = (value - xPrev!!) / dt
        val edx = lowPass(dx, dxPrev, alpha(dCutoff, dt))
        dxPrev = edx

        // Adaptive cutoff based on speed
        // When moving fast (high derivative), increase cutoff to be more responsive
        val cutoff = minCutoff + beta * abs(edx)

        // Apply low-pass filter with adaptive cutoff
        val filtered = lowPass(value, xPrev!!, alpha(cutoff, dt))
        xPrev = filtered

        return filtered
    }

    /**
     * Calculate smoothing factor (alpha) from cutoff frequency and time delta.
     */
    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    /**
     * Simple exponential low-pass filter.
     */
    private fun lowPass(x: Float, xPrev: Float, alpha: Float): Float {
        return alpha * x + (1f - alpha) * xPrev
    }

    /**
     * Reset the filter state.
     */
    fun reset() {
        xPrev = null
        dxPrev = 0f
        tPrev = 0
    }
}
