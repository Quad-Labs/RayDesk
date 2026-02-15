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

/**
 * Renders the curved dashboard HUD below the monitor.
 *
 * Displays:
 * - Connection quality bars (left)
 * - Control hints (center)
 * - FPS counter (right)
 *
 * Uses a semi-transparent material with text overlay.
 * The dashboard is tilted toward the user for readability.
 */
class DashboardRenderer(private val context: Context) {

    companion object {
        private const val TAG = "DashboardRenderer"
        // Wider texture to prevent text squishing (matches dashboard arc aspect)
        private const val TEXTURE_WIDTH = 2048
        private const val TEXTURE_HEIGHT = 192
    }

    // GL resources
    private var dashboardMesh: DashboardMesh? = null
    private var shaderProgram: Int = 0
    private var textureId: Int = 0
    private var isInitialized: Boolean = false

    // Uniform locations
    private var uMVPMatrix: Int = -1
    private var uDashTexture: Int = -1
    private var uBaseColor: Int = -1
    private var uAlpha: Int = -1

    // Working matrices
    private val mvpMatrix = FloatArray(16)

    // HUD data
    private var fps: Int = 0
    private var connectionQuality: Int = 4  // 0-4 bars
    private var controlHint: String = "Look around to explore"
    private var isDirty: Boolean = true

    // Drawing resources
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
        alpha = 255
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE  // Full white for visibility
        textSize = 44f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        style = Paint.Style.FILL
        alpha = 255
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Dashboard style
    var baseColor = floatArrayOf(0.1f, 0.12f, 0.18f)  // Dark blue-gray
    var alpha = 0.85f

    fun initialize() {
        if (isInitialized) return
        // Create shader program
        shaderProgram = createDashboardShaderProgram()
        if (shaderProgram == 0) {
            Log.e(TAG, "Failed to create dashboard shader program")
            return
        }

        // Get uniform locations
        uMVPMatrix = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        uDashTexture = GLES30.glGetUniformLocation(shaderProgram, "uDashTexture")
        uBaseColor = GLES30.glGetUniformLocation(shaderProgram, "uBaseColor")
        uAlpha = GLES30.glGetUniformLocation(shaderProgram, "uAlpha")

        // Create dashboard mesh
        dashboardMesh = DashboardMesh()
        dashboardMesh?.initialize()

        // Create texture
        createTexture()

        isInitialized = true
        isDirty = true
    }

    private fun createDashboardShaderProgram(): Int {
        // Simplified shader - no edge fade, just solid background with texture overlay
        val vertexShader = """
            #version 300 es

            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;

            out vec2 vTexCoord;

            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentShader = """
            #version 300 es

            precision mediump float;

            uniform sampler2D uDashTexture;
            uniform vec3 uBaseColor;
            uniform float uAlpha;

            in vec2 vTexCoord;

            out vec4 fragColor;

            void main() {
                // Sample text/content texture
                vec4 textSample = texture(uDashTexture, vTexCoord);

                // Simple solid background color
                vec3 color = uBaseColor;

                // Composite text/content on top
                color = mix(color, textSample.rgb, textSample.a);

                fragColor = vec4(color, uAlpha);
            }
        """.trimIndent()

        return ShaderUtils.createProgram(vertexShader, fragmentShader)
    }

    private fun createTexture() {
        // Create texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Create bitmap for text rendering
        bitmap = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
    }

    fun setFps(value: Int) {
        if (value != fps) {
            fps = value
            isDirty = true
        }
    }

    fun setConnectionQuality(quality: Int) {
        val clamped = quality.coerceIn(0, 4)
        if (clamped != connectionQuality) {
            connectionQuality = clamped
            isDirty = true
        }
    }

    fun setControlHint(hint: String) {
        if (hint != controlHint) {
            controlHint = hint
            isDirty = true
        }
    }

    private fun updateTexture() {
        if (!isDirty || !isInitialized) return

        val bmp = bitmap ?: return
        val cvs = canvas ?: return

        // Clear bitmap
        bmp.eraseColor(Color.TRANSPARENT)

        val centerY = TEXTURE_HEIGHT / 2f + textPaint.textSize / 3f
        val hintCenterY = TEXTURE_HEIGHT / 2f + hintPaint.textSize / 3f

        // Draw connection quality bars (left side) - around 5% from left
        val barWidth = 16f
        val barSpacing = 6f
        val barMaxHeight = 80f
        val barY = (TEXTURE_HEIGHT - barMaxHeight) / 2f
        val barStartX = 100f

        for (i in 0 until 4) {
            val height = barMaxHeight * (i + 1) / 4
            barPaint.color = if (i < connectionQuality) {
                when {
                    connectionQuality >= 3 -> Color.GREEN
                    connectionQuality >= 2 -> Color.YELLOW
                    else -> Color.RED
                }
            } else {
                Color.argb(80, 255, 255, 255)
            }
            cvs.drawRect(
                barStartX + i * (barWidth + barSpacing),
                barY + (barMaxHeight - height),
                barStartX + i * (barWidth + barSpacing) + barWidth,
                barY + barMaxHeight,
                barPaint
            )
        }

        // Draw control hint (center) - at 50% of texture width
        val hintWidth = hintPaint.measureText(controlHint)
        val hintX = (TEXTURE_WIDTH - hintWidth) / 2f

        // Draw semi-transparent background behind hint text for visibility
        barPaint.color = Color.argb(180, 30, 35, 45)
        val hintPadding = 20f
        cvs.drawRoundRect(
            hintX - hintPadding,
            hintCenterY - hintPaint.textSize - 5f,
            hintX + hintWidth + hintPadding,
            hintCenterY + 10f,
            12f, 12f,
            barPaint
        )

        cvs.drawText(controlHint, hintX, hintCenterY, hintPaint)

        // Draw FPS (right side) - around 90% from left
        val fpsText = "$fps FPS"
        val fpsWidth = textPaint.measureText(fpsText)
        val fpsX = TEXTURE_WIDTH - fpsWidth - 100f
        val fpsColor = when {
            fps >= 55 -> Color.GREEN
            fps >= 30 -> Color.YELLOW
            else -> Color.RED
        }

        // Draw semi-transparent background behind FPS text
        barPaint.color = Color.argb(180, 30, 35, 45)
        val fpsPadding = 15f
        cvs.drawRoundRect(
            fpsX - fpsPadding,
            centerY - textPaint.textSize - 5f,
            fpsX + fpsWidth + fpsPadding,
            centerY + 10f,
            12f, 12f,
            barPaint
        )

        textPaint.color = fpsColor
        cvs.drawText(fpsText, fpsX, centerY, textPaint)
        textPaint.color = Color.WHITE  // Reset

        // Upload to GPU
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)

        isDirty = false
    }

    fun updateRadius(monitorRadius: Float) {
        dashboardMesh?.updateRadius(monitorRadius)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized || shaderProgram == 0) return

        // Update texture if needed
        updateTexture()

        GLES30.glUseProgram(shaderProgram)

        // Enable blending for semi-transparent dashboard
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        // Calculate MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)

        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uDashTexture, 0)

        // Material properties
        GLES30.glUniform3fv(uBaseColor, 1, baseColor, 0)
        GLES30.glUniform1f(uAlpha, alpha)

        // Draw
        dashboardMesh?.draw()

        // Restore state
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        dashboardMesh?.release()
        dashboardMesh = null

        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
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
        shaderProgram = 0
        textureId = 0
        isDirty = true
    }

    fun isInitialized(): Boolean = isInitialized
}
