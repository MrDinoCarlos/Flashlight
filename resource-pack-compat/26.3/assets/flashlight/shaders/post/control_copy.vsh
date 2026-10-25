#version 330
#extension GL_ARB_separate_shader_objects : require
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    // Fixed-size metadata targets use their own dimensions, not the world's ortho matrix.
    gl_Position=vec4(uv*2.0-1.0,.2,1.0);
}
