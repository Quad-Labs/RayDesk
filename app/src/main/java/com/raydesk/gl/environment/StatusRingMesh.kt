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
 * Full 360° glowing torus ring that circles the user.
 *
 * Positioned below the monitor as a visual boundary/horizon line.
 * The ring is a thin tube (torus) with procedural glow in the shader.
 *
 * Geometry: Torus with major radius (ring) and minor radius (tube thickness)
 */
class StatusRingMesh(
    private val ringSegments: Int = 64,   // Segments around the ring (360°)
    private val tubeSegments: Int = 8     // Segments around the tube cross-section
) {
    companion object {
        private const val TAG = "StatusRingMesh"

        // Ring positioning - MUST be visible in AR glasses narrow FOV
        const val VERTICAL_POSITION = -0.32f  // Just below monitor bottom, above status text
        const val MAJOR_RADIUS = 2.4f         // Ring radius (same level as monitor)
        const val MINOR_RADIUS = 0.04f        // Tube thickness (4cm - thinner line)
    }

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false
    private var indexCount: Int = 0

    private var currentMajorRadius: Float = MAJOR_RADIUS

    /**
     * Generate torus vertices.
     *
     * Vertex format: [x, y, z, u, v] (position + texcoord)
     * - u: 0-1 around the ring (for position-based effects)
     * - v: 0-1 around the tube cross-section (for glow falloff from center)
     */
    private fun generateVertices(): FloatArray {
        val vertices = mutableListOf<Float>()

        for (i in 0..ringSegments) {
            val ringAngle = (i.toFloat() / ringSegments) * 2f * PI.toFloat()
            val cosRing = cos(ringAngle.toDouble()).toFloat()
            val sinRing = sin(ringAngle.toDouble()).toFloat()

            for (j in 0..tubeSegments) {
                val tubeAngle = (j.toFloat() / tubeSegments) * 2f * PI.toFloat()
                val cosTube = cos(tubeAngle.toDouble()).toFloat()
                val sinTube = sin(tubeAngle.toDouble()).toFloat()

                // Position on torus surface
                val x = (currentMajorRadius + MINOR_RADIUS * cosTube) * sinRing
                val y = VERTICAL_POSITION + MINOR_RADIUS * sinTube
                val z = -(currentMajorRadius + MINOR_RADIUS * cosTube) * cosRing

                // UV coordinates
                val u = i.toFloat() / ringSegments  // Around ring (0-1)
                val v = j.toFloat() / tubeSegments  // Around tube (0-1)

                vertices.addAll(listOf(x, y, z, u, v))
            }
        }

        return vertices.toFloatArray()
    }

    private fun generateIndices(): ShortArray {
        val indices = mutableListOf<Short>()
        val vertsPerRing = tubeSegments + 1

        for (i in 0 until ringSegments) {
            for (j in 0 until tubeSegments) {
                val current = (i * vertsPerRing + j).toShort()
                val next = (current + 1).toShort()
                val nextRing = ((i + 1) * vertsPerRing + j).toShort()
                val nextRingNext = (nextRing + 1).toShort()

                // Two triangles per quad
                indices.addAll(listOf(current, nextRing, next))
                indices.addAll(listOf(next, nextRing, nextRingNext))
            }
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

        // Vertex attributes: position (0), texcoord (1)
        val stride = 5 * 4  // 5 floats * 4 bytes
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    /**
     * Update ring radius to match monitor distance.
     */
    fun updateRadius(monitorRadius: Float) {
        if (!isInitialized) return

        val newRadius = monitorRadius - 0.15f  // Slightly closer than monitor
        if (newRadius == currentMajorRadius) return

        currentMajorRadius = newRadius
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

    fun getRadius(): Float = currentMajorRadius
    fun isInitialized(): Boolean = isInitialized
}
