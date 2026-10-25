#version 150
in vec4 Position;
void main() {
    vec2 uv = Position.xy;
    // Fixed-size metadata targets use their own dimensions, not the world's ortho matrix.
    gl_Position=vec4(uv*2.0-1.0,.2,1.0);
}
