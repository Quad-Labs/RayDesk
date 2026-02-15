package com.raydesk.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages EGL context and render loop for rendering OpenGL content to a TextureView.
 *
 * This component is essential for binocular display support on RayNeo X3 Pro.
 * The Mercury SDK uses TextureView + MirroringView pattern, so we need to render
 * OpenGL content to a TextureView (not GLSurfaceView) so MirroringView can mirror it.
 *
 * Key responsibilities:
 * - Creates and manages EGL display, config, context, and surface
 * - Runs render loop on dedicated HandlerThread (~60fps target)
 * - Handles TextureView SurfaceTexture lifecycle callbacks
 * - Provides thread-safe start/stop/requestRender API
 *
 * Usage:
 * ```kotlin
 * val renderer = GLTextureRenderer(textureView, object : GLTextureRenderer.Renderer {
 *     override fun onSurfaceCreated() { /* init GL resources */ }
 *     override fun onSurfaceChanged(width: Int, height: Int) { /* setup viewport */ }
 *     override fun onDrawFrame() { /* render frame */ }
 * })
 * renderer.start()
 * // ... later
 * renderer.stop()
 * ```
 */
class GLTextureRenderer(
    private val textureView: TextureView,
    private val renderer: Renderer
) : Choreographer.FrameCallback {

    /**
     * Renderer interface for OpenGL drawing.
     * All callbacks are invoked on the render thread with valid EGL context.
     */
    interface Renderer {
        /**
         * Called when the EGL surface is created and context is current.
         * Initialize OpenGL resources here (shaders, textures, buffers).
         */
        fun onSurfaceCreated()

        /**
         * Called when the surface size changes.
         * Set up viewport and projection matrices here.
         */
        fun onSurfaceChanged(width: Int, height: Int)

        /**
         * Called for each frame to render.
         * Draw your OpenGL content here.
         */
        fun onDrawFrame()
    }

    companion object {
        private const val TAG = "GLTextureRenderer"

        // EGL configuration attributes for OpenGL ES 3.0 with RGBA8888
        private val EGL_CONFIG_ATTRIBS = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,  // ES3 is superset of ES2 bit
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,     // No depth buffer needed for 2D video
            EGL14.EGL_STENCIL_SIZE, 0,   // No stencil buffer needed
            EGL14.EGL_NONE
        )

        // Context attributes for OpenGL ES 3.0
        private val EGL_CONTEXT_ATTRIBS = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,  // OpenGL ES 3.0
            EGL14.EGL_NONE
        )

        // Default frame interval for ~60fps
        private const val DEFAULT_FRAME_INTERVAL_MS = 16L

        // Timeout for synchronous operations
        private const val OPERATION_TIMEOUT_MS = 5000L
    }

    // EGL objects (nullable to support test environments without real EGL)
    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // Render thread
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var choreographer: Choreographer? = null

    // State flags
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val surfaceAvailable = AtomicBoolean(false)

    // Surface dimensions
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Frame timing
    private var frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS
    private var lastFrameTimeMs = 0L

    // Continuous render mode (vs on-demand)
    private val continuousRender = AtomicBoolean(true)

    // TextureView surface listener
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            surfaceAvailable.set(true)

            // Initialize EGL on render thread if running
            if (isRunning.get()) {
                postOnRenderThread { initializeEGL(surface, width, height) }
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height

            postOnRenderThread {
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    renderer.onSurfaceChanged(width, height)
                }
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            surfaceAvailable.set(false)

            // Release EGL surface synchronously to ensure cleanup before system releases texture
            val latch = CountDownLatch(1)
            postOnRenderThread {
                releaseEGLSurface()
                latch.countDown()
            }

            try {
                latch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for EGL surface release")
            }

            // Return true to let TextureView release the SurfaceTexture
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // Called when SurfaceTexture is updated via updateTexImage()
            // We don't need to handle this
        }
    }

    /**
     * Start the render loop.
     * Creates render thread and initializes EGL if surface is available.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        // Create render thread
        renderThread = HandlerThread("GLTextureRenderer").apply {
            start()
        }
        renderHandler = Handler(renderThread!!.looper)

        // Set up surface listener
        textureView.surfaceTextureListener = surfaceTextureListener

        // If surface is already available, initialize EGL
        val existingSurface = textureView.surfaceTexture
        if (existingSurface != null && textureView.isAvailable) {
            surfaceWidth = textureView.width
            surfaceHeight = textureView.height
            surfaceAvailable.set(true)
            postOnRenderThread { initializeEGL(existingSurface, surfaceWidth, surfaceHeight) }
        }
    }

    /**
     * Stop the render loop and release all resources.
     * Blocks until cleanup is complete.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.w(TAG, "Not running")
            return
        }
        // Remove surface listener
        textureView.surfaceTextureListener = null

        // Release EGL on render thread and wait for completion
        val latch = CountDownLatch(1)
        postOnRenderThread {
            releaseEGL()
            latch.countDown()
        }

        try {
            if (!latch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timeout waiting for EGL release")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for EGL release")
        }

        // Quit render thread
        renderThread?.quitSafely()
        try {
            renderThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining render thread")
        }
        renderThread = null
        renderHandler = null
    }

    /**
     * Pause rendering. Frames will not be drawn until resume() is called.
     */
    fun pause() {
        isPaused.set(true)
    }

    /**
     * Resume rendering after pause().
     */
    fun resume() {
        isPaused.set(false)

        // Schedule a frame
        if (isRunning.get() && continuousRender.get()) {
            scheduleNextFrame()
        }
    }

    /**
     * Request a single frame redraw.
     * Used in on-demand rendering mode.
     */
    fun requestRender() {
        if (!isRunning.get() || isPaused.get()) return

        postOnRenderThread { drawFrame() }
    }

    /**
     * Set continuous render mode.
     * When true (default), frames are rendered continuously at target frame rate.
     * When false, frames are only rendered when requestRender() is called.
     */
    fun setContinuousRenderMode(continuous: Boolean) {
        continuousRender.set(continuous)
        if (continuous && isRunning.get() && !isPaused.get()) {
            scheduleNextFrame()
        }
    }

    /**
     * Set target frame rate in frames per second.
     * Default is 60fps.
     */
    fun setTargetFps(fps: Int) {
        frameIntervalMs = if (fps > 0) 1000L / fps else DEFAULT_FRAME_INTERVAL_MS
    }

    /**
     * Check if renderer is currently running.
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Check if renderer is paused.
     */
    fun isPaused(): Boolean = isPaused.get()

    /**
     * Run a block on the render thread with EGL context current.
     * Useful for running GL operations from other threads.
     */
    fun runOnRenderThread(block: () -> Unit) {
        if (!isRunning.get()) {
            Log.w(TAG, "Cannot run on render thread - not running")
            return
        }
        postOnRenderThread(block)
    }

    // ==================== EGL Management ====================

    private fun initializeEGL(surfaceTexture: SurfaceTexture, width: Int, height: Int) {

        // Get EGL display
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: ${getEGLErrorString()}")
        }
        eglDisplay = display

        // Initialize EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            eglDisplay = null
            throw RuntimeException("eglInitialize failed: ${getEGLErrorString()}")
        }

        // Choose config
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, EGL_CONFIG_ATTRIBS, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed: ${getEGLErrorString()}")
        }
        if (numConfigs[0] == 0) {
            throw RuntimeException("No suitable EGL config found")
        }
        val config = configs[0]!!
        eglConfig = config

        // Create context
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            EGL_CONTEXT_ATTRIBS,
            0
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed: ${getEGLErrorString()}")
        }
        eglContext = context

        // Create window surface from SurfaceTexture
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, surfaceAttribs, 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${getEGLErrorString()}")
        }
        eglSurface = surface

        // Make context current
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw RuntimeException("eglMakeCurrent failed: ${getEGLErrorString()}")
        }

        logGLInfo()

        // Initialize Choreographer for VSync-aligned rendering
        // Must be done on the render thread (which has a Looper)
        try {
            choreographer = Choreographer.getInstance()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Choreographer not available, falling back to Handler.postDelayed")
            choreographer = null
        }

        // Notify renderer
        try {
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error in renderer callbacks", e)
        }

        // Start render loop if in continuous mode
        if (continuousRender.get() && !isPaused.get()) {
            Log.i(TAG, "Starting continuous render loop NOW (Choreographer=${choreographer != null})")
            scheduleNextFrame()
        }
    }

    private fun releaseEGLSurface() {
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return

        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        eglSurface = null
    }

    private fun releaseEGL() {

        // Remove pending Choreographer callbacks
        choreographer?.removeFrameCallback(this)
        choreographer = null

        releaseEGLSurface()

        val display = eglDisplay
        val context = eglContext

        if (context != null && display != null) {
            EGL14.eglDestroyContext(display, context)
            eglContext = null
        }

        if (display != null) {
            EGL14.eglTerminate(display)
            eglDisplay = null
        }

        eglConfig = null
    }

    // ==================== Render Loop ====================

    /**
     * Schedule the next frame using Choreographer for VSync alignment.
     * This ensures 60Hz rendering synchronized with the display refresh.
     */
    private fun scheduleNextFrame() {
        if (!isRunning.get() || isPaused.get() || !continuousRender.get()) return

        val chore = choreographer
        if (chore != null) {
            // Use Choreographer for VSync-aligned rendering (preferred)
            chore.postFrameCallback(this)
        } else {
            // Fallback to Handler.postDelayed if Choreographer not available
            val now = System.currentTimeMillis()
            val elapsed = now - lastFrameTimeMs
            val delay = (frameIntervalMs - elapsed).coerceAtLeast(0)

            renderHandler?.postDelayed({
                if (isRunning.get() && !isPaused.get()) {
                    drawFrame()
                    scheduleNextFrame()
                }
            }, delay)
        }
    }

    /**
     * Choreographer.FrameCallback implementation.
     * Called at VSync (~60Hz on most displays).
     *
     * This is the core of decoupled rendering:
     * - Head tracking is sampled EVERY frame (60Hz)
     * - Video texture is updated only when new frames arrive (~30fps)
     * - Result: Smooth head tracking even when video is slower
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning.get() || isPaused.get()) return

        drawFrame()

        // Schedule next frame if continuous rendering is enabled
        if (continuousRender.get()) {
            choreographer?.postFrameCallback(this)
        }
    }

    private fun drawFrame() {
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        val context = eglContext ?: return

        lastFrameTimeMs = System.currentTimeMillis()

        try {
            // Ensure context is current (may have been cleared)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.e(TAG, "eglMakeCurrent failed in drawFrame: ${getEGLErrorString()}")
                return
            }

            // Call renderer
            renderer.onDrawFrame()

            // Swap buffers to display
            if (!EGL14.eglSwapBuffers(display, surface)) {
                val error = EGL14.eglGetError()
                if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
                    Log.w(TAG, "Surface lost, skipping frame")
                } else {
                    Log.e(TAG, "eglSwapBuffers failed: ${getEGLErrorString(error)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in drawFrame", e)
        }
    }

    // ==================== Helpers ====================

    private fun postOnRenderThread(block: () -> Unit) {
        val handler = renderHandler
        if (handler != null && isOnRenderThread()) {
            block()
        } else {
            handler?.post(block)
        }
    }

    private fun isOnRenderThread(): Boolean {
        return Looper.myLooper() == renderThread?.looper
    }

    private fun getEGLErrorString(): String {
        return getEGLErrorString(EGL14.eglGetError())
    }

    private fun getEGLErrorString(error: Int): String {
        return when (error) {
            EGL14.EGL_SUCCESS -> "EGL_SUCCESS"
            EGL14.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED"
            EGL14.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS"
            EGL14.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC"
            EGL14.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE"
            EGL14.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG"
            EGL14.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT"
            EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
            EGL14.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY"
            EGL14.EGL_BAD_MATCH -> "EGL_BAD_MATCH"
            EGL14.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP"
            EGL14.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW"
            EGL14.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER"
            EGL14.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE"
            EGL14.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST"
            else -> "Unknown error: 0x${Integer.toHexString(error)}"
        }
    }

    private fun logGLInfo() {
        val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
        val version = GLES30.glGetString(GLES30.GL_VERSION)
    }
}
