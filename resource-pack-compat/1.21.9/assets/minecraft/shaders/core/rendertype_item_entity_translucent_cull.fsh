#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;

out vec4 fragColor;


// FlashLight versioned renderer hook v2
#moj_import <minecraft:projection.glsl>
in float flashlightVertexDistance;
#define vertexDistance flashlightVertexDistance
#define FogStart FogEnvironmentalStart
#define FogEnd FogEnvironmentalEnd
float linear_fog_fade(float d, float a, float b) { return 1.0-clamp((d-a)/max(.000001,b-a),0.0,1.0); }
#moj_import <flashlight:beam_fragment.glsl>
void flashlightVanillaFragment() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

void main() {
    gl_FragDepth = gl_FragCoord.z;
    vec4 flashlightColor;
    if (flashlightFragment(flashlightColor)) {
        fragColor = flashlightColor;
        return;
    }
    flashlightVanillaFragment();
}
