#version 300 es

#extension GL_OES_EGL_image_external_essl3 : require

precision mediump float;

uniform samplerExternalOES uVideoTexture;
uniform mat4 uSTMatrix;
uniform float uTestPatternEnabled;  // 1.0 = test pattern, 0.0 = video
uniform float uTime;  // For animation
uniform vec2 uCursorPos;       // Cursor position (0-1 in viewport space)
uniform float uCursorEnabled;  // 1.0 = show cursor, 0.0 = hide
uniform float uZoomLevel;      // 0.0 = zoomed in, 1.0 = zoomed out (full desktop)
uniform float uDisplayMode;    // 0.0 = keyhole panning, 1.0 = floating monitor
uniform float uCasSharpening;  // CAS sharpening amount (0.0 = off, 0.5 = default, 1.0 = max)
uniform vec2 uTexelSize;       // 1.0 / textureResolution

// Bezel effect uniforms
uniform float uBezelEnabled;  // 1.0 = enabled, 0.0 = disabled
uniform vec3 uBezelColor;     // RGB color
uniform float uBezelWidth;    // Width in UV space (e.g., 0.02)
uniform float uAspectRatio;   // Stream width / height for even thickness

in vec2 vTexCoord;      // Texture coordinates for video
in vec2 vScreenCoord;   // Original mesh coordinates (0-1) for cursor/UI
out vec4 fragColor;

// ============================================================================
// AMD FidelityFX CAS - Contrast Adaptive Sharpening
// Adapted for mobile/ES 3.0
//
// Purpose: Combat 4:2:0 chroma subsampling text artifacts (75% color
// resolution loss). This enhances edges without causing ringing artifacts.
// ============================================================================

vec3 CASFilter(vec2 uv, vec2 texelSize, float sharpenAmount) {
    // Sample 3x3 neighborhood (cross pattern for efficiency)
    // DEBUG: Use direct UV with Y-flip instead of STMatrix
    vec2 transformedUV = vec2(uv.x, 1.0 - uv.y);

    vec3 e = texture(uVideoTexture, transformedUV).rgb; // Center

    // Sample cardinal neighbors (most important for edge detection)
    // DEBUG: Use direct UV with Y-flip
    vec3 b = texture(uVideoTexture, vec2(uv.x, 1.0 - (uv.y - texelSize.y))).rgb; // Top
    vec3 d = texture(uVideoTexture, vec2(uv.x - texelSize.x, 1.0 - uv.y)).rgb; // Left
    vec3 f = texture(uVideoTexture, vec2(uv.x + texelSize.x, 1.0 - uv.y)).rgb; // Right
    vec3 h = texture(uVideoTexture, vec2(uv.x, 1.0 - (uv.y + texelSize.y))).rgb; // Bottom

    // Soft min/max for contrast detection (avoids harsh ringing)
    vec3 minRGB = min(min(min(d, e), min(f, b)), h);
    vec3 maxRGB = max(max(max(d, e), max(f, b)), h);

    // Avoid division by zero with small epsilon
    vec3 range = maxRGB - minRGB + 0.001;

    // Calculate adaptive sharpening weight based on local contrast
    // High contrast edges get less sharpening to avoid ringing
    // Low contrast areas get more sharpening for text clarity
    vec3 adaptiveWeight = clamp((minRGB / (maxRGB + 0.001) - 0.5) * sharpenAmount + 0.5, 0.0, 1.0);

    // Calculate unsharp mask contribution (center - neighbors)
    vec3 unsharp = e - (b + d + f + h) * 0.25;

    // Apply sharpening with adaptive weights
    vec3 result = e + unsharp * adaptiveWeight * sharpenAmount * 2.0;

    return clamp(result, 0.0, 1.0);
}

// ============================================================================
// Test Pattern Generator
// ============================================================================

vec4 testPattern(vec2 uv, float time) {
    // Create animated color gradient
    float hue = mod(uv.x + time * 0.1, 1.0);

    // HSV to RGB conversion
    float h6 = hue * 6.0;
    float r = abs(h6 - 3.0) - 1.0;
    float g = 2.0 - abs(h6 - 2.0);
    float b = 2.0 - abs(h6 - 4.0);

    vec3 rgb = clamp(vec3(r, g, b), 0.0, 1.0);

    // Add vertical stripes for motion visibility
    float stripe = step(0.5, mod(uv.x * 20.0 + time * 2.0, 1.0));
    rgb = mix(rgb, rgb * 0.8, stripe * 0.3);

    return vec4(rgb, 1.0);
}

// ============================================================================
// Cursor Drawing
// ============================================================================

vec4 drawCursor(vec2 uv, vec2 cursorPos, float zoomLevel) {
    vec2 diff = uv - cursorPos;

    // Scale cursor with zoom
    float baseSize = 0.015;
    float maxSize = 0.04;
    float scale = baseSize + (maxSize - baseSize) * zoomLevel;

    vec2 p = diff / scale;

    // Mouse pointer shape (arrow pointing up-left)
    bool inBody = p.x >= 0.0 && p.y >= 0.0 &&
                  p.x + p.y < 1.0 &&
                  p.y < p.x * 2.5 + 0.1;

    bool inTail = p.x > 0.25 && p.x < 0.55 &&
                  p.y > 0.4 && p.y < 0.9 &&
                  p.y > p.x - 0.1;

    if (inBody || inTail) {
        return vec4(1.0, 1.0, 1.0, 1.0);  // White pointer
    }

    // Black outline for visibility
    float outline = scale * 0.15;
    vec2 pOut = diff / (scale + outline);

    bool inBodyOut = pOut.x >= -0.05 && pOut.y >= -0.05 &&
                     pOut.x + pOut.y < 1.1 &&
                     pOut.y < pOut.x * 2.5 + 0.2;

    bool inTailOut = pOut.x > 0.2 && pOut.x < 0.6 &&
                     pOut.y > 0.35 && pOut.y < 0.95;

    if ((inBodyOut || inTailOut) && !inBody && !inTail) {
        return vec4(0.0, 0.0, 0.0, 1.0);  // Black outline
    }

    return vec4(0.0);  // Transparent
}

// ============================================================================
// Bezel Effect - Multi-layer glow
// ============================================================================

void applyBezel(inout vec4 color, vec2 uv) {
    if (uBezelEnabled < 0.5) return;

    // Correct for aspect ratio to ensure even bezel thickness on all edges
    float dx = min(uv.x, 1.0 - uv.x) * uAspectRatio;
    float dy = min(uv.y, 1.0 - uv.y);
    float edgeDist = min(dx, dy);

    // SUBTLE EDGE GLOW - reduced for AR glasses visibility
    float glowWidth = uBezelWidth * uAspectRatio * 0.4;  // Narrower

    // Single thin glow line at edge
    float glow = 1.0 - smoothstep(0.0, glowWidth, edgeDist);
    glow = glow * 0.25;  // Reduced intensity

    // Apply subtle glow
    color.rgb += uBezelColor * glow;
}

// ============================================================================
// Main
// ============================================================================

void main() {
    vec4 baseColor;

    if (uTestPatternEnabled > 0.5) {
        // GPU-generated animated test pattern (use screen coords for pattern)
        baseColor = testPattern(vScreenCoord, uTime);
    } else {
        // Apply CAS sharpening if enabled (> 0.01)
        if (uCasSharpening > 0.01) {
            vec3 sharpened = CASFilter(vTexCoord, uTexelSize, uCasSharpening);
            baseColor = vec4(sharpened, 1.0);
        } else {
            // DEBUG: Compare STMatrix vs direct UV
            // Standard sampling with STMatrix
            vec2 transformedCoord = (uSTMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;

            // DEBUG: Log STMatrix effect by showing different colors based on UV range
            // If left half is video and right half is black, STMatrix is compressing U
            // Temporarily use direct UV with manual Y-flip to bypass STMatrix
            vec2 directUV = vec2(vTexCoord.x, 1.0 - vTexCoord.y);  // Simple Y-flip

            // Use direct UV instead of STMatrix-transformed (DEBUG)
            baseColor = texture(uVideoTexture, directUV);
        }
    }

    // Apply bezel effect
    applyBezel(baseColor, vScreenCoord);

    // Overlay cursor on top (use screen coords for cursor position)
    if (uCursorEnabled > 0.5) {
        vec4 cursor = drawCursor(vScreenCoord, uCursorPos, uZoomLevel);
        if (cursor.a > 0.1) {
            fragColor = mix(baseColor, cursor, cursor.a);
        } else {
            fragColor = baseColor;
        }
    } else {
        fragColor = baseColor;
    }
}
