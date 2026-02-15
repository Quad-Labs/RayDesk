#version 300 es

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform mat4 uMVPMatrix;

out vec3 vPosition;
out vec2 vTexCoord;

void main() {
    vPosition = aPosition;
    vTexCoord = aTexCoord;
    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
}
