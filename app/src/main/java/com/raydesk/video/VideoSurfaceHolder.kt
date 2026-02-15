package com.raydesk.video

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder

/**
 * Adapts a SurfaceProvider to SurfaceHolder interface for Moonlight integration.
 *
 * Moonlight's MediaCodecDecoderRenderer expects a SurfaceHolder to output decoded
 * video frames. This adapter allows us to provide our own Surface (from
 * VideoTextureProvider) instead of using a SurfaceView.
 *
 * Usage:
 * ```
 * val holder = VideoSurfaceHolder(videoTextureProvider)
 * decoderRenderer.setRenderTarget(holder)
 * ```
 *
 * Most SurfaceHolder methods are no-ops since we manage the Surface externally.
 */
class VideoSurfaceHolder(
    private val surfaceProvider: SurfaceProvider
) : SurfaceHolder {

    companion object {
        private const val TAG = "VideoSurfaceHolder"
    }

    /**
     * Returns the Surface from the provider.
     * This is the key method that Moonlight calls.
     */
    override fun getSurface(): Surface? {
        val surface = surfaceProvider.getSurface()
        return surface
    }

    // ==================== Callback Management (No-ops) ====================
    // Moonlight doesn't use callbacks - it calls setRenderTarget() directly

    override fun addCallback(callback: SurfaceHolder.Callback?) {
        // No-op: We don't need callbacks, surface is managed externally
    }

    override fun removeCallback(callback: SurfaceHolder.Callback?) {
        // No-op
    }

    // ==================== Surface State ====================

    override fun isCreating(): Boolean {
        // Surface is created by VideoTextureProvider, not us
        return false
    }

    // ==================== Configuration (No-ops) ====================
    // Size and format are determined by VideoTextureProvider

    @Deprecated("Deprecated in Java")
    override fun setType(type: Int) {
        // Deprecated method, no-op
    }

    override fun setFixedSize(width: Int, height: Int) {
        // Size is determined by the video stream and VideoTextureProvider
    }

    override fun setSizeFromLayout() {
        // No-op
    }

    override fun setFormat(format: Int) {
        // Format is determined by VideoTextureProvider
    }

    override fun setKeepScreenOn(screenOn: Boolean) {
        // Screen management is handled by the activity
    }

    // ==================== Canvas Operations (Not Supported) ====================
    // We use SurfaceTexture, not direct Canvas drawing

    override fun lockCanvas(): Canvas? {
        // Not supported - video goes through SurfaceTexture
        return null
    }

    override fun lockCanvas(dirty: Rect?): Canvas? {
        // Not supported
        return null
    }

    override fun unlockCanvasAndPost(canvas: Canvas?) {
        // No-op
    }

    override fun lockHardwareCanvas(): Canvas {
        throw UnsupportedOperationException("VideoSurfaceHolder does not support canvas operations")
    }

    // ==================== Frame Info ====================

    override fun getSurfaceFrame(): Rect {
        // Return empty rect - actual size is managed by VideoTextureProvider
        return Rect()
    }
}

/**
 * Interface for providing a Surface.
 * Allows VideoTextureProvider to be used with VideoSurfaceHolder.
 */
interface SurfaceProvider {
    fun getSurface(): Surface?
}

/**
 * No-op SurfaceProvider for use cases where video is not needed.
 *
 * Used by MoonlightBridge in ConnectionActivity for pairing-only operations
 * where no video decoding is required.
 */
class NoopSurfaceProvider : SurfaceProvider {
    override fun getSurface(): Surface? = null
}
