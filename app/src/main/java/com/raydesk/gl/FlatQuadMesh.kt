package com.raydesk.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Flat quad mesh for video rendering with dynamic aspect ratio support.
 *
 * For floating monitor mode, the mesh represents a screen in 3D space.
 * For keyhole mode, the mesh fills the viewport with UV-based panning.
 *
 * The mesh can be reconfigured when stream resolution is detected to
 * ensure proper aspect ratio (no stretching).
 *
 * @param initialWidth Initial width (default 2.0 units)
 * @param initialHeight Initial height (calculated from aspect ratio)
 * @param initialAspect Initial aspect ratio (16:9 default)
 */
class FlatQuadMesh(
    initialWidth: Float = 1f,   // Unit quad - model matrix handles all scaling
    initialHeight: Float = 1f,  // Unit quad - model matrix handles all scaling
    initialAspect: Float = 16f / 9f
) {
    // Mutable dimensions (can be updated when stream resolution is detected)
    // For floating monitor mode: use unit quad, model matrix scales
    // For keyhole mode: mesh dimensions don't matter (ortho fills viewport)
    private var width: Float = initialWidth
    private var height: Float = initialHeight
    private var aspectRatio: Float = initialAspect

    // Z distance - for keyhole mode this doesn't matter (ortho projection)
    // For floating monitor mode, position is handled by model matrix
    private val distance: Float = 0f

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false

    private val indices = shortArrayOf(
        0, 1, 2,  // First triangle
        0, 2, 3   // Second triangle
    )

    /**
     * Generate vertex array for current dimensions.
     *
     * UV coordinates are set up for SurfaceTexture:
     * - V is NOT flipped here (0,0 at bottom-left)
     * - The SurfaceTexture transform matrix handles orientation
     */
    private fun generateVertices(): FloatArray {
        return floatArrayOf(
            // Position (x,y,z)              // TexCoord (u,v)
            -width / 2, -height / 2, distance,   0f, 0f,  // Bottom-left
             width / 2, -height / 2, distance,   1f, 0f,  // Bottom-right
             width / 2,  height / 2, distance,   1f, 1f,  // Top-right
            -width / 2,  height / 2, distance,   0f, 1f   // Top-left
        )
    }

    fun initialize() {
        val vertices = generateVertices()

        // Create VAO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        // Create VBO
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]

        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexBuffer,
            GLES30.GL_DYNAMIC_DRAW  // Dynamic - may be updated when aspect changes
        )

        // Create IBO
        val ibos = IntArray(1)
        GLES30.glGenBuffers(1, ibos, 0)
        iboId = ibos[0]

        val indexBuffer: ShortBuffer = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
        indexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            indexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Set up vertex attributes
        val stride = 5 * 4  // 5 floats per vertex * 4 bytes

        // Position attribute (location 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)

        // TexCoord attribute (location 1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    /**
     * Update mesh dimensions when stream aspect ratio changes.
     *
     * This regenerates vertices to match the new aspect ratio,
     * preventing stretching when the actual stream resolution differs
     * from expected.
     *
     * @param newAspectRatio New aspect ratio (width / height)
     */
    fun updateAspectRatio(newAspectRatio: Float) {
        if (newAspectRatio <= 0f || !isInitialized) return

        // Only update if aspect ratio actually changed
        if (kotlin.math.abs(newAspectRatio - aspectRatio) < 0.001f) return

        aspectRatio = newAspectRatio
        height = width / aspectRatio

        // Regenerate and upload vertices
        val vertices = generateVertices()

        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertices.size * 4, vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Update mesh dimensions from stream resolution.
     *
     * @param streamWidth Stream width in pixels
     * @param streamHeight Stream height in pixels
     */
    fun updateFromResolution(streamWidth: Int, streamHeight: Int) {
        if (streamHeight > 0) {
            updateAspectRatio(streamWidth.toFloat() / streamHeight)
        }
    }

    fun draw() {
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indices.size, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vaoId > 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (iboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(iboId), 0)
            iboId = 0
        }
        isInitialized = false
    }

    // Accessors
    fun getWidth(): Float = width
    fun getHeight(): Float = height
    fun getAspectRatio(): Float = aspectRatio
}
