#version 300 es

precision mediump float;

uniform float uDomeStyle;      // 0.0 = gradient, 1.0 = starfield
uniform vec4 uHorizonColor;    // RGBA at horizon (y=0)
uniform vec4 uZenithColor;     // RGBA at zenith (top)
uniform float uDomeRadius;     // For Y normalization
uniform float uAlpha;          // Overall alpha

in vec3 vPosition;
in vec2 vTexCoord;

out vec4 fragColor;

// Hash functions for procedural generation
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
}

// Generate a single star layer
float starLayer(vec2 uv, float density, float threshold, float baseSize) {
    vec2 cell = floor(uv * density);
    vec2 local = fract(uv * density);

    float star = 0.0;

    // Check 3x3 neighborhood
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 neighbor = vec2(float(x), float(y));
            vec2 cellPos = cell + neighbor;

            // Random position and brightness
            vec2 starPos = vec2(hash(cellPos), hash(cellPos + 100.0));
            float brightness = hash(cellPos + 200.0);

            // Distance to star center
            vec2 diff = neighbor + starPos - local;
            float dist = length(diff);

            // Threshold determines star density
            if (brightness > threshold) {
                // Size varies with brightness
                float size = baseSize + (brightness - threshold) * baseSize * 2.0;
                // Core brightness with glow falloff
                float core = smoothstep(size, size * 0.3, dist);
                float glow = smoothstep(size * 2.0, 0.0, dist) * 0.5;
                star += (core + glow) * (0.7 + brightness * 0.3);
            }
        }
    }

    return star;
}

// Multi-layer star field for depth
float stars(vec2 uv) {
    float result = 0.0;

    // Layer 1: Very bright prominent stars (sparse)
    result += starLayer(uv * 5.0, 15.0, 0.9, 0.12) * 1.5;

    // Layer 2: Bright medium stars
    result += starLayer(uv * 10.0 + 0.3, 30.0, 0.75, 0.08) * 1.1;

    // Layer 3: Medium density stars
    result += starLayer(uv * 18.0 + 0.7, 45.0, 0.6, 0.05) * 0.8;

    // Layer 4: Dense small background stars
    result += starLayer(uv * 30.0 + 1.2, 70.0, 0.45, 0.03) * 0.5;

    return clamp(result, 0.0, 2.0);
}

void main() {
    // Height-based factor (0 at horizon, 1 at zenith)
    float t = clamp(vPosition.y / uDomeRadius, 0.0, 1.0);

    if (uDomeStyle < 0.5) {
        // Solid gradient mode
        vec3 color = mix(uHorizonColor.rgb, uZenithColor.rgb, t);
        fragColor = vec4(color, uAlpha);
    } else {
        // Starfield mode - multi-layer procedural stars
        vec3 bgColor = mix(uHorizonColor.rgb, uZenithColor.rgb, t);

        // Generate layered stars using spherical UV mapping
        // atan2 returns [-PI, PI], normalize to [0, 1]
        float theta = atan(vPosition.z, vPosition.x);  // [-PI, PI]
        float normalizedTheta = (theta + 3.14159265359) / (2.0 * 3.14159265359);  // [0, 1]

        vec2 sphereUV = vec2(normalizedTheta, t);

        float starIntensity = stars(sphereUV);

        // Fade stars near horizon for atmospheric effect
        float horizonFade = smoothstep(0.0, 0.3, t);
        starIntensity *= horizonFade;

        // Star color - bright white with slight blue tint
        vec3 starColor = vec3(1.0, 1.0, 1.0);

        // Add subtle twinkle effect
        float twinkle = 0.9 + 0.1 * sin(normalizedTheta * 100.0 + t * 50.0);

        // Final composition - brighter stars
        vec3 color = bgColor + starColor * starIntensity * twinkle * 2.5;

        fragColor = vec4(color, uAlpha);
    }
}
