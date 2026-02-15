#version 300 es

precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform mat4 uMVPMatrix;
uniform vec4 uKeyholeRect;  // (u0, v0, u1, v1)
uniform float uDisplayMode; // 0.0 = keyhole panning, 1.0 = floating monitor

out vec2 vTexCoord;      // Texture coordinates for video sampling
out vec2 vScreenCoord;   // Original mesh coordinates (0-1) for cursor/UI overlay

void main() {
    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);

    // Original mesh UV for cursor overlay (0-1 across viewport)
    vScreenCoord = aTexCoord;

    // Texture coordinate handling based on display mode
    if (uDisplayMode > 0.5) {
        // Floating monitor mode: use full texture (0-1)
        // The 3D MVP matrix positions the screen in space
        vTexCoord = aTexCoord;
    } else {
        // Keyhole panning mode: map mesh UV (0-1) to keyhole region
        vTexCoord = vec2(
            mix(uKeyholeRect.x, uKeyholeRect.z, aTexCoord.x),
            mix(uKeyholeRect.y, uKeyholeRect.w, aTexCoord.y)
        );
    }
}
