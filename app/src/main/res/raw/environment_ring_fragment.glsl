#version 300 es

precision mediump float;

uniform vec4 uRingColor;
uniform float uAlpha;
uniform float uTime;
uniform sampler2D uTextTexture;  // Text overlay texture

in vec2 vTexCoord;
in float vAngle;

out vec4 fragColor;

// Constants
const float PI = 3.14159265359;
const float ACTIVE_ZONE_HALF_ANGLE = PI / 3.0;  // 60 degrees each side = 120 degree arc

void main() {
    // Pulsing base glow
    float pulse = 0.85 + 0.15 * sin(uTime * 1.5);

    // Fade at top/bottom edges of ring band
    float edgeFade = smoothstep(0.0, 0.25, vTexCoord.y) * smoothstep(1.0, 0.75, vTexCoord.y);

    // Active zone detection (front-facing arc where text is shown)
    // vAngle is atan(x, -z), so 0 = looking straight ahead (-Z direction)
    float absAngle = abs(vAngle);
    float inActiveZone = smoothstep(ACTIVE_ZONE_HALF_ANGLE + 0.1, ACTIVE_ZONE_HALF_ANGLE - 0.1, absAngle);

    // Map vTexCoord.x to active zone UV (center the text in the active arc)
    // When in active zone: remap x from 0-1 across entire ring to 0-1 within active zone
    float activeZoneStart = 0.5 - (ACTIVE_ZONE_HALF_ANGLE / PI) * 0.5;
    float activeZoneEnd = 0.5 + (ACTIVE_ZONE_HALF_ANGLE / PI) * 0.5;
    float textU = (vTexCoord.x - activeZoneStart) / (activeZoneEnd - activeZoneStart);
    textU = clamp(textU, 0.0, 1.0);

    // Sample text texture (active zone only)
    vec4 textSample = texture(uTextTexture, vec2(textU, vTexCoord.y));
    float textAlpha = textSample.a * inActiveZone;

    // Base ring color with gradient toward edges
    float centerGlow = 1.0 - abs(vTexCoord.y - 0.5) * 1.5;
    centerGlow = clamp(centerGlow, 0.3, 1.0);
    vec3 baseColor = uRingColor.rgb * pulse * centerGlow;

    // Add flowing energy lines effect
    float flow = sin((vTexCoord.x - uTime * 0.1) * 20.0) * 0.5 + 0.5;
    flow = flow * 0.15 * (1.0 - inActiveZone * 0.5);  // Dim flow in text area
    baseColor += uRingColor.rgb * flow;

    // Composite text over base ring
    vec3 finalColor = mix(baseColor, textSample.rgb, textAlpha * 0.9);

    fragColor = vec4(finalColor, uAlpha * edgeFade);
}
