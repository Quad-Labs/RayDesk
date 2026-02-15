package com.raydesk.streaming

/**
 * Represents the current state of the streaming connection.
 */
sealed class StreamState {
    object Disconnected : StreamState()
    object Connecting : StreamState()
    object Pairing : StreamState()
    data class Streaming(val width: Int, val height: Int) : StreamState()
    data class Error(val message: String) : StreamState()
}
