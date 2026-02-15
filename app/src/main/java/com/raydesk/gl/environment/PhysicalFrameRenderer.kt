package com.raydesk.gl.environment

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.raydesk.gl.ShaderUtils

/**
 * Renders the 3D physical monitor frame with glossy material.
 *
 * Uses MatCap-style shading to give the frame a "brushed metal" or
 * "glossy plastic" appearance. As the user moves their head, a subtle
 * specular highlight moves across the frame, communicating "physical object."
 *
 * Material options:
 * - GLOSSY_BLACK: Dark plastic, subtle reflection
 * - BRUSHED_METAL: Silver with directional highlights
 * - MATTE: Soft diffuse, no highlights
 */
class PhysicalFrameRenderer(private val context: Context) {

    companion object {
        private const val TAG = "PhysicalFrameRenderer"
    }

    // GL resources
    private var frameMesh: MonitorFrameMesh? = null
    private var shaderProgram: Int = 0
    private var isInitialized: Boolean = false

    // Uniform locations
    private var uMVPMatrix: Int = -1
    private var uModelMatrix: Int = -1
    private var uViewDir: Int = -1
    private var uBaseColor: Int = -1
    private var uSpecularColor: Int = -1
    private var uGlossiness: Int = -1
    private var uAmbient: Int = -1

    // Working matrices
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Material settings
    var baseColor = floatArrayOf(0.08f, 0.08f, 0.1f)     // Dark gray/black
    var specularColor = floatArrayOf(0.4f, 0.45f, 0.5f)  // Bluish highlight
    var glossiness = 32f                                   // Shininess exponent
    var ambient = 0.15f                                    // Ambient light level

    enum class FrameMaterial {
        GLOSSY_BLACK,
        BRUSHED_METAL,
        MATTE
    }

    fun initialize() {
        if (isInitialized) return
        // Create shader program
        shaderProgram = createFrameShaderProgram()
        if (shaderProgram == 0) {
            Log.e(TAG, "Failed to create frame shader program")
            return
        }

        // Get uniform locations
        uMVPMatrix = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        uModelMatrix = GLES30.glGetUniformLocation(shaderProgram, "uModelMatrix")
        uViewDir = GLES30.glGetUniformLocation(shaderProgram, "uViewDir")
        uBaseColor = GLES30.glGetUniformLocation(shaderProgram, "uBaseColor")
        uSpecularColor = GLES30.glGetUniformLocation(shaderProgram, "uSpecularColor")
        uGlossiness = GLES30.glGetUniformLocation(shaderProgram, "uGlossiness")
        uAmbient = GLES30.glGetUniformLocation(shaderProgram, "uAmbient")

        // Create frame mesh
        frameMesh = MonitorFrameMesh()
        frameMesh?.initialize()

        Matrix.setIdentityM(modelMatrix, 0)

        isInitialized = true
    }

    private fun createFrameShaderProgram(): Int {
        val vertexShader = """
            #version 300 es

            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;

            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;

            out vec3 vWorldPos;
            out vec3 vNormal;

            void main() {
                vec4 worldPos = uModelMatrix * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(uModelMatrix) * aNormal;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentShader = """
            #version 300 es

            precision mediump float;

            uniform vec3 uViewDir;      // View direction (camera forward)
            uniform vec3 uBaseColor;    // Base material color
            uniform vec3 uSpecularColor; // Specular highlight color
            uniform float uGlossiness;  // Shininess exponent
            uniform float uAmbient;     // Ambient light level

            in vec3 vWorldPos;
            in vec3 vNormal;

            out vec4 fragColor;

            void main() {
                vec3 normal = normalize(vNormal);

                // Fake light from above-front
                vec3 lightDir = normalize(vec3(0.3, 0.8, -0.5));

                // Diffuse lighting (Lambert)
                float diffuse = max(dot(normal, lightDir), 0.0);

                // Specular lighting (Blinn-Phong)
                vec3 viewDir = normalize(-uViewDir);
                vec3 halfDir = normalize(lightDir + viewDir);
                float specAngle = max(dot(normal, halfDir), 0.0);
                float specular = pow(specAngle, uGlossiness);

                // Rim lighting for edge definition
                float rim = 1.0 - max(dot(normal, viewDir), 0.0);
                rim = pow(rim, 3.0) * 0.3;

                // Final color composition
                vec3 color = uBaseColor * (uAmbient + diffuse * 0.6);
                color += uSpecularColor * specular * 0.5;
                color += uSpecularColor * rim;

                // Subtle gradient based on normal direction for depth
                float normalGradient = (normal.y + 1.0) * 0.5;
                color *= 0.85 + normalGradient * 0.3;

                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()

        return ShaderUtils.createProgram(vertexShader, fragmentShader)
    }

    fun setMaterial(material: FrameMaterial) {
        when (material) {
            FrameMaterial.GLOSSY_BLACK -> {
                baseColor = floatArrayOf(0.08f, 0.08f, 0.1f)
                specularColor = floatArrayOf(0.5f, 0.55f, 0.6f)
                glossiness = 48f
                ambient = 0.12f
            }
            FrameMaterial.BRUSHED_METAL -> {
                baseColor = floatArrayOf(0.3f, 0.32f, 0.35f)
                specularColor = floatArrayOf(0.7f, 0.75f, 0.8f)
                glossiness = 24f
                ambient = 0.2f
            }
            FrameMaterial.MATTE -> {
                baseColor = floatArrayOf(0.15f, 0.15f, 0.18f)
                specularColor = floatArrayOf(0.2f, 0.2f, 0.22f)
                glossiness = 8f
                ambient = 0.25f
            }
        }
    }

    /**
     * Update frame to match monitor dimensions.
     */
    fun updateMonitorParams(radius: Float, arcAngle: Float, height: Float) {
        frameMesh?.updateMonitorParams(radius, arcAngle, height)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized || shaderProgram == 0) return

        GLES30.glUseProgram(shaderProgram)

        // Enable depth testing, disable blending for opaque frame
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Disable face culling - frame has multiple face orientations
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Calculate MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uModelMatrix, 1, false, modelMatrix, 0)

        // Extract view direction from view matrix (camera forward = -Z in view space)
        val viewDir = floatArrayOf(-viewMatrix[2], -viewMatrix[6], -viewMatrix[10])
        GLES30.glUniform3fv(uViewDir, 1, viewDir, 0)

        // Material properties
        GLES30.glUniform3fv(uBaseColor, 1, baseColor, 0)
        GLES30.glUniform3fv(uSpecularColor, 1, specularColor, 0)
        GLES30.glUniform1f(uGlossiness, glossiness)
        GLES30.glUniform1f(uAmbient, ambient)

        // Draw frame
        frameMesh?.draw()

        // Restore culling state
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun release() {
        frameMesh?.release()
        frameMesh = null

        if (shaderProgram > 0) {
            GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }

        isInitialized = false
    }

    fun onContextLost() {
        isInitialized = false
        shaderProgram = 0
    }

    fun isInitialized(): Boolean = isInitialized
}
