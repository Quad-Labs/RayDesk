package com.raydesk.gl.environment

import android.content.Context
import android.util.Log
import com.raydesk.gl.ShaderUtils
import com.raydesk.test.R

/**
 * Shader program compilation for environment elements.
 */
object EnvironmentShaders {
    private const val TAG = "EnvironmentShaders"

    /**
     * Create dome shader program.
     *
     * @param context Android context for resource loading
     * @return Shader program ID, or 0 on failure
     */
    fun createDomeProgram(context: Context): Int {
        val vertexSource = ShaderUtils.loadShaderFromResource(
            context, R.raw.environment_dome_vertex
        )
        val fragmentSource = ShaderUtils.loadShaderFromResource(
            context, R.raw.environment_dome_fragment
        )

        val program = ShaderUtils.createProgram(vertexSource, fragmentSource)
        if (program == 0) {
            Log.e(TAG, "Failed to create dome shader program")
        }
        return program
    }

    /**
     * Create ring shader program.
     *
     * @param context Android context for resource loading
     * @return Shader program ID, or 0 on failure
     */
    fun createRingProgram(context: Context): Int {
        val vertexSource = ShaderUtils.loadShaderFromResource(
            context, R.raw.environment_ring_vertex
        )
        val fragmentSource = ShaderUtils.loadShaderFromResource(
            context, R.raw.environment_ring_fragment
        )

        val program = ShaderUtils.createProgram(vertexSource, fragmentSource)
        if (program == 0) {
            Log.e(TAG, "Failed to create ring shader program")
        }
        return program
    }
}
