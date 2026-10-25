#version 330
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    // Fixed-size metadata targets use their own dimensions, not the world's ortho matrix.
    gl_Position=vec4(uv*2.0-1.0,.2,1.0);
}
