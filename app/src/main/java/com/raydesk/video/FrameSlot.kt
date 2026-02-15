package com.raydesk.video

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Frame information for the decoupled rendering pipeline.
 *
 * @param frameNumber Monotonically increasing frame counter from the decode thread
 * @param timestamp Timestamp in nanoseconds when the frame was produced
 */
data class FrameInfo(
    val frameNumber: Long,
    val timestamp: Long
)

/**
 * Lock-free double-buffer for decoupled video rendering.
 *
 * This is the critical component that enables 60Hz head tracking updates
 * even when video frames arrive at 30fps. The render thread NEVER blocks
 * waiting for video frames.
 *
 * Architecture:
 * ```
 * Decode Thread → publish() → [FrameSlot] → consume() → Render Thread
 *                 (producer)                           (consumer)
 * ```
 *
 * Thread safety:
 * - publish() can be called from any thread (typically decode thread)
 * - consume() can be called from any thread (typically render thread)
 * - hasNewFrame() and markConsumed() are thread-safe
 * - All operations are lock-free using AtomicReference
 *
 * Usage in render loop:
 * ```kotlin
 * // In onDrawFrame() at 60Hz:
 * val frame = frameSlot.consume()
 * if (frameSlot.hasNewFrame()) {
 *     videoProvider.updateTexImage()  // Only update texture when new frame
 *     frameSlot.markConsumed(frame!!.frameNumber)
 * }
 * // Always render (head tracking updates every frame)
 * renderQuad(headPose)
 * ```
 *
 * This prevents the "Jell-O effect" - visual wobble when head tracking
 * updates are delayed waiting for video frames.
 */
class FrameSlot {

    // Atomic slot holding the latest frame (null if no frame published yet)
    private val slot = AtomicReference<FrameInfo?>(null)

    // Frame number of the last consumed frame (for hasNewFrame comparison)
    private val lastConsumed = AtomicLong(-1)

    /**
     * Publish a new frame to the slot.
     * Overwrites any previous frame (latest wins).
     *
     * Called by the decode thread when a new video frame is decoded.
     * Non-blocking, lock-free operation.
     *
     * @param frame The frame information to publish
     */
    fun publish(frame: FrameInfo) {
        slot.set(frame)
    }

    /**
     * Consume the latest frame from the slot.
     * Does NOT remove the frame - subsequent calls return the same frame.
     *
     * Called by the render thread every frame to check for new video.
     * Non-blocking, lock-free operation.
     *
     * @return The latest frame, or null if no frame has been published
     */
    fun consume(): FrameInfo? {
        return slot.get()
    }

    /**
     * Check if a new frame is available since the last markConsumed().
     *
     * A "new" frame means:
     * - A frame has been published, AND
     * - Its frameNumber differs from the last consumed frameNumber
     *
     * @return true if there's a new frame to process
     */
    fun hasNewFrame(): Boolean {
        val current = slot.get()
        return current != null && current.frameNumber != lastConsumed.get()
    }

    /**
     * Mark a frame as consumed by its frame number.
     * After this call, hasNewFrame() will return false until a new frame is published.
     *
     * Called by the render thread after processing a frame (updateTexImage).
     *
     * @param frameNumber The frame number that was consumed
     */
    fun markConsumed(frameNumber: Long) {
        lastConsumed.set(frameNumber)
    }
}
