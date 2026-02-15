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
 * 3D beveled frame mesh that wraps around the curved monitor.
 *
 * Creates a physical "picture frame" appearance instead of shader-based glow.
 * The frame has depth and bevels to look like real hardware.
 *
 * Features:
 * - Follows cylinder curvature of monitor
 * - Configurable bevel depth and width
 * - Matches monitor's aspect ratio and radius
 */
class MonitorFrameMesh(
    private val frameWidth: Float = 0.03f,      // Frame thickness (3cm)
    private val frameDepth: Float = 0.015f,     // Bevel depth (1.5cm)
    private val segments: Int = 64              // Segments along curve
) {
    companion object {
        private const val TAG = "MonitorFrameMesh"
    }

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false
    private var indexCount: Int = 0

    // Monitor parameters (updated dynamically)
    private var monitorRadius: Float = 2.5f
    private var monitorArcAngle: Float = (60f * PI / 180f).toFloat()  // 60 degrees
    private var monitorHeight: Float = 1.0f

    /**
     * Generate frame vertices.
     *
     * The frame consists of 4 strips (top, bottom, left, right edges)
     * each with inner and outer edges that create the bevel.
     *
     * Vertex format: [x, y, z, nx, ny, nz] (position + normal)
     */
    private fun generateVertices(): FloatArray {
        val vertices = mutableListOf<Float>()

        val halfHeight = monitorHeight / 2f
        val halfArc = monitorArcAngle / 2f

        // Frame boundaries
        // Z-FIGHTING FIX: Push frame slightly toward user (smaller radius)
        // so frame "cups" the monitor instead of coinciding with surface
        val zFightingOffset = 0.005f  // 5mm toward user
        val innerRadius = monitorRadius - zFightingOffset
        val outerRadius = monitorRadius + frameDepth - zFightingOffset
        val innerHalfHeight = halfHeight
        val outerHalfHeight = halfHeight + frameWidth

        // Generate top frame strip
        generateHorizontalStrip(vertices, innerHalfHeight, outerHalfHeight, innerRadius, outerRadius, halfArc, 1f)

        // Generate bottom frame strip
        generateHorizontalStrip(vertices, -outerHalfHeight, -innerHalfHeight, innerRadius, outerRadius, halfArc, -1f)

        // Generate left frame strip (uses different geometry)
        generateVerticalStrip(vertices, -halfArc, innerRadius, outerRadius, innerHalfHeight, outerHalfHeight, -1f)

        // Generate right frame strip
        generateVerticalStrip(vertices, halfArc, innerRadius, outerRadius, innerHalfHeight, outerHalfHeight, 1f)

        return vertices.toFloatArray()
    }

    private fun generateHorizontalStrip(
        vertices: MutableList<Float>,
        innerY: Float, outerY: Float,
        innerRadius: Float, outerRadius: Float,
        halfArc: Float,
        normalY: Float
    ) {
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val angle = -halfArc + t * monitorArcAngle

            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            // Inner edge (closer to monitor)
            val innerX = innerRadius * sinA
            val innerZ = -innerRadius * cosA
            vertices.addAll(listOf(innerX, innerY, innerZ, 0f, normalY, 0f))

            // Outer edge (frame exterior)
            val outerX = outerRadius * sinA
            val outerZ = -outerRadius * cosA
            vertices.addAll(listOf(outerX, outerY, outerZ, 0f, normalY, 0f))
        }
    }

    private fun generateVerticalStrip(
        vertices: MutableList<Float>,
        angle: Float,
        innerRadius: Float, outerRadius: Float,
        innerHalfHeight: Float, outerHalfHeight: Float,
        normalX: Float
    ) {
        val cosA = cos(angle.toDouble()).toFloat()
        val sinA = sin(angle.toDouble()).toFloat()

        // Normal pointing sideways
        val nx = cosA * normalX
        val nz = sinA * normalX

        // 4 vertices per strip (top-outer, top-inner, bottom-inner, bottom-outer)
        val topOuter = floatArrayOf(outerRadius * sinA, outerHalfHeight, -outerRadius * cosA)
        val topInner = floatArrayOf(innerRadius * sinA, innerHalfHeight, -innerRadius * cosA)
        val bottomInner = floatArrayOf(innerRadius * sinA, -innerHalfHeight, -innerRadius * cosA)
        val bottomOuter = floatArrayOf(outerRadius * sinA, -outerHalfHeight, -outerRadius * cosA)

        vertices.addAll(listOf(topOuter[0], topOuter[1], topOuter[2], nx, 0f, nz))
        vertices.addAll(listOf(topInner[0], topInner[1], topInner[2], nx, 0f, nz))
        vertices.addAll(listOf(bottomInner[0], bottomInner[1], bottomInner[2], nx, 0f, nz))
        vertices.addAll(listOf(bottomOuter[0], bottomOuter[1], bottomOuter[2], nx, 0f, nz))
    }

    private fun generateIndices(): ShortArray {
        val indices = mutableListOf<Short>()

        // Top strip indices
        val topStart = 0
        for (i in 0 until segments) {
            val base = topStart + i * 2
            indices.add(base.toShort())
            indices.add((base + 1).toShort())
            indices.add((base + 2).toShort())
            indices.add((base + 1).toShort())
            indices.add((base + 3).toShort())
            indices.add((base + 2).toShort())
        }

        // Bottom strip indices
        val bottomStart = (segments + 1) * 2
        for (i in 0 until segments) {
            val base = bottomStart + i * 2
            indices.add(base.toShort())
            indices.add((base + 2).toShort())
            indices.add((base + 1).toShort())
            indices.add((base + 1).toShort())
            indices.add((base + 2).toShort())
            indices.add((base + 3).toShort())
        }

        // Left strip indices (4 vertices as a quad)
        val leftStart = bottomStart + (segments + 1) * 2
        indices.add(leftStart.toShort())
        indices.add((leftStart + 1).toShort())
        indices.add((leftStart + 2).toShort())
        indices.add(leftStart.toShort())
        indices.add((leftStart + 2).toShort())
        indices.add((leftStart + 3).toShort())

        // Right strip indices
        val rightStart = leftStart + 4
        indices.add(rightStart.toShort())
        indices.add((rightStart + 2).toShort())
        indices.add((rightStart + 1).toShort())
        indices.add(rightStart.toShort())
        indices.add((rightStart + 3).toShort())
        indices.add((rightStart + 2).toShort())

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

        // Vertex attributes: position (location 0), normal (location 1)
        val stride = 6 * 4  // 6 floats * 4 bytes
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    /**
     * Update frame to match monitor dimensions.
     */
    fun updateMonitorParams(radius: Float, arcAngle: Float, height: Float) {
        if (!isInitialized) return

        monitorRadius = radius
        monitorArcAngle = arcAngle
        monitorHeight = height

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
