#version 300 es

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform mat4 uMVPMatrix;

out vec2 vTexCoord;
out float vAngle;  // For active zone detection

void main() {
    vTexCoord = aTexCoord;
    // Calculate angle from position for active zone
    vAngle = atan(aPosition.x, -aPosition.z);  // -Z is forward
    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
}
