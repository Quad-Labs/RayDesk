package com.raydesk.data

/**
 * Represents a paired Moonlight/Sunshine server with saved credentials.
 *
 * When users pair with a Sunshine server, the certificates are stored so they
 * can reconnect without re-pairing. This data class holds all information
 * needed to establish an authenticated connection.
 *
 * @property uuid Unique ID from Moonlight ComputerDetails (server identifier)
 * @property name Human-readable server name (e.g., "DESKTOP-PC")
 * @property address IP address or hostname of the server
 * @property lastConnected Timestamp in milliseconds of last successful connection
 * @property serverCert Base64-encoded X509 server certificate
 * @property clientCert Base64-encoded client certificate for this device
 * @property clientKey Base64-encoded private key for this device
 */
data class SavedServer(
    val uuid: String,
    val name: String,
    val address: String,
    val lastConnected: Long,
    val serverCert: String,
    val clientCert: String,
    val clientKey: String
)
