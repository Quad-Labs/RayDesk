package com.raydesk.ui

/**
 * Manages focus navigation across UI items.
 *
 * Items are organized in order:
 * 1. Resolution preset selector (index 0)
 * 2. Search servers button (index 1)
 * 3. Saved servers (2..savedCount+1)
 * 4. Discovered servers (savedCount+2..savedCount+discoveredCount+1)
 * 5. Manual entry option (if enabled, at the end)
 *
 * Standard Edition: No Bluetooth pairing option.
 *
 * Usage:
 * ```kotlin
 * val navigator = FocusNavigator(savedCount = 2, discoveredCount = 1, hasManualEntry = true)
 *
 * navigator.moveNext()     // Swipe forward
 * navigator.movePrevious() // Swipe backward
 *
 * when (navigator.getCurrentItemType()) {
 *     ItemType.RESOLUTION_PRESET -> cycleResolutionPreset()
 *     ItemType.SEARCH_SERVERS -> startServerSearch()
 *     ItemType.SAVED -> handleSavedServer(navigator.getSavedServerIndex()!!)
 *     ItemType.DISCOVERED -> handleDiscoveredServer(navigator.getDiscoveredServerIndex()!!)
 *     ItemType.MANUAL_ENTRY -> showManualIPEntry()
 * }
 * ```
 */
class FocusNavigator(
    private var savedCount: Int,
    private var discoveredCount: Int,
    private var hasManualEntry: Boolean,
    @Suppress("UNUSED_PARAMETER") hasBluetoothPairing: Boolean = false  // Ignored in Standard Edition
) {
    var currentIndex: Int = 0
        private set

    val totalItems: Int
        get() = 2 + savedCount + discoveredCount + (if (hasManualEntry) 1 else 0)  // +2 for resolution preset and search

    enum class ItemType {
        RESOLUTION_PRESET,  // First item - tap to cycle resolution
        SEARCH_SERVERS,     // Second item - tap to search for servers
        SAVED,
        DISCOVERED,
        BLUETOOTH_PAIRING,  // Kept for API compatibility, never returned in Standard Edition
        MANUAL_ENTRY
    }

    /**
     * Move focus to the next item (wraps around).
     */
    @Synchronized
    fun moveNext(): Int {
        if (totalItems == 0) return currentIndex
        currentIndex = (currentIndex + 1) % totalItems
        return currentIndex
    }

    /**
     * Move focus to the previous item (wraps around).
     */
    @Synchronized
    fun movePrevious(): Int {
        if (totalItems == 0) return currentIndex
        currentIndex = if (currentIndex == 0) totalItems - 1 else currentIndex - 1
        return currentIndex
    }

    /**
     * Get the type of the currently focused item.
     */
    @Synchronized
    fun getCurrentItemType(): ItemType {
        if (totalItems == 0) {
            return ItemType.RESOLUTION_PRESET
        }

        // Index 0 = resolution preset
        // Index 1 = search servers
        // Index 2..savedCount+1 = saved servers
        // Index savedCount+2..savedCount+discoveredCount+1 = discovered servers
        // Last index = manual entry (if enabled)
        val savedStartIndex = 2
        val savedEndIndex = savedStartIndex + savedCount
        val discoveredEndIndex = savedEndIndex + discoveredCount

        return when {
            currentIndex == 0 -> ItemType.RESOLUTION_PRESET
            currentIndex == 1 -> ItemType.SEARCH_SERVERS
            currentIndex < savedEndIndex -> ItemType.SAVED
            currentIndex < discoveredEndIndex -> ItemType.DISCOVERED
            hasManualEntry && currentIndex == discoveredEndIndex -> ItemType.MANUAL_ENTRY
            else -> ItemType.RESOLUTION_PRESET
        }
    }

    /**
     * Get the index within the saved servers list, or null if not focused on a saved server.
     */
    @Synchronized
    fun getSavedServerIndex(): Int? {
        // Saved servers start at index 2 (after resolution preset and search)
        val adjustedIndex = currentIndex - 2
        return if (adjustedIndex >= 0 && adjustedIndex < savedCount) adjustedIndex else null
    }

    /**
     * Get the index within the discovered servers list, or null if not focused on a discovered server.
     */
    @Synchronized
    fun getDiscoveredServerIndex(): Int? {
        // Discovered servers start at index 2 + savedCount
        val adjustedIndex = currentIndex - 2 - savedCount
        return if (adjustedIndex >= 0 && adjustedIndex < discoveredCount) adjustedIndex else null
    }

    /**
     * Update the item counts when server lists change.
     * Automatically clamps focus index to valid range.
     */
    @Synchronized
    fun updateCounts(saved: Int, discovered: Int) {
        this.savedCount = saved
        this.discoveredCount = discovered
        if (currentIndex >= totalItems && totalItems > 0) {
            currentIndex = totalItems - 1
        }
    }

    /**
     * Reset focus to the first item.
     */
    @Synchronized
    fun reset() {
        currentIndex = 0
    }
}
