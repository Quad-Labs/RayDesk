package com.raydesk.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence layer for saved Moonlight/Sunshine servers.
 *
 * Stores server credentials and connection info in SharedPreferences as JSON.
 * All operations are thread-safe using [SharedPreferences.Editor.apply].
 *
 * Usage:
 * ```kotlin
 * val repo = ServerRepository(context)
 *
 * // Save a server after pairing
 * repo.saveServer(SavedServer(
 *     uuid = "abc-123",
 *     name = "Gaming PC",
 *     address = "192.168.1.5",
 *     lastConnected = System.currentTimeMillis(),
 *     serverCert = "...",
 *     clientCert = "...",
 *     clientKey = "..."
 * ))
 *
 * // Get all saved servers
 * val servers = repo.getSavedServers()
 *
 * // Update last connected on successful connection
 * repo.updateLastConnected("abc-123")
 * ```
 */
class ServerRepository(context: Context) {

    companion object {
        private const val TAG = "ServerRepository"
        private const val PREFS_NAME = "raydesk_servers"
        private const val KEY_SERVER_KEYS = "server_keys"
        private const val KEY_PREFIX_SERVER = "server_"

        // JSON keys
        private const val JSON_UUID = "uuid"
        private const val JSON_NAME = "name"
        private const val JSON_ADDRESS = "address"
        private const val JSON_LAST_CONNECTED = "lastConnected"
        private const val JSON_SERVER_CERT = "serverCert"
        private const val JSON_CLIENT_CERT = "clientCert"
        private const val JSON_CLIENT_KEY = "clientKey"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves a server to persistent storage.
     *
     * If a server with the same UUID already exists, it will be overwritten.
     *
     * @param server The server to save
     */
    fun saveServer(server: SavedServer) {
        val json = serverToJson(server)
        val serverKey = KEY_PREFIX_SERVER + server.uuid

        // Get existing keys and add new one
        val keys = getServerKeys().toMutableSet()
        keys.add(server.uuid)

        prefs.edit()
            .putString(serverKey, json.toString())
            .putStringSet(KEY_SERVER_KEYS, keys)
            .apply()
    }

    /**
     * Retrieves all saved servers.
     *
     * Invalid or corrupt entries are skipped (logged as warnings).
     *
     * @return List of saved servers, sorted by lastConnected (most recent first)
     */
    fun getSavedServers(): List<SavedServer> {
        val keys = getServerKeys()
        val servers = mutableListOf<SavedServer>()

        for (uuid in keys) {
            val server = getServer(uuid)
            if (server != null) {
                servers.add(server)
            }
        }

        // Sort by last connected (most recent first)
        return servers.sortedByDescending { it.lastConnected }
    }

    /**
     * Retrieves a specific server by UUID.
     *
     * @param uuid The server's unique identifier
     * @return The server if found, null otherwise
     */
    fun getServer(uuid: String): SavedServer? {
        val serverKey = KEY_PREFIX_SERVER + uuid
        val jsonString = prefs.getString(serverKey, null) ?: return null

        return try {
            jsonToServer(JSONObject(jsonString))
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse server JSON for $uuid: ${e.message}")
            null
        }
    }

    /**
     * Deletes a server from persistent storage.
     *
     * @param uuid The UUID of the server to delete
     */
    fun deleteServer(uuid: String) {
        val serverKey = KEY_PREFIX_SERVER + uuid
        val keys = getServerKeys().toMutableSet()
        keys.remove(uuid)

        prefs.edit()
            .remove(serverKey)
            .putStringSet(KEY_SERVER_KEYS, keys)
            .apply()
    }

    /**
     * Updates the lastConnected timestamp for a server.
     *
     * Call this after a successful connection to update the server's position
     * in the recents list.
     *
     * @param uuid The UUID of the server to update
     */
    fun updateLastConnected(uuid: String) {
        val server = getServer(uuid) ?: return

        val updated = server.copy(lastConnected = System.currentTimeMillis())
        saveServer(updated)
    }

    // ==================== Private Helpers ====================

    private fun getServerKeys(): Set<String> {
        return prefs.getStringSet(KEY_SERVER_KEYS, emptySet()) ?: emptySet()
    }

    private fun serverToJson(server: SavedServer): JSONObject {
        return JSONObject().apply {
            put(JSON_UUID, server.uuid)
            put(JSON_NAME, server.name)
            put(JSON_ADDRESS, server.address)
            put(JSON_LAST_CONNECTED, server.lastConnected)
            put(JSON_SERVER_CERT, server.serverCert)
            put(JSON_CLIENT_CERT, server.clientCert)
            put(JSON_CLIENT_KEY, server.clientKey)
        }
    }

    @Throws(JSONException::class)
    private fun jsonToServer(json: JSONObject): SavedServer {
        return SavedServer(
            uuid = json.getString(JSON_UUID),
            name = json.getString(JSON_NAME),
            address = json.getString(JSON_ADDRESS),
            lastConnected = json.getLong(JSON_LAST_CONNECTED),
            serverCert = json.getString(JSON_SERVER_CERT),
            clientCert = json.getString(JSON_CLIENT_CERT),
            clientKey = json.getString(JSON_CLIENT_KEY)
        )
    }
}
