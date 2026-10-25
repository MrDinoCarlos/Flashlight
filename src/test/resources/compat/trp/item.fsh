#version 150

#moj_import <fog.glsl>

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
in vec4 normal;

in vec3 trpLocalPosition;
in vec3 trpLocalDelta;
in vec3 trpViewPosition;
flat in float trpMarkerFlag;
flat in float trpFaceIndex;
flat in float trpApertureBand;
flat in float trpBeamStyle;
flat in float trpOpenAir;
flat in float trpHandedness;
flat in vec3 trpImpactViewNormal;
flat in vec2 trpMetadataBands;
flat in vec4 trpBeamColor;
in vec2 trpLightCoordinate;
in vec2 trpImpactCoordinate;
flat in vec3 trpLightViewNormal;
flat in vec3 trpLightBeamDirection;
flat in vec3 trpLightViewPosition;
flat in float trpStrobeMarker;
flat in float trpStrobeLegacyMarker;
flat in vec4 trpStrobePayload;
in vec2 trpStrobeCoordinate;

out vec4 fragColor;

const int TRP_VOLUME_SAMPLES = 40;
const vec2 TRP_GLARE_BLOOM_RADIUS = vec2(0.52, 0.30);
const vec2 TRP_GLARE_LENS_RADIUS = vec2(0.24, 0.14);
const float TRP_PROXY_HALF_WIDTH = 1.0;
const float TRP_PROXY_MIN_Z = -0.125;
const float TRP_PROXY_MAX_Z = 1.5;
// Item entity translucency keeps one shared depth value per screen pixel. Use
// a small physical layer so volumes occupying the same air space resolve to
// exactly the same depth and both reach the normal alpha accumulator. Rounding
// away from the camera retains opaque-world occlusion and limits the maximum
// conservative offset to 15.625 mm.
const float TRP_VOLUME_DEPTH_LAYER = 1.0 / 64.0;
const int TRP_STROBE_PIPELINE_MODE = 0;

float trpVisibleDepth(float depth) {
    // Protocol v4 validates the complete technical carrier before removing it.
    // Real lenses, beams and projections therefore keep their physical depth,
    // including when the camera enters the cone or zooms close to the fixture.
    return depth;
}

float trpStableVolumeDepth(float depth) {
    // Adjacent beams that terminate on the same surface reconstruct that
    // surface independently. Tiny floating-point differences used to make the
    // item depth buffer alternate between them, exposing hard circular seams
    // at grazing camera angles. Snap only volumetric depth away from the camera;
    // opaque world geometry therefore keeps winning and cannot reveal a beam
    // through a block.
    const float DEPTH_STEPS = 4194304.0;
    return min(1.0, ceil(clamp(depth, 0.0, 1.0) * DEPTH_STEPS) / DEPTH_STEPS);
}

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

int trpStrobeMarkerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

int trpExactProjectionCode() {
    float projectionK = 2.0 / max(abs(ProjMat[1][1]), 0.0001);
    const float minimumK = 0.0000152587890625;
    return int(round(log2(clamp(projectionK, minimumK, 256.0) / minimumK)
        * (65535.0 / 24.0)));
}

vec4 trpEncodeViewPosition(float component) {
    int value = int(round((clamp(component, -2048.0, 2047.0) + 2048.0) * 1000.0));
    int red = value - value / 255 * 255;
    value /= 255;
    int green = value - value / 255 * 255;
    value /= 255;
    int blue = value - value / 255 * 255;
    return vec4(vec3(red, green, blue) / 255.0, 1.0);
}

float trpHash(vec3 position) {
    // Polynomial hash: keeps the same subtle volumetric breakup without doing
    // one expensive transcendental sine for every ray-march sample and pixel.
    vec3 value = fract(position * 0.1031);
    value += dot(value, value.yzx + 33.33);
    return fract((value.x + value.y) * value.z);
}

float trpSourceRadius(float apertureBand) {
    if (apertureBand < 0.5) {
        return 0.00012;
    }
    return pow(4.0, apertureBand - 1.0) / 2048.0;
}

float trpLengthRatio(float ratioCode) {
    return 0.0625 * pow(262144.0, clamp(ratioCode, 0.0, 4095.0) / 4095.0);
}

float trpGoboFocusSigma(int focusBand) {
    if (focusBand <= 0) return 0.0;
    if (focusBand == 1) return 0.75;
    if (focusBand == 2) return 1.5;
    if (focusBand == 3) return 2.5;
    if (focusBand == 4) return 4.0;
    if (focusBand == 5) return 6.0;
    if (focusBand == 6) return 8.5;
    if (focusBand == 7) return 12.0;
    if (focusBand == 8) return 17.0;
    if (focusBand == 9) return 24.0;
    return 34.0;
}

int trpGoboFocusAnchor(int focusBand) {
    if (focusBand >= 8) return 8;
    if (focusBand >= 4) return 4;
    return 0;
}

float trpProjectionAlpha(vec2 atlasCoordinate) {
    vec4 projection = texture(Sampler0, atlasCoordinate);
    bool marker = projection.b > max(projection.r, projection.g) + 0.005;
    return marker ? 0.0 : projection.a;
}

float trpFocusedProjectionAlpha(int focusBand) {
    if (focusBand >= 11) {
        return 0.0;
    }
    int anchorBand = trpGoboFocusAnchor(focusBand);
    float targetSigma = trpGoboFocusSigma(focusBand);
    float anchorSigma = trpGoboFocusSigma(anchorBand);
    float residualSigma = sqrt(max(
        0.0, targetSigma * targetSigma - anchorSigma * anchorSigma
    ));
    if (residualSigma < 0.01) {
        return trpProjectionAlpha(texCoord0);
    }

    // Recover the atlas-to-face mapping from derivatives so samples outside
    // this 512px sprite become transparent instead of leaking an adjacent
    // Nexo/TRP sprite into a wide-focus gobo.
    vec2 localDx = dFdx(trpImpactCoordinate);
    vec2 localDy = dFdy(trpImpactCoordinate);
    vec2 atlasDx = dFdx(texCoord0);
    vec2 atlasDy = dFdy(texCoord0);
    float determinant = localDx.x * localDy.y - localDy.x * localDx.y;
    if (abs(determinant) < 0.000000001) {
        return trpProjectionAlpha(texCoord0);
    }
    vec2 atlasFromLocalX = (atlasDx * localDy.y - atlasDy * localDx.y)
        / determinant;
    vec2 atlasFromLocalY = (atlasDy * localDx.x - atlasDx * localDy.x)
        / determinant;
    vec2 localTexel = vec2(1.0 / 512.0);
    const float weights[5] = float[5](
        0.054488685, 0.244201342, 0.402619947, 0.244201342, 0.054488685
    );
    float alpha = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec2 localOffset = vec2(float(x), float(y))
                * residualSigma * localTexel;
            vec2 sampleLocal = trpImpactCoordinate + localOffset;
            if (all(greaterThanEqual(sampleLocal, vec2(0.0)))
                && all(lessThanEqual(sampleLocal, vec2(1.0)))) {
                vec2 sampleAtlas = texCoord0
                    + atlasFromLocalX * localOffset.x
                    + atlasFromLocalY * localOffset.y;
                alpha += trpProjectionAlpha(sampleAtlas)
                    * weights[x + 2] * weights[y + 2];
            }
        }
    }
    return alpha;
}

void trpDecodeVolumeMetadata(
    out float lengthRatioCode,
    out float dimmer,
    out vec3 beamColor,
    out float sourceGlareEnabled
) {
    vec3 encodedBytes = floor(trpBeamColor.rgb * 255.0 + 0.5);
    // 12 ratio bits cover narrow iris states as well as wide washes. The CPU
    // snaps proxy width to this exact ratio so cap and side faces reconstruct
    // the same source instead of moving it when the camera changes faces.
    float packedMetadata = mod(encodedBytes.r, 8.0)
        + mod(encodedBytes.g, 8.0) * 8.0
        + mod(encodedBytes.b, 8.0) * 64.0
        + round(trpMetadataBands.x) * 512.0
        + round(trpMetadataBands.y) * 8192.0;
    lengthRatioCode = mod(packedMetadata, 4096.0);
    sourceGlareEnabled = mod(floor(packedMetadata / 4096.0), 2.0);
    float dimmerCode = floor(packedMetadata / 8192.0);
    dimmer = dimmerCode <= 0.0
        ? 0.0
        : exp2((dimmerCode - 15.0) * (16.0 / 14.0));

    // Restore the centre of the original eight-value RGB interval.
    beamColor = (floor(encodedBytes / 8.0) * 8.0 + vec3(3.5)) / 255.0;
}

bool trpSolvePlane(vec2 localCoordinate, out vec3 firstColumn, out vec3 secondColumn) {
    vec2 localDx = dFdx(localCoordinate);
    vec2 localDy = dFdy(localCoordinate);
    vec3 viewDx = dFdx(trpViewPosition);
    vec3 viewDy = dFdy(trpViewPosition);
    float determinant = localDx.x * localDy.y - localDy.x * localDx.y;
    // Zoom changes the magnitude of both derivatives without making their
    // directions degenerate. An absolute threshold erased valid large faces.
    float derivativeScale = length(localDx) * length(localDy);
    if (derivativeScale == 0.0 || abs(determinant) <= derivativeScale * 0.000001) {
        return false;
    }
    firstColumn = (viewDx * localDy.y - viewDy * localDx.y) / determinant;
    secondColumn = (viewDy * localDx.x - viewDx * localDy.x) / determinant;
    return true;
}

bool trpReconstructTransform(
    float lengthRatioCode,
    out mat3 viewFromLocal,
    out vec3 sourceViewPosition
) {
    vec2 tangentCoordinate;
    if (trpFaceIndex < 1.5) {
        tangentCoordinate = trpLocalDelta.xz;
    } else if (trpFaceIndex < 3.5) {
        tangentCoordinate = trpLocalDelta.xy;
    } else {
        tangentCoordinate = trpLocalDelta.yz;
    }

    vec3 firstColumn;
    vec3 secondColumn;
    if (!trpSolvePlane(tangentCoordinate, firstColumn, secondColumn)) {
        return false;
    }

    vec3 xColumn;
    vec3 yColumn;
    vec3 zColumn;
    if (trpFaceIndex < 1.5) {
        xColumn = firstColumn;
        zColumn = secondColumn;
        yColumn = trpHandedness * normalize(cross(zColumn, xColumn)) * length(xColumn);
    } else if (trpFaceIndex < 3.5) {
        xColumn = firstColumn;
        yColumn = secondColumn;
        zColumn = trpHandedness * normalize(cross(xColumn, yColumn))
            * length(xColumn) * trpLengthRatio(lengthRatioCode);
    } else {
        yColumn = firstColumn;
        zColumn = secondColumn;
        xColumn = trpHandedness * normalize(cross(yColumn, zColumn)) * length(yColumn);
    }

    if (length(xColumn) < 0.00001 || length(yColumn) < 0.00001
        || length(zColumn) < 0.00001) {
        return false;
    }
    viewFromLocal = mat3(xColumn, yColumn, zColumn);
    if (abs(determinant(viewFromLocal)) < 0.00000001) {
        return false;
    }
    sourceViewPosition = trpViewPosition - viewFromLocal * trpLocalPosition;
    return true;
}

bool trpBoxInterval(vec3 origin, vec3 direction, out float nearDistance, out float farDistance) {
    vec3 safeDirection = direction;
    safeDirection.x = abs(safeDirection.x) < 0.00001
        ? (safeDirection.x < 0.0 ? -0.00001 : 0.00001) : safeDirection.x;
    safeDirection.y = abs(safeDirection.y) < 0.00001
        ? (safeDirection.y < 0.0 ? -0.00001 : 0.00001) : safeDirection.y;
    safeDirection.z = abs(safeDirection.z) < 0.00001
        ? (safeDirection.z < 0.0 ? -0.00001 : 0.00001) : safeDirection.z;

    float maximumZ = trpOpenAir > 0.5 ? 1.0 : TRP_PROXY_MAX_Z;
    // Past z=1 an impacted beam is only an invisible coverage extrusion used
    // to meet an oblique plane. Its optical radius must stay at the zoom's
    // terminal 0.5 instead of continuing to diverge with the larger proxy.
    float maximumRadius = 0.5;
    vec3 first = (vec3(-maximumRadius, -maximumRadius, 0.0) - origin) / safeDirection;
    vec3 second = (vec3(maximumRadius, maximumRadius, maximumZ) - origin) / safeDirection;
    vec3 lower = min(first, second);
    vec3 upper = max(first, second);
    nearDistance = max(max(lower.x, lower.y), max(lower.z, 0.0));
    farDistance = min(min(upper.x, upper.y), upper.z);
    return farDistance > nearDistance;
}

bool trpClipToCone(
    vec3 origin,
    vec3 direction,
    float sourceRadius,
    inout float nearDistance,
    inout float farDistance
) {
    float slope = 0.5 - sourceRadius;
    float radiusAtOrigin = sourceRadius + slope * origin.z;
    float a = dot(direction.xy, direction.xy) - slope * slope * direction.z * direction.z;
    float b = 2.0 * (dot(origin.xy, direction.xy) - radiusAtOrigin * slope * direction.z);
    float c = dot(origin.xy, origin.xy) - radiusAtOrigin * radiusAtOrigin;

    if (a > 0.000001) {
        float discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return false;
        }
        float root = sqrt(discriminant);
        float first = (-b - root) / (2.0 * a);
        float second = (-b + root) / (2.0 * a);
        nearDistance = max(nearDistance, min(first, second));
        farDistance = min(farDistance, max(first, second));
    }
    return farDistance > nearDistance;
}

bool trpClipToImpactPlane(
    vec3 origin,
    vec3 direction,
    mat3 viewFromLocal,
    inout float nearDistance,
    inout float farDistance,
    out bool reachesImpact,
    out float impactDistance
) {
    reachesImpact = false;
    impactDistance = farDistance;
    if (trpOpenAir > 0.5) {
        return true;
    }

    // Convert the axis-aligned world hit face to the non-uniform local beam
    // coordinate system. The plane passes through the beam endpoint (z = 1).
    vec3 localPlaneNormal = transpose(viewFromLocal) * trpImpactViewNormal;
    float normalLength = length(localPlaneNormal);
    if (normalLength < 0.00001) {
        return true;
    }
    localPlaneNormal /= normalLength;
    // Bukkit normals face out of the solid. Keep the lens/source on that side
    // even if a driver supplies a view matrix with the opposite convention.
    if (localPlaneNormal.z > 0.0) {
        localPlaneNormal *= -1.0;
    }

    float originSide = dot(localPlaneNormal, origin - vec3(0.0, 0.0, 1.0));
    float directionSide = dot(localPlaneNormal, direction);
    if (abs(directionSide) < 0.00001) {
        return originSide >= -0.0005;
    }

    impactDistance = -originSide / directionSide;
    vec3 impactPoint = origin + direction * impactDistance;
    // The impact display is the projection of the far circular aperture onto
    // the real block face. Do not require the plane hit to fall before the old
    // perpendicular proxy cap: on an oblique wall one half is necessarily past
    // z = 1, and that is exactly the half that used to disappear under haze.
    reachesImpact = originSide >= -0.002
        && impactDistance >= max(0.0, nearDistance) - 0.002
        && dot(impactPoint.xy, impactPoint.xy) <= 0.251;

    // Retain only the half-space on the lens side of the receiving block face.
    if (directionSide < 0.0) {
        farDistance = min(farDistance, impactDistance);
    } else {
        nearDistance = max(nearDistance, impactDistance);
    }
    return farDistance > nearDistance;
}

void main() {
    if (trpStrobeMarker > 0.5) {
        vec2 coordinateDx = dFdx(trpStrobeCoordinate);
        vec2 coordinateDy = dFdy(trpStrobeCoordinate);
        mat2 coordinatePerPixel = mat2(coordinateDx, coordinateDy);
        if (abs(determinant(coordinatePerPixel)) < 0.000000000001) {
            discard;
        }
        vec2 pixelOffset = inverse(coordinatePerPixel)
            * (trpStrobeCoordinate - vec2(0.5));
        // Bias exact half-pixel boundaries consistently across both triangles.
        // Otherwise interpolation round-off can create two center cells.
        ivec2 cell = ivec2(floor(pixelOffset + vec2(0.501)));
        float centerDepth = gl_FragCoord.z
            - pixelOffset.x * dFdx(gl_FragCoord.z)
            - pixelOffset.y * dFdy(gl_FragCoord.z);
        if (trpStrobeLegacyMarker > 0.5) {
            if (abs(cell.x) > 2 || cell.y != 0) discard;
            int projectionCode = trpExactProjectionCode();
            if (cell.x == -2) {
                fragColor = vec4(float((projectionCode >> 8) & 255), 17.0, 91.0, 255.0) / 255.0;
            } else if (cell.x == 2) {
                fragColor = vec4(float(projectionCode & 255), 203.0, 47.0, 255.0) / 255.0;
            } else if (cell.x < 0) {
                // The alternate green byte identifies a TRP light while the
                // original guard remains exclusive to StrobeLights emitters.
                float radiusLowCode = clamp(
                    round(trpStrobePayload.a * 255.0) - 1.0,
                    0.0,
                    15.0
                );
                float leftGreen = trpStrobePayload.a < 0.5
                    ? 70.0 + radiusLowCode : 69.0;
                fragColor = vec4(194.0, leftGreen, 253.0, 255.0) / 255.0;
            } else if (cell.x == 0) {
                fragColor = vec4(trpStrobePayload.rgb, 1.0);
            } else {
                fragColor = vec4(61.0, 186.0, 2.0, 255.0) / 255.0;
            }
        } else {
            if (abs(cell.x) > 1 || abs(cell.y) > 1) discard;
            int cellIndex = (cell.y + 1) * 3 + cell.x + 1;
            int encodedValue = trpStrobeMarkerValue(trpStrobePayload.rgb);
            if (cellIndex == 4) {
                float radiusLowCode = clamp(
                    round(trpStrobePayload.a * 255.0) - 1.0,
                    0.0,
                    15.0
                );
                float centerAlpha = trpStrobePayload.a < 0.5
                    ? 7.0 + radiusLowCode : 5.0;
                fragColor = vec4(vec3(0.4), centerAlpha / 255.0);
            } else {
                int payloadIndex = cellIndex < 4 ? cellIndex : cellIndex - 1;
                int triplet = (encodedValue >> (payloadIndex * 3)) & 7;
                vec3 bitColor = vec3(
                    float(triplet & 1),
                    float((triplet >> 1) & 1),
                    float((triplet >> 2) & 1)
                );
                int projectionCode = trpExactProjectionCode();
                int alphaByte = payloadIndex < 4
                    ? 2 + ((projectionCode >> (payloadIndex * 4)) & 15) : 2;
                fragColor = vec4(bitColor * 0.5, float(alphaByte) / 255.0);
            }
        }
        gl_FragDepth = centerDepth * 0.025;
        return;
    }

    if (trpMarkerFlag > 2.5) {
        vec2 coordinateWidth = max(fwidth(trpLightCoordinate), vec2(0.000001));
        ivec2 markerPixel = ivec2(floor(
            (trpLightCoordinate - vec2(0.5)) / coordinateWidth + vec2(0.5)
        ));
        float intensityBand = round(trpMetadataBands.x);
        float radiusHighBand = round(trpMetadataBands.y);
        // The upper texture bits preserve the opaque Light Painter palette;
        // the low red/green pairs carry the lower four radius bits.
        vec4 encodedTexel = floor(texture(Sampler0, texCoord0) * 255.0 + 0.5);
        vec3 encodedBytes = encodedTexel.rgb;
        float radiusLowBand = mod(encodedBytes.r, 4.0) * 4.0
            + mod(encodedBytes.g, 4.0);
        float volumeMarker = encodedTexel.a < 249.5 ? 1.0 : 0.0;
        float wrapImpact = encodedTexel.a > 248.5 && encodedTexel.a < 249.5
            ? 1.0 : 0.0;
        float volumeLengthBand = mod(encodedBytes.r, 4.0)
            + mod(encodedBytes.g, 4.0) * 4.0
            + mod(encodedBytes.b, 4.0) * 16.0;
        vec3 lightColor = (floor(encodedBytes / 4.0) * 4.0 + vec3(1.5)) / 255.0;
        // In a combined pack the vertex stage routes this marker through the
        // dedicated StrobeLights carrier before reaching this branch. Emit
        // only TRP's own guarded row here: the standalone post chain consumes
        // and removes it, while no orphan Strobe transport remains visible.
        if (all(equal(markerPixel, ivec2(-1, 0)))
            || all(equal(markerPixel, ivec2(13, 0)))) {
            // Keep data pixels opaque: the vanilla item render type multiplies
            // RGB by source alpha. Two low nibbles carry metadata while the
            // high nibbles, blue byte and alpha remain a guarded signature.
            // A guard at each end means the final composite can always identify
            // the whole carrier even when terrain clips one end at equal depth.
            fragColor = vec4(
                192.0 + intensityBand,
                64.0 + radiusHighBand,
                mix(253.0, 252.0, volumeMarker),
                255.0
            ) / 255.0;
        } else if (all(equal(markerPixel, ivec2(0, 0)))) {
            fragColor = vec4(lightColor, 1.0);
        } else if (all(equal(markerPixel, ivec2(1, 0)))) {
            fragColor = vec4(trpLightBeamDirection * 0.5 + 0.5, 1.0);
        } else if (all(equal(markerPixel, ivec2(2, 0)))) {
            fragColor = vec4(trpLightViewNormal * 0.5 + 0.5, 1.0);
        } else if (all(equal(markerPixel, ivec2(3, 0)))) {
            int projectionCode = trpExactProjectionCode();
            fragColor = vec4(
                mix(
                    radiusLowBand,
                    mix(128.0, 64.0, wrapImpact) + volumeLengthBand,
                    volumeMarker
                ) / 255.0,
                float((projectionCode >> 8) & 255) / 255.0,
                float(projectionCode & 255) / 255.0,
                1.0
            );
        } else if (markerPixel.y == 0 && markerPixel.x >= 4 && markerPixel.x <= 12) {
            // Repeat each position component three times. Redundancy survives
            // the exact half-pixel boundary shared by the carrier triangles.
            int positionComponent = (markerPixel.x - 4) / 3;
            fragColor = trpEncodeViewPosition(trpLightViewPosition[positionComponent]);
        } else {
            discard;
        }
        // Payload position is explicit, so use a private front depth. This
        // prevents a nearer translucent beam from discarding the marker of a
        // rear beam before the order-independent compositor can collect it.
        gl_FragDepth = gl_FragCoord.z * 0.020;
        return;
    }

    if (trpMarkerFlag < 0.5) {
        vec4 sampledTexture = texture(Sampler0, texCoord0);
        vec4 sampledBytes = floor(sampledTexture * 255.0 + 0.5);
        vec4 color = sampledTexture * vertexColor * ColorModulator;
        if (color.a < 0.1) {
            discard;
        }
        gl_FragDepth = trpVisibleDepth(gl_FragCoord.z);
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    if (trpMarkerFlag > 1.5) {
        vec4 projection = texture(Sampler0, texCoord0);
        // Keep metadata above OptiFine's transparent-color cleanup threshold,
        // then explicitly hide it in the fragment pass.
        // Projection masks are achromatic. Also reject bilinear mixtures of
        // the blue data border, which are no longer the exact marker RGBA.
        bool projectionMarker = projection.b > max(projection.r, projection.g) + 0.005;
        bool modernGoboFocus = round(trpMetadataBands.y) == 14.0;
        int focusBand = int(round(trpMetadataBands.x));
        float projectionAlpha = modernGoboFocus
            ? trpFocusedProjectionAlpha(focusBand) : projection.a;
        if (projectionAlpha < 0.1 || (!modernGoboFocus && projectionMarker)) {
            discard;
        }
        vec3 encodedBytes = floor(trpBeamColor.rgb * 255.0 + 0.5);
        float dimmerCode = mod(encodedBytes.g, 4.0) * 4.0
            + mod(encodedBytes.b, 4.0);
        float dimmer = dimmerCode <= 0.0
            ? 0.0
            : exp2((dimmerCode - 15.0) * (16.0 / 14.0));
        if (dimmer <= 0.0) {
            discard;
        }
        vec3 projectionColor = (floor(encodedBytes / 4.0) * 4.0 + vec3(1.5)) / 255.0;
        float alpha = projectionAlpha * pow(dimmer, 0.72);
        alpha *= mix(0.42, 1.0, linear_fog_fade(vertexDistance, FogStart, FogEnd));
        if (alpha < 0.002) {
            discard;
        }
        // Keep emitted RGB bright and fade its alpha. Darkening RGB produced
        // a grey "shadow" on white walls and overlapping prism crescents.
        gl_FragDepth = trpVisibleDepth(gl_FragCoord.z);
        fragColor = vec4(projectionColor * ColorModulator.rgb, alpha);
        return;
    }

    float lengthRatioCode;
    float dimmer;
    vec3 decodedBeamColor;
    float sourceGlareEnabled;
    trpDecodeVolumeMetadata(
        lengthRatioCode, dimmer, decodedBeamColor, sourceGlareEnabled
    );
    bool spot = trpBeamStyle > 0.5;
    if (dimmer <= 0.0) {
        discard;
    }

    mat3 viewFromLocal;
    vec3 sourceViewPosition;
    if (!trpReconstructTransform(lengthRatioCode, viewFromLocal, sourceViewPosition)) {
        discard;
    }

    vec3 rayOrigin = inverse(viewFromLocal) * -sourceViewPosition;
    vec3 rayDirection = normalize(trpLocalPosition - rayOrigin);
    float proxyMaximumZ = TRP_PROXY_MAX_Z;
    bool cameraInsideProxy = rayOrigin.x > -TRP_PROXY_HALF_WIDTH
        && rayOrigin.x < TRP_PROXY_HALF_WIDTH
        && rayOrigin.y > -TRP_PROXY_HALF_WIDTH
        && rayOrigin.y < TRP_PROXY_HALF_WIDTH
        && rayOrigin.z > TRP_PROXY_MIN_Z
        && rayOrigin.z < proxyMaximumZ;
    bool inwardCarrier = trpHandedness < 0.0;
    // In the near-plane shell the front proxy face is clipped before this
    // shader can run. Let the inward face cover precisely those sightlines.
    // Use the real proxy bounds, which extend beyond the optical volume.
    vec3 safeProxyRay = rayDirection;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(safeProxyRay[axis]) < 0.00000001) {
            safeProxyRay[axis] = safeProxyRay[axis] < 0.0 ? -0.00000001 : 0.00000001;
        }
    }
    vec3 proxyFirst = (vec3(-TRP_PROXY_HALF_WIDTH, -TRP_PROXY_HALF_WIDTH,
        TRP_PROXY_MIN_Z) - rayOrigin) / safeProxyRay;
    vec3 proxySecond = (vec3(TRP_PROXY_HALF_WIDTH, TRP_PROXY_HALF_WIDTH,
        TRP_PROXY_MAX_Z) - rayOrigin) / safeProxyRay;
    vec3 proxyNear = min(proxyFirst, proxySecond);
    float proxyEntry = max(0.0, max(proxyNear.x, max(proxyNear.y, proxyNear.z)));
    vec3 entryView = sourceViewPosition + viewFromLocal
        * (rayOrigin + rayDirection * proxyEntry);
    vec4 entryClip = ProjMat * vec4(entryView, 1.0);
    bool chooseInward = cameraInsideProxy || entryClip.w <= 0.0
        || abs(entryClip.z) >= entryClip.w;
    if (chooseInward != inwardCarrier) {
        discard;
    }
    float cameraSourceDistance = length(sourceViewPosition);
    vec3 viewAxis = normalize(viewFromLocal[2]);
    vec3 physicalRayDirection = normalize(viewFromLocal * rayDirection);
    float cameraForwardDistance = dot(-sourceViewPosition, viewAxis);
    float cameraInFront = smoothstep(0.025, 0.200, cameraForwardDistance);
    float glareDistanceStrength = mix(
        0.10,
        1.0,
        1.0 - smoothstep(3.0, 42.0, cameraSourceDistance)
    );
    // Scale the source-plane footprint with distance to keep a stable angular
    // size. Approaching the light therefore raises exposure, not halo size.
    float glareAngularScale = clamp(cameraSourceDistance / 16.0, 0.22, 2.50);
    float nearDistance;
    float farDistance;
    if (!trpBoxInterval(rayOrigin, rayDirection, nearDistance, farDistance)) {
        discard;
    }

    float sourceRadius = trpSourceRadius(trpApertureBand);
    if (!trpClipToCone(rayOrigin, rayDirection, sourceRadius, nearDistance, farDistance)) {
        discard;
    }
    // Clip the volume against the actual block face instead of a perpendicular
    // end disc. Keep whether this sightline reaches the focused projection so
    // its haze can remain present but optically thin enough to see the gobo.
    bool reachesImpact;
    float impactDistance;
    if (!trpClipToImpactPlane(
        rayOrigin, rayDirection, viewFromLocal, nearDistance, farDistance,
        reachesImpact, impactDistance
    )) {
        discard;
    }

    // When the camera is physically inside the cone and looks back towards its
    // source, a long ray of haze otherwise blends over the fixture and its
    // emissive lens. Preserve the surrounding volume but clear that sightline.
    float cameraLongitudinal = rayOrigin.z;
    float cameraRadius = mix(sourceRadius, 0.5, saturate(cameraLongitudinal));
    float cameraRadial = length(rayOrigin.xy) / max(cameraRadius, 0.00012);
    // Glare gets a slightly forgiving boundary so numerical reconstruction at
    // the edge of a very wide WTF! wash cannot make the effect disappear.
    float glareCameraInside = smoothstep(-0.180, -0.045, cameraLongitudinal)
        * (1.0 - smoothstep(0.985, 1.060, cameraLongitudinal))
        * (1.0 - smoothstep(1.00, 1.30, cameraRadial));
    glareCameraInside *= cameraInFront;
    float sourceFacing = dot(physicalRayDirection, -viewAxis);
    float lookingTowardSource = smoothstep(0.30, 0.82, sourceFacing);
    float sourceVisibility = 0.0;
    float sourceGlare = 0.0;

    // Independently of which proxy face produced this fragment, protect the
    // real optical face. Intersect the view ray with the source plane and
    // preserve the lens aperture without thinning the surrounding volume.
    if (abs(rayDirection.z) > 0.00001) {
        float sourceDistance = -rayOrigin.z / rayDirection.z;
        vec2 sourcePoint = rayOrigin.xy + rayDirection.xy * sourceDistance;
        vec2 physicalSourcePoint = vec2(
            sourcePoint.x * length(viewFromLocal[0]),
            sourcePoint.y * length(viewFromLocal[1])
        );
        // Clear haze only over the actual optical aperture. A fixed 480 mm
        // rectangle erased the beginning of smaller beams; clearing the whole
        // cone when looking back also made the volume vanish from inside.
        float sourceWindowRadius = max(sourceRadius, 0.00012);
        float sourceWindow = step(0.0, sourceDistance)
            * (1.0 - smoothstep(0.90, 1.05,
                length(sourcePoint) / sourceWindowRadius));
        sourceVisibility = sourceWindow * lookingTowardSource * cameraInFront;

        // Keep the strobe glare bright but compact. The previous 1.85 x 1.05 m
        // angular footprint became a giant opaque disc at show distance. A
        // small bloom and a thin horizontal streak now frame the real lens.
        vec2 angularGlarePoint = physicalSourcePoint / glareAngularScale;
        float glareDistance = length(angularGlarePoint / TRP_GLARE_BLOOM_RADIUS);
        float glareBloom = 1.0 - smoothstep(0.04, 1.15, glareDistance);
        float horizontalStreak =
            (1.0 - smoothstep(0.012, 0.10, abs(angularGlarePoint.y)))
            * (1.0 - smoothstep(0.16, 1.30, abs(angularGlarePoint.x)));
        float glareShape = max(glareBloom * 0.72, horizontalStreak);

        // Keep the real optical face unobstructed. The former 18% glare floor
        // still wrote a translucent source plate over the lens and hid its DMX
        // pixels when the camera was inside the volume. Bloom now starts just
        // outside the complete WTF!/Spiider face and feathers outwards.
        float lensDistance = length(angularGlarePoint / TRP_GLARE_LENS_RADIUS);
        float lensReadability = smoothstep(0.90, 1.18, lensDistance);
        glareShape *= lensReadability;
        sourceGlare = sourceGlareEnabled * glareCameraInside * lookingTowardSource
            * step(0.0, sourceDistance) * glareShape * glareDistanceStrength;
    }

    float interval = farDistance - nearDistance;
    float accumulatedDensity = 0.0;
    float accumulatedDensityDistance = 0.0;
    float coneAspect = length(viewFromLocal[2])
        / max(0.00001, max(length(viewFromLocal[0]), length(viewFromLocal[1])));
    float narrowWash = smoothstep(1.0, 5.0, coneAspect);
    // A 50-degree wash crosses far more haze per screen ray than a narrow
    // beam. Without divergence compensation it saturates into a flat coloured
    // proxy face. Keep broad washes airy and give them a longer feather.
    float washDensity = spot ? 1.0 : mix(0.32, 1.0, narrowWash);
    for (int sampleIndex = 0; sampleIndex < TRP_VOLUME_SAMPLES; sampleIndex++) {
        float fraction = (float(sampleIndex) + 0.5) / float(TRP_VOLUME_SAMPLES);
        float sampleDistance = nearDistance + interval * fraction;
        vec3 position = rayOrigin + rayDirection * sampleDistance;
        float longitudinal = saturate(position.z);
        float radius = mix(sourceRadius, 0.5, longitudinal);
        float radial = length(position.xy) / max(radius, 0.00012);

        float edgeStart = spot ? 0.82 : mix(0.48, 0.68, narrowWash);
        float edge = 1.0 - smoothstep(edgeStart, 1.0, radial);
        float centre = exp(-radial * radial * (spot ? 2.7 : 1.65));
        // Fade over 10 mm at the lens, independent of the total beam length.
        float lensFade = smoothstep(0.0, 0.010,
            longitudinal * length(viewFromLocal[2]));
        float endFade = trpOpenAir > 0.5
            ? 1.0 - smoothstep(0.55, 0.92, longitudinal)
            : 1.0;
        float distanceFalloff = mix(1.0, spot ? 0.82 : 0.88, longitudinal);
        float noise = mix(0.955, 1.045, trpHash(position * vec3(31.0, 31.0, 53.0)));
        float sampleDensity = edge * mix(0.30, 1.0, centre) * washDensity
            * lensFade * endFade * distanceFalloff * noise;
        accumulatedDensity += sampleDensity;
        accumulatedDensityDistance += sampleDensity * sampleDistance;
    }

    float averageDensity = accumulatedDensity / float(TRP_VOLUME_SAMPLES);
    vec3 localStart = rayOrigin + rayDirection * nearDistance;
    vec3 localEnd = rayOrigin + rayDirection * farDistance;
    vec3 viewDelta = viewFromLocal * (localEnd - localStart);
    float physicalDistance = length(viewDelta);

    vec3 viewRay = normalize(viewDelta);
    float forwardScatter = pow(max(dot(viewAxis, -viewRay), 0.0), 6.0);
    float scattering = mix(0.88, 1.30, forwardScatter);
    float opticalDepth = averageDensity * physicalDistance * (spot ? 0.72 : 0.88);
    float rawHazeAlpha = (1.0 - exp(-opticalDepth))
        * pow(dimmer, 0.72) * scattering * (1.0 - sourceVisibility);
    float glareAlpha = sourceGlare * pow(dimmer, 0.36) * 0.780;
    // Haze emits/scatters light but must not behave like an opaque coloured
    // sheet when viewed along the cone. Keep the volumetric body translucent;
    // the explicitly modelled WTF! source glare may still peak more strongly.
    float maximumHazeAlpha = spot ? 0.46 : mix(0.24, 0.38, narrowWash);
    // Compress smoothly into the cap. A hard min created a flat opacity shelf;
    // several neighbouring cones made its boundary appear as large rings.
    float alpha = maximumHazeAlpha * (
        1.0 - exp(-saturate(rawHazeAlpha) / max(maximumHazeAlpha, 0.001))
    );
    alpha = max(alpha, min(0.72, glareAlpha));
    if (alpha < 0.002) {
        discard;
    }

    vec3 beamColor = decodedBeamColor * ColorModulator.rgb;
    float centreLift = saturate(averageDensity * 0.36 * dimmer);
    beamColor = mix(beamColor, vec3(1.0), centreLift);
    float glareWhiteMix = saturate(sourceGlare * 1.25);
    beamColor = mix(beamColor, vec3(1.0), glareWhiteMix);
    vec3 localMidpoint = (localStart + localEnd) * 0.5;
    float volumeDistance = length(sourceViewPosition + viewFromLocal * localMidpoint);
    float volumeFogFade = linear_fog_fade(volumeDistance, FogStart, FogEnd);
    // Vanilla fog shades the carrier surface toward the night-sky colour. That
    // makes a long, open-air beam turn black and disappear. Volumetric light
    // keeps most of its emitted RGB while its accumulated alpha fades instead.
    alpha *= mix(0.42, 1.0, volumeFogFade);
    beamColor = mix(
        beamColor,
        FogColor.rgb,
        (1.0 - volumeFogFade) * FogColor.a * 0.12
    );
    // A translucent volume needs one representative depth even though its
    // light occupies a complete interval. Use the camera ray's closest point
    // to the optical axis: two axes that cross in the same visible pixel then
    // resolve to the same physical depth and both reach the alpha accumulator.
    // The density centroid remains the stable fallback while looking almost
    // exactly along the axis, where the closest-point equation is singular.
    float densityCentroidDistance = accumulatedDensityDistance
        / max(accumulatedDensity, 0.000001);
    float radialDirectionLengthSquared = dot(rayDirection.xy, rayDirection.xy);
    float axisClosestDistance = densityCentroidDistance;
    if (radialDirectionLengthSquared > 0.000001) {
        axisClosestDistance = clamp(
            -dot(rayOrigin.xy, rayDirection.xy) / radialDirectionLengthSquared,
            nearDistance,
            farDistance
        );
    }
    // At the focused footprint keep the receiving-plane depth. Opaque gobo
    // pixels (rendered slightly in front of the block) then win independently
    // of entity order, while transparent cut-outs retain the complete haze.
    float depthDistance = reachesImpact ? impactDistance : axisClosestDistance;
    vec3 localDepthPosition = rayOrigin + rayDirection * depthDistance;
    vec3 viewDepthPosition = sourceViewPosition + viewFromLocal * localDepthPosition;
    if (!reachesImpact && viewDepthPosition.z < 0.0) {
        float layeredViewDistance = ceil(
            -viewDepthPosition.z / TRP_VOLUME_DEPTH_LAYER
        ) * TRP_VOLUME_DEPTH_LAYER;
        viewDepthPosition.z = -layeredViewDistance;
    }
    vec4 clipDepthPosition = ProjMat * vec4(viewDepthPosition, 1.0);
    if (clipDepthPosition.w > 0.00001) {
        float normalizedDepth = clipDepthPosition.z / clipDepthPosition.w;
        gl_FragDepth = trpStableVolumeDepth(
            clamp(normalizedDepth * 0.5 + 0.5, 0.0, 1.0)
        );
    } else {
        gl_FragDepth = trpStableVolumeDepth(gl_FragCoord.z);
    }
    fragColor = vec4(beamColor, alpha);
}
