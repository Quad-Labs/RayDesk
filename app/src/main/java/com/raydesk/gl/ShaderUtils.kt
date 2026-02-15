// app/src/main/java/com/raydesk/gl/ShaderUtils.kt
package com.raydesk.gl

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OpenGL shader compilation utilities.
 */
object ShaderUtils {

    private const val TAG = "ShaderUtils"

    /**
     * Create shader program from vertex and fragment shader sources.
     */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

        if (vertexShader == 0 || fragmentShader == 0) {
            return 0
        }

        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "Could not create program")
            return 0
        }

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            val info = GLES30.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link failed: $info")
            GLES30.glDeleteProgram(program)
            return 0
        }

        // Delete shaders after linking
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "Could not create shader type $type")
            return 0
        }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile failed: $info")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    /**
     * Load shader source from raw resource.
     */
    fun loadShaderFromResource(context: Context, resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val builder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            builder.append(line).append('\n')
        }
        reader.close()
        return builder.toString()
    }

    /**
     * Check for OpenGL errors.
     */
    fun checkGlError(op: String) {
        var error: Int
        while (GLES30.glGetError().also { error = it } != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError 0x${Integer.toHexString(error)}")
        }
    }
}
