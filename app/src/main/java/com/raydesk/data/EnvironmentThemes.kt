package com.raydesk.data

/**
 * Predefined environment themes.
 *
 * Three themes available:
 * - DEFAULT: Blue glow, black skybox (minimal)
 * - STARRY_NIGHT: Cyan glow with starfield dome
 * - SUNSET: Purple glow with gradient horizon
 */
object EnvironmentThemes {

    val DEFAULT = EnvironmentTheme(
        id = "default",
        displayName = "Default",
        bezelStyle = BezelStyle.GLOW,
        bezelColor = 0xFF4D80FF.toInt(),      // Blue glow (0.3, 0.5, 1.0)
        domeStyle = DomeStyle.NONE,            // Black/no dome
        domeColors = DomeColorConfig(
            horizonColor = 0xFF000000.toInt(),  // Black
            zenithColor = 0xFF000000.toInt()    // Black
        ),
        ringColor = 0xFF4D80FF.toInt(),        // Blue glow
        ringAccentColor = 0xFF3366CC.toInt(),
        textColor = 0xFFFFFFFF.toInt()         // White
    )

    val STARRY_NIGHT = EnvironmentTheme(
        id = "starry_night",
        displayName = "Starry Night",
        bezelStyle = BezelStyle.GLOW,
        bezelColor = 0xFF4DCCFF.toInt(),       // Cyan glow (0.3, 0.8, 1.0)
        domeStyle = DomeStyle.STARFIELD,
        domeColors = DomeColorConfig(
            horizonColor = 0xFF1A1A40.toInt(),  // Visible purple horizon
            zenithColor = 0xFF0F0F30.toInt(),   // Deep purple night sky
            starColor = 0xFFE0E8FF.toInt()      // White/blue stars
        ),
        ringColor = 0xFF4DCCFF.toInt(),        // Cyan glow
        ringAccentColor = 0xFF33AADD.toInt(),
        textColor = 0xFFFFFFFF.toInt()         // White
    )

    val SUNSET = EnvironmentTheme(
        id = "sunset",
        displayName = "Sunset",
        bezelStyle = BezelStyle.GLOW,
        bezelColor = 0xFFB34DFF.toInt(),       // Purple glow (0.7, 0.3, 1.0)
        domeStyle = DomeStyle.SOLID_GRADIENT,
        domeColors = DomeColorConfig(
            horizonColor = 0xFFF97316.toInt(),  // Orange horizon
            zenithColor = 0xFF6B21A8.toInt()    // Purple zenith
        ),
        ringColor = 0xFFB34DFF.toInt(),        // Purple glow
        ringAccentColor = 0xFF9933DD.toInt(),
        textColor = 0xFFFFFFFF.toInt()         // White
    )

    // Legacy alias for backwards compatibility
    val BLUE_TRANSPARENT = DEFAULT

    /** All available themes */
    val ALL = listOf(DEFAULT, STARRY_NIGHT, SUNSET)

    /** Get theme by ID, falling back to default */
    fun getById(id: String): EnvironmentTheme {
        return ALL.find { it.id == id } ?: DEFAULT
    }
}
