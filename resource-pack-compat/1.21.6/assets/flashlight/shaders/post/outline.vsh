#version 330
out vec2 texCoord;
out vec2 oneTexel;
in vec4 Position;
uniform sampler2D InSampler;
void main() {
    vec2 uv = Position.xy;
    vec4 clip=vec4(uv*2.0-1.0,0.0,1.0);
    gl_Position=vec4(clip.xy,.2,1.0);
    texCoord=uv;
    oneTexel=1.0/vec2(textureSize(InSampler,0));
}
