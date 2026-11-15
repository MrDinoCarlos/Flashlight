// Lossless transport through Minecraft's ordinary RGBA8 outline target.
// No entity lightmap or world-light data is changed.
const int FL_CONTROL_WIDTH = 27;
const int FL_CONTROL_HEIGHT = 4;
const int FL_MAX_LIGHTS = 32;
const int FL_MAX_SHADOWS = 8;
const int FL_SHADOW_Y = FL_CONTROL_HEIGHT * FL_MAX_LIGHTS;
vec4 flEncodeFloat(float value) {
    if (value == 0.0) return vec4(0.0);
    float magnitude = abs(value);
    int exponent = int(floor(log2(magnitude)));
    uint mantissa = uint(clamp(round((magnitude / exp2(float(exponent)) - 1.0) * 8388608.0), 0.0, 8388607.0));
    uint bits = (value < 0.0 ? 2147483648u : 0u) | (uint(exponent + 127) << 23u) | mantissa;
    return vec4(float(bits & 255u), float((bits >> 8u) & 255u), float((bits >> 16u) & 255u), float(bits >> 24u)) / 255.0;
}
float flDecodeFloat(vec4 pixel) {
    uvec4 bytes = uvec4(round(pixel * 255.0));
    uint bits = bytes.x | (bytes.y << 8u) | (bytes.z << 16u) | (bytes.w << 24u);
    if (bits == 0u) return 0.0;
    int exponent = int((bits >> 23u) & 255u) - 127;
    return (bits >= 2147483648u ? -1.0 : 1.0) * (1.0 + float(bits & 8388607u) / 8388608.0) * exp2(float(exponent));
}
bool flControlTexel(vec4 texel) {
    vec4 bytes=round(texel*255.0);
    return bytes.r>=11.0 && bytes.r<=14.0 && all(lessThan(abs(bytes.gba-vec3(49,197,100)),vec3(.5)));
}
bool flShadowHeader(vec4 texel) {
    return all(lessThan(abs(round(texel.rgb*255.0)-vec3(83,72,68)),vec3(.5))) && texel.a>0.0;
}
vec4 flShadowCell(sampler2D source,int slot,int column) {
    return texelFetch(source,ivec2(column,FL_SHADOW_Y+slot*FL_CONTROL_HEIGHT+1),0);
}
bool flHeader(vec4 texel) {
    return all(lessThan(abs(round(texel.rgb * 255.0) - vec3(70.0,76.0,84.0)), vec3(0.5))) && texel.a > 0.0;
}
vec4 flCell(sampler2D source, int slot, int column) {
    return texelFetch(source, ivec2(column, slot * FL_CONTROL_HEIGHT + 1), 0);
}
mat4 flReadProjection(sampler2D source,int slot) {
    mat4 projection;
    for(int column=0;column<4;column++) for(int row=0;row<4;row++)
        projection[column][row]=flDecodeFloat(flCell(source,slot,8+column*4+row));
    return projection;
}
void flReadLight(sampler2D source,int slot,out vec4 originPower,out vec4 axisRange,out vec3 optics) {
    ivec3 bytes=ivec3(round(flCell(source,slot,7).rgb*255.0));
    int word=bytes.r|(bytes.g<<8)|(bytes.b<<16);
    originPower=vec4(flDecodeFloat(flCell(source,slot,1)),flDecodeFloat(flCell(source,slot,2)),
        flDecodeFloat(flCell(source,slot,3)),float((word>>5)&255)/255.0);
    axisRange=vec4(normalize(vec3(flDecodeFloat(flCell(source,slot,4)),flDecodeFloat(flCell(source,slot,5)),
        flDecodeFloat(flCell(source,slot,6)))),4.0+float((word>>13)&31));
    optics=vec3(tan(radians(4.0+float((word>>18)&63))*.5),flDecodeFloat(flCell(source,slot,24)),
        clamp(flDecodeFloat(flCell(source,slot,25)),0.0,3.0));
}
