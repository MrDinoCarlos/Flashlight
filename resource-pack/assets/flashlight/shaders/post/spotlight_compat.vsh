#version 150
#moj_import <flashlight:spotlight_data.glsl>
const int FL_CACHED_SHADOWS = 3;
void flReadShadow(sampler2D source,int slot,out vec4 center,out vec4 axis) {
    center=vec4(flDecodeFloat(flShadowCell(source,slot,1)),flDecodeFloat(flShadowCell(source,slot,2)),
        flDecodeFloat(flShadowCell(source,slot,3)),flDecodeFloat(flShadowCell(source,slot,25)));
    axis=vec4(flDecodeFloat(flShadowCell(source,slot,4)),flDecodeFloat(flShadowCell(source,slot,5)),
        flDecodeFloat(flShadowCell(source,slot,6)),flDecodeFloat(flShadowCell(source,slot,24)));
    float inverseRadius=1.0/max(.025,center.w);
    center.w=inverseRadius;
    axis=vec4(normalize(axis.xyz),1.0/max(.05,axis.w)-inverseRadius);
}
in vec4 Position;
uniform mat4 ProjMat;
uniform vec2 OutSize;
uniform sampler2D DataSampler;
out vec2 texCoord;
flat out uvec3 flState;
flat out mat4 flProjection;
flat out vec4 flInverseData;
flat out vec4 flOriginPower;
flat out vec4 flAxisRange;
flat out vec4 flOpticsState;
flat out vec4 flShadowCenters[3];
flat out vec4 flShadowAxes[3];
void main() {
    vec4 clip=ProjMat*vec4(Position.xy,0.0,1.0);
    gl_Position=vec4(clip.xy,.2,1.0);
    texCoord=Position.xy/OutSize;
    // Tracking distance can hide any controller, including slot zero. Scan the
    // tiny metadata strip once per vertex rather than once per scene pixel.
    uint flActiveLights=0u,flActiveShadows=0u;
    int flFirstLight=-1,cachedShadows=0;
    flProjection=mat4(1.0);
    flInverseData=vec4(1.0);
    flOriginPower=vec4(0.0); flAxisRange=vec4(0,0,-1,4); flOpticsState=vec4(0.0);
    for(int index=0;index<FL_CACHED_SHADOWS;index++) {
        flShadowCenters[index]=vec4(0.0); flShadowAxes[index]=vec4(0.0);
    }
    for(int slot=0;slot<FL_MAX_SHADOWS;slot++) {
        if(!flShadowHeader(flShadowCell(DataSampler,slot,0))) continue;
        flActiveShadows|=1u<<uint(slot);
        if(cachedShadows<FL_CACHED_SHADOWS) {
            flReadShadow(DataSampler,slot,flShadowCenters[cachedShadows],flShadowAxes[cachedShadows]);
            cachedShadows++;
        }
    }
    for(int slot=0;slot<FL_MAX_LIGHTS;slot++) {
        if(!flHeader(flCell(DataSampler,slot,0))) continue;
        flActiveLights|=1u<<uint(slot);
        if(flFirstLight<0) {
            flFirstLight=slot;
            // All controllers in this world pass share the camera projection.
            flProjection=flReadProjection(DataSampler,slot);
            vec3 optics;
            flReadLight(DataSampler,slot,flOriginPower,flAxisRange,optics);
            flOpticsState.xyz=optics;
        }
    }
    mat4 q=inverse(flProjection);
    // Store all five nonzero inverse coefficients without changing precision.
    // A flag in the shadow word selects this exact sparse reconstruction.
    bool symmetric=q[0][1]==0.0 && q[0][2]==0.0 && q[0][3]==0.0 &&
        q[1][0]==0.0 && q[1][2]==0.0 && q[1][3]==0.0 && q[2][0]==0.0 && q[2][1]==0.0 && q[2][2]==0.0 &&
        q[3][0]==0.0 && q[3][1]==0.0;
    flState=uvec3(flActiveLights,uint(max(0,flFirstLight)),flActiveShadows | (symmetric ? 256u : 0u));
    flInverseData=vec4(q[0][0],q[1][1],q[2][3],q[3][3]);
    flOpticsState.w=q[3][2];
}
