package com.raydesk.data

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Bezel rendering styles.
 */
enum class BezelStyle {
    GLOW,           // Shader-based edge glow (default)
    FRAME,          // Future - additional geometry for physical frame look
    CORNERS_ONLY,   // Future - just corner markers
    NONE            // No bezel
}

/**
 * Dome rendering styles.
 */
enum class DomeStyle {
    SOLID_GRADIENT,  // Default for Blue/Sunset themes
    STARFIELD,       // Default for Starry Night theme
    NONE             // Disable dome
}

/**
 * Dome color configuration.
 */
data class DomeColorConfig(
    @ColorInt val horizonColor: Int,
    @ColorInt val zenithColor: Int,
    @ColorInt val starColor: Int? = null  // Only for STARFIELD style
)

/**
 * Complete environment theme definition.
 *
 * Colors stored as Android Color Int, converted to float arrays for shaders.
 */
data class EnvironmentTheme(
    val id: String,
    val displayName: String,
    val bezelStyle: BezelStyle,
    @ColorInt val bezelColor: Int,
    val domeStyle: DomeStyle,
    val domeColors: DomeColorConfig,
    @ColorInt val ringColor: Int,
    @ColorInt val ringAccentColor: Int,
    @ColorInt val textColor: Int
) {
    /** Convert bezel color to shader-ready float array [r, g, b, a] */
    fun bezelColorFloat(): FloatArray = colorToFloatArray(bezelColor)

    /** Convert ring color to shader-ready float array */
    fun ringColorFloat(): FloatArray = colorToFloatArray(ringColor)

    /** Convert dome horizon color to shader-ready float array */
    fun horizonColorFloat(): FloatArray = colorToFloatArray(domeColors.horizonColor)

    /** Convert dome zenith color to shader-ready float array */
    fun zenithColorFloat(): FloatArray = colorToFloatArray(domeColors.zenithColor)

    companion object {
        fun colorToFloatArray(@ColorInt color: Int): FloatArray {
            return floatArrayOf(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
                Color.alpha(color) / 255f
            )
        }
    }
}
