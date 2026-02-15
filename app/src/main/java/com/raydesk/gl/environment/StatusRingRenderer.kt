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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the 360° glowing status ring and curved text billboards.
 *
 * Components:
 * 1. Glowing torus ring - thin tube circling the user
 * 2. Curved text billboards - FPS, connection bars, hints below the ring
 *
 * The ring and text glow color come from the current theme.
 */
class StatusRingRenderer(private val context: Context) {

    companion object {
        private const val TAG = "StatusRingRenderer"

        // Text billboard positioning - further below the display
        private const val TEXT_Y_POSITION = -0.55f  // Below monitor bottom
        private const val TEXT_RADIUS = 2.35f       // Same depth as monitor
        private const val TEXT_ARC_DEGREES = 22f    // Wide enough for full hint text

        // Text zones (degrees from center, negative = left) - closer together
        private const val LEFT_ZONE_ANGLE = -15f    // Connection bars
        private const val CENTER_ZONE_ANGLE = 0f    // Control hints
        private const val RIGHT_ZONE_ANGLE = 15f    // FPS

        // Texture sizes
        private const val TEXT_TEXTURE_WIDTH = 768
        private const val TEXT_TEXTURE_HEIGHT = 128
    }

    // GL resources - Ring
    private var ringMesh: StatusRingMesh? = null
    private var ringProgram: Int = 0

    // GL resources - Text billboards
    private var textVaoId: Int = 0
    private var textVboId: Int = 0
    private var textIboId: Int = 0
    private var textIndexCount: Int = 0

    // Textures for each text zone
    private var leftTextureId: Int = 0    // Connection bars
    private var centerTextureId: Int = 0  // Hints
    private var rightTextureId: Int = 0   // FPS
    private var textProgram: Int = 0

    private var isInitialized: Boolean = false

    // Uniform locations - Ring
    private var ringUMVPMatrix: Int = -1
    private var ringUGlowColor: Int = -1
    private var ringUAlpha: Int = -1

    // Uniform locations - Text
    private var textUMVPMatrix: Int = -1
    private var textUTexture: Int = -1
    private var textUGlowColor: Int = -1

    // Working matrices
    private val mvpMatrix = FloatArray(16)

    // Theme colors
    private var glowColor = floatArrayOf(0.3f, 0.5f, 1.0f)  // Default blue

    // Zoom scaling
    private var currentZoom: Float = 1.0f
    private var needsGeometryRebuild: Boolean = false

    // HUD data
    private var fps: Int = 0
    private var connectionQuality: Int = 4
    private var controlHint = "Swipe: Scroll · Double-tap: Recenter · Triple-tap: Menu"
    private var isDirty: Boolean = true

    // Drawing resources
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val canvases = mutableMapOf<String, Canvas>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f  // Single line hint - larger for readability
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun initialize() {
        if (isInitialized) return
        // Initialize ring mesh
        ringMesh = StatusRingMesh()
        ringMesh?.initialize()

        // Create ring shader
        ringProgram = createRingShaderProgram()
        if (ringProgram == 0) {
            Log.e(TAG, "Failed to create ring shader program")
            return
        }

        ringUMVPMatrix = GLES30.glGetUniformLocation(ringProgram, "uMVPMatrix")
        ringUGlowColor = GLES30.glGetUniformLocation(ringProgram, "uGlowColor")
        ringUAlpha = GLES30.glGetUniformLocation(ringProgram, "uAlpha")

        // Create text shader
        textProgram = createTextShaderProgram()
        if (textProgram == 0) {
            Log.e(TAG, "Failed to create text shader program")
            return
        }

        textUMVPMatrix = GLES30.glGetUniformLocation(textProgram, "uMVPMatrix")
        textUTexture = GLES30.glGetUniformLocation(textProgram, "uTexture")
        textUGlowColor = GLES30.glGetUniformLocation(textProgram, "uGlowColor")

        // Create text billboard geometry
        createTextBillboards()

        // Create textures
        createTextures()

        isInitialized = true
        isDirty = true
    }

    private fun createRingShaderProgram(): Int {
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

            uniform vec3 uGlowColor;
            uniform float uAlpha;

            in vec2 vTexCoord;

            out vec4 fragColor;

            void main() {
                // v coordinate (0-1) goes around tube cross-section
                // Calculate distance from tube centerline for glow effect
                float tubePos = vTexCoord.y;

                // Map 0-1 to distance from center (0 at edges, 1 at center of visible part)
                // The tube is visible from outside, so we see v=0.25 to v=0.75 facing us
                float distFromCenter = abs(tubePos - 0.5) * 2.0;

                // Glow: bright core with soft falloff - increased intensity for visibility
                float core = 1.0 - smoothstep(0.0, 0.5, distFromCenter);
                float glow = 1.0 - smoothstep(0.0, 1.0, distFromCenter);
                float intensity = core * 1.2 + glow * 0.5;  // Boosted brightness

                // Apply glow color with extra brightness
                vec3 color = uGlowColor * intensity * 1.5;

                fragColor = vec4(color, clamp(intensity * uAlpha * 1.2, 0.0, 1.0));
            }
        """.trimIndent()

        return ShaderUtils.createProgram(vertexShader, fragmentShader)
    }

    private fun createTextShaderProgram(): Int {
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

            uniform sampler2D uTexture;
            uniform vec3 uGlowColor;

            in vec2 vTexCoord;

            out vec4 fragColor;

            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);

                // Text is white on transparent background
                // Apply glow color tint to the text
                vec3 glowText = mix(texColor.rgb, uGlowColor, 0.3);

                // Add subtle glow around text
                float glowIntensity = texColor.a * 0.5;
                vec3 finalColor = glowText + uGlowColor * glowIntensity * 0.2;

                fragColor = vec4(finalColor, texColor.a);
            }
        """.trimIndent()

        return ShaderUtils.createProgram(vertexShader, fragmentShader)
    }

    private fun createTextBillboards() {
        // Clean up existing buffers if rebuilding
        if (textVaoId > 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(textVaoId), 0)
            textVaoId = 0
        }
        if (textVboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(textVboId), 0)
            textVboId = 0
        }
        if (textIboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(textIboId), 0)
            textIboId = 0
        }

        // Create curved quads for each text zone
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        val zones = listOf(
            LEFT_ZONE_ANGLE to 0,
            CENTER_ZONE_ANGLE to 1,
            RIGHT_ZONE_ANGLE to 2
        )

        // Apply zoom scaling - scale radius and Y position with zoom
        // When zoom is 2x, monitor appears twice as close, so text should also be closer
        val scaledRadius = TEXT_RADIUS / currentZoom
        val scaledYPosition = TEXT_Y_POSITION / currentZoom
        val scaledHeight = 0.12f / currentZoom  // Billboard height scales with zoom

        var vertexOffset: Short = 0
        val segments = 8  // Segments per billboard for curve

        for ((centerAngle, _) in zones) {
            val halfArc = (TEXT_ARC_DEGREES * PI / 180f / 2f).toFloat()
            val centerRad = (centerAngle * PI / 180f).toFloat()

            for (i in 0..segments) {
                val t = i.toFloat() / segments
                val angle = centerRad - halfArc + t * halfArc * 2f

                val x = scaledRadius * sin(angle.toDouble()).toFloat()
                val z = -scaledRadius * cos(angle.toDouble()).toFloat()

                // Top vertex
                vertices.addAll(listOf(x, scaledYPosition + scaledHeight / 2f, z, t, 0f))
                // Bottom vertex
                vertices.addAll(listOf(x, scaledYPosition - scaledHeight / 2f, z, t, 1f))
            }

            // Generate indices for this billboard
            for (i in 0 until segments) {
                val topLeft = (vertexOffset + i * 2).toShort()
                val bottomLeft = (topLeft + 1).toShort()
                val topRight = (topLeft + 2).toShort()
                val bottomRight = (topLeft + 3).toShort()

                indices.addAll(listOf(topLeft, bottomLeft, topRight))
                indices.addAll(listOf(bottomLeft, bottomRight, topRight))
            }

            vertexOffset = (vertexOffset + (segments + 1) * 2).toShort()
        }

        textIndexCount = indices.size
        val vertexArray = vertices.toFloatArray()
        val indexArray = indices.toShortArray()

        // Create VAO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        textVaoId = vaos[0]
        GLES30.glBindVertexArray(textVaoId)

        // Create VBO
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        textVboId = vbos[0]

        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(vertexArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexArray)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexArray.size * 4, vertexBuffer, GLES30.GL_DYNAMIC_DRAW)

        // Create IBO
        val ibos = IntArray(1)
        GLES30.glGenBuffers(1, ibos, 0)
        textIboId = ibos[0]

        val indexBuffer: ShortBuffer = ByteBuffer
            .allocateDirect(indexArray.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indexArray)
        indexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, textIboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexArray.size * 2, indexBuffer, GLES30.GL_STATIC_DRAW)

        // Vertex attributes
        val stride = 5 * 4
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)

    }

    private fun createTextures() {
        val zones = listOf("left", "center", "right")
        val textureIds = IntArray(3)
        GLES30.glGenTextures(3, textureIds, 0)

        leftTextureId = textureIds[0]
        centerTextureId = textureIds[1]
        rightTextureId = textureIds[2]

        for ((index, zone) in zones.withIndex()) {
            val texId = textureIds[index]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val bmp = Bitmap.createBitmap(TEXT_TEXTURE_WIDTH, TEXT_TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmaps[zone] = bmp
            canvases[zone] = Canvas(bmp)
        }
    }

    fun setGlowColor(color: FloatArray) {
        if (color.size >= 3) {
            glowColor = floatArrayOf(color[0], color[1], color[2])
        }
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

    fun updateRadius(monitorRadius: Float) {
        ringMesh?.updateRadius(monitorRadius)
    }

    /**
     * Update zoom level for scaling status text with monitor.
     * Status text scales to stay at consistent relative position.
     */
    fun setZoom(zoom: Float) {
        if (zoom != currentZoom && zoom > 0f) {
            currentZoom = zoom
            needsGeometryRebuild = true
        }
    }

    private fun updateTextures() {
        if (!isDirty || !isInitialized) return

        // Update left texture (connection bars)
        bitmaps["left"]?.let { bmp ->
            canvases["left"]?.let { cvs ->
                bmp.eraseColor(Color.TRANSPARENT)

                val barWidth = 20f
                val barSpacing = 8f
                val barMaxHeight = 80f
                val startX = (TEXT_TEXTURE_WIDTH - (4 * barWidth + 3 * barSpacing)) / 2f
                val barY = (TEXT_TEXTURE_HEIGHT - barMaxHeight) / 2f

                for (i in 0 until 4) {
                    val height = barMaxHeight * (i + 1) / 4
                    barPaint.color = if (i < connectionQuality) {
                        when {
                            connectionQuality >= 3 -> Color.WHITE
                            connectionQuality >= 2 -> Color.argb(220, 255, 255, 200)
                            else -> Color.argb(200, 255, 200, 200)
                        }
                    } else {
                        Color.argb(60, 255, 255, 255)
                    }
                    cvs.drawRect(
                        startX + i * (barWidth + barSpacing),
                        barY + (barMaxHeight - height),
                        startX + i * (barWidth + barSpacing) + barWidth,
                        barY + barMaxHeight,
                        barPaint
                    )
                }

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, leftTextureId)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            }
        }

        // Update center texture (control hint - single line)
        bitmaps["center"]?.let { bmp ->
            canvases["center"]?.let { cvs ->
                bmp.eraseColor(Color.TRANSPARENT)

                // Single line hint centered vertically
                cvs.drawText(controlHint, TEXT_TEXTURE_WIDTH / 2f,
                    TEXT_TEXTURE_HEIGHT / 2f + hintPaint.textSize / 3f, hintPaint)

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, centerTextureId)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            }
        }

        // Update right texture (FPS)
        bitmaps["right"]?.let { bmp ->
            canvases["right"]?.let { cvs ->
                bmp.eraseColor(Color.TRANSPARENT)

                val fpsText = "$fps FPS"
                textPaint.color = when {
                    fps >= 55 -> Color.WHITE
                    fps >= 30 -> Color.argb(255, 255, 255, 150)
                    else -> Color.argb(255, 255, 150, 150)
                }

                cvs.drawText(fpsText, TEXT_TEXTURE_WIDTH / 2f, TEXT_TEXTURE_HEIGHT / 2f + textPaint.textSize / 3f, textPaint)

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rightTextureId)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            }
        }

        isDirty = false
    }

    private var drawCallCount = 0

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized) {
            Log.w(TAG, "draw() called but not initialized!")
            return
        }

        drawCallCount++
        if (drawCallCount % 60 == 1) {
        }

        // Rebuild geometry if zoom changed
        if (needsGeometryRebuild) {
            needsGeometryRebuild = false
            createTextBillboards()
        }

        // Update textures if needed
        updateTextures()

        // Enable blending
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        // Calculate MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Ring disabled - just showing status text below monitor
        // drawRing()

        // Draw text billboards
        drawTextBillboards()

        // Restore state
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawRing() {
        if (ringProgram == 0) return

        // Use additive blending for glow effect
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)

        GLES30.glUseProgram(ringProgram)
        GLES30.glUniformMatrix4fv(ringUMVPMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform3fv(ringUGlowColor, 1, glowColor, 0)
        GLES30.glUniform1f(ringUAlpha, 1.0f)  // Full opacity for visibility

        ringMesh?.draw()

        // Restore normal blending for text
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawTextBillboards() {
        if (textProgram == 0 || textVaoId == 0) return

        GLES30.glUseProgram(textProgram)
        GLES30.glUniformMatrix4fv(textUMVPMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform3fv(textUGlowColor, 1, glowColor, 0)

        GLES30.glBindVertexArray(textVaoId)

        // Each billboard has (segments + 1) * 2 vertices = 18 vertices,
        // and segments * 6 indices = 48 indices
        val segments = 8
        val indicesPerBillboard = segments * 6

        // Draw left (connection bars)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, leftTextureId)
        GLES30.glUniform1i(textUTexture, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indicesPerBillboard, GLES30.GL_UNSIGNED_SHORT, 0)

        // Draw center (hints)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, centerTextureId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indicesPerBillboard, GLES30.GL_UNSIGNED_SHORT, indicesPerBillboard * 2)

        // Draw right (FPS)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rightTextureId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indicesPerBillboard, GLES30.GL_UNSIGNED_SHORT, indicesPerBillboard * 4)

        GLES30.glBindVertexArray(0)
    }

    fun release() {
        ringMesh?.release()
        ringMesh = null

        if (textVaoId > 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(textVaoId), 0)
            textVaoId = 0
        }
        if (textVboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(textVboId), 0)
            textVboId = 0
        }
        if (textIboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(textIboId), 0)
            textIboId = 0
        }

        val textures = intArrayOf(leftTextureId, centerTextureId, rightTextureId)
        GLES30.glDeleteTextures(3, textures, 0)
        leftTextureId = 0
        centerTextureId = 0
        rightTextureId = 0

        if (ringProgram > 0) {
            GLES30.glDeleteProgram(ringProgram)
            ringProgram = 0
        }
        if (textProgram > 0) {
            GLES30.glDeleteProgram(textProgram)
            textProgram = 0
        }

        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
        canvases.clear()

        isInitialized = false
    }

    fun onContextLost() {
        isInitialized = false
        ringProgram = 0
        textProgram = 0
        textVaoId = 0
        textVboId = 0
        textIboId = 0
        leftTextureId = 0
        centerTextureId = 0
        rightTextureId = 0
        isDirty = true
    }

    fun isInitialized(): Boolean = isInitialized
}
