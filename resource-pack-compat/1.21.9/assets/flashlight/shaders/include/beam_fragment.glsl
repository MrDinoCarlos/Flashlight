// Adapted from MrDino's TRP Server Edition analytic ItemDisplay renderer.
#moj_import <flashlight:optics.glsl>
#ifdef FLASHLIGHT_TRP_HOST
#define nfLocalPosition trpLocalPosition
#else
in vec3 nfLocalPosition;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLocalDelta trpLocalDelta
#else
in vec3 nfLocalDelta;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfViewPosition trpViewPosition
#else
in vec3 nfViewPosition;
#endif
flat in float nfMarkerFlag;
#ifdef FLASHLIGHT_TRP_HOST
#define nfFaceIndex trpFaceIndex
#else
flat in float nfFaceIndex;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfApertureBand trpApertureBand
#else
flat in float nfApertureBand;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfBeamStyle trpBeamStyle
#else
flat in float nfBeamStyle;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfOpenAir trpOpenAir
#else
flat in float nfOpenAir;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfHandedness trpHandedness
#else
flat in float nfHandedness;
#endif
flat in float flashlightInward;
#ifdef FLASHLIGHT_TRP_HOST
#define nfImpactViewNormal trpImpactViewNormal
#else
flat in vec3 nfImpactViewNormal;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfMetadataBands trpMetadataBands
#else
flat in vec2 nfMetadataBands;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfBeamColor trpBeamColor
#else
flat in vec4 nfBeamColor;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightCoordinate trpLightCoordinate
#else
in vec2 nfLightCoordinate;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightViewNormal trpLightViewNormal
#else
flat in vec3 nfLightViewNormal;
#endif
#ifdef FLASHLIGHT_TRP_HOST
#define nfLightBeamDirection trpLightBeamDirection
#else
flat in vec3 nfLightBeamDirection;
#endif

flat in float flashlightRange;
flat in vec2 flashlightPatchOptics;
const int NF_VOLUME_SAMPLES = 10;
const vec2 NF_GLARE_BLOOM_RADIUS = vec2(0.52, 0.30);
const vec2 NF_GLARE_LENS_RADIUS = vec2(0.24, 0.14);
#moj_import <flashlight:beam_proxy.glsl>
// Minecraft keeps one depth value per translucent pixel. Give intersecting
// volumes the depth of their illuminated centre and snap only that depth away
// from the camera, so their colours reach the regular alpha accumulator while
// opaque blocks still occlude the beam.
const float NF_VOLUME_DEPTH_LAYER = 1.0 / 64.0;

float nfStableVolumeDepth(float depth) {
    // Adjacent beams that terminate on the same surface reconstruct that
    // surface independently. Tiny floating-point differences used to make the
    // item depth buffer alternate between them, exposing hard circular seams
    // at grazing camera angles. Snap only volumetric depth away from the camera;
    // opaque world geometry therefore keeps winning and cannot reveal a beam
    // through a block.
    const float DEPTH_STEPS = 4194304.0;
    // Minecraft 1.21.4 uses forward depth: round away from the camera.
    return min(1.0, ceil(clamp(depth, 0.0, 1.0) * DEPTH_STEPS) / DEPTH_STEPS);
}

float nfSaturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float nfHash(vec3 position) {
    // Polynomial hash: keeps the same subtle volumetric breakup without doing
    // one expensive transcendental sine for every ray-march sample and pixel.
    vec3 value = fract(position * 0.1031);
    value += dot(value, value.yzx + 33.33);
    return fract((value.x + value.y) * value.z);
}

float nfSourceRadius(float apertureBand) {
    if (apertureBand < 0.5) {
        return 0.00012;
    }
    return pow(4.0, apertureBand - 1.0) / 2048.0;
}

float nfLengthRatio(float ratioCode) {
    return 0.0625 * pow(262144.0, clamp(ratioCode, 0.0, 4095.0) / 4095.0);
}

// Same derivative reconstruction as TRP; 32 bits also transport the true
// receiving plane in beam-local coordinates, without unavailable 1.20 uniforms.
vec3 flashlightImpactNormal;
vec3 flashlightOctNormal(vec2 encoded) {
    vec2 f = encoded * 2.0 - 1.0;
    vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * mix(vec2(-1.0), vec2(1.0), greaterThanEqual(n.xy, vec2(0.0)));
    return normalize(n);
}
void nfDecodeVolumeMetadata(out float ratioCode, out float dimmer,
        out vec3 beamColor, out float glareEnabled) {
    uvec3 bytes = uvec3(round(nfBeamColor.rgb * 255.0));
    uvec2 light = uvec2(round(nfMetadataBands));
    uint word = bytes.r | (bytes.g << 8u) | (bytes.b << 16u)
        | (light.x << 24u) | (light.y << 28u);
    ratioCode = float(word & 4095u);
    dimmer = float((word >> 12u) & 255u) / 255.0;
    flashlightImpactNormal = flashlightOctNormal(vec2(
        float((word >> 20u) & 63u), float((word >> 26u) & 63u)) / 63.0);
    beamColor = vec3(1.0, 0.945, 0.82);
    glareEnabled = 0.0;
}

bool nfSolvePlane(vec2 localCoordinate, out vec3 firstColumn, out vec3 secondColumn) {
    vec2 localDx = dFdx(localCoordinate);
    vec2 localDy = dFdy(localCoordinate);
    vec3 viewDx = dFdx(nfViewPosition);
    vec3 viewDy = dFdy(nfViewPosition);
    float determinant = localDx.x * localDy.y - localDy.x * localDx.y;
    float derivativeScale = length(localDx) * length(localDy);
    if (derivativeScale == 0.0 || abs(determinant) <= derivativeScale * 0.000001) {
        return false;
    }
    firstColumn = (viewDx * localDy.y - viewDy * localDx.y) / determinant;
    secondColumn = (viewDy * localDx.x - viewDx * localDy.x) / determinant;
    return true;
}

bool nfReconstructTransform(
    float lengthRatioCode,
    out mat3 viewFromLocal,
    out vec3 sourceViewPosition
) {
    vec2 tangentCoordinate;
    if (nfFaceIndex < 1.5) {
        tangentCoordinate = nfLocalDelta.xz;
    } else if (nfFaceIndex < 3.5) {
        tangentCoordinate = nfLocalDelta.xy;
    } else {
        tangentCoordinate = nfLocalDelta.yz;
    }

    vec3 firstColumn;
    vec3 secondColumn;
    if (!nfSolvePlane(tangentCoordinate, firstColumn, secondColumn)) {
        return false;
    }

    vec3 xColumn;
    vec3 yColumn;
    vec3 zColumn;
    if (nfFaceIndex < 1.5) {
        xColumn = firstColumn;
        zColumn = secondColumn;
        yColumn = nfHandedness * normalize(cross(zColumn, xColumn)) * length(xColumn);
    } else if (nfFaceIndex < 3.5) {
        xColumn = firstColumn;
        yColumn = secondColumn;
        zColumn = nfHandedness * normalize(cross(xColumn, yColumn))
            * length(xColumn) * nfLengthRatio(lengthRatioCode);
    } else {
        yColumn = firstColumn;
        zColumn = secondColumn;
        xColumn = nfHandedness * normalize(cross(yColumn, zColumn)) * length(yColumn);
    }

    if (length(xColumn) < 0.00001 || length(yColumn) < 0.00001
        || length(zColumn) < 0.00001) {
        return false;
    }
    viewFromLocal = mat3(xColumn, yColumn, zColumn);
    if (abs(determinant(viewFromLocal)) < 0.00000001) {
        return false;
    }
#ifdef FLASHLIGHT_TRP_HOST
#define nfLocalPosition trpLocalPosition
#else
    sourceViewPosition = nfViewPosition - viewFromLocal * nfLocalPosition;
#endif
    return true;
}

bool nfBoxInterval(vec3 origin, vec3 direction, out float nearDistance, out float farDistance) {
    vec3 safeDirection = direction;
    safeDirection.x = abs(safeDirection.x) < 0.00001
        ? (safeDirection.x < 0.0 ? -0.00001 : 0.00001) : safeDirection.x;
    safeDirection.y = abs(safeDirection.y) < 0.00001
        ? (safeDirection.y < 0.0 ? -0.00001 : 0.00001) : safeDirection.y;
    safeDirection.z = abs(safeDirection.z) < 0.00001
        ? (safeDirection.z < 0.0 ? -0.00001 : 0.00001) : safeDirection.z;

    float maximumZ = nfOpenAir > 0.5 ? 1.0 : NF_PROXY_MAX_Z;
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

bool nfClipToCone(
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

bool nfClipToImpactPlane(
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
    if (nfOpenAir > 0.5) {
        return true;
    }

    // Convert the axis-aligned world hit face to the non-uniform local beam
    // coordinate system. The plane passes through the beam endpoint (z = 1).
    vec3 localPlaneNormal = flashlightImpactNormal;
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


bool flashlightFragment(out vec4 beamOutput) {
    if (nfMarkerFlag > 4.5) { discard; return true; }
    if (nfMarkerFlag < 0.5) return false;
    if (nfMarkerFlag > 2.5) {
        vec3 xColumn,yColumn;
        if (!nfSolvePlane(nfLocalPosition.xy,xColumn,yColumn)) discard;
        float dimmer=(nfMetadataBands.x+nfMetadataBands.y*16.0)/255.0;
        if (dimmer<=0.0) discard;
        vec3 bytes=round(nfBeamColor.rgb*255.0);
        vec2 centre=bytes.rg/255.0*4.0-2.0;
        bool hyperbola=nfMarkerFlag>3.5;
        if(hyperbola) {
            vec2 encoded=bytes.rg/255.0*2.0-1.0;
            centre=sign(encoded)*(pow(vec2(65.0),abs(encoded))-1.0);
        }
        float diameter=0.125*pow(512.0,bytes.b/255.0);
        vec2 offset=(nfLocalPosition.xy-0.5)*vec2(length(xColumn),length(yColumn));
        float c=cos(flashlightPatchOptics.y),s=sin(flashlightPatchOptics.y);
        offset=vec2(c*offset.x+s*offset.y,-s*offset.x+c*offset.y)
            /vec2(diameter*flashlightPatchOptics.x,diameter)*2.0;
        vec2 coordinate=centre+offset;
        float radial=hyperbola ? sqrt(1.0+coordinate.y*coordinate.y)/max(0.0001,coordinate.x) : length(coordinate);
        float edge=1.0-smoothstep(0.70,1.0,radial);
        float alpha=edge*edge*(0.20+0.58*exp(-radial*radial*7.0))*pow(dimmer,0.72);
        alpha*=mix(0.42,1.0,linear_fog_fade(vertexDistance,FogStart,FogEnd));
        if (alpha<0.002) discard;
        gl_FragDepth=gl_FragCoord.z;
        beamOutput=vec4(vec3(1.0,0.945,0.82)*ColorModulator.rgb,alpha);
        return true;
    }
    if (nfMarkerFlag > 1.5) {
        vec4 projection = texture(Sampler0, texCoord0);
        // Keep metadata above OptiFine's transparent-color cleanup threshold,
        // then explicitly hide it in the fragment pass.
        // Projection masks are achromatic. Also reject bilinear mixtures of
        // the blue data border, which are no longer the exact marker RGBA.
        bool projectionMarker = projection.b > max(projection.r, projection.g) + 0.005;
        if (projection.a < 0.002 || projectionMarker) {
            discard;
        }
        vec3 encodedBytes = floor(nfBeamColor.rgb * 255.0 + 0.5);
        float dimmer = nfBeamColor.r;
        if (dimmer <= 0.0) {
            discard;
        }
        vec3 projectionColor = vec3(1.0, 0.945, 0.82);
        float alpha = projection.r * pow(dimmer, 0.72);
        alpha *= mix(0.42, 1.0, linear_fog_fade(vertexDistance, FogStart, FogEnd));
        if (alpha < 0.002) {
            discard;
        }
        // Keep emitted RGB bright and fade its alpha. Darkening RGB produced
        // a grey "shadow" on white walls and overlapping prism crescents.
        gl_FragDepth = gl_FragCoord.z;
        beamOutput = vec4(projectionColor * ColorModulator.rgb, alpha);
        return true;
    }

    float lengthRatioCode;
    float dimmer;
    vec3 decodedBeamColor;
    float sourceGlareEnabled;
    nfDecodeVolumeMetadata(
        lengthRatioCode, dimmer, decodedBeamColor, sourceGlareEnabled
    );
    bool spot = nfBeamStyle > 0.5;
    if (dimmer <= 0.0) {
        discard;
    }

    mat3 viewFromLocal;
    vec3 sourceViewPosition;
    if (!nfReconstructTransform(lengthRatioCode, viewFromLocal, sourceViewPosition)) {
        discard;
    }

    vec3 rayOrigin = inverse(viewFromLocal) * -sourceViewPosition;
    vec3 rayDirection = normalize(nfLocalPosition - rayOrigin);
    float proxyMaximumZ = NF_PROXY_MAX_Z;
    bool cameraInsideProxy = rayOrigin.x > -NF_PROXY_HALF_WIDTH
        && rayOrigin.x < NF_PROXY_HALF_WIDTH
        && rayOrigin.y > -NF_PROXY_HALF_WIDTH
        && rayOrigin.y < NF_PROXY_HALF_WIDTH
        && rayOrigin.z > NF_PROXY_MIN_Z
        && rayOrigin.z < proxyMaximumZ;
    bool inwardCarrier = flashlightInward > 0.5;
    // In the near-plane shell the front proxy face is clipped before this
    // shader can run. Let the inward face cover precisely those sightlines.
    // Use the real proxy bounds, which extend beyond the optical volume.
    vec3 safeProxyRay = rayDirection;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(safeProxyRay[axis]) < 0.00000001) {
            safeProxyRay[axis] = safeProxyRay[axis] < 0.0 ? -0.00000001 : 0.00000001;
        }
    }
    vec3 proxyFirst = (vec3(-NF_PROXY_HALF_WIDTH, -NF_PROXY_HALF_WIDTH,
        NF_PROXY_MIN_Z) - rayOrigin) / safeProxyRay;
    vec3 proxySecond = (vec3(NF_PROXY_HALF_WIDTH, NF_PROXY_HALF_WIDTH,
        NF_PROXY_MAX_Z) - rayOrigin) / safeProxyRay;
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
    if (!nfBoxInterval(rayOrigin, rayDirection, nearDistance, farDistance)) {
        discard;
    }

    float sourceRadius = FLASHLIGHT_LENS_RADIUS / max(0.0001, length(viewFromLocal[0]));
    if (!nfClipToCone(rayOrigin, rayDirection, sourceRadius, nearDistance, farDistance)) {
        discard;
    }
    // Clip the volume against the actual block face instead of a perpendicular
    // end disc. Keep whether this sightline reaches the focused projection so
    // its haze can remain present but optically thin enough to see the gobo.
    bool reachesImpact;
    float impactDistance;
    if (!nfClipToImpactPlane(
        rayOrigin, rayDirection, viewFromLocal, nearDistance, farDistance,
        reachesImpact, impactDistance
    )) {
        discard;
    }

    // When the camera is physically inside the cone and looks back towards its
    // source, a long ray of haze otherwise blends over the fixture and its
    // emissive lens. Preserve the surrounding volume but clear that sightline.
    float cameraLongitudinal = rayOrigin.z;
    float cameraRadius = mix(sourceRadius, 0.5, nfSaturate(cameraLongitudinal));
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
        float glareDistance = length(angularGlarePoint / NF_GLARE_BLOOM_RADIUS);
        float glareBloom = 1.0 - smoothstep(0.04, 1.15, glareDistance);
        float horizontalStreak =
            (1.0 - smoothstep(0.012, 0.10, abs(angularGlarePoint.y)))
            * (1.0 - smoothstep(0.16, 1.30, abs(angularGlarePoint.x)));
        float glareShape = max(glareBloom * 0.72, horizontalStreak);

        // Keep the real optical face unobstructed. The former 18% glare floor
        // still wrote a translucent source plate over the lens and hid its DMX
        // pixels when the camera was inside the volume. Bloom now starts just
        // outside the complete WTF!/Spiider face and feathers outwards.
        float lensDistance = length(angularGlarePoint / NF_GLARE_LENS_RADIUS);
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
    // One subtle noise value per screen ray, not one hash per integration step.
    float noise=mix(0.98,1.02,nfHash(rayOrigin+rayDirection*(nearDistance+farDistance)*0.5));
    for (int sampleIndex = 0; sampleIndex < NF_VOLUME_SAMPLES; sampleIndex++) {
        float fraction = (float(sampleIndex) + 0.5) / float(NF_VOLUME_SAMPLES);
        float sampleDistance = nearDistance + interval * fraction;
        vec3 position = rayOrigin + rayDirection * sampleDistance;
        float longitudinal = nfSaturate(position.z);
        float radius = mix(sourceRadius, 0.5, longitudinal);
        float radial = length(position.xy) / max(radius, 0.00012);

        float edgeStart = spot ? 0.55 : mix(0.48, 0.68, narrowWash);
        float edge = 1.0 - smoothstep(edgeStart, 1.0, radial);
        float centre = exp(-radial * radial * (spot ? 2.7 : 1.65));
        // Fade over 10 mm at the lens, independent of the total beam length.
        float lensFade = smoothstep(0.0, 0.010,
            longitudinal * length(viewFromLocal[2]));
        float endFade = 1.0;
        float metres = longitudinal * length(viewFromLocal[2]);
        float distanceFalloff = (1.0 - smoothstep(flashlightRange * 0.65, flashlightRange, metres))
            / (1.0 + metres * metres / 36.0);
        float sampleDensity = edge * mix(0.30, 1.0, centre) * washDensity
            * lensFade * endFade * distanceFalloff * noise;
        accumulatedDensity += sampleDensity;
        accumulatedDensityDistance += sampleDensity * sampleDistance;
    }

    float averageDensity = accumulatedDensity / float(NF_VOLUME_SAMPLES);
    vec3 localStart = rayOrigin + rayDirection * nearDistance;
    vec3 localEnd = rayOrigin + rayDirection * farDistance;
    vec3 viewDelta = viewFromLocal * (localEnd - localStart);
    float physicalDistance = length(viewDelta);

    vec3 viewRay = normalize(viewDelta);
    float forwardScatter = pow(max(dot(viewAxis, -viewRay), 0.0), 6.0);
    float scattering = mix(0.88, 1.30, forwardScatter);
    float opticalDepth = averageDensity * physicalDistance * (spot ? 0.035 : 0.05);
    float rawHazeAlpha = (1.0 - exp(-opticalDepth))
        * pow(dimmer, 0.72) * scattering * (1.0 - sourceVisibility);
    float glareAlpha = sourceGlare * pow(dimmer, 0.36) * 0.780;
    // Haze emits/scatters light but must not behave like an opaque coloured
    // sheet when viewed along the cone. Keep the volumetric body translucent;
    // the explicitly modelled WTF! source glare may still peak more strongly.
    float maximumHazeAlpha = spot ? 0.11 : mix(0.06, 0.09, narrowWash);
    // Compress smoothly into the cap. A hard min created a flat opacity shelf;
    // several neighbouring cones made its boundary appear as large rings.
    float alpha = maximumHazeAlpha * (
        1.0 - exp(-nfSaturate(rawHazeAlpha) / max(maximumHazeAlpha, 0.001))
    );
    alpha = max(alpha, min(0.72, glareAlpha));
    if (alpha < 0.002) {
        discard;
    }

    vec3 beamColor = decodedBeamColor * ColorModulator.rgb;
    float centreLift = nfSaturate(averageDensity * 0.36 * dimmer);
    beamColor = mix(beamColor, vec3(1.0), centreLift);
    float glareWhiteMix = nfSaturate(sourceGlare * 1.25);
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
    // light occupies a complete interval. Using the first lit sample made the
    // front shell of one cone cut every cone behind it. The density centroid
    // represents the illuminated air and lets crossing colours blend smoothly.
    float densityCentroidDistance = accumulatedDensityDistance
        / max(accumulatedDensity, 0.000001);
    // At the focused footprint keep the full haze colour at the receiving
    // plane so opaque gobos and world geometry still win the depth test.
    // Depth belongs to the illuminated air, not the quantized receiving plane.
    // Pinning it to the wall split the cone along a hard diagonal.
    float depthDistance = densityCentroidDistance;
    vec3 localDepthPosition = rayOrigin + rayDirection * depthDistance;
    vec3 viewDepthPosition = sourceViewPosition + viewFromLocal * localDepthPosition;
    if (!reachesImpact && viewDepthPosition.z < 0.0) {
        float layeredViewDistance = ceil(
            -viewDepthPosition.z / NF_VOLUME_DEPTH_LAYER
        ) * NF_VOLUME_DEPTH_LAYER;
        viewDepthPosition.z = -layeredViewDistance;
    }
    vec4 clipDepthPosition = ProjMat * vec4(viewDepthPosition, 1.0);
    if (clipDepthPosition.w > 0.00001) {
        float normalizedDepth = clipDepthPosition.z / clipDepthPosition.w;
        gl_FragDepth = nfStableVolumeDepth(
            clamp(normalizedDepth * 0.5 + 0.5, 0.0, 1.0)
        );
    } else {
        gl_FragDepth = nfStableVolumeDepth(gl_FragCoord.z);
    }
    beamOutput = vec4(beamColor, alpha);
    return true;
}
