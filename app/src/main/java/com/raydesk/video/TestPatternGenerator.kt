package com.raydesk.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Generates test patterns on a Surface to verify the video pipeline.
 *
 * This is used to test that VideoTextureProvider → StreamRenderer pipeline
 * works correctly before integrating with Moonlight.
 *
 * Patterns available:
 * - CHECKERBOARD: Classic test pattern
 * - COLOR_BARS: SMPTE-style color bars
 * - MOVING_GRADIENT: Animated gradient for motion testing
 */
class TestPatternGenerator {

    companion object {
        private const val TAG = "TestPattern"
        private const val DEFAULT_FPS = 30
    }

    enum class Pattern {
        CHECKERBOARD,
        COLOR_BARS,
        MOVING_GRADIENT
    }

    private var surface: Surface? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isRunning = false

    private var width = 1920
    private var height = 1080
    private var frameCount = 0
    private var currentPattern = Pattern.CHECKERBOARD

    private val paint = Paint().apply {
        isAntiAlias = false
    }

    /**
     * Start generating test patterns on the given surface.
     *
     * @param surface The surface to draw to (from VideoTextureProvider)
     * @param width Output width
     * @param height Output height
     * @param pattern Which pattern to generate
     * @param fps Frames per second
     */
    fun start(
        surface: Surface,
        width: Int = 1920,
        height: Int = 1080,
        pattern: Pattern = Pattern.CHECKERBOARD,
        fps: Int = DEFAULT_FPS
    ) {
        if (isRunning) {
            Log.w(TAG, "Already running, call stop() first")
            return
        }

        this.surface = surface
        this.width = width
        this.height = height
        this.currentPattern = pattern
        this.frameCount = 0
        this.isRunning = true

        handlerThread = HandlerThread("TestPatternGenerator").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val frameIntervalMs = 1000L / fps

        handler?.post(object : Runnable {
            override fun run() {
                if (!isRunning) return

                drawFrame()
                frameCount++

                handler?.postDelayed(this, frameIntervalMs)
            }
        })

        Log.i(TAG, "Started pattern=$pattern at ${fps}fps (${width}x${height})")
    }

    /**
     * Stop generating patterns.
     */
    fun stop() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        surface = null

        Log.i(TAG, "Stopped after $frameCount frames")
    }

    private fun drawFrame() {
        val s = surface ?: return

        try {
            val canvas = s.lockCanvas(null) ?: return
            try {
                when (currentPattern) {
                    Pattern.CHECKERBOARD -> drawCheckerboard(canvas)
                    Pattern.COLOR_BARS -> drawColorBars(canvas)
                    Pattern.MOVING_GRADIENT -> drawMovingGradient(canvas)
                }
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing frame: ${e.message}")
        }
    }

    private fun drawCheckerboard(canvas: Canvas) {
        val cellSize = 64
        val cols = width / cellSize + 1
        val rows = height / cellSize + 1

        // Animate by offsetting the pattern
        val offset = (frameCount / 2) % 2

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val isWhite = (row + col + offset) % 2 == 0
                paint.color = if (isWhite) Color.WHITE else Color.BLACK

                val left = col * cellSize
                val top = row * cellSize
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + cellSize).toFloat(),
                    (top + cellSize).toFloat(),
                    paint
                )
            }
        }

        // Draw frame counter in corner
        paint.color = Color.GREEN
        paint.textSize = 48f
        canvas.drawText("Frame: $frameCount", 50f, 80f, paint)
    }

    private fun drawColorBars(canvas: Canvas) {
        // SMPTE color bar pattern
        val colors = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            Color.MAGENTA,
            Color.RED,
            Color.BLUE,
            Color.BLACK
        )

        val barWidth = width / colors.size

        colors.forEachIndexed { index, color ->
            paint.color = color
            val left = index * barWidth
            canvas.drawRect(
                left.toFloat(),
                0f,
                (left + barWidth).toFloat(),
                height.toFloat(),
                paint
            )
        }

        // Draw frame counter
        paint.color = Color.GREEN
        paint.textSize = 48f
        canvas.drawText("Frame: $frameCount", 50f, 80f, paint)
    }

    private fun drawMovingGradient(canvas: Canvas) {
        // Create a gradient that moves horizontally
        val shift = (frameCount * 4) % width

        for (x in 0 until width step 4) {
            val hue = ((x + shift) % width) * 360f / width
            paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            canvas.drawRect(x.toFloat(), 0f, (x + 4).toFloat(), height.toFloat(), paint)
        }

        // Draw frame counter
        paint.color = Color.BLACK
        paint.textSize = 48f
        canvas.drawText("Frame: $frameCount", 50f, 80f, paint)
    }
}
