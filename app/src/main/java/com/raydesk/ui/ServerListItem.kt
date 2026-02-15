package com.raydesk.ui

import com.raydesk.data.SavedServer
import com.raydesk.streaming.DiscoveredServer

/**
 * Data class representing a server list item for display in ConnectionActivity.
 *
 * This is a unified representation for UI purposes, abstracting over:
 * - SavedServer (paired servers with credentials)
 * - DiscoveredServer (found via mDNS/probe)
 * - Manual entry option
 */
data class ServerListItem(
    val uuid: String,
    val name: String,
    val address: String,
    val isOnline: Boolean,
    val isPaired: Boolean,
    val type: Type
) {
    enum class Type {
        SAVED,
        DISCOVERED,
        MANUAL_ENTRY
    }

    companion object {
        /**
         * Create a list item from a saved server.
         *
         * @param server The saved server data
         * @param isOnline Whether the server is currently reachable
         */
        fun fromSaved(server: SavedServer, isOnline: Boolean): ServerListItem {
            return ServerListItem(
                uuid = server.uuid,
                name = server.name,
                address = server.address,
                isOnline = isOnline,
                isPaired = true,
                type = Type.SAVED
            )
        }

        /**
         * Create a list item from a discovered server.
         */
        fun fromDiscovered(server: DiscoveredServer): ServerListItem {
            return ServerListItem(
                uuid = server.uuid,
                name = server.name,
                address = server.address,
                isOnline = server.isOnline,
                isPaired = server.isPaired,
                type = Type.DISCOVERED
            )
        }

        /**
         * Create the manual IP entry option item.
         */
        fun manualEntry(): ServerListItem {
            return ServerListItem(
                uuid = "manual",
                name = "Enter IP manually",
                address = "",
                isOnline = false,
                isPaired = false,
                type = Type.MANUAL_ENTRY
            )
        }
    }
}

/**
 * Focus indicator symbol for list items.
 *
 * Design spec: focused items show filled circle, unfocused show empty circle.
 */
enum class FocusIndicator(val symbol: String) {
    FOCUSED("\u25C9"),    // Filled circle
    UNFOCUSED("\u25CB");  // Empty circle

    companion object {
        fun forIndex(index: Int, focusedIndex: Int): FocusIndicator {
            return if (index == focusedIndex) FOCUSED else UNFOCUSED
        }
    }
}
