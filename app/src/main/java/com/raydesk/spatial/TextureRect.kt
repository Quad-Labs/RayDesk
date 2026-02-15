package com.raydesk.spatial

/**
 * Normalized texture coordinates for keyhole viewport.
 * All values in range 0.0 to 1.0.
 *
 * Represents a rectangular region of a texture:
 * - (u0, v0) is the top-left corner
 * - (u1, v1) is the bottom-right corner
 *
 * Used by KeyholeViewport to specify which portion of the
 * desktop texture should be rendered to the AR display.
 */
data class TextureRect(
    val u0: Float,  // Left edge
    val v0: Float,  // Top edge
    val u1: Float,  // Right edge
    val v1: Float   // Bottom edge
) {
    init {
        require(u0 in 0f..1f) { "u0 must be in range 0-1" }
        require(v0 in 0f..1f) { "v0 must be in range 0-1" }
        require(u1 in 0f..1f) { "u1 must be in range 0-1" }
        require(v1 in 0f..1f) { "v1 must be in range 0-1" }
        require(u1 > u0) { "u1 must be greater than u0" }
        require(v1 > v0) { "v1 must be greater than v0" }
    }

    /**
     * Convert to float array for OpenGL texture coordinate upload.
     * Format: [u0, v0, u1, v1]
     */
    fun toFloatArray(): FloatArray = floatArrayOf(u0, v0, u1, v1)
}
