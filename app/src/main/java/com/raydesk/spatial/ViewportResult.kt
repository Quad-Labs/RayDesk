package com.raydesk.spatial

/**
 * Result of viewport calculation including cursor screen position.
 *
 * This class bundles the viewport texture rectangle with the cursor's
 * position on screen, enabling "soft-edge" cursor behavior:
 * - When viewport is not clamped: cursor is centered on screen (0.5, 0.5)
 * - When viewport is clamped at edge: cursor moves toward that edge
 *
 * @param textureRect UV coordinates for the viewport in texture space (0-1)
 * @param cursorScreenX Cursor X position on screen (0-1, normalized). 0=left, 1=right
 * @param cursorScreenY Cursor Y position on screen (0-1, normalized). 0=top, 1=bottom
 */
data class ViewportResult(
    val textureRect: TextureRect,
    val cursorScreenX: Float,
    val cursorScreenY: Float
)
