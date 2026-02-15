package com.raydesk.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.raydesk.test.R

/**
 * Custom view for displaying a list of servers with focus handling.
 *
 * ServerListView renders a vertical list of ServerListItem objects with:
 * - Focus indicator: filled circle (focused) or empty circle (unfocused)
 * - Server name
 * - Online/Offline status with indicator dot
 * - Blue text color (#1E2DED) for focused item, white for others
 *
 * Focus is managed externally via [setFocusedIndex] and integrates with [FocusNavigator].
 *
 * Design compliance:
 * - Pure black background (from parent)
 * - White text 20sp
 * - Blue focus color (#1E2DED)
 * - Focus indicator symbols: filled circle / empty circle
 * - 8dp vertical padding between items
 *
 * Usage:
 * ```kotlin
 * val serverListView = findViewById<ServerListView>(R.id.serverList)
 * serverListView.setItems(items)
 * serverListView.setFocusedIndex(focusNavigator.currentIndex)
 * serverListView.setOnItemClickListener { index, item ->
 *     handleServerSelection(item)
 * }
 * ```
 */
class ServerListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Current list of server items */
    private var items: List<ServerListItem> = emptyList()

    /** Index of the currently focused item (-1 for none) */
    private var focusedIndex: Int = -1

    /** Callback for item clicks */
    private var onItemClickListener: ((index: Int, item: ServerListItem) -> Unit)? = null

    init {
        orientation = VERTICAL
    }

    /**
     * Set the list of server items to display.
     *
     * This will rebuild the view hierarchy with the new items.
     * Must be called from the main thread.
     *
     * @param items List of [ServerListItem] to display
     */
    @MainThread
    fun setItems(items: List<ServerListItem>) {
        this.items = items
        rebuildViews()
    }

    /**
     * Set the currently focused item index.
     *
     * The focused item will be displayed with:
     * - Blue text color (#1E2DED)
     * - Filled circle indicator
     *
     * Must be called from the main thread.
     *
     * @param index Index of the focused item (0-based), or -1 for no focus
     */
    @MainThread
    fun setFocusedIndex(index: Int) {
        if (focusedIndex != index) {
            focusedIndex = index
            rebuildViews()
        }
    }

    /**
     * Set a listener for item click events.
     *
     * @param listener Callback receiving the clicked item index and the [ServerListItem]
     */
    fun setOnItemClickListener(listener: (index: Int, item: ServerListItem) -> Unit) {
        this.onItemClickListener = listener
    }

    /**
     * Get the current item count.
     *
     * @return Number of items in the list
     */
    fun getItemCount(): Int = items.size

    /**
     * Get the item at the specified index.
     *
     * @param index Index of the item to retrieve
     * @return The [ServerListItem] at the index, or null if out of bounds
     */
    fun getItem(index: Int): ServerListItem? = items.getOrNull(index)

    /**
     * Rebuild all child views based on current items and focus state.
     */
    private fun rebuildViews() {
        removeAllViews()

        items.forEachIndexed { index, item ->
            val isFocused = index == focusedIndex
            val viewModel = ServerItemViewModel.from(item, isFocused)
            addView(createItemView(index, item, viewModel))
        }
    }

    /**
     * Create a TextView for a single server item.
     */
    private fun createItemView(
        index: Int,
        item: ServerListItem,
        viewModel: ServerItemViewModel
    ): TextView {
        return TextView(context).apply {
            text = viewModel.displayText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(
                if (viewModel.isFocused) {
                    ContextCompat.getColor(context, R.color.focus_blue)
                } else {
                    Color.WHITE
                }
            )
            setPadding(0, dpToPx(ITEM_PADDING_DP), 0, dpToPx(ITEM_PADDING_DP))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )

            // Set up click listener for this item
            setOnClickListener {
                onItemClickListener?.invoke(index, item)
            }
        }
    }

    /**
     * Convert dp to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        /** Text size for server items in sp */
        const val TEXT_SIZE_SP = 20f

        /** Vertical padding between items in dp */
        const val ITEM_PADDING_DP = 8
    }
}
