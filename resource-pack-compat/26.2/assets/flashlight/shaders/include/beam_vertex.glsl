// Adapted from MrDino's TRP Server Edition analytic ItemDisplay renderer.
#ifdef FLASHLIGHT_TRP_HOST
#define nfLocalPosition trpLocalPosition
#else
out vec3 nfLocalPosition;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLocalDelta trpLocalDelta
#else
out vec3 nfLocalDelta;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfViewPosition trpViewPosition
#else
out vec3 nfViewPosition;
#endif
flat out float nfMarkerFlag;
#ifdef FLASHLIGHT_TRP_HOST
#define nfFaceIndex trpFaceIndex
#else
flat out float nfFaceIndex;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfApertureBand trpApertureBand
#else
flat out float nfApertureBand;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfBeamStyle trpBeamStyle
#else
flat out float nfBeamStyle;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfOpenAir trpOpenAir
#else
flat out float nfOpenAir;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfHandedness trpHandedness
#else
flat out float nfHandedness;
#endif
flat out float flashlightInward;
#ifdef FLASHLIGHT_TRP_HOST
#define nfImpactViewNormal trpImpactViewNormal
#else
flat out vec3 nfImpactViewNormal;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfMetadataBands trpMetadataBands
#else
flat out vec2 nfMetadataBands;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfBeamColor trpBeamColor
#else
flat out vec4 nfBeamColor;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightCoordinate trpLightCoordinate
#else
out vec2 nfLightCoordinate;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightViewNormal trpLightViewNormal
#else
flat out vec3 nfLightViewNormal;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightBeamDirection trpLightBeamDirection
#else
flat out vec3 nfLightBeamDirection;
#endif

flat out float flashlightRange;
flat out vec2 flashlightPatchOptics;
#moj_import <flashlight:beam_proxy.glsl>
const float NF_MARKER_PADDING = 0.125;
const float NF_MARKER_SPAN = 0.75;

float nfDecodeMarkerCoordinate(float coordinate) {
    return clamp((coordinate - NF_MARKER_PADDING) / NF_MARKER_SPAN, 0.0, 1.0);
}

float nfProxyWidth(float coordinate) {
    return mix(-NF_PROXY_HALF_WIDTH, NF_PROXY_HALF_WIDTH, coordinate);
}

float nfProxyDepth(float coordinate) {
    return mix(NF_PROXY_MIN_Z, NF_PROXY_MAX_Z, coordinate);
}

vec3 nfDecodeLocalPosition(float face, vec2 encoded) {
    // Each atlas tile has 1/8 padding on all sides so neighbouring face IDs
    // cannot bleed through texture filtering. Remove that padding before using
    // the channels as geometric coordinates; otherwise every face reconstructs
    // a different transform and the volume jumps as the camera moves around it.
    float u = nfDecodeMarkerCoordinate(encoded.x);
    float v = nfDecodeMarkerCoordinate(encoded.y);
    if (face < 0.5) {
        return vec3(nfProxyWidth(u), -NF_PROXY_HALF_WIDTH, nfProxyDepth(1.0 - v));
    }
    if (face < 1.5) {
        return vec3(nfProxyWidth(u), NF_PROXY_HALF_WIDTH, nfProxyDepth(v));
    }
    if (face < 2.5) {
        return vec3(nfProxyWidth(1.0 - u), nfProxyWidth(1.0 - v), NF_PROXY_MIN_Z);
    }
    if (face < 3.5) {
        // This is the real rear face of the extended proxy, not the optical
        // impact plane. Decoding it at z=1 reconstructed a false camera/source
        // transform whenever this face drove the fragment and could paint a
        // detached volume beyond solid geometry.
        return vec3(nfProxyWidth(u), nfProxyWidth(1.0 - v), NF_PROXY_MAX_Z);
    }
    if (face < 4.5) {
        return vec3(-NF_PROXY_HALF_WIDTH, nfProxyWidth(1.0 - v), nfProxyDepth(u));
    }
    return vec3(NF_PROXY_HALF_WIDTH, nfProxyWidth(1.0 - v), nfProxyDepth(1.0 - u));
}

vec3 nfDecodeSurfaceNormal(float face) {
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


// Confirm the coordinate field, not just a few RGBA bytes. Ordinary glass or
// another pack's flat colours must keep the vanilla rendering path.
bool flashlightCoordinateField(vec4 marker,float stepPixels,float minimum,float maximum) {
    vec2 pixel=1.0/vec2(textureSize(Sampler0,0));
    vec2 toward=mix(vec2(1.0),vec2(-1.0),greaterThan(marker.rg,vec2(0.5)));
    vec4 x=textureLod(Sampler0,UV0+vec2(toward.x*pixel.x*stepPixels,0.0),0.0);
    vec4 y=textureLod(Sampler0,UV0+vec2(0.0,toward.y*pixel.y*stepPixels),0.0);
    vec2 delta=vec2((x.r-marker.r)*toward.x,(y.g-marker.g)*toward.y)*255.0;
    return all(greaterThanEqual(delta,vec2(minimum))) && all(lessThanEqual(delta,vec2(maximum)))
        && abs(x.g-marker.g)<0.004 && abs(y.r-marker.r)<0.004
        && all(lessThan(abs(x.ba-marker.ba),vec2(0.002)))
        && all(lessThan(abs(y.ba-marker.ba),vec2(0.002)));
}

void flashlightVertex() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    // Vertex texture sampling has no screen-space derivatives. Pinning the
    // marker atlas to mip zero keeps its encoded coordinates exact at distance.
    vec4 marker = textureLod(Sampler0, UV0, 0.0);
    int markerCode = int(floor(marker.b * 255.0 + 0.5)) - 160;
    float markerAlpha = floor(marker.a * 255.0 + 0.5);
    bool worldProjection = abs(ProjMat[2][3]) > 0.000001;
    bool encodedAlpha = worldProjection && markerAlpha >= 105.5 && markerAlpha <= 221.5;
    // Do not classify a volume from corner gradients or the raw UV inset.
    // The client bakes/shrinks the model UVs and may filter/rescale sprites.
    // Use TRP's established, constant per-face marker instead.
    bool isBeam = encodedAlpha && markerCode>=48 && markerCode<54;
    vec3 markerBytes = floor(marker.rgb * 255.0 + 0.5);
    bool isImpact = worldProjection && markerAlpha >= 47.5 && markerAlpha <= 48.5
        && markerBytes.r >= 22.5 && markerBytes.r <= 23.5
        && markerBytes.g >= 40.5 && markerBytes.g <= 41.5
        && markerBytes.b >= 250.5 && markerBytes.b <= 251.5;
    bool isRgbLight = false;
    bool patchCorner = (abs(markerBytes.r-17.0)<1.0 || abs(markerBytes.r-239.0)<1.0)
        && (abs(markerBytes.g-17.0)<1.0 || abs(markerBytes.g-239.0)<1.0);
    bool isPatch = worldProjection && patchCorner && markerAlpha>=223.5
        && markerBytes.b>=63.5 && markerBytes.b<=95.5;
    if(isPatch) isPatch=flashlightCoordinateField(marker,1.0,12.0,18.0);
    bool hyperbola=markerBytes.b>=79.5;
    flashlightPatchOptics=vec2(pow(16.0,mod(markerBytes.b-64.0,16.0)/15.0),
        (markerAlpha-224.0)*3.14159265359/(hyperbola ? 16.0 : 32.0));
    float markerState = markerAlpha - 106.0;
    float carrierState = mod(markerState, 4.0);
    float surfaceFace = 0.0;
    flashlightRange = 4.0 + floor(markerState / 4.0);
    bool isControl = worldProjection && markerBytes.r>=11.0 && markerBytes.r<=14.0
        && all(lessThan(abs(round(marker.gba*255.0)-vec3(49,197,100)),vec3(.5)));
    nfMarkerFlag = isControl ? 5.0 : (isBeam ? 1.0 : (isImpact ? 2.0 : (isPatch ? (hyperbola ? 4.0 : 3.0) : 0.0)));
    nfOpenAir = isBeam && mod(carrierState, 2.0) > 0.5 ? 1.0 : 0.0;
    flashlightInward=isBeam && carrierState>1.5 ? 1.0 : 0.0;
    // Both windings share one positive-handed ItemDisplay transform.
    nfHandedness = 1.0;
    nfImpactViewNormal = isBeam
        ? vec3(0.0, 0.0, 1.0)
        : vec3(0.0, 0.0, 1.0);
    nfViewPosition = viewPosition.xyz;
    nfBeamColor = Color;
    nfMetadataBands = clamp(round(vec2(UV2) / 16.0), vec2(0.0), vec2(15.0));
    nfLightCoordinate = vec2(0.5);
    nfLightViewNormal = vec3(0.0, 0.0, 1.0);
    nfLightBeamDirection = vec3(0.0, 0.0, 1.0);

    if (isBeam) {
        int face = markerCode % 6;
        int opticalState = markerCode / 6;
        nfFaceIndex = float(face);
        nfApertureBand = float(opticalState % 7);
        nfBeamStyle = float(opticalState / 7);
        nfLocalPosition = nfDecodeLocalPosition(float(face), marker.rg);
        float middleZ=(NF_PROXY_MIN_Z+NF_PROXY_MAX_Z)*0.5;
        vec3 faceCenter = face == 0 ? vec3(0.0, -NF_PROXY_HALF_WIDTH, middleZ)
            : face == 1 ? vec3(0.0, NF_PROXY_HALF_WIDTH, middleZ)
            : face == 2 ? vec3(0.0, 0.0, NF_PROXY_MIN_Z)
            : face == 3 ? vec3(0.0, 0.0, NF_PROXY_MAX_Z)
            : face == 4 ? vec3(-NF_PROXY_HALF_WIDTH, 0.0, middleZ)
            : vec3(NF_PROXY_HALF_WIDTH, 0.0, middleZ);
        // Center before interpolation: subtracting in the fragment is too
        // late to recover tiny derivative changes lost at strong zoom.
        nfLocalDelta = nfLocalPosition - faceCenter;
    } else {
        nfFaceIndex = 0.0;
        nfApertureBand = 0.0;
        nfBeamStyle = 0.0;
        nfOpenAir = 0.0;
        nfHandedness = 1.0;
        nfImpactViewNormal = vec3(0.0, 0.0, 1.0);
        nfLocalPosition = isPatch ? vec3(clamp((marker.r*255.0-17.0)/222.0,0.0,1.0),
            1.0-clamp((marker.g*255.0-17.0)/222.0,0.0,1.0),0.0) : vec3(0.0);
        nfLocalDelta = vec3(0.0);
    }
}
