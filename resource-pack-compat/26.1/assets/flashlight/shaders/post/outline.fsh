#version 330
#moj_import <flashlight:spotlight_data.glsl>
uniform sampler2D InSampler;
in vec2 texCoord;
in vec2 oneTexel;
out vec4 fragColor;
vec4 flOutlineSample(vec2 uv) {
    ivec2 size=textureSize(InSampler,0);
    ivec2 pixel=clamp(ivec2(uv*vec2(size)),ivec2(0),size-1);
    if(pixel.x<FL_CONTROL_WIDTH && pixel.y<FL_CONTROL_HEIGHT*FL_MAX_LIGHTS
        && flHeader(flCell(InSampler,pixel.y/FL_CONTROL_HEIGHT,0))) return vec4(0.0);
    if(pixel.x<FL_CONTROL_WIDTH && pixel.y>=FL_SHADOW_Y && pixel.y<FL_SHADOW_Y+FL_MAX_SHADOWS*FL_CONTROL_HEIGHT
        && flShadowHeader(flShadowCell(InSampler,(pixel.y-FL_SHADOW_Y)/FL_CONTROL_HEIGHT,0))) return vec4(0.0);
    return texture(InSampler,uv);
}
void main() {
    // Vanilla's outline detector with private control data removed in-place.
    vec4 center=flOutlineSample(texCoord);
    vec4 left=flOutlineSample(texCoord-vec2(oneTexel.x,0));
    vec4 right=flOutlineSample(texCoord+vec2(oneTexel.x,0));
    vec4 up=flOutlineSample(texCoord-vec2(0,oneTexel.y));
    vec4 down=flOutlineSample(texCoord+vec2(0,oneTexel.y));
    float total=clamp(abs(center.a-left.a)+abs(center.a-right.a)+abs(center.a-up.a)+abs(center.a-down.a),0.0,1.0);
    vec3 color=center.rgb*center.a+left.rgb*left.a+right.rgb*right.a+up.rgb*up.a+down.rgb*down.a;
    fragColor=vec4(color*.2,total);
}
