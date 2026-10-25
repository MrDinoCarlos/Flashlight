#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>
#include <flashlight:spotlight_data.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
uniform sampler2D Sampler0;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec3 flOriginalPosition;
layout(location = 3) out vec2 flControlUV;
layout(location = 4) flat out int flIsControl;
layout(location = 5) flat out int flControlKind;
layout(location = 6) flat out vec4 flControlParams;
void main() {
    vertexColor = Color;
    texCoord0 = UV0;
    vec4 view = ModelViewMat * vec4(Position,1.0);
    flOriginalPosition = view.xyz;
    flControlUV = vec2(0.0);
    vec4 marker=textureLod(Sampler0,UV0,0.0);
    flIsControl = flControlTexel(marker) ? 1 : 0;
    flControlKind=int(round(marker.r*255.0));
    flControlParams=vec4(0.0);
    gl_Position = ProjMat * view;
    if (flIsControl != 0) {
        int word = int(round(Color.r*255.0)) | (int(round(Color.g*255.0))<<8) | (int(round(Color.b*255.0))<<16);
        int slot = word & 31;
        int corner = gl_VertexIndex % 4;
        flControlUV = corner==0 ? vec2(0,1) : corner==1 ? vec2(0,0) : corner==2 ? vec2(1,0) : vec2(1,1);
        vec2 toward=vec2(.5-flControlUV.x,flControlUV.y-.5)*2.0;
        flControlParams=textureLod(Sampler0,UV0+toward*6.0/vec2(textureSize(Sampler0,0)),0.0);
        vec2 pixel = vec2(flControlUV.x*float(FL_CONTROL_WIDTH), (float(slot)+flControlUV.y)*float(FL_CONTROL_HEIGHT)
            +(flControlKind==14 ? float(FL_SHADOW_Y) : 0.0));
        gl_Position = vec4(pixel / ScreenSize * 2.0 - 1.0,0.0,1.0);
    }
}
