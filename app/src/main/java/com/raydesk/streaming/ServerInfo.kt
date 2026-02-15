package com.raydesk.streaming

/**
 * Information about a discovered streaming server.
 */
data class ServerInfo(
    val name: String,
    val address: String,
    val uuid: String,
    val isPaired: Boolean
)
