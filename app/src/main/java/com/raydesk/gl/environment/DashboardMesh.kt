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
 * Curved dashboard mesh positioned below the monitor.
 *
 * Replaces the abstract "status ring" with a familiar console/desk UI.
 * A curved slate that stays visible in the narrow FOV of AR glasses.
 *
 * Features:
 * - Curved to match monitor radius
 * - Positioned at -20° below eye level (stays in view)
 * - Wide enough for HUD text (FPS, connection, hints)
 * - Slight tilt toward user for readability
 */
class DashboardMesh(
    private val segments: Int = 32
) {
    companion object {
        private const val TAG = "DashboardMesh"

        // Dashboard positioning
        // Dashboard MUST be below monitor bottom edge (monitor is ~1m tall, bottom at -0.5m)
        const val TILT_DEGREES = 20f          // Tilted toward user for readability
        const val VERTICAL_OFFSET = -0.58f    // Below monitor bottom edge (-0.5m)
        const val HEIGHT = 0.10f              // Dashboard height (10cm, compact)
        const val ARC_DEGREES = 70f           // Visible arc (narrower than monitor)
        const val RADIUS_OFFSET = 0.15f       // Closer to user than monitor
    }

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false
    private var indexCount: Int = 0

    private var currentRadius: Float = 2.4f  // Monitor radius - offset

    /**
     * Generate dashboard vertices.
     *
     * Creates a curved quad tilted toward the user.
     * Vertex format: [x, y, z, u, v] (position + texcoord)
     */
    private fun generateVertices(): FloatArray {
        val vertices = mutableListOf<Float>()

        val arcRad = (ARC_DEGREES * PI / 180f).toFloat()
        val halfArc = arcRad / 2f
        val tiltRad = (TILT_DEGREES * PI / 180f).toFloat()

        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val angle = -halfArc + t * arcRad

            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            // Base positions on cylinder
            val baseX = currentRadius * sinA
            val baseZ = -currentRadius * cosA

            // Apply tilt - top edge is further, bottom edge is closer
            val topY = VERTICAL_OFFSET + HEIGHT / 2f
            val bottomY = VERTICAL_OFFSET - HEIGHT / 2f

            // Tilted positions
            val tiltOffset = HEIGHT / 2f * sin(tiltRad.toDouble()).toFloat()
            val topZ = baseZ - tiltOffset
            val bottomZ = baseZ + tiltOffset

            // Top vertex
            vertices.addAll(listOf(baseX, topY, topZ, t, 0f))

            // Bottom vertex
            vertices.addAll(listOf(baseX, bottomY, bottomZ, t, 1f))
        }

        return vertices.toFloatArray()
    }

    private fun generateIndices(): ShortArray {
        val indices = mutableListOf<Short>()

        for (i in 0 until segments) {
            val topLeft = (i * 2).toShort()
            val bottomLeft = (topLeft + 1).toShort()
            val topRight = (topLeft + 2).toShort()
            val bottomRight = (topLeft + 3).toShort()

            // Two triangles per quad segment
            indices.addAll(listOf(topLeft, bottomLeft, topRight))
            indices.addAll(listOf(bottomLeft, bottomRight, topRight))
        }

        return indices.toShortArray()
    }

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
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexArray.size * 4, vertexBuffer, GLES30.GL_DYNAMIC_DRAW)

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
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexArray.size * 2, indexBuffer, GLES30.GL_STATIC_DRAW)

        // Vertex attributes: position (location 0), texcoord (location 1)
        val stride = 5 * 4  // 5 floats * 4 bytes
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    /**
     * Update dashboard radius to follow monitor distance.
     */
    fun updateRadius(monitorRadius: Float) {
        if (!isInitialized) return

        val newRadius = monitorRadius - RADIUS_OFFSET
        if (newRadius == currentRadius) return

        currentRadius = newRadius
        regenerateGeometry()
    }

    private fun regenerateGeometry() {
        val vertexArray = generateVertices()
        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(vertexArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexArray)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexArray.size * 4, vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        if (!isInitialized) return

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
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

    fun isInitialized(): Boolean = isInitialized
}
