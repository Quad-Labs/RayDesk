package com.raydesk.streaming

/**
 * Configuration for a streaming session.
 *
 * NOTE: Width and height are suggestions to Sunshine. The actual stream resolution
 * is determined by the host and may differ. RayDesk auto-detects the actual
 * resolution via StreamResolutionListener.
 *
 * Bitrate: Gemini blind spot analysis recommends 20 Mbps minimum for text clarity
 * on AR glasses (desktop UI has high-frequency detail that suffers from compression).
 */
data class StreamConfig(
    val width: Int = 1920,      // 1080p default - most common desktop resolution
    val height: Int = 1080,     // 16:9 aspect ratio
    val fps: Int = 60,          // 60fps matches X3 Pro's display rate
    val bitrate: Int = 20_000,  // 20 Mbps for text clarity (value is in kbps, NOT bps!)
    val enableHdr: Boolean = false
)
