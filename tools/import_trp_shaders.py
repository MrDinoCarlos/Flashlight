"""One-time port of the user's TRP analytic renderer to the real 1.21.4 core ABI.

Historical initial import only. The vendored renderer now contains subsequent
multi-surface and performance changes; running this importer would erase them.
Releases do not need TRP or a Minecraft installation.
"""
import argparse
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    raise SystemExit("Historical importer retired: maintained shaders are in resource-pack/assets/flashlight/shaders/include.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--trp", type=Path, required=True)
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    args = parser.parse_args()
    base = args.trp / "resource-pack/vanilla-reset/shaders/core"
    vertex = (base / "rendertype_entity_translucent_cull.vsh").read_text(encoding="utf-8")
    fragment = (base / "rendertype_entity_translucent_cull.fsh").read_text(encoding="utf-8")
    include = ROOT / "resource-pack/assets/flashlight/shaders/include"
    include.mkdir(parents=True, exist_ok=True)
    declarations = vertex[vertex.index("out vec3 trpLocalPosition;"):vertex.index("const float TRP_PROXY_HALF_WIDTH")]
    declarations += "flat out float flashlightRange;\n"
    helpers = vertex[vertex.index("const float TRP_PROXY_HALF_WIDTH"):vertex.index("void main()")]
    body = vertex[vertex.index("    // Vertex texture sampling"):vertex.rfind("}")]
    body = body.replace("markerAlpha >= 70.5 && markerAlpha <= 94.5", "markerAlpha >= 105.5 && markerAlpha <= 221.5")
    body = body.replace("markerAlpha - 71.0", "markerAlpha - 106.0")
    body = body.replace("float surfaceFace = floor(markerState / 4.0);", "float surfaceFace = 0.0;\n    flashlightRange = 4.0 + floor(markerState / 4.0);")
    body = body.replace("markerAlpha >= 31.5 && markerAlpha <= 32.5", "markerAlpha >= 47.5 && markerAlpha <= 48.5")
    body = body.replace("markerBytes.r >= 16.5 && markerBytes.r <= 17.5", "markerBytes.r >= 22.5 && markerBytes.r <= 23.5")
    body = body.replace("markerBytes.g >= 28.5 && markerBytes.g <= 29.5", "markerBytes.g >= 40.5 && markerBytes.g <= 41.5")
    body = body.replace("markerBytes.b >= 254.5", "markerBytes.b >= 250.5 && markerBytes.b <= 251.5")
    body = body.replace("normalize(transpose(IViewRotMat) * trpDecodeSurfaceNormal(surfaceFace))", "vec3(0.0, 0.0, 1.0)")
    # No Fabulous marker strip, point lights, or extra post chain are required.
    start = body.index("    if (isRgbLight) {")
    end = body.index("    if (isBeam) {", start)
    body = body[:start] + body[end:]
    body = re.sub(r"    bool isRgbLight = .*?;\n", "    bool isRgbLight = false;\n", body, flags=re.S)
    header = "// Adapted from MrDino's TRP Server Edition analytic ItemDisplay renderer.\n"
    (include / "beam_vertex.glsl").write_text(header + declarations + helpers +
        "\nvoid flashlightVertex() {\n    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);\n" + body + "}\n", encoding="utf-8")

    declarations = fragment[fragment.index("in vec3 trpLocalPosition;"):fragment.index("out vec4 fragColor;")]
    declarations = '#moj_import <flashlight:optics.glsl>\n' + declarations
    declarations += "flat in float flashlightRange;\n"
    helpers = fragment[fragment.index("const int TRP_VOLUME_SAMPLES"):fragment.index("void main()")]
    helpers = helpers.replace("// Minecraft 26.x uses reversed depth, so smaller values are farther away.",
                              "// Minecraft 1.21.4 uses forward depth: round away from the camera.")
    helpers = helpers.replace("return max(0.0, floor(clamp(depth, 0.0, 1.0) * DEPTH_STEPS) / DEPTH_STEPS);",
                              "return min(1.0, ceil(clamp(depth, 0.0, 1.0) * DEPTH_STEPS) / DEPTH_STEPS);")
    decode_start = helpers.index("void trpDecodeVolumeMetadata(")
    decode_end = helpers.index("bool trpSolvePlane(")
    helpers = helpers[:decode_start] + '''// Same derivative reconstruction as TRP; 32 bits also transport the true
// receiving plane in beam-local coordinates, without unavailable 1.20 uniforms.
vec3 flashlightImpactNormal;
vec3 flashlightOctNormal(vec2 encoded) {
    vec2 f = encoded * 2.0 - 1.0;
    vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * mix(vec2(-1.0), vec2(1.0), greaterThanEqual(n.xy, vec2(0.0)));
    return normalize(n);
}
void trpDecodeVolumeMetadata(out float ratioCode, out float dimmer,
        out vec3 beamColor, out float glareEnabled) {
    uvec3 bytes = uvec3(round(trpBeamColor.rgb * 255.0));
    uvec2 light = uvec2(round(trpMetadataBands));
    uint word = bytes.r | (bytes.g << 8u) | (bytes.b << 16u)
        | (light.x << 24u) | (light.y << 28u);
    ratioCode = float(word & 4095u);
    dimmer = float((word >> 12u) & 255u) / 255.0;
    flashlightImpactNormal = flashlightOctNormal(vec2(
        float((word >> 20u) & 63u), float((word >> 26u) & 63u)) / 63.0);
    beamColor = vec3(1.0, 0.945, 0.82);
    glareEnabled = 0.0;
}

''' + helpers[decode_end:]
    helpers = helpers.replace("vec3 localPlaneNormal = transpose(viewFromLocal) * trpImpactViewNormal;",
                              "vec3 localPlaneNormal = flashlightImpactNormal;")
    body = fragment[fragment.index("void main() {") + len("void main() {"):fragment.rfind("}")]
    body = body[body.index("    if (trpMarkerFlag > 1.5) {"):]
    body = re.sub(r"        float dimmerCode = .*?        if \(dimmer <= 0.0\)",
                  "        float dimmer = trpBeamColor.r;\n        if (dimmer <= 0.0)", body, count=1, flags=re.S)
    body = re.sub(r"vec3 projectionColor = .*?;", "vec3 projectionColor = vec3(1.0, 0.945, 0.82);", body, count=1)
    body = body.replace("fragColor =", "beamOutput =").replace("return;", "return true;")
    body = body.replace("projection.a < 0.1", "projection.a < 0.002")
    body = body.replace("float alpha = projection.a * pow(dimmer, 0.72);", "float alpha = projection.r * pow(dimmer, 0.72);")
    body = body.replace("projection.rgb * projectionColor * ColorModulator.rgb", "projectionColor * ColorModulator.rgb")
    # Flashlight mist should be subtle when viewed from behind the lens.
    body = body.replace("* (spot ? 0.72 : 0.88)", "* (spot ? 0.035 : 0.05)")
    body = body.replace("float edgeStart = spot ? 0.82", "float edgeStart = spot ? 0.55")
    body = body.replace("spot ? 0.46 : mix(0.24, 0.38, narrowWash)", "spot ? 0.11 : mix(0.06, 0.09, narrowWash)")
    body = body.replace("float sourceRadius = trpSourceRadius(trpApertureBand);",
                        "float sourceRadius = FLASHLIGHT_LENS_RADIUS / max(0.0001, length(viewFromLocal[0]));")
    body = body.replace("float endFade = trpOpenAir > 0.5\n            ? 1.0 - smoothstep(0.55, 0.92, longitudinal)\n            : 1.0;", "float endFade = 1.0;")
    body = body.replace("float distanceFalloff = mix(1.0, spot ? 0.82 : 0.88, longitudinal);",
        "float metres = longitudinal * length(viewFromLocal[2]);\n"
        "        float distanceFalloff = (1.0 - smoothstep(flashlightRange * 0.65, flashlightRange, metres))\n"
        "            / (1.0 + metres * metres / 36.0);")
    body = body.replace("float depthDistance = reachesImpact ? impactDistance : densityCentroidDistance;",
        "// Depth belongs to the illuminated air, not the quantized receiving plane.\n"
        "    // Pinning it to the wall split the cone along a hard diagonal.\n"
        "    float depthDistance = densityCentroidDistance;")
    (include / "beam_fragment.glsl").write_text(header + declarations + helpers +
        "\nbool flashlightFragment(out vec4 beamOutput) {\n    if (trpMarkerFlag < 0.5) return false;\n" + body +
        "    return true;\n}\n", encoding="utf-8")

    core = ROOT / "resource-pack/assets/minecraft/shaders/core"
    core.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.minecraft_jar) as mc:
        for name in ("entity", "rendertype_item_entity_translucent_cull"):
            for extension in ("vsh", "fsh"):
                source = mc.read(f"assets/minecraft/shaders/core/{name}.{extension}").decode()
                if extension == "vsh":
                    source = source.replace("void main() {", 'uniform sampler2D Sampler0;\n#moj_import <flashlight:beam_vertex.glsl>\n\nvoid main() {')
                    last = source.rfind("}")
                    source = source[:last] + "    flashlightVertex();\n" + source[last:]
                else:
                    source = source.replace("void main() {", 'uniform mat4 ProjMat;\n#moj_import <flashlight:beam_fragment.glsl>\n\nvoid main() {\n    gl_FragDepth = gl_FragCoord.z;\n    if (flashlightFragment(fragColor)) return;')
                (core / f"{name}.{extension}").write_text(source, encoding="utf-8")
        fixtures = ROOT / "src/test/resources/gpu/minecraft"
        fixtures.mkdir(parents=True, exist_ok=True)
        for name in ("light.glsl", "fog.glsl"):
            (fixtures / name).write_bytes(mc.read("assets/minecraft/shaders/include/" + name))
    (ROOT / "resource-pack/TRP-RENDERER-NOTICE.txt").write_text(
        "Analytic volume and projection adapted from MrDino's Theatrical Realistic Pack Server Edition, vanilla-reset renderer.\n"
        "The Minecraft 1.21.4 wrappers preserve the original vanilla rendering for all unmarked textures.\n", encoding="utf-8")
    print("TRP analytic volume ported to Minecraft 1.21.4 resource-pack core shaders.")


if __name__ == "__main__":
    main()
