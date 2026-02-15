package com.raydesk.data

import android.content.Context

/**
 * Persistent streaming settings.
 *
 * Saves user preferences for display mode, input settings, and zoom
 * using SharedPreferences. Loaded on StreamingActivity start.
 */
data class StreamingSettings(
    val displayMode: String = "FLOATING",
    val inputMode: String = "TRACKPAD",
    val trackpadSpeed: String = "MEDIUM",
    val zoomSpeed: String = "MEDIUM",
    val zoomLevel: Float = 1.0f,           // 1.0 = 100%, range 0.5 to 3.0
    val isZoomLocked: Boolean = false,
    val isCursorTrackingEnabled: Boolean = true,
    val isEnvironmentEnabled: Boolean = false,
    val environmentThemeId: String = "blue_transparent",
    val monitorNumber: Int = 1,
    val headTrackingSpeed: String = "MEDIUM"
) {
    companion object {
        private const val PREFS_NAME = "streaming_settings"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_TRACKPAD_SPEED = "trackpad_speed"
        private const val KEY_ZOOM_SPEED = "zoom_speed"
        private const val KEY_ZOOM_LEVEL = "zoom_level"
        private const val KEY_ZOOM_LOCKED = "zoom_locked"
        private const val KEY_HEAD_TRACKING = "head_tracking"
        private const val KEY_ENVIRONMENT_ENABLED = "environment_enabled"
        private const val KEY_ENVIRONMENT_THEME = "environment_theme"
        private const val KEY_MONITOR_NUMBER = "monitor_number"
        private const val KEY_HEAD_TRACKING_SPEED = "head_tracking_speed"

        fun default() = StreamingSettings()

        fun load(context: Context): StreamingSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return StreamingSettings(
                displayMode = prefs.getString(KEY_DISPLAY_MODE, "FLOATING") ?: "FLOATING",
                inputMode = prefs.getString(KEY_INPUT_MODE, "TRACKPAD") ?: "TRACKPAD",
                trackpadSpeed = prefs.getString(KEY_TRACKPAD_SPEED, "MEDIUM") ?: "MEDIUM",
                zoomSpeed = prefs.getString(KEY_ZOOM_SPEED, "MEDIUM") ?: "MEDIUM",
                zoomLevel = prefs.getFloat(KEY_ZOOM_LEVEL, 1.0f),
                isZoomLocked = prefs.getBoolean(KEY_ZOOM_LOCKED, false),
                isCursorTrackingEnabled = prefs.getBoolean(KEY_HEAD_TRACKING, true),  // Keep same key for backward compat
                isEnvironmentEnabled = prefs.getBoolean(KEY_ENVIRONMENT_ENABLED, false),
                environmentThemeId = prefs.getString(KEY_ENVIRONMENT_THEME, "blue_transparent") ?: "blue_transparent",
                monitorNumber = prefs.getInt(KEY_MONITOR_NUMBER, 1),
                headTrackingSpeed = prefs.getString(KEY_HEAD_TRACKING_SPEED, "MEDIUM") ?: "MEDIUM"
            )
        }
    }

    fun save(context: Context) {
        android.util.Log.i("StreamingSettings", "[SAVE] Saving to SharedPreferences: cursorTracking=$isCursorTrackingEnabled, inputMode=$inputMode")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DISPLAY_MODE, displayMode)
            .putString(KEY_INPUT_MODE, inputMode)
            .putString(KEY_TRACKPAD_SPEED, trackpadSpeed)
            .putString(KEY_ZOOM_SPEED, zoomSpeed)
            .putFloat(KEY_ZOOM_LEVEL, zoomLevel)
            .putBoolean(KEY_ZOOM_LOCKED, isZoomLocked)
            .putBoolean(KEY_HEAD_TRACKING, isCursorTrackingEnabled)  // Keep same key for backward compat
            .putBoolean(KEY_ENVIRONMENT_ENABLED, isEnvironmentEnabled)
            .putString(KEY_ENVIRONMENT_THEME, environmentThemeId)
            .putInt(KEY_MONITOR_NUMBER, monitorNumber)
            .putString(KEY_HEAD_TRACKING_SPEED, headTrackingSpeed)
            .apply()  // Async write - avoids blocking UI thread during menu interactions
        android.util.Log.i("StreamingSettings", "[SAVE] Save completed")
    }

    /**
     * Create a copy with updated display mode and save.
     */
    fun withDisplayMode(context: Context, mode: String): StreamingSettings {
        return copy(displayMode = mode).also { it.save(context) }
    }

    /**
     * Create a copy with updated input mode and save.
     */
    fun withInputMode(context: Context, mode: String): StreamingSettings {
        return copy(inputMode = mode).also { it.save(context) }
    }

    /**
     * Create a copy with updated trackpad speed and save.
     */
    fun withTrackpadSpeed(context: Context, speed: String): StreamingSettings {
        return copy(trackpadSpeed = speed).also { it.save(context) }
    }

    /**
     * Create a copy with updated zoom speed and save.
     */
    fun withZoomSpeed(context: Context, speed: String): StreamingSettings {
        return copy(zoomSpeed = speed).also { it.save(context) }
    }

    /**
     * Create a copy with updated zoom level and save.
     */
    fun withZoomLevel(context: Context, level: Float): StreamingSettings {
        return copy(zoomLevel = level.coerceIn(0.5f, 3.0f)).also { it.save(context) }
    }

    /**
     * Create a copy with updated zoom lock and save.
     */
    fun withZoomLocked(context: Context, locked: Boolean): StreamingSettings {
        return copy(isZoomLocked = locked).also { it.save(context) }
    }

    /**
     * Create a copy with updated cursor tracking and save.
     */
    fun withCursorTracking(context: Context, enabled: Boolean): StreamingSettings {
        return copy(isCursorTrackingEnabled = enabled).also { it.save(context) }
    }

    /**
     * Create a copy with updated environment enabled state and save.
     */
    fun withEnvironmentEnabled(context: Context, enabled: Boolean): StreamingSettings {
        return copy(isEnvironmentEnabled = enabled).also { it.save(context) }
    }

    /**
     * Create a copy with updated environment theme and save.
     */
    fun withEnvironmentTheme(context: Context, themeId: String): StreamingSettings {
        return copy(environmentThemeId = themeId).also { it.save(context) }
    }

    fun withMonitorNumber(context: Context, number: Int): StreamingSettings {
        return copy(monitorNumber = number.coerceIn(1, 12)).also { it.save(context) }
    }

    /**
     * Create a copy with updated head tracking speed and save.
     */
    fun withHeadTrackingSpeed(context: Context, speed: String): StreamingSettings {
        return copy(headTrackingSpeed = speed).also { it.save(context) }
    }
}
