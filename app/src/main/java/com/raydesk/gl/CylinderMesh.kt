package com.raydesk.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Curved cylindrical mesh for immersive single-monitor display.
 *
 * Creates a curved screen that wraps around the user for an immersive viewing
 * experience. The entire stream texture is mapped to a single curved surface.
 *
 * Coordinate system:
 * - User at origin looking toward -Z
 * - Y-axis is up
 * - Cylinder wraps horizontally around user
 *
 * @param segmentsH Horizontal segments (32 for smooth curve)
 * @param segmentsV Vertical segments (8 is sufficient)
 */
class CylinderMesh(
    private val segmentsH: Int = 32,
    private val segmentsV: Int = 8
) {
    companion object {
        private const val TAG = "CylinderMesh"

        // Default geometry - 40 degree arc centered on forward direction
        private const val DEFAULT_START_ANGLE = -20f  // degrees left of center
        private const val DEFAULT_END_ANGLE = 20f     // degrees right of center
        private const val DEFAULT_HEIGHT = 0.98f      // meters
    }

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var isInitialized: Boolean = false

    // Current geometry parameters
    private var currentRadius: Float = 2.5f
    private var currentHeight: Float = DEFAULT_HEIGHT
    private var startAngle: Float = DEFAULT_START_ANGLE
    private var endAngle: Float = DEFAULT_END_ANGLE

    // Base physical dimensions - computed once, used to maintain consistent apparent size during zoom
    // Arc length stays constant so closer = larger arc angle = bigger apparent size
    private var baseRadius: Float = 2.5f
    private var baseHeight: Float = DEFAULT_HEIGHT
    private var baseArcLength: Float = 0f  // Physical arc length in meters (computed from initial geometry)
    private var baseArcDegrees: Float = DEFAULT_END_ANGLE - DEFAULT_START_ANGLE  // 40 degrees

    // Vertex data counts
    private var totalVertexCount: Int = 0
    private var totalIndexCount: Int = 0

    /**
     * Generate vertices for the curved cylinder surface.
     *
     * @param radius Distance from user to cylinder surface in meters
     * @param height Height of the cylinder in meters
     * @param startAngle Start angle in degrees (negative = left of center)
     * @param endAngle End angle in degrees (positive = right of center)
     * @return Float array of vertices: [x, y, z, u, v, ...]
     */
    private fun generateVertices(
        radius: Float,
        height: Float,
        startAngle: Float,
        endAngle: Float
    ): FloatArray {
        val vertices = mutableListOf<Float>()

        for (v in 0..segmentsV) {
            // Y position: -height/2 to +height/2 (centered vertically)
            val y = (v.toFloat() / segmentsV - 0.5f) * height
            // V texture coordinate: 0 to 1
            val texV = v.toFloat() / segmentsV

            for (h in 0..segmentsH) {
                // Interpolate angle from start to end
                val t = h.toFloat() / segmentsH
                val angleDegrees = startAngle + t * (endAngle - startAngle)
                val angleRadians = Math.toRadians(angleDegrees.toDouble())

                // Cylinder coordinates (user at origin, looking at -Z)
                val x = (radius * sin(angleRadians)).toFloat()
                val z = (-radius * cos(angleRadians)).toFloat()

                // U texture coordinate: 0 to 1
                val texU = t

                // Add vertex: position (x, y, z) + texcoord (u, v)
                vertices.addAll(listOf(x, y, z, texU, texV))
            }
        }

        return vertices.toFloatArray()
    }

    /**
     * Generate indices for triangle list.
     *
     * CRITICAL: Winding order for inside-facing surface viewed from origin.
     * Viewer at (0,0,0) looking at cylinder at -Z.
     * For CCW winding from viewer's perspective:
     * - TL → TR → BL (counter-clockwise: right, then down-left)
     * - TR → BR → BL (counter-clockwise: down, then left)
     */
    private fun generateIndices(): ShortArray {
        val indices = mutableListOf<Short>()

        for (v in 0 until segmentsV) {
            for (h in 0 until segmentsH) {
                val topLeft = (v * (segmentsH + 1) + h).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = (topLeft + segmentsH + 1).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                // Two triangles per quad - CORRECT CCW winding for inside-facing surface
                // Triangle 1: TL → TR → BL (CCW from viewer at origin)
                // Triangle 2: TR → BR → BL (CCW from viewer at origin)
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

        // Generate vertices and indices
        val vertexArray = generateVertices(currentRadius, currentHeight, startAngle, endAngle)
        val indexArray = generateIndices()

        totalVertexCount = vertexArray.size / 5  // 5 floats per vertex
        totalIndexCount = indexArray.size

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
            GLES30.GL_DYNAMIC_DRAW
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

        // Set up vertex attributes
        val stride = 5 * 4  // 5 floats per vertex * 4 bytes

        // Position attribute (location 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)

        // TexCoord attribute (location 1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

        // Initialize base physical dimensions for zoom calculations
        // These will be refined by updateFromResolution() when stream starts
        baseRadius = currentRadius
        baseHeight = currentHeight
        baseArcDegrees = endAngle - startAngle
        val arcRadians = Math.toRadians(baseArcDegrees.toDouble()).toFloat()
        baseArcLength = baseRadius * arcRadians

        isInitialized = true
    }

    /**
     * Update cylinder radius (for zoom).
     * Smaller radius = closer/larger view, larger radius = further/smaller view.
     *
     * ZOOM BEHAVIOR (Fixed Arc Length):
     * - Physical dimensions (arc length + height) stay CONSTANT
     * - Arc angle = arcLength / radius (widens as you get closer)
     * - Perspective projection naturally makes closer objects appear larger
     *
     * This simulates physically moving closer to a fixed-size curved screen.
     *
     * @param radius New radius in meters
     */
    fun updateRadius(radius: Float) {
        if (!isInitialized) {
            Log.w(TAG, "[ZOOM-FIX] updateRadius skipped - not initialized")
            return
        }
        if (radius == currentRadius) {
            return
        }

        currentRadius = radius

        // PROPER ZOOM: Recalculate arc angle to maintain physical arc length
        // arcLength = radius * arcAngle(radians) → arcAngle = arcLength / radius
        if (baseArcLength > 0f) {
            val newArcAngleRadians = baseArcLength / radius
            val newArcAngleDegrees = Math.toDegrees(newArcAngleRadians.toDouble()).toFloat()

            // Clamp to reasonable values (20° minimum to stay visible, 160° max per Gemini's recommendation)
            val clampedArcDegrees = newArcAngleDegrees.coerceIn(20f, 160f)

            // Center the arc around forward direction (-Z)
            startAngle = -clampedArcDegrees / 2
            endAngle = clampedArcDegrees / 2

            // Keep physical height CONSTANT (Gemini's fix)
            // The perspective projection will naturally make the screen appear larger when closer
            // Previously we scaled height which made the screen physically larger - wrong!
            currentHeight = baseHeight

        } else {
            // Fallback if baseArcLength not set (shouldn't happen after updateFromResolution)
            currentHeight = baseHeight
            Log.w(TAG, "[ZOOM-FIX] updateRadius: baseArcLength=0, using fallback")
        }

        regenerateVertices()
    }

    /**
     * Regenerate vertex positions (called on radius change).
     */
    private fun regenerateVertices() {
        val vertexArray = generateVertices(currentRadius, currentHeight, startAngle, endAngle)
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

    /**
     * Update height based on stream resolution to maintain aspect ratio.
     * Also computes and stores base physical dimensions for proper zoom behavior.
     *
     * IMPORTANT: This must be called after initialize() to set up the base arc length
     * which is used by updateRadius() for proper "moving closer to screen" zoom effect.
     */
    fun updateFromResolution(width: Int, height: Int) {
        if (height <= 0 || !isInitialized) return

        // Calculate height to maintain aspect ratio at current radius
        val arcDegrees = endAngle - startAngle
        val arcRadians = Math.toRadians(arcDegrees.toDouble()).toFloat()
        val arcLength = currentRadius * arcRadians

        val aspect = width.toFloat() / height.toFloat()
        currentHeight = arcLength / aspect

        // Store base physical dimensions for zoom calculations
        // These define the "physical size" of the virtual screen which stays constant during zoom
        baseRadius = currentRadius
        baseHeight = currentHeight
        baseArcDegrees = arcDegrees
        baseArcLength = arcLength  // Physical arc length in meters (e.g., 2.5m * 0.698rad = 1.745m)
        regenerateVertices()
    }

    /**
     * Draw the cylinder.
     */
    fun draw() {
        if (!isInitialized) return

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, totalIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
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

    // Accessors
    fun getRadius(): Float = currentRadius
    fun getHeight(): Float = currentHeight
    fun getArcAngleDegrees(): Float = endAngle - startAngle
    fun isInitialized(): Boolean = isInitialized
}
