package com.raydesk.gl.environment

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hemisphere mesh for environment dome backdrop.
 *
 * Creates an inside-facing hemisphere positioned above the user.
 * User at origin, dome curves overhead and around.
 *
 * @param radius Dome radius in meters (default 10m)
 * @param segmentsH Horizontal segments (32 for smooth curve)
 * @param segmentsV Vertical segments (16 for hemisphere)
 */
class DomeMesh(
    private val radius: Float = 10f,
    private val segmentsH: Int = 32,
    private val segmentsV: Int = 16
) {
    companion object {
        private const val TAG = "DomeMesh"
    }

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false
    private var indexCount: Int = 0

    /**
     * Generate hemisphere vertices.
     *
     * Hemisphere from y=0 (horizon) to y=radius (zenith).
     * Normals face inward (rendered from inside).
     *
     * @return Float array: [x, y, z, u, v, ...]
     */
    private fun generateVertices(): FloatArray {
        val vertices = mutableListOf<Float>()

        for (v in 0..segmentsV) {
            // Phi: 0 (horizon) to PI/2 (zenith)
            val phi = (v.toFloat() / segmentsV) * (PI / 2.0)
            val y = (radius * sin(phi)).toFloat()
            val ringRadius = (radius * cos(phi)).toFloat()
            val texV = v.toFloat() / segmentsV

            for (h in 0..segmentsH) {
                // Theta: 0 to 2*PI (full circle)
                val theta = (h.toFloat() / segmentsH) * 2.0 * PI
                val x = (ringRadius * cos(theta)).toFloat()
                val z = (ringRadius * sin(theta)).toFloat()
                val texU = h.toFloat() / segmentsH

                vertices.addAll(listOf(x, y, z, texU, texV))
            }
        }

        return vertices.toFloatArray()
    }

    /**
     * Generate indices for inside-facing triangles.
     */
    private fun generateIndices(): ShortArray {
        val indices = mutableListOf<Short>()

        for (v in 0 until segmentsV) {
            for (h in 0 until segmentsH) {
                val topLeft = (v * (segmentsH + 1) + h).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = (topLeft + segmentsH + 1).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                // Reversed winding for inside-facing
                indices.addAll(listOf(topLeft, topRight, bottomLeft))
                indices.addAll(listOf(topRight, bottomRight, bottomLeft))
            }
        }

        return indices.toShortArray()
    }

    /**
     * Initialize OpenGL buffers.
     * Must be called on GL thread.
     */
    fun initialize() {

        val vertexArray = generateVertices()
        val indexArray = generateIndices()
        indexCount = indexArray.size

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
            .allocateDirect(vertexArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexArray)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexArray.size * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Create IBO
        val ibos = IntArray(1)
        GLES30.glGenBuffers(1, ibos, 0)
        iboId = ibos[0]

        val indexBuffer: ShortBuffer = ByteBuffer
            .allocateDirect(indexArray.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indexArray)
        indexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indexArray.size * 2,
            indexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Vertex attributes
        val stride = 5 * 4  // 5 floats * 4 bytes

        // Position (location 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)

        // TexCoord (location 1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    /**
     * Draw the dome.
     */
    fun draw() {
        if (!isInitialized) return

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Release OpenGL resources.
     */
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

    fun getRadius(): Float = radius
    fun isInitialized(): Boolean = isInitialized
}
