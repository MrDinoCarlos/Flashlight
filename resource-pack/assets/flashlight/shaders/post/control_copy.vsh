#version 150
in vec4 Position;
uniform vec2 OutSize;
void main() {
    // Fixed-size metadata targets use their own dimensions, not the world's ortho matrix.
    gl_Position=vec4(Position.xy/OutSize*2.0-1.0,.2,1.0);
}
