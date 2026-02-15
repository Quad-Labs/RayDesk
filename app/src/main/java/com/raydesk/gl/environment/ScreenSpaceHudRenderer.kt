package com.raydesk.gl.environment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.raydesk.gl.ShaderUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Screen-space HUD renderer for AR glasses.
 *
 * Renders status information at the bottom of the glasses display,
 * fixed to the viewport (not world-space). Works with binocular
 * display by rendering to both eye regions.
 *
 * Content (left to right):
 * - FPS counter
 * - Connection quality bars
 * - Zoom level percentage
 * - Display mode
 * - Control hints
 *
 * Style: Subtle semi-transparent text
 */
class ScreenSpaceHudRenderer(private val context: Context) {

    companion object {
        private const val TAG = "ScreenSpaceHud"

        // HUD texture dimensions (wide for spacing, SDK handles binocular)
        private const val HUD_TEXTURE_WIDTH = 1024  // Wide texture for proper spacing
        private const val HUD_TEXTURE_HEIGHT = 40   // Taller for larger text + backdrop

        // HUD positioning (normalized coordinates, 0-1)
        private const val HUD_HEIGHT_RATIO = 0.05f  // 5% of screen height (subtle)

        // Visual settings - matching StatusRingTextRenderer for parity
        private const val TEXT_ALPHA = 255          // Fully opaque
        private const val HINT_ALPHA = 255          // Fully opaque for hints too
        private const val TEXT_SIZE_MAIN = 24f      // Match old status text size
        private const val TEXT_SIZE_HINT = 18f      // Slightly smaller for hints
        private const val BACKDROP_ALPHA = 120      // Semi-transparent dark backdrop
    }

    // GL resources
    private var shaderProgram: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var textureId: Int = 0

    // Uniform locations
    private var uMVPMatrix: Int = -1
    private var uTexture: Int = -1
    private var uAlpha: Int = -1

    // Orthographic projection for screen-space rendering
    private val orthoMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16)

    // HUD state
    private var fps: Int = 0
    private var connectionQuality: Int = 4  // 0-5 bars
    private var inputMode: String = "TRACKPAD"  // Control scheme
    private var isDirty: Boolean = true

    // Drawing resources
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    // Backdrop paint - semi-transparent dark background
    private val backdropPaint = Paint().apply {
        color = Color.BLACK
        alpha = BACKDROP_ALPHA
    }

    // Main text paint (left: bars, right: stats) - white, bold (matches old status)
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = TEXT_ALPHA
        textSize = TEXT_SIZE_MAIN
        typeface = Typeface.DEFAULT_BOLD
    }

    // Hint text paint (center: control hints)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = HINT_ALPHA
        textSize = TEXT_SIZE_HINT
        typeface = Typeface.DEFAULT_BOLD
    }

    private var isInitialized = false
    private var viewportWidth = 1280
    private var viewportHeight = 480

    /**
     * Initialize GL resources.
     */
    fun initialize() {
        if (isInitialized) return
        // Create shader program
        shaderProgram = ShaderUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (shaderProgram == 0) {
            Log.e(TAG, "Failed to create HUD shader program")
            return
        }

        // Get uniform locations
        uMVPMatrix = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        uTexture = GLES30.glGetUniformLocation(shaderProgram, "uTexture")
        uAlpha = GLES30.glGetUniformLocation(shaderProgram, "uAlpha")

        // Initialize identity matrix
        Matrix.setIdentityM(identityMatrix, 0)

        // Create mesh (simple quad)
        createQuadMesh()

        // Create texture
        createTexture()

        // Create initial bitmap and canvas
        bitmap = Bitmap.createBitmap(HUD_TEXTURE_WIDTH, HUD_TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)

        isInitialized = true
        isDirty = true
    }

    private fun createQuadMesh() {
        // Quad vertices: position (x,y) + texcoord (u,v)
        // Positioned at bottom of normalized screen space
        val vertices = floatArrayOf(
            // Position (x, y)    TexCoord (u, v)
            0f, 0f,               0f, 1f,   // Bottom-left
            1f, 0f,               1f, 1f,   // Bottom-right
            1f, 1f,               1f, 0f,   // Top-right
            0f, 1f,               0f, 0f    // Top-left
        )

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

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
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)

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
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, indexBuffer, GLES30.GL_STATIC_DRAW)

        // Set up vertex attributes
        val stride = 4 * 4  // 4 floats * 4 bytes

        // Position attribute (location 0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)

        // TexCoord attribute (location 1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)
    }

    private fun createTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Update viewport dimensions for proper positioning.
     */
    fun updateViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /**
     * Draw the HUD. Call after all 3D rendering.
     *
     * Note: Renders once to full viewport. Mercury SDK's MirroringView
     * handles binocular duplication automatically.
     */
    fun draw() {
        if (!isInitialized) return

        // Update texture if content changed
        if (isDirty) {
            updateTexture()
            isDirty = false
        }

        // Save GL state
        val depthTestEnabled = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST)
        val blendEnabled = GLES30.glIsEnabled(GLES30.GL_BLEND)
        val cullFaceEnabled = GLES30.glIsEnabled(GLES30.GL_CULL_FACE)

        // Configure for 2D overlay rendering
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glUseProgram(shaderProgram)

        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glUniform1f(uAlpha, 1.0f)

        // Render once to full viewport - SDK handles binocular mirroring
        // Position HUD at bottom of viewport, spanning nearly full width
        Matrix.orthoM(orthoMatrix, 0, 0f, 1f, 0f, 1f, -1f, 1f)

        // Scale and position the HUD at bottom of screen (wide, subtle)
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.scaleM(mvpMatrix, 0, 0.96f, HUD_HEIGHT_RATIO, 1f)  // 96% width for max spread
        Matrix.translateM(mvpMatrix, 0, 0.02f, 0f, 0f)  // Small margin on sides

        // Combine with ortho projection
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, orthoMatrix, 0, mvpMatrix, 0)

        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, tempMatrix, 0)

        // Draw quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 6, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)

        // Restore GL state
        if (depthTestEnabled) GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        if (!blendEnabled) GLES30.glDisable(GLES30.GL_BLEND)
        if (cullFaceEnabled) GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun updateTexture() {
        val bmp = bitmap ?: return
        val cvs = canvas ?: return

        // Clear bitmap
        bmp.eraseColor(Color.TRANSPARENT)

        // Draw semi-transparent dark backdrop
        cvs.drawRect(0f, 0f, HUD_TEXTURE_WIDTH.toFloat(), HUD_TEXTURE_HEIGHT.toFloat(), backdropPaint)

        val mainTextY = HUD_TEXTURE_HEIGHT * 0.72f  // Vertically centered
        val hintTextY = HUD_TEXTURE_HEIGHT * 0.68f  // Slightly higher for hint text
        val edgePadding = 24f

        // LEFT (leftmost): Connection quality bars
        val qualityBars = buildQualityBars(connectionQuality)
        mainPaint.textAlign = Paint.Align.LEFT
        cvs.drawText(qualityBars, edgePadding, mainTextY, mainPaint)

        // CENTER: Context-aware control hints based on input mode
        val controlHints = buildControlHints(inputMode)
        hintPaint.textAlign = Paint.Align.CENTER
        cvs.drawText(controlHints, HUD_TEXTURE_WIDTH / 2f, hintTextY, hintPaint)

        // RIGHT (rightmost): FPS only
        val statsText = "${fps} FPS"
        mainPaint.textAlign = Paint.Align.RIGHT
        cvs.drawText(statsText, HUD_TEXTURE_WIDTH - edgePadding, mainTextY, mainPaint)

        // Upload to GPU
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun buildControlHints(mode: String): String {
        return when (mode.uppercase()) {
            "TRACKPAD" -> "Swipe: Trackpad  ·  2×Tap: Recenter  ·  3×Tap: Menu"
            "MOUSE" -> "Mouse: Control  ·  2×Tap: Recenter  ·  3×Tap: Menu"
            "GESTURE" -> "←→ Zoom  ·  ↑↓ Scroll  ·  2×Tap: Recenter  ·  3×Tap: Menu"
            else -> "2×Tap: Recenter  ·  3×Tap: Menu"
        }
    }

    private fun buildQualityBars(quality: Int): String {
        val filled = quality.coerceIn(0, 5)
        val empty = 5 - filled
        return "■".repeat(filled) + "□".repeat(empty)
    }

    // === Public setters ===

    fun setFps(value: Int) {
        if (fps != value) {
            fps = value
            isDirty = true
        }
    }

    fun setConnectionQuality(value: Int) {
        if (connectionQuality != value) {
            connectionQuality = value.coerceIn(0, 5)
            isDirty = true
        }
    }

    fun setInputMode(value: String) {
        if (inputMode != value) {
            inputMode = value
            isDirty = true
        }
    }

    /**
     * Release GL resources.
     */
    fun release() {
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
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
        if (shaderProgram > 0) {
            GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        bitmap?.recycle()
        bitmap = null
        canvas = null
        isInitialized = false
    }

    fun onContextLost() {
        isInitialized = false
        textureId = 0
        vaoId = 0
        vboId = 0
        iboId = 0
        shaderProgram = 0
    }

    fun isInitialized(): Boolean = isInitialized

    // === Shaders ===

    private val VERTEX_SHADER = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;

        uniform mat4 uMVPMatrix;

        out vec2 vTexCoord;

        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #version 300 es
        precision mediump float;

        in vec2 vTexCoord;

        uniform sampler2D uTexture;
        uniform float uAlpha;

        out vec4 fragColor;

        void main() {
            vec4 texColor = texture(uTexture, vTexCoord);
            fragColor = vec4(texColor.rgb, texColor.a * uAlpha);
        }
    """.trimIndent()
}
