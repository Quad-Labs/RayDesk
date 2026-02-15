package com.raydesk.ui

/**
 * View model for individual server list item rendering.
 *
 * Encapsulates the display logic for ServerListItem, including:
 * - Focus indicator (filled/empty circle)
 * - Formatted display text
 * - Color state (focused vs unfocused)
 *
 * Used by ServerListView to generate display text for each item.
 */
data class ServerItemViewModel(
    val displayText: String,
    val focusIndicator: FocusIndicator,
    val onlineIndicator: String,
    val isFocused: Boolean
) {
    companion object {
        /**
         * Create a view model from a ServerListItem.
         *
         * @param item The server list item to display
         * @param isFocused Whether this item is currently focused
         * @return A view model with formatted display text and state
         */
        fun from(item: ServerListItem, isFocused: Boolean): ServerItemViewModel {
            val focusIndicator = if (isFocused) FocusIndicator.FOCUSED else FocusIndicator.UNFOCUSED
            val onlineIndicator = if (item.isOnline) "\u2022" else "\u25CB" // Filled dot or empty circle
            val onlineText = if (item.isOnline) "Online" else "Offline"

            val displayText = "${focusIndicator.symbol}  ${item.name}    $onlineIndicator $onlineText"

            return ServerItemViewModel(
                displayText = displayText,
                focusIndicator = focusIndicator,
                onlineIndicator = onlineIndicator,
                isFocused = isFocused
            )
        }
    }
}
