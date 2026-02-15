package com.raydesk.gl.environment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log

/**
 * Renders HUD text to texture for status ring.
 *
 * Uses dirty flag pattern to avoid per-frame texture uploads.
 * Text only regenerated when values actually change.
 */
class StatusRingTextRenderer(private val context: Context) {
    companion object {
        private const val TAG = "StatusRingTextRenderer"
        private const val TEXTURE_WIDTH = 512
        private const val TEXTURE_HEIGHT = 64
    }

    private var textureId: Int = 0
    private var isInitialized: Boolean = false
    private var isDirty: Boolean = true

    // Cached values
    private var lastFps: Int = -1
    private var lastQuality: Int = -1
    private var lastHint: String = ""

    // Drawing resources
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val qualityPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    fun initialize() {

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

        isInitialized = true
        isDirty = true
    }

    /**
     * Update FPS display.
     */
    fun setFps(fps: Int) {
        if (fps != lastFps) {
            lastFps = fps
            isDirty = true
        }
    }

    /**
     * Update connection quality (0-4).
     */
    fun setQuality(quality: Int) {
        if (quality != lastQuality) {
            lastQuality = quality
            isDirty = true
        }
    }

    /**
     * Update control hint text.
     */
    fun setHint(hint: String) {
        if (hint != lastHint) {
            lastHint = hint
            isDirty = true
        }
    }

    /**
     * Update texture if dirty.
     *
     * @return true if texture was updated
     */
    fun updateTextureIfNeeded(): Boolean {
        if (!isDirty || !isInitialized) return false

        val bmp = bitmap ?: return false
        val cvs = canvas ?: return false

        // Clear bitmap
        bmp.eraseColor(Color.TRANSPARENT)

        // Draw quality bars (left)
        val barWidth = 8f
        val barSpacing = 4f
        val barMaxHeight = 40f
        val barY = 12f
        for (i in 0 until 4) {
            val height = barMaxHeight * (i + 1) / 4
            qualityPaint.color = if (i < lastQuality) Color.WHITE else Color.argb(64, 255, 255, 255)
            cvs.drawRect(
                20f + i * (barWidth + barSpacing),
                barY + (barMaxHeight - height),
                20f + i * (barWidth + barSpacing) + barWidth,
                barY + barMaxHeight,
                qualityPaint
            )
        }

        // Draw hint (center)
        val hintWidth = textPaint.measureText(lastHint)
        cvs.drawText(lastHint, (TEXTURE_WIDTH - hintWidth) / 2, 40f, textPaint)

        // Draw FPS (right)
        val fpsText = "$lastFps FPS"
        val fpsWidth = textPaint.measureText(fpsText)
        cvs.drawText(fpsText, TEXTURE_WIDTH - fpsWidth - 20f, 40f, textPaint)

        // Upload to texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)

        isDirty = false
        return true
    }

    /**
     * Called when GL context is lost.
     */
    fun invalidate() {
        textureId = 0
        isInitialized = false
        isDirty = true
    }

    fun release() {
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        bitmap?.recycle()
        bitmap = null
        canvas = null
        isInitialized = false
    }

    fun getTextureId(): Int = textureId
    fun isInitialized(): Boolean = isInitialized
}
