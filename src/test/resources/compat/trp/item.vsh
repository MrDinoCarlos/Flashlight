#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;
out vec4 normal;

out vec3 trpLocalPosition;
out vec3 trpLocalDelta;
out vec3 trpViewPosition;
flat out float trpMarkerFlag;
flat out float trpFaceIndex;
flat out float trpApertureBand;
flat out float trpBeamStyle;
flat out float trpOpenAir;
flat out float trpHandedness;
flat out vec3 trpImpactViewNormal;
flat out vec2 trpMetadataBands;
flat out vec4 trpBeamColor;
out vec2 trpLightCoordinate;
out vec2 trpImpactCoordinate;
flat out vec3 trpLightViewNormal;
flat out vec3 trpLightBeamDirection;
flat out vec3 trpLightViewPosition;
flat out float trpStrobeMarker;
flat out float trpStrobeLegacyMarker;
flat out vec4 trpStrobePayload;
out vec2 trpStrobeCoordinate;

const float TRP_PROXY_HALF_WIDTH = 1.0;
const float TRP_PROXY_MIN_Z = -0.125;
const float TRP_PROXY_MAX_Z = 1.5;
const float TRP_MARKER_PADDING = 0.125;
const float TRP_MARKER_SPAN = 0.75;
// ResourcePackMerger replaces this constant only in a combined pack:
// 0 = TRP pipeline, 1 = StrobeLights 3x3 bit grid, 2 = legacy guarded row.
const int TRP_STROBE_PIPELINE_MODE = 0;

float trpDecodeMarkerCoordinate(float coordinate) {
    return clamp((coordinate - TRP_MARKER_PADDING) / TRP_MARKER_SPAN, 0.0, 1.0);
}

float trpProxyWidth(float coordinate) {
    return mix(-TRP_PROXY_HALF_WIDTH, TRP_PROXY_HALF_WIDTH, coordinate);
}

float trpProxyDepth(float coordinate) {
    return mix(TRP_PROXY_MIN_Z, TRP_PROXY_MAX_Z, coordinate);
}

vec3 trpDecodeLocalPosition(float face, vec2 encoded) {
    // Each atlas tile has 1/8 padding on all sides so neighbouring face IDs
    // cannot bleed through texture filtering. Remove that padding before using
    // the channels as geometric coordinates; otherwise every face reconstructs
    // a different transform and the volume jumps as the camera moves around it.
    float u = trpDecodeMarkerCoordinate(encoded.x);
    float v = trpDecodeMarkerCoordinate(encoded.y);
    if (face < 0.5) {
        return vec3(trpProxyWidth(u), -TRP_PROXY_HALF_WIDTH, trpProxyDepth(1.0 - v));
    }
    if (face < 1.5) {
        return vec3(trpProxyWidth(u), TRP_PROXY_HALF_WIDTH, trpProxyDepth(v));
    }
    if (face < 2.5) {
        return vec3(trpProxyWidth(1.0 - u), trpProxyWidth(1.0 - v), TRP_PROXY_MIN_Z);
    }
    if (face < 3.5) {
        // This is the real rear face of the extended proxy, not the optical
        // impact plane. Decoding it at z=1 reconstructed a false camera/source
        // transform whenever this face drove the fragment and could paint a
        // detached volume beyond solid geometry.
        return vec3(trpProxyWidth(u), trpProxyWidth(1.0 - v), TRP_PROXY_MAX_Z);
    }
    if (face < 4.5) {
        return vec3(-TRP_PROXY_HALF_WIDTH, trpProxyWidth(1.0 - v), trpProxyDepth(u));
    }
    return vec3(TRP_PROXY_HALF_WIDTH, trpProxyWidth(1.0 - v), trpProxyDepth(1.0 - u));
}

vec3 trpDecodeSurfaceNormal(float face) {
    if (face < 0.5) {
        return vec3(0.0, -1.0, 0.0);
    }
    if (face < 1.5) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (face < 2.5) {
        return vec3(0.0, 0.0, -1.0);
    }
    if (face < 3.5) {
        return vec3(0.0, 0.0, 1.0);
    }
    if (face < 4.5) {
        return vec3(-1.0, 0.0, 0.0);
    }
    return vec3(1.0, 0.0, 0.0);
}

bool trpIsLightPainterColor(vec3 colorBytes) {
    // Match the original Light Painter palette by its upper six bits. TRP's
    // 16 variants per colour use the ignored low bits for radius precision.
    vec3 highBits = floor(colorBytes / 4.0);
    return all(equal(highBits, vec3(63.0, 53.0, 42.0)))
        || all(equal(highBits, vec3(63.0, 63.0, 63.0)))
        || all(equal(highBits, vec3(58.0, 63.0, 52.0)))
        || all(equal(highBits, vec3(63.0, 0.0, 0.0)))
        || all(equal(highBits, vec3(63.0, 32.0, 0.0)))
        || all(equal(highBits, vec3(63.0, 63.0, 0.0)))
        || all(equal(highBits, vec3(32.0, 63.0, 0.0)))
        || all(equal(highBits, vec3(0.0, 63.0, 0.0)))
        || all(equal(highBits, vec3(0.0, 63.0, 32.0)))
        || all(equal(highBits, vec3(0.0, 63.0, 63.0)))
        || all(equal(highBits, vec3(0.0, 32.0, 63.0)))
        || all(equal(highBits, vec3(0.0, 0.0, 63.0)))
        || all(equal(highBits, vec3(32.0, 0.0, 63.0)))
        || all(equal(highBits, vec3(63.0, 0.0, 63.0)))
        || all(equal(highBits, vec3(63.0, 0.0, 32.0)));
}

int trpMarkerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool trpIsStrobeCameraFlash(int encodedValue) {
    return (encodedValue >> 20) == 13 && (encodedValue & 1) == 1;
}

bool trpIsStrobeSource(int encodedValue) {
    return (encodedValue >> 20) == 10 && (encodedValue & 15) == 5;
}

bool trpIsModernStrobeCarrierTexel(vec4 texel) {
    vec4 bytes = floor(texel * 255.0 + 0.5);
    // OptiFine's AWT sprite scaling rounds low-alpha RGB through an 8-bit
    // premultiplied intermediate. Both exact forms identify the same texture.
    return all(equal(bytes, vec4(37.0, 211.0, 83.0, 5.0)))
        || all(equal(bytes, vec4(51.0, 204.0, 102.0, 5.0)));
}

bool trpIsLegacyStrobeCarrierTexel(vec4 texel) {
    vec4 bytes = floor(texel * 255.0 + 0.5);
    bool sourceCarrier = bytes.g == 32.0 && bytes.b == 224.0;
    bool flashCarrier = bytes.g == 224.0 && bytes.b == 32.0;
    return bytes.a == 253.0 && (sourceCarrier || flashCarrier);
}

int trpDecodeLegacyStrobePayload(vec4 carrierTexel, ivec2 lightCoordinates) {
    bool sourceTexture = carrierTexel.b > carrierTexel.g * 2.0;
    bool flashTexture = carrierTexel.g > carrierTexel.b * 2.0;
    float reference = max(carrierTexel.g, carrierTexel.b);
    int highByte = int(floor(clamp(
        carrierTexel.r / max(reference, 1.0 / 255.0) * 224.0,
        0.0,
        255.0
    ) + 0.5));
    int blockNibble = clamp(lightCoordinates.x / 16, 0, 15);
    int skyNibble = clamp(lightCoordinates.y / 16, 0, 15);
    int transportValue = (highByte << 8) | (skyNibble << 4) | blockNibble;
    if (sourceTexture) {
        return (10 << 20)
            | (((transportValue >> 12) & 15) << 16)
            | (((transportValue >> 8) & 15) << 12)
            | (((transportValue >> 4) & 15) << 8)
            | ((transportValue & 15) << 4)
            | 5;
    }
    if (flashTexture) {
        int power7 = ((((transportValue >> 12) & 15) * 127) + 7) / 15;
        return (13 << 20)
            | (power7 << 13)
            | (((transportValue >> 8) & 15) << 9)
            | (((transportValue >> 4) & 15) << 5)
            | ((transportValue & 15) << 1)
            | 1;
    }
    return 0;
}

vec3 trpStrobeSourceColor(int encodedValue) {
    return vec3(
        float((encodedValue >> 12) & 15),
        float((encodedValue >> 8) & 15),
        float((encodedValue >> 4) & 15)
    ) / 15.0;
}

int trpStrobeProjectionCode(float value) {
    if (value < 0.141421) return 0;
    if (value < 0.178885) return 1;
    if (value < 0.223607) return 2;
    if (value < 0.280625) return 3;
    if (value < 0.354965) return 4;
    if (value < 0.447214) return 5;
    if (value < 0.561249) return 6;
    if (value < 0.709930) return 7;
    if (value < 0.894427) return 8;
    if (value < 1.095445) return 9;
    if (value < 1.296148) return 10;
    if (value < 1.496663) return 11;
    if (value < 1.788854) return 12;
    if (value < 2.190890) return 13;
    if (value < 2.683282) return 14;
    return 15;
}

int trpStrobeOffscreenMode(vec3 source) {
    vec3 absolute = abs(source);
    if (absolute.z >= absolute.x && absolute.z >= absolute.y) {
        return source.z >= 0.0 ? 6 : 1;
    }
    if (absolute.x >= absolute.y && absolute.x >= absolute.z) {
        return source.x >= 0.0 ? 2 : 3;
    }
    return source.y >= 0.0 ? 4 : 5;
}

vec3 trpStrobeOffscreenProxy(vec3 source, int mode) {
    float axisScale = 0.0625;
    float depthScale = 0.25;
    if (mode == 0) return vec3(0.0, 0.0, 0.25);
    if (mode == 1) return vec3(-source.x * axisScale, -source.y * axisScale,
        -source.z * depthScale);
    if (mode == 2) return vec3(-source.z * axisScale, source.y * axisScale,
        source.x * depthScale);
    if (mode == 3) return vec3(source.z * axisScale, source.y * axisScale,
        -source.x * depthScale);
    if (mode == 4) return vec3(source.x * axisScale, -source.z * axisScale,
        source.y * depthScale);
    if (mode == 5) return vec3(source.x * axisScale, source.z * axisScale,
        -source.y * depthScale);
    return vec3(source.x * axisScale, source.y * axisScale, source.z * depthScale);
}

vec3 trpPackStrobeOffscreenColor(
    vec3 color,
    int mode,
    int projectionCode,
    int expansionCode
) {
    ivec3 color4 = ivec3(floor(clamp(color, 0.0, 1.0) * 15.0 + 0.5));
    int packedValue = ((mode & 7) << 20)
        | ((projectionCode & 15) << 16)
        | ((expansionCode & 15) << 12)
        | (color4.r << 8)
        | (color4.g << 4)
        | color4.b;
    return vec3(
        float((packedValue >> 16) & 255),
        float((packedValue >> 8) & 255),
        float(packedValue & 255)
    ) / 255.0;
}

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color)
        * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);

    // Vertex texture sampling has no screen-space derivatives. Pinning the
    // marker atlas to mip zero keeps its encoded coordinates exact at distance.
    vec4 marker = textureLod(Sampler0, UV0, 0.0);
    int markerCode = int(floor(marker.b * 255.0 + 0.5)) - 160;
    float markerAlpha = floor(marker.a * 255.0 + 0.5);
    // Inventory icons share this shader and atlas. Never interpret an
    // orthographic GUI vertex as a world-space technical carrier.
    bool worldProjection = abs(ProjMat[2][3]) > 0.000001;
    bool encodedAlpha = worldProjection && markerAlpha >= 70.5 && markerAlpha <= 94.5;
    bool isBeam = encodedAlpha && markerCode >= 0 && markerCode < 84;
    vec3 markerBytes = floor(marker.rgb * 255.0 + 0.5);
    bool isImpact = worldProjection && markerAlpha >= 31.5 && markerAlpha <= 32.5
        && markerBytes.r >= 16.5 && markerBytes.r <= 17.5
        && markerBytes.g >= 28.5 && markerBytes.g <= 29.5
        && markerBytes.b >= 254.5;
    // Private alphas distinguish point lights from analytic beam carriers.
    // Ordinary white lenses and GUI textures can share the same RGB palette.
    bool isImpactRgbLight = worldProjection && markerAlpha == 250.0
        && trpIsLightPainterColor(markerBytes);
    bool isBeamVolumeLight = worldProjection
        && (markerAlpha == 249.0 || markerAlpha == 248.0)
        && trpIsLightPainterColor(markerBytes);
    bool isRgbLight = isImpactRgbLight || isBeamVolumeLight;
    float markerState = markerAlpha - 71.0;
    float carrierState = mod(markerState, 4.0);
    float surfaceFace = floor(markerState / 4.0);
    trpMarkerFlag = isBeam ? 1.0 : (isImpact ? 2.0 : (isRgbLight ? 3.0 : 0.0));
    trpOpenAir = isBeam && mod(carrierState, 2.0) > 0.5 ? 1.0 : 0.0;
    trpHandedness = isBeam && carrierState > 1.5 ? -1.0 : 1.0;
    trpImpactViewNormal = isBeam
        ? normalize(transpose(IViewRotMat) * trpDecodeSurfaceNormal(surfaceFace))
        : vec3(0.0, 0.0, 1.0);
    trpViewPosition = viewPosition.xyz;
    trpBeamColor = Color;
    trpMetadataBands = clamp(round(vec2(UV2) / 16.0), vec2(0.0), vec2(15.0));
    trpLightCoordinate = vec2(0.5);
    trpImpactCoordinate = vec2(0.5);
    trpLightViewNormal = vec3(0.0, 0.0, 1.0);
    trpLightBeamDirection = vec3(0.0, 0.0, 1.0);
    trpLightViewPosition = vec3(0.0);
    trpStrobeMarker = 0.0;
    trpStrobeLegacyMarker = 0.0;
    trpStrobePayload = vec4(0.0);
    trpStrobeCoordinate = vec2(0.5);

    if (isImpact) {
        if (gl_VertexID % 4 == 0) {
            trpImpactCoordinate = vec2(0.0, 0.0);
        } else if (gl_VertexID % 4 == 1) {
            trpImpactCoordinate = vec2(0.0, 1.0);
        } else if (gl_VertexID % 4 == 2) {
            trpImpactCoordinate = vec2(1.0, 1.0);
        } else {
            trpImpactCoordinate = vec2(1.0, 0.0);
        }
    }

    // StrobeLights and TRP both use the item-entity layer. Preserve TRP's
    // continuous beam shader and emit StrobeLights' guarded carrier here so a
    // combined resource pack can render both systems in the same frame.
    int strobeValue = trpMarkerValue(Color.rgb);
    bool modernStrobeTexture = trpIsModernStrobeCarrierTexel(marker);
    bool legacyStrobeTexture = trpIsLegacyStrobeCarrierTexel(marker);
    if (legacyStrobeTexture) {
        strobeValue = trpDecodeLegacyStrobePayload(marker, UV2);
    }
    bool strobeCarrier = (modernStrobeTexture || legacyStrobeTexture)
        && (trpIsStrobeCameraFlash(strobeValue) || trpIsStrobeSource(strobeValue))
        && worldProjection;
    if (strobeCarrier) {
        // Position already contains the ItemDisplay transform baked by the
        // vertex consumer. Replacing it with model-local (0.5) anchors every
        // light to the camera and collapses unrelated emitters onto one pixel.
        vec3 payload = vec3(
            float((strobeValue >> 16) & 255),
            float((strobeValue >> 8) & 255),
            float(strobeValue & 255)
        ) / 255.0;
        if (trpIsStrobeSource(strobeValue)) {
            int expansionCode = (strobeValue >> 16) & 15;
            vec3 fixedSource = vec3(viewPosition.x, viewPosition.y, -viewPosition.z);
            int mode = trpStrobeOffscreenMode(fixedSource);
            vec3 proxy = trpStrobeOffscreenProxy(fixedSource, mode);
            proxy.z += 0.25;
            int projectionCode = trpStrobeProjectionCode(
                2.0 / max(abs(ProjMat[1][1]), 0.0001)
            );
            viewPosition.xyz = vec3(proxy.x, proxy.y, -proxy.z);
            payload = trpPackStrobeOffscreenColor(
                trpStrobeSourceColor(strobeValue), mode, projectionCode, expansionCode
            );
        }
        trpStrobeMarker = 1.0;
        trpStrobeLegacyMarker = legacyStrobeTexture ? 1.0 : 0.0;
        trpStrobePayload = vec4(payload, 1.0);
        float halfMarker = viewPosition.z / 32.0;
        if (gl_VertexID % 4 == 0) {
            viewPosition.xy += vec2(-halfMarker, halfMarker);
            trpStrobeCoordinate = vec2(0.0, 0.0);
        } else if (gl_VertexID % 4 == 1) {
            viewPosition.xy += vec2(-halfMarker, -halfMarker);
            trpStrobeCoordinate = vec2(0.0, 1.0);
        } else if (gl_VertexID % 4 == 2) {
            viewPosition.xy += vec2(halfMarker, -halfMarker);
            trpStrobeCoordinate = vec2(1.0, 1.0);
        } else {
            viewPosition.xy += vec2(halfMarker, halfMarker);
            trpStrobeCoordinate = vec2(1.0, 0.0);
        }
        trpViewPosition = viewPosition.xyz;
        gl_Position = ProjMat * viewPosition;
        if (trpIsStrobeSource(strobeValue)) {
            // The transport occupies a private, FOV-independent screen lane.
            // Coincident TRP and Strobe sources must not overwrite each other.
            gl_Position.xy = vec2(
                viewPosition.x / -viewPosition.z * 0.75 - 0.45,
                viewPosition.y / -viewPosition.z * 1.5
            ) * gl_Position.w;
        }
        return;
    }

    if (isImpactRgbLight && TRP_STROBE_PIPELINE_MODE > 0) {
        // The combined pack uses StrobeLights' post chain. Transport the TRP
        // impact light through the same complete offscreen contract as a real
        // StrobeLights source. Passing the SOURCE_LIGHT_SIGNATURE bytes as RGB
        // turned green lights yellow and made their position depend on FOV.
        float intensityBand = trpMetadataBands.x;
        float radiusHighBand = trpMetadataBands.y;
        float radiusLowBand = mod(markerBytes.r, 4.0) * 4.0
            + mod(markerBytes.g, 4.0);
        int radiusCode = int(clamp(round(radiusHighBand * 16.0 + radiusLowBand),
            0.0, 255.0));
        // StrobeLights v4 keeps the high and low nibbles of TRP's logarithmic
        // radius separately. This retains all 256 radius bands instead of
        // rounding them down to the unrelated StrobeLights expansion scale.
        int expansionCode = radiusCode / 16;
        int radiusLowCode = radiusCode - expansionCode * 16;
        vec3 lightColor = (floor(markerBytes / 4.0) * 4.0 + vec3(1.5)) / 255.0;
        float maximumLightChannel = max(max(lightColor.r, lightColor.g), lightColor.b);
        vec3 normalizedLightColor = maximumLightChannel > 0.0001
            ? lightColor / maximumLightChannel : vec3(0.0);
        vec3 encodedLightColor = round(clamp(normalizedLightColor, 0.0, 1.0)
            * clamp(round(intensityBand), 0.0, 15.0)) / 15.0;

        vec3 fixedSource = vec3(viewPosition.x, viewPosition.y, -viewPosition.z);
        int mode = trpStrobeOffscreenMode(fixedSource);
        vec3 proxy = trpStrobeOffscreenProxy(fixedSource, mode);
        proxy.z += 0.25;
        int projectionCode = trpStrobeProjectionCode(
            2.0 / max(abs(ProjMat[1][1]), 0.0001)
        );
        viewPosition.xyz = vec3(proxy.x, proxy.y, -proxy.z);
        trpStrobeMarker = 1.0;
        trpStrobeLegacyMarker = TRP_STROBE_PIPELINE_MODE == 2 ? 1.0 : 0.0;
        trpStrobePayload = vec4(
            trpPackStrobeOffscreenColor(
                encodedLightColor, mode, projectionCode, expansionCode
            ),
            float(radiusLowCode + 1) / 255.0
        );

        float halfMarker = viewPosition.z / 32.0;
        if (gl_VertexID % 4 == 0) {
            viewPosition.xy += vec2(-halfMarker, halfMarker);
            trpStrobeCoordinate = vec2(0.0, 0.0);
        } else if (gl_VertexID % 4 == 1) {
            viewPosition.xy += vec2(-halfMarker, -halfMarker);
            trpStrobeCoordinate = vec2(0.0, 1.0);
        } else if (gl_VertexID % 4 == 2) {
            viewPosition.xy += vec2(halfMarker, -halfMarker);
            trpStrobeCoordinate = vec2(1.0, 1.0);
        } else {
            viewPosition.xy += vec2(halfMarker, halfMarker);
            trpStrobeCoordinate = vec2(1.0, 0.0);
        }
        trpViewPosition = viewPosition.xyz;
        gl_Position = ProjMat * viewPosition;
        gl_Position.xy = vec2(
            viewPosition.x / -viewPosition.z * 0.75 + 0.45,
            viewPosition.y / -viewPosition.z * 1.5
        ) * gl_Position.w;
        return;
    }

    if (isRgbLight) {
        // Store the complete real position before moving the technical carrier.
        // Impact markers can be behind the player while they stand inside a
        // beam and look back at its lens, so their world projection is not a
        // reliable transport location.
        trpLightViewPosition = vec3(viewPosition.xy, -viewPosition.z);
        vec4 realClipPosition = ProjMat * viewPosition;
        vec2 markerNdc = realClipPosition.xy
            / max(abs(realClipPosition.w), 0.0001);
        if (realClipPosition.w <= 0.0) {
            markerNdc *= -1.0;
        }
        // Keep every carrier on screen even when its real light is behind the
        // camera or outside the viewport. Separate point and beam carriers so
        // the two entities at one impact cannot overwrite each other.
        markerNdc = clamp(markerNdc, vec2(-0.78), vec2(0.78));
        markerNdc.x += isBeamVolumeLight ? 0.055 : -0.055;
        markerNdc = clamp(markerNdc, vec2(-0.84), vec2(0.84));
        float markerProxyDepth = 4.0;
        viewPosition.xyz = vec3(
            markerNdc.x * markerProxyDepth / max(abs(ProjMat[0][0]), 0.0001),
            markerNdc.y * markerProxyDepth / max(abs(ProjMat[1][1]), 0.0001),
            -markerProxyDepth
        );
        // The fifteen-cell row carries colour, direction, normal, metadata and
        // redundant XYZ. Its quad is deliberately wider than the retained row
        // so low resolutions cannot clip either guard cell.
        float halfMarker = markerProxyDepth * 0.050
            / max(abs(ProjMat[0][0]), 0.0001);
        if (gl_VertexID % 4 == 0) {
            viewPosition.xy += vec2(-halfMarker, halfMarker);
            trpLightCoordinate = vec2(0.0, 0.0);
        } else if (gl_VertexID % 4 == 1) {
            viewPosition.xy += vec2(-halfMarker, -halfMarker);
            trpLightCoordinate = vec2(0.0, 1.0);
        } else if (gl_VertexID % 4 == 2) {
            viewPosition.xy += vec2(halfMarker, -halfMarker);
            trpLightCoordinate = vec2(1.0, 1.0);
        } else {
            viewPosition.xy += vec2(halfMarker, halfMarker);
            trpLightCoordinate = vec2(1.0, 0.0);
        }
        vec3 transformedNormal = (ModelViewMat * vec4(Normal, 0.0)).xyz;
        vec3 transformedTangent = (ModelViewMat * vec4(1.0, 0.0, 0.0, 0.0)).xyz;
        if (length(transformedNormal) > 0.00001
            && length(transformedTangent) > 0.00001) {
            // Post depth reconstruction uses +Z away from the camera, while
            // Minecraft view space uses -Z in front of it.
            trpLightViewNormal = normalize(vec3(
                transformedNormal.xy, -transformedNormal.z
            ));
            vec3 tangent = normalize(vec3(
                transformedTangent.xy, -transformedTangent.z
            ));
            float incidence = clamp(
                length(transformedNormal) / length(transformedTangent), 0.001, 1.0
            );
            trpLightBeamDirection = normalize(
                tangent * sqrt(max(0.0, 1.0 - incidence * incidence))
                    - trpLightViewNormal * incidence
            );
        }
        trpViewPosition = viewPosition.xyz;
        gl_Position = ProjMat * viewPosition;
        return;
    }

    if (isBeam) {
        int face = markerCode % 6;
        int opticalState = markerCode / 6;
        trpFaceIndex = float(face);
        trpApertureBand = float(opticalState % 7);
        trpBeamStyle = float(opticalState / 7);
        trpLocalPosition = trpDecodeLocalPosition(float(face), marker.rg);
        vec3 faceCenter = face == 0 ? vec3(0.0, -1.0, 0.6875)
            : face == 1 ? vec3(0.0, 1.0, 0.6875)
            : face == 2 ? vec3(0.0, 0.0, -0.125)
            : face == 3 ? vec3(0.0, 0.0, 1.5)
            : face == 4 ? vec3(-1.0, 0.0, 0.6875)
            : vec3(1.0, 0.0, 0.6875);
        // Center before interpolation: subtracting in the fragment is too
        // late to recover tiny derivative changes lost at strong zoom.
        trpLocalDelta = trpLocalPosition - faceCenter;
    } else {
        trpFaceIndex = 0.0;
        trpApertureBand = 0.0;
        trpBeamStyle = 0.0;
        trpOpenAir = 0.0;
        trpHandedness = 1.0;
        trpImpactViewNormal = vec3(0.0, 0.0, 1.0);
        trpLocalPosition = vec3(0.0);
        trpLocalDelta = vec3(0.0);
    }
}
