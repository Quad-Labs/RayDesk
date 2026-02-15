package com.raydesk.data

import android.content.Context
import kotlin.math.abs

/**
 * Cursor sensitivity settings with adaptive zones.
 *
 * Zones:
 * - Precision (0 to precisionZone): 0.5x sensitivity for fine targeting
 * - Normal (precisionZone to normalZone): 1.0x sensitivity
 * - Accelerated (normalZone to comfortRange): Smooth ramp to maxMultiplier
 *
 * @param baseSensitivity Base pixels per degree (1.0 = default)
 * @param precisionZone Degrees for precision zone (default 5°)
 * @param comfortRange Max comfortable head turn in degrees (default 25°)
 * @param maxMultiplier Maximum sensitivity multiplier in accelerated zone
 */
data class CursorSettings(
    val baseSensitivity: Float = 1.0f,
    val precisionZone: Float = 5f,
    val comfortRange: Float = 25f,
    val maxMultiplier: Float = 3f
) {
    // Normal zone ends at 60% of comfort range
    private val normalZone: Float get() = comfortRange * 0.6f

    companion object {
        private const val PREFS_NAME = "cursor_settings"
        private const val KEY_BASE_SENSITIVITY = "base_sensitivity"
        private const val KEY_PRECISION_ZONE = "precision_zone"
        private const val KEY_COMFORT_RANGE = "comfort_range"
        private const val KEY_MAX_MULTIPLIER = "max_multiplier"

        fun default() = CursorSettings()

        fun load(context: Context): CursorSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return CursorSettings(
                baseSensitivity = prefs.getFloat(KEY_BASE_SENSITIVITY, 1.0f),
                precisionZone = prefs.getFloat(KEY_PRECISION_ZONE, 5f),
                comfortRange = prefs.getFloat(KEY_COMFORT_RANGE, 25f),
                maxMultiplier = prefs.getFloat(KEY_MAX_MULTIPLIER, 3f)
            )
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_BASE_SENSITIVITY, baseSensitivity)
            .putFloat(KEY_PRECISION_ZONE, precisionZone)
            .putFloat(KEY_COMFORT_RANGE, comfortRange)
            .putFloat(KEY_MAX_MULTIPLIER, maxMultiplier)
            .apply()
    }

    /**
     * Calculate sensitivity multiplier for a given head angle.
     *
     * @param angleDegrees Absolute angle from center in degrees
     * @return Sensitivity multiplier (0.5 to maxMultiplier)
     */
    fun calculateSensitivity(angleDegrees: Float): Float {
        val absAngle = abs(angleDegrees)

        return when {
            absAngle < precisionZone -> {
                // Precision zone: 0.5x
                0.5f * baseSensitivity
            }
            absAngle < normalZone -> {
                // Normal zone: smooth transition from 0.5x to 1.0x
                val t = (absAngle - precisionZone) / (normalZone - precisionZone)
                (0.5f + 0.5f * smoothstep(t)) * baseSensitivity
            }
            else -> {
                // Accelerated zone: smooth ramp from 1.0x to maxMultiplier
                val t = ((absAngle - normalZone) / (comfortRange - normalZone)).coerceIn(0f, 1f)
                (1f + (maxMultiplier - 1f) * smoothstep(t)) * baseSensitivity
            }
        }
    }

    /**
     * Calculate cursor offset for a given head angle.
     * Integrates sensitivity over the angle range for smooth movement.
     *
     * @param angleDegrees Signed angle from center in degrees
     * @return Cursor offset in normalized units (multiply by base pixels/degree)
     */
    fun calculateCursorOffset(angleDegrees: Float): Float {
        val absAngle = abs(angleDegrees)
        val sign = if (angleDegrees >= 0) 1f else -1f

        // Piecewise integration of sensitivity curve
        val offset = when {
            absAngle <= precisionZone -> {
                // All in precision zone
                absAngle * 0.5f * baseSensitivity
            }
            absAngle <= normalZone -> {
                // Precision + normal zone
                val precisionPart = precisionZone * 0.5f * baseSensitivity
                val normalPart = (absAngle - precisionZone) * 0.75f * baseSensitivity // Average of 0.5-1.0
                precisionPart + normalPart
            }
            else -> {
                // All three zones
                val precisionPart = precisionZone * 0.5f * baseSensitivity
                val normalPart = (normalZone - precisionZone) * 0.75f * baseSensitivity

                // Accelerated zone with increasing sensitivity
                val accelAngle = absAngle - normalZone
                val avgMultiplier = 1f + (maxMultiplier - 1f) * 0.5f // Approximate average
                val accelPart = accelAngle * avgMultiplier * baseSensitivity
                precisionPart + normalPart + accelPart
            }
        }

        return offset * sign
    }

    /**
     * Smoothstep interpolation for smooth zone transitions.
     */
    private fun smoothstep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
}
