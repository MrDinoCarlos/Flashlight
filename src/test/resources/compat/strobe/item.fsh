#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:utils.glsl>

uniform sampler2D Sampler0;

uniform mat4 ProjMat;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;
in vec2 texCoord2;
in vec4 normal;
in vec4 glpos;
flat in float marker;
flat in vec4 markerPayload;
in float scale;

out vec4 fragColor;

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

void main() {
    bool gui = isGUI(ProjMat);

    
    if (marker < 0.5) {
        vec4 color = texture(Sampler0, texCoord0);
        if (color.a < 0.1) {
            discard;
        }
        color *= vertexColor * ColorModulator;
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        fragColor.a = fragColor.a < 0.1 ? 0.1 : fragColor.a;

        gl_FragDepth = gl_FragCoord.z;
    } else {
        vec2 uvDx = dFdx(texCoord2);
        vec2 uvDy = dFdy(texCoord2);
        mat2 uvPerPixel = mat2(uvDx, uvDy);
        if (abs(determinant(uvPerPixel)) < 0.000000000001) {
            discard;
        }

        vec2 pixelOffset = inverse(uvPerPixel) * (texCoord2 - vec2(0.5));
        // Resolve exact half-pixel boundaries consistently across both triangles.
        ivec2 cell = ivec2(floor(pixelOffset + vec2(0.501)));
        if (abs(cell.x) > 1 || abs(cell.y) > 1) {
            discard;
        }

        float centerDepth = gl_FragCoord.z
            - pixelOffset.x * dFdx(gl_FragCoord.z)
            - pixelOffset.y * dFdy(gl_FragCoord.z);
        int cellIndex = (cell.y + 1) * 3 + cell.x + 1;
        int encodedValue = markerValue(markerPayload.rgb);
        if (cellIndex == 4) {
            fragColor = vec4(vec3(0.4), 5.0 / 255.0);
        } else {
            int payloadIndex = cellIndex < 4 ? cellIndex : cellIndex - 1;
            int triplet = (encodedValue >> (payloadIndex * 3)) & 7;
            vec3 bitColor = vec3(
                float(triplet & 1),
                float((triplet >> 1) & 1),
                float((triplet >> 2) & 1)
            );
            int projectionCode16 = encodeExactProjectionK(
                2.0 / max(abs(ProjMat[1][1]), 0.0001)
            );
            int alphaByte = payloadIndex < 4
                ? 2 + ((projectionCode16 >> (payloadIndex * 4)) & 15) : 2;
            fragColor = vec4(bitColor * 0.5, float(alphaByte) / 255.0);
        }
        gl_FragDepth = centerDepth * LIGHTDEPTH;
    }
}
