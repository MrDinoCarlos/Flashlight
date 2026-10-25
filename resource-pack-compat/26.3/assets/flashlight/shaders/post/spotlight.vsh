#version 330
#extension GL_ARB_separate_shader_objects : require
#include <flashlight:spotlight_data.glsl>
uniform sampler2D DataSampler;
layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out uint flActiveLights;
layout(location = 2) flat out int flFirstLight;
layout(location = 3) flat out mat4 flProjection;
layout(location = 7) flat out mat4 flInverseProjection;
layout(location = 11) flat out vec4 flOriginPower;
layout(location = 12) flat out vec4 flAxisRange;
layout(location = 13) flat out vec3 flOptics;
layout(location = 14) flat out uint flActiveShadows;
layout(location = 15) flat out vec4 flShadowCenters[8];
layout(location = 23) flat out vec4 flShadowAxes[8];
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec4 clip=vec4(uv*2.0-1.0,0.0,1.0);
    gl_Position=vec4(clip.xy,.2,1.0);
    texCoord=uv;
    // Tracking distance can hide any controller, including slot zero. Scan the
    // tiny metadata strip once per vertex rather than once per scene pixel.
    flActiveLights=0u;
    flFirstLight=-1;
    flProjection=mat4(1.0); flInverseProjection=mat4(1.0);
    flOriginPower=vec4(0.0); flAxisRange=vec4(0,0,-1,4); flOptics=vec3(0.0);
    flActiveShadows=0u;
    for(int slot=0;slot<FL_MAX_SHADOWS;slot++) {
        flShadowCenters[slot]=vec4(0.0); flShadowAxes[slot]=vec4(0.0);
        if(!flShadowHeader(flShadowCell(DataSampler,slot,0))) continue;
        flActiveShadows|=1u<<uint(slot);
        flShadowCenters[slot]=vec4(flDecodeFloat(flShadowCell(DataSampler,slot,1)),flDecodeFloat(flShadowCell(DataSampler,slot,2)),
            flDecodeFloat(flShadowCell(DataSampler,slot,3)),flDecodeFloat(flShadowCell(DataSampler,slot,25)));
        flShadowAxes[slot]=vec4(flDecodeFloat(flShadowCell(DataSampler,slot,4)),flDecodeFloat(flShadowCell(DataSampler,slot,5)),
            flDecodeFloat(flShadowCell(DataSampler,slot,6)),flDecodeFloat(flShadowCell(DataSampler,slot,24)));
    }
    for(int slot=0;slot<FL_MAX_LIGHTS;slot++) {
        if(!flHeader(flCell(DataSampler,slot,0))) continue;
        flActiveLights|=1u<<uint(slot);
        if(flFirstLight<0) {
            flFirstLight=slot;
            // All controllers in this world pass share the camera projection.
            // Decode/invert it four times per frame, rather than per pixel/light.
            flProjection=flReadProjection(DataSampler,slot);
            flInverseProjection=inverse(flProjection);
            flReadLight(DataSampler,slot,flOriginPower,flAxisRange,flOptics);
        }
    }
}
