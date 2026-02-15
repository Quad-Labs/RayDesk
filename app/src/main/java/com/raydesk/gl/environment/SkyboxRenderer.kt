package com.raydesk.gl.environment

import android.content.Context
import android.opengl.GLES30
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
 * Renders skybox using equirectangular panorama textures.
 *
 * Replaces the procedural dome with asset-based rendering for
 * a polished "Virtual Desktop" look.
 *
 * Supports:
 * - 4K equirectangular panoramas (JPG, PNG)
 * - Background dimming to make monitor pop
 * - AR mode (black = transparent) vs Immersive mode
 */
class SkyboxRenderer(private val context: Context) {

    companion object {
        private const val TAG = "SkyboxRenderer"
        private const val SPHERE_RADIUS = 50f  // Large sphere around user
        private const val SEGMENTS_H = 48
        private const val SEGMENTS_V = 24
    }

    // GL resources
    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var iboId: Int = 0
    private var textureId: Int = 0
    private var shaderProgram: Int = 0
    private var isInitialized: Boolean = false
    private var indexCount: Int = 0

    // Uniform locations
    private var uMVPMatrix: Int = -1
    private var uSkyTexture: Int = -1
    private var uDimmer: Int = -1
    private var uRotation: Int = -1
    private var uTime: Int = -1

    // Settings
    var backgroundDimmer: Float = 0.6f  // Dim background so monitor pops
    var rotationOffset: Float = 0f       // Rotate skybox horizontally

    // Animation
    private var startTimeMs: Long = System.currentTimeMillis()
    var animationEnabled: Boolean = true  // Enable slow rotation

    // Working matrices
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // Available skybox themes (uses procedural fallback if texture not found)
    enum class SkyboxTheme(val displayName: String) {
        NONE("None"),           // Solid black (Default theme)
        STARFIELD("Starfield"), // Stars on dark background (Starry Night)
        GRADIENT("Gradient"),   // Gradient horizon (Sunset theme)
        NEBULA("Nebula"),
        VOID("Void")
    }

    private var currentTheme: SkyboxTheme = SkyboxTheme.STARFIELD

    // Custom gradient colors (for GRADIENT theme)
    private var horizonColor = intArrayOf(0xF9, 0x73, 0x16)  // Orange
    private var zenithColor = intArrayOf(0x6B, 0x21, 0xA8)   // Purple

    fun initialize() {
        if (isInitialized) return
        // Create shader program
        shaderProgram = createSkyboxProgram()
        if (shaderProgram == 0) {
            Log.e(TAG, "Failed to create skybox shader program")
            return
        }

        // Get uniform locations
        uMVPMatrix = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        uSkyTexture = GLES30.glGetUniformLocation(shaderProgram, "uSkyTexture")
        uDimmer = GLES30.glGetUniformLocation(shaderProgram, "uDimmer")
        uRotation = GLES30.glGetUniformLocation(shaderProgram, "uRotation")
        uTime = GLES30.glGetUniformLocation(shaderProgram, "uTime")

        // Create sphere geometry
        createSphereGeometry()

        // Load default texture
        loadTexture(currentTheme)

        isInitialized = true
    }

    private fun createSkyboxProgram(): Int {
        val vertexShader = """
            #version 300 es

            layout(location = 0) in vec3 aPosition;

            uniform mat4 uMVPMatrix;
            uniform float uRotation;
            uniform float uTime;

            out vec3 vWorldPos;

            void main() {
                // Combine manual rotation with time-based slow drift
                // Slow rotation: 1 full revolution per 20 minutes = 0.005 rad/s
                float animRotation = uRotation + uTime * 0.005;
                float c = cos(animRotation);
                float s = sin(animRotation);

                // Apply rotation to world position for texture lookup
                vec3 rotatedPos = vec3(
                    aPosition.x * c - aPosition.z * s,
                    aPosition.y,
                    aPosition.x * s + aPosition.z * c
                );
                vWorldPos = rotatedPos;

                // Transform position
                vec4 pos = uMVPMatrix * vec4(aPosition, 1.0);

                // RENDER AT INFINITY: Force z to far plane (z = w)
                // This ensures skybox never moves when user walks, only rotates
                gl_Position = vec4(pos.xy, pos.w * 0.9999, pos.w);
            }
        """.trimIndent()

        val fragmentShader = """
            #version 300 es

            precision mediump float;

            uniform sampler2D uSkyTexture;
            uniform float uDimmer;

            in vec3 vWorldPos;

            out vec4 fragColor;

            const float PI = 3.14159265359;

            void main() {
                // Convert world position to equirectangular UV
                vec3 dir = normalize(vWorldPos);

                // Spherical to UV mapping
                float u = atan(dir.z, dir.x) / (2.0 * PI) + 0.5;
                float v = asin(clamp(dir.y, -1.0, 1.0)) / PI + 0.5;

                // Sample texture
                vec4 skyColor = texture(uSkyTexture, vec2(u, v));

                // Apply dimming for monitor contrast
                skyColor.rgb *= uDimmer;

                fragColor = skyColor;
            }
        """.trimIndent()

        return ShaderUtils.createProgram(vertexShader, fragmentShader)
    }

    private fun createSphereGeometry() {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        // Generate sphere vertices (inside-facing for skybox)
        for (v in 0..SEGMENTS_V) {
            val phi = (v.toFloat() / SEGMENTS_V) * PI.toFloat()
            val y = SPHERE_RADIUS * cos(phi.toDouble()).toFloat()
            val ringRadius = SPHERE_RADIUS * sin(phi.toDouble()).toFloat()

            for (h in 0..SEGMENTS_H) {
                val theta = (h.toFloat() / SEGMENTS_H) * 2f * PI.toFloat()
                val x = ringRadius * cos(theta.toDouble()).toFloat()
                val z = ringRadius * sin(theta.toDouble()).toFloat()

                vertices.addAll(listOf(x, y, z))
            }
        }

        // Generate indices (reversed winding for inside-facing)
        for (v in 0 until SEGMENTS_V) {
            for (h in 0 until SEGMENTS_H) {
                val topLeft = (v * (SEGMENTS_H + 1) + h).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = (topLeft + SEGMENTS_H + 1).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                // Reversed winding for inside view
                indices.addAll(listOf(topLeft, bottomLeft, topRight))
                indices.addAll(listOf(topRight, bottomLeft, bottomRight))
            }
        }

        indexCount = indices.size
        val vertexArray = vertices.toFloatArray()
        val indexArray = indices.toShortArray()

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
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexArray.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)

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

        // Position attribute only (location 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, 0)
        GLES30.glEnableVertexAttribArray(0)

        GLES30.glBindVertexArray(0)

    }

    fun loadTexture(theme: SkyboxTheme) {
        currentTheme = theme

        // Delete old texture
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }

        // Create new texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Create procedural texture based on theme
        createProceduralTexture(theme)
    }

    private fun createProceduralTexture(theme: SkyboxTheme) {
        val width = 512
        val height = 256
        val pixels = IntArray(width * height)

        // Handle NONE theme - solid black
        if (theme == SkyboxTheme.NONE) {
            for (i in pixels.indices) {
                pixels[i] = (0xFF shl 24) // Solid black (ABGR)
            }
            val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
            buffer.asIntBuffer().put(pixels)
            buffer.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            return
        }

        // Theme-specific colors
        val (themeHorizon, themeZenith, starColor, hasStars) = when (theme) {
            SkyboxTheme.STARFIELD -> Quadruple(
                intArrayOf(0x1A, 0x1A, 0x40),  // Purple horizon
                intArrayOf(0x08, 0x08, 0x20),  // Deep purple zenith
                intArrayOf(0xFF, 0xFF, 0xFF),  // White stars
                true
            )
            SkyboxTheme.GRADIENT -> Quadruple(
                horizonColor,                   // Custom horizon (from theme)
                zenithColor,                    // Custom zenith (from theme)
                intArrayOf(0, 0, 0),            // No stars
                false
            )
            SkyboxTheme.NEBULA -> Quadruple(
                intArrayOf(0x2A, 0x10, 0x40),  // Magenta horizon
                intArrayOf(0x10, 0x05, 0x30),  // Deep purple zenith
                intArrayOf(0xFF, 0xCC, 0xFF),  // Pink stars
                true
            )
            SkyboxTheme.VOID -> Quadruple(
                intArrayOf(0x10, 0x12, 0x18),  // Dark gray horizon
                intArrayOf(0x05, 0x05, 0x08),  // Near black zenith
                intArrayOf(0xCC, 0xCC, 0xDD),  // Dim stars
                true
            )
            SkyboxTheme.NONE -> Quadruple(
                intArrayOf(0, 0, 0),
                intArrayOf(0, 0, 0),
                intArrayOf(0, 0, 0),
                false
            )
        }

        // Simple hash for procedural stars
        fun hash(x: Int, y: Int): Float {
            val n = x * 127 + y * 311
            return ((kotlin.math.sin(n.toDouble()) * 43758.5453) % 1.0).toFloat().let { kotlin.math.abs(it) }
        }

        for (y in 0 until height) {
            val t = y.toFloat() / height
            // Gradient from horizon (bottom) to zenith (top)
            val r = (themeHorizon[0] + (themeZenith[0] - themeHorizon[0]) * t).toInt()
            val g = (themeHorizon[1] + (themeZenith[1] - themeHorizon[1]) * t).toInt()
            val b = (themeHorizon[2] + (themeZenith[2] - themeHorizon[2]) * t).toInt()

            for (x in 0 until width) {
                var finalR = r
                var finalG = g
                var finalB = b

                // Add procedural stars (only for themes with stars)
                if (hasStars && t > 0.15f) {
                    // Use individual pixel hash for point-like stars
                    val starHash = hash(x, y)
                    val starThreshold = 0.992f  // ~0.8% of pixels are stars
                    if (starHash > starThreshold) {
                        val brightness = ((starHash - starThreshold) / (1f - starThreshold) * 255).toInt()
                        // Vary star brightness more naturally
                        val intensityMod = if (hash(x + 1000, y) > 0.5f) 1.0f else 0.6f
                        val finalBrightness = (brightness * intensityMod).toInt()
                        finalR = minOf(255, finalR + starColor[0] * finalBrightness / 255)
                        finalG = minOf(255, finalG + starColor[1] * finalBrightness / 255)
                        finalB = minOf(255, finalB + starColor[2] * finalBrightness / 255)
                    }
                }

                // ABGR format for OpenGL
                pixels[y * width + x] = (0xFF shl 24) or (finalB shl 16) or (finalG shl 8) or finalR
            }
        }

        val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
        buffer.asIntBuffer().put(pixels)
        buffer.position(0)

        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    // Helper class for 4-tuple returns
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized || shaderProgram == 0) return

        // Skybox renders behind everything - disable depth write
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        // Enable depth test but skybox will always pass (z forced to far plane in shader)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        GLES30.glUseProgram(shaderProgram)

        // Calculate MVP (view without translation for infinite distance effect)
        val viewNoTranslation = viewMatrix.copyOf()
        viewNoTranslation[12] = 0f
        viewNoTranslation[13] = 0f
        viewNoTranslation[14] = 0f

        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewNoTranslation, 0)

        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(uDimmer, backgroundDimmer)
        GLES30.glUniform1f(uRotation, rotationOffset)

        // Time uniform for slow rotation animation
        val elapsedSeconds = if (animationEnabled) {
            (System.currentTimeMillis() - startTimeMs) / 1000f
        } else {
            0f
        }
        GLES30.glUniform1f(uTime, elapsedSeconds)

        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uSkyTexture, 0)

        // Draw
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)

        // Restore state
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthFunc(GLES30.GL_LESS)  // Restore default depth func
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
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (shaderProgram > 0) {
            GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        isInitialized = false
    }

    fun onContextLost() {
        isInitialized = false
        vaoId = 0
        vboId = 0
        iboId = 0
        textureId = 0
        shaderProgram = 0
    }

    fun setTheme(theme: SkyboxTheme) {
        if (isInitialized && theme != currentTheme) {
            loadTexture(theme)
        } else {
            currentTheme = theme
        }
    }

    /**
     * Set custom gradient colors for GRADIENT theme.
     * Colors should be RGB int arrays [R, G, B] with values 0-255.
     */
    fun setGradientColors(horizon: IntArray, zenith: IntArray) {
        if (horizon.size >= 3 && zenith.size >= 3) {
            horizonColor = horizon.copyOf()
            zenithColor = zenith.copyOf()
            // Regenerate texture if using gradient theme
            if (isInitialized && currentTheme == SkyboxTheme.GRADIENT) {
                loadTexture(SkyboxTheme.GRADIENT)
            }
        }
    }

    /**
     * Set gradient colors from Android Color ints.
     */
    fun setGradientColorsFromInt(horizonColorInt: Int, zenithColorInt: Int) {
        horizonColor = intArrayOf(
            android.graphics.Color.red(horizonColorInt),
            android.graphics.Color.green(horizonColorInt),
            android.graphics.Color.blue(horizonColorInt)
        )
        zenithColor = intArrayOf(
            android.graphics.Color.red(zenithColorInt),
            android.graphics.Color.green(zenithColorInt),
            android.graphics.Color.blue(zenithColorInt)
        )
        // Regenerate texture if using gradient theme
        if (isInitialized && currentTheme == SkyboxTheme.GRADIENT) {
            loadTexture(SkyboxTheme.GRADIENT)
        }
    }

    fun isInitialized(): Boolean = isInitialized
}
