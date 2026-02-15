package com.raydesk.streaming

import android.util.Log

/**
 * Smart hybrid reconnection manager for RayDesk streaming.
 *
 * Reconnection Logic:
 * - Auto-reconnect up to 3 times with exponential backoff (500ms, 1000ms, 2000ms)
 * - Return to ConnectionActivity if max retries exceeded
 * - Return to ConnectionActivity if overall timeout (30s) exceeded
 *
 * Connection States:
 * - Connected: Green (#0FC843) - streaming normally
 * - Reconnecting: Yellow (#F1C426) - auto-retry active
 * - Disconnected: Red (#F02A23) - return to ConnectionActivity
 *
 * Usage:
 * ```kotlin
 * val manager = ReconnectionManager(
 *     onAutoReconnect = { startReconnection() },
 *     onNavigateToConnection = { navigateToConnectionActivity() },
 *     onReconnectAttempt = { attempt, max -> showOverlay("Reconnecting $attempt/$max") }
 * )
 *
 * // When stream disconnects
 * manager.onDisconnect()
 *
 * // When reconnection succeeds
 * manager.onReconnectSuccess()
 *
 * // When reconnection fails
 * manager.onReconnectFailed()
 * ```
 */
class ReconnectionManager(
    private val onAutoReconnect: () -> Unit,
    private val onNavigateToConnection: () -> Unit,
    private val onReconnectAttempt: (attempt: Int, maxAttempts: Int) -> Unit
) {
    companion object {
        private const val TAG = "ReconnectionManager"

        /** Maximum number of auto-retry attempts */
        const val MAX_AUTO_RETRIES = 3

        /** Overall timeout for all reconnection attempts (30 seconds) */
        const val RECONNECT_TIMEOUT_MS = 30_000L

        /** Exponential backoff delays for each retry (500ms, 1000ms, 2000ms) */
        val RETRY_DELAYS_MS = listOf(500L, 1000L, 2000L)
    }

    private var isReconnectingState = false
    private var retryCount = 0
    private var firstDisconnectTime: Long = 0L

    /**
     * Called when a disconnect is detected.
     *
     * Decision logic:
     * 1. Check if overall timeout exceeded (30s since first disconnect) → navigate to connection
     * 2. Check if max retries exceeded → navigate to connection
     * 3. Otherwise → auto-reconnect with backoff
     */
    fun onDisconnect() {
        val now = System.currentTimeMillis()

        // Record first disconnect time for overall timeout tracking
        if (firstDisconnectTime == 0L) {
            firstDisconnectTime = now
            Log.i(TAG, "First disconnect recorded at $now")
        }

        // Check overall timeout
        val totalElapsed = now - firstDisconnectTime
        if (totalElapsed >= RECONNECT_TIMEOUT_MS) {
            Log.w(TAG, "Reconnection timeout exceeded (${totalElapsed}ms) - navigating to connection")
            resetState()
            onNavigateToConnection()
            return
        }

        // Check max retries
        if (retryCount >= MAX_AUTO_RETRIES) {
            Log.w(TAG, "Max retries exceeded ($retryCount/$MAX_AUTO_RETRIES) - navigating to connection")
            resetState()
            onNavigateToConnection()
            return
        }

        // Auto-reconnect with backoff
        retryCount++
        isReconnectingState = true
        Log.i(TAG, "Auto-reconnect attempt $retryCount/$MAX_AUTO_RETRIES (elapsed: ${totalElapsed}ms)")
        onReconnectAttempt(retryCount, MAX_AUTO_RETRIES)
        onAutoReconnect()
    }

    /**
     * Cancels any ongoing reconnection attempt.
     */
    fun cancelReconnection() {
        isReconnectingState = false
    }

    /**
     * Returns whether a reconnection is currently in progress.
     */
    fun isReconnecting(): Boolean = isReconnectingState

    /**
     * Returns the current retry attempt count.
     */
    fun getRetryCount(): Int = retryCount

    /**
     * Called when a reconnection attempt fails.
     * Does not increment retry count - that happens in onDisconnect.
     */
    fun onReconnectFailed() {
        isReconnectingState = false
    }

    /**
     * Called when reconnection succeeds.
     * Resets the retry counter for future disconnects.
     */
    fun onReconnectSuccess() {
        resetState()
    }

    /**
     * Returns the delay for the current retry attempt.
     *
     * @return Delay in milliseconds, or 0 if not reconnecting
     */
    fun getCurrentRetryDelay(): Long {
        if (!isReconnectingState || retryCount == 0) {
            return 0L
        }
        val index = (retryCount - 1).coerceIn(0, RETRY_DELAYS_MS.size - 1)
        return RETRY_DELAYS_MS[index]
    }

    private fun resetState() {
        isReconnectingState = false
        retryCount = 0
        firstDisconnectTime = 0L
    }
}
