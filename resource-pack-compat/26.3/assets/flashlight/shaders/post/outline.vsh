#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec2 oneTexel;
uniform sampler2D InSampler;
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec4 clip=vec4(uv*2.0-1.0,0.0,1.0);
    gl_Position=vec4(clip.xy,.2,1.0);
    texCoord=uv;
    oneTexel=1.0/vec2(textureSize(InSampler,0));
}
