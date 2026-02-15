package com.raydesk.streaming

/**
 * Represents a streaming server discovered via mDNS or manual probe.
 *
 * This data class contains all information needed to display servers in the UI
 * and initiate connections. The [isPaired] field indicates whether we have
 * saved credentials for this server (checked against [ServerRepository]).
 *
 * @property uuid Unique identifier from Moonlight ComputerDetails
 * @property name Human-readable server name (e.g., "Gaming-PC")
 * @property address IP address or hostname of the server
 * @property port HTTP port (typically 47989 for Moonlight/Sunshine)
 * @property isOnline Whether the server responded to recent probe
 * @property isPaired Whether we have saved credentials for this server
 */
data class DiscoveredServer(
    val uuid: String,
    val name: String,
    val address: String,
    val port: Int,
    val isOnline: Boolean,
    val isPaired: Boolean
)
