package com.raydesk.data

/**
 * Interface for providing HUD data to the status ring.
 *
 * Implemented by StreamingActivity to supply real-time stats.
 */
interface StatusRingDataProvider {
    /**
     * Current rendering frame rate.
     */
    fun getCurrentFps(): Int

    /**
     * Connection quality indicator (0-4 bars).
     * 0 = no connection, 4 = excellent
     */
    fun getConnectionQuality(): Int

    /**
     * Current control hint text.
     * Examples: "Swipe: Zoom", "Double-tap: Menu"
     */
    fun getCurrentHint(): String
}
