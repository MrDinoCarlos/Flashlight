#version 150

#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;


// FlashLight versioned renderer hook v2
uniform mat4 ProjMat;
#moj_import <flashlight:beam_fragment.glsl>
void flashlightVanillaFragment() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color *= vertexColor * ColorModulator;
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
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
