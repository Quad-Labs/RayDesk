package com.raydesk.spatial

/**
 * Calculates edge glow indicator when cursor is outside viewport.
 * Used to help user locate their cursor when it's off-screen.
 */
class EdgeGlint {

    enum class Edge { LEFT, RIGHT, TOP, BOTTOM, NONE }

    data class GlintState(
        val visible: Boolean,
        val edge: Edge,
        val intensity: Float  // 0.0 to 1.0
    )

    /**
     * Simple rectangle in desktop pixel coordinates.
     * Platform-independent alternative to android.graphics.Rect for testability.
     */
    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun contains(x: Int, y: Int): Boolean =
            x >= left && x < right && y >= top && y < bottom
    }

    /**
     * Calculate glint state based on cursor position relative to viewport.
     *
     * @param cursorX Desktop X coordinate (0-1920)
     * @param cursorY Desktop Y coordinate (0-1080)
     * @param viewportRect Current viewport rectangle in desktop coordinates
     * @return GlintState indicating which edge to highlight and how bright
     */
    fun calculate(cursorX: Int, cursorY: Int, viewportRect: Rect): GlintState {
        if (viewportRect.contains(cursorX, cursorY)) {
            return GlintState(visible = false, edge = Edge.NONE, intensity = 0f)
        }

        // Calculate distances to each edge
        val distLeft = if (cursorX < viewportRect.left) viewportRect.left - cursorX else Int.MAX_VALUE
        val distRight = if (cursorX > viewportRect.right) cursorX - viewportRect.right else Int.MAX_VALUE
        val distTop = if (cursorY < viewportRect.top) viewportRect.top - cursorY else Int.MAX_VALUE
        val distBottom = if (cursorY > viewportRect.bottom) cursorY - viewportRect.bottom else Int.MAX_VALUE

        // Find closest edge
        val minDist = minOf(distLeft, distRight, distTop, distBottom)

        val edge = when (minDist) {
            distLeft -> Edge.LEFT
            distRight -> Edge.RIGHT
            distTop -> Edge.TOP
            distBottom -> Edge.BOTTOM
            else -> Edge.NONE
        }

        // Intensity based on distance (0.2 min, 1.0 max at 500px)
        val intensity = (minDist / 500f).coerceIn(0.2f, 1f)

        return GlintState(visible = true, edge = edge, intensity = intensity)
    }
}
