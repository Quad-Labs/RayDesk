package com.raydesk.gl.environment

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import com.raydesk.data.DomeStyle
import com.raydesk.data.EnvironmentTheme
import com.raydesk.data.EnvironmentThemes

/**
 * Orchestrates rendering of all environment elements.
 *
 * Architecture:
 * 1. Skybox - Background (solid black, gradient, or starfield based on theme)
 * 2. [Monitor content rendered by StreamRenderer]
 * 3. Status Ring - 360° glowing ring with curved text billboards
 *
 * Components:
 * - SkyboxRenderer: Theme-based backgrounds
 * - StatusRingRenderer: Glowing ring + FPS, connection, hints
 */
class EnvironmentRenderer(
    private val context: Context,
    private var theme: EnvironmentTheme = EnvironmentThemes.DEFAULT
) {
    companion object {
        private const val TAG = "EnvironmentRenderer"
    }

    // Renderers
    private var skyboxRenderer: SkyboxRenderer? = null
    private var statusRingRenderer: StatusRingRenderer? = null

    // Legacy components (kept for backwards compatibility)
    private var frameRenderer: PhysicalFrameRenderer? = null
    private var textRenderer: StatusRingTextRenderer? = null

    private var isInitialized = false

    /**
     * Initialize GL resources.
     * Must be called on GL thread after EGL context created.
     */
    fun initialize() {
        if (isInitialized) {
            return
        }
        // Initialize skybox
        skyboxRenderer = SkyboxRenderer(context).also {
            it.initialize()
            it.backgroundDimmer = 0.5f
        }

        // Initialize status ring (replaces dashboard)
        statusRingRenderer = StatusRingRenderer(context).also {
            it.initialize()
        }

        // Apply current theme
        applyTheme(theme)

        isInitialized = true
    }

    /**
     * Release GL resources.
     */
    fun release() {

        skyboxRenderer?.release()
        skyboxRenderer = null

        statusRingRenderer?.release()
        statusRingRenderer = null

        frameRenderer?.release()
        frameRenderer = null

        textRenderer?.release()
        textRenderer = null

        isInitialized = false
    }

    /**
     * Called when GL context is lost.
     */
    fun onContextLost() {
        isInitialized = false
        skyboxRenderer?.onContextLost()
        statusRingRenderer?.onContextLost()
        frameRenderer?.onContextLost()
        textRenderer?.invalidate()
    }

    /**
     * Draw background elements (skybox).
     * Called BEFORE monitor rendering.
     */
    fun drawBackground(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized) return
        skyboxRenderer?.draw(viewMatrix, projectionMatrix)
    }

    /**
     * Draw frame around monitor (currently disabled - using shader bezel glow).
     */
    fun drawFrame(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // Physical frame disabled - shader bezel glow provides cleaner look
    }

    /**
     * Draw foreground elements.
     * Called AFTER monitor rendering.
     *
     * Note: Status ring disabled - replaced by screen-space HUD in StreamRenderer.
     */
    fun drawForeground(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // Status ring disabled - using screen-space HUD instead
        // statusRingRenderer?.draw(viewMatrix, projectionMatrix)
    }

    /**
     * Legacy draw method for backwards compatibility.
     */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!isInitialized) return
        drawBackground(viewMatrix, projectionMatrix)
    }

    /**
     * Update monitor parameters for ring positioning.
     */
    fun updateMonitorParams(radius: Float, arcAngle: Float, height: Float) {
        statusRingRenderer?.updateRadius(radius)
    }

    /**
     * Legacy method - delegates to updateMonitorParams.
     */
    fun updateRingRadius(monitorRadius: Float) {
        updateMonitorParams(monitorRadius, (60f * Math.PI / 180f).toFloat(), 1.0f)
    }

    /**
     * Set theme and apply to all components.
     */
    fun setTheme(newTheme: EnvironmentTheme) {
        theme = newTheme
        if (isInitialized) {
            applyTheme(newTheme)
        }
    }

    private fun applyTheme(theme: EnvironmentTheme) {

        // Configure skybox based on dome style
        skyboxRenderer?.let { skybox ->
            when (theme.domeStyle) {
                DomeStyle.NONE -> {
                    skybox.setTheme(SkyboxRenderer.SkyboxTheme.NONE)
                }
                DomeStyle.STARFIELD -> {
                    skybox.setTheme(SkyboxRenderer.SkyboxTheme.STARFIELD)
                }
                DomeStyle.SOLID_GRADIENT -> {
                    skybox.setGradientColorsFromInt(
                        theme.domeColors.horizonColor,
                        theme.domeColors.zenithColor
                    )
                    skybox.setTheme(SkyboxRenderer.SkyboxTheme.GRADIENT)
                }
            }
        } ?: Log.w(TAG, "skyboxRenderer is null!")

        // Configure status ring glow color
        val ringColor = theme.ringColorFloat()
        statusRingRenderer?.setGlowColor(ringColor) ?: Log.w(TAG, "statusRingRenderer is null!")

    }

    /**
     * Get the current theme's bezel/ring color for StreamRenderer.
     */
    fun getBezelColor(): FloatArray = theme.bezelColorFloat()

    /**
     * Set skybox background dimmer (0.0 = black, 1.0 = full brightness).
     */
    fun setSkyboxDimmer(dimmer: Float) {
        skyboxRenderer?.backgroundDimmer = dimmer.coerceIn(0f, 1f)
    }

    /**
     * Set skybox theme directly.
     */
    fun setSkyboxTheme(skyboxTheme: SkyboxRenderer.SkyboxTheme) {
        skyboxRenderer?.setTheme(skyboxTheme)
    }

    /**
     * Set frame material style (legacy).
     */
    fun setFrameMaterial(material: PhysicalFrameRenderer.FrameMaterial) {
        frameRenderer?.setMaterial(material)
    }

    // HUD methods
    fun setFps(fps: Int) {
        statusRingRenderer?.setFps(fps)
        textRenderer?.setFps(fps)
    }

    fun setConnectionQuality(quality: Int) {
        statusRingRenderer?.setConnectionQuality(quality)
        textRenderer?.setQuality(quality)
    }

    fun setControlHint(hint: String) {
        statusRingRenderer?.setControlHint(hint)
        textRenderer?.setHint(hint)
    }

    /**
     * Update zoom level for status text scaling.
     * Status text scales with zoom to maintain relative position to monitor.
     */
    fun setZoom(zoom: Float) {
        statusRingRenderer?.setZoom(zoom)
    }

    fun isInitialized(): Boolean = isInitialized

    /**
     * Get current theme.
     */
    fun getTheme(): EnvironmentTheme = theme
}
