// app/src/main/java/com/raydesk/video/VideoTextureProvider.kt
package com.raydesk.video

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface

/**
 * Manages SurfaceTexture for video frame reception.
 * Provides OES texture for OpenGL rendering.
 *
 * Implements SurfaceProvider so it can be used with VideoSurfaceHolder
 * for Moonlight integration.
 */
class VideoTextureProvider : SurfaceProvider {

    companion object {
        private const val TAG = "VideoTexture"
    }

    private var textureId: Int = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    // Initialize with identity matrix so shader works before first frame
    private val transformMatrix = FloatArray(16).apply {
        android.opengl.Matrix.setIdentityM(this, 0)
    }

    private var frameAvailable = false
    private val frameLock = Object()
    private var frameArrivedCount = 0L  // Changed to Long for FrameSlot compatibility
    private var frameConsumedCount = 0
    private var lastLogTime = 0L

    // FrameSlot for decoupled rendering (optional, set externally)
    private var frameSlot: FrameSlot? = null

    /**
     * Set the FrameSlot for decoupled rendering.
     * When set, frame arrivals are published to the slot for the render thread to consume.
     *
     * @param slot The FrameSlot to publish to, or null to disable
     */
    fun setFrameSlot(slot: FrameSlot?) {
        frameSlot = slot
    }

    /**
     * Initialize OpenGL texture and SurfaceTexture.
     * Must be called on GL thread after EGL context is created.
     */
    fun initialize() {
        // Generate OES texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        if (textureId <= 0) {
            throw RuntimeException("Failed to generate texture")
        }

        // Configure texture
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        // Create SurfaceTexture attached to this texture
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameArrivedCount++
                    // Log first few frames to confirm video is arriving
                    if (frameArrivedCount <= 5L || frameArrivedCount % 100L == 0L) {
                    }
                }
                // Publish to FrameSlot for decoupled rendering (non-blocking)
                frameSlot?.publish(FrameInfo(
                    frameNumber = frameArrivedCount,
                    timestamp = System.nanoTime()
                ))
            }
        }

        // Create Surface for MediaCodec output
        surface = Surface(surfaceTexture)

    }

    /**
     * Get Surface for MediaCodec to decode into.
     * Returns null if not initialized.
     */
    override fun getSurface(): Surface? {
        return surface
    }

    /**
     * Get texture ID for OpenGL binding.
     */
    fun getTextureId(): Int = textureId

    // Debug counters for updateTexture calls
    private var updateTextureCallCount = 0
    private var lastUpdateTextureLogTime = 0L

    /**
     * Update texture with latest frame. Call on GL thread.
     *
     * CRITICAL: This MUST be called every frame (60Hz) to drain the SurfaceTexture buffer.
     * MediaCodec outputs to a BufferQueue with only 1-2 slots. If we don't consume frames
     * fast enough, the decoder blocks and Moonlight sees "no video traffic".
     *
     * @return true if a new frame was consumed
     */
    fun updateTexture(): Boolean {
        val hadNewFrame = synchronized(frameLock) {
            val available = frameAvailable
            frameAvailable = false
            available
        }

        // Debug: Log updateTexture call rate
        updateTextureCallCount++
        val now = System.currentTimeMillis()
        if (now - lastUpdateTextureLogTime >= 2000) {
            updateTextureCallCount = 0
            lastUpdateTextureLogTime = now
        }

        // ALWAYS call updateTexImage() to drain the buffer queue, even if frameAvailable
        // was false. This ensures we don't miss frames due to callback timing issues.
        // updateTexImage() is a no-op if no new frame is pending.
        try {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transformMatrix)

            if (hadNewFrame) {
                frameConsumedCount++
                // Log first few consumed frames and periodically to confirm pipeline works
                if (frameConsumedCount <= 5 || frameConsumedCount % 100 == 0) {
                }
            }
        } catch (e: IllegalStateException) {
            // Can happen if surface is not in a valid state
            Log.w(TAG, "updateTexImage failed: ${e.message}")
        }

        return hadNewFrame
    }

    /**
     * Get SurfaceTexture transform matrix for shader.
     */
    fun getTransformMatrix(): FloatArray = transformMatrix

    /**
     * Release resources.
     */
    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null

        if (textureId > 0) {
            val textures = intArrayOf(textureId)
            GLES30.glDeleteTextures(1, textures, 0)
            textureId = -1
        }

    }
}
