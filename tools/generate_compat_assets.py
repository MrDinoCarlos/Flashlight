"""Generate version-specific shader overrides from verified Mojang reference inputs.

Run fetch_compat_assets.py first. Normal Gradle builds use the committed outputs
and do not download a Minecraft client. The original 1.21.4 pack stays the base.
"""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "resource-pack"
PROFILES = ["1.21.5", "1.21.6", "1.21.9", "26.1", "26.2", "26.3"]
MAIN = re.compile(r"void\s+main\s*\(\s*\)\s*\{")
DECLARATION = re.compile(r"^(flat\s+)?(in|out)\s+(\w+)\s+(\w+)(?:\[(\d+)\])?\s*;", re.M)


def read(root, name):
    return (root / name).read_text(encoding="utf-8")


def save(root, name, content):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def locations(source, start=0, mapping=None):
    if mapping is None:
        mapping = {}
        for match in DECLARATION.finditer(source):
            name = match[4]
            if name not in mapping:
                mapping[name] = start
                start += (int(match[3][-1]) if match[3].startswith("mat") else 1) * (int(match[5]) if match[5] else 1)
    return DECLARATION.sub(lambda m: f"layout(location = {mapping[m[4]]}) " + m[0], source)


def modern_source(source, explicit):
    source = re.sub(r"#version \d+", "#version 330", source)
    if explicit:
        source = source.replace("#moj_import", "#include").replace("gl_VertexID", "gl_VertexIndex")
        if "#extension GL_ARB_separate_shader_objects" not in source:
            source = source.replace("#version 330", "#version 330\n#extension GL_ARB_separate_shader_objects : require", 1)
    return source


def main():
    for version in PROFILES:
        reference = ROOT / "build/minecraft-assets" / version
        output = ROOT / "resource-pack-compat" / version
        modern = version != "1.21.5"
        triangle = version not in ("1.21.5", "1.21.6")
        explicit = version == "26.3"
        directive = "#include" if explicit else "#moj_import"
        include = lambda name: f"{directive} <minecraft:{name}.glsl>\n"

        # Private includes remain versioned with their callers. Vanilla uniforms
        # are imported from that client's files, never copied from another version.
        vertex = read(BASE, "assets/flashlight/shaders/include/beam_vertex.glsl")
        fragment = read(BASE, "assets/flashlight/shaders/include/beam_fragment.glsl")
        if explicit:
            mapping = {m[4]: index + 9 for index, m in enumerate(DECLARATION.finditer(vertex))}
            vertex = locations(vertex, mapping=mapping)
            fragment = locations(fragment, mapping=mapping)
            # Vulkan's projection uses a [0,1] depth interval; OpenGL may use either.
            fragment = fragment.replace("entryClip.z / entryClip.w * 0.5 + 0.5", "flDeviceDepth(entryClip.z / entryClip.w)")
            fragment = fragment.replace("normalizedDepth * 0.5 + 0.5", "flDeviceDepth(normalizedDepth)")
            fragment = "float flDeviceDepth(float ndc) {\n#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\nreturn ndc;\n#else\nreturn ndc*.5+.5;\n#endif\n}\n" + fragment
        for path in (BASE / "assets/flashlight/shaders/include").glob("*.glsl"):
            content = vertex if path.name == "beam_vertex.glsl" else fragment if path.name == "beam_fragment.glsl" else path.read_text(encoding="utf-8")
            if explicit:
                content = content.replace("#moj_import", "#include")
            save(output, "assets/flashlight/shaders/include/" + path.name, content)

        # Preserve every vanilla branch (emissive, overlay, per-face lighting,
        # glint, dissolve and OIT). Only our private marker uses the added branch.
        cores = ["entity", "rendertype_item_entity_translucent_cull"] if version.startswith("1.") else ["entity", "item"]
        for core in cores:
            for stage in ("vsh", "fsh"):
                relative = f"assets/minecraft/shaders/core/{core}.{stage}"
                source = read(reference, relative)
                is_vertex = stage == "vsh"
                declarations = "\n// FlashLight versioned renderer hook v2\n"
                if is_vertex:
                    declarations += "uniform sampler2D Sampler0;\n"
                    if modern:
                        declarations += ("layout(location = 8) " if explicit else "") + "out float flashlightVertexDistance;\n"
                    declarations += f"{directive} <flashlight:beam_vertex.glsl>\n"
                    wrapper = "\nvoid main() {\n    flashlightVanillaVertex();\n"
                    if modern:
                        wrapper += "    flashlightVertexDistance = length(Position);\n"
                    wrapper += "    flashlightVertex();\n}\n"
                else:
                    if modern:
                        declarations += include("projection")
                        declarations += ("layout(location = 8) " if explicit else "") + "in float flashlightVertexDistance;\n"
                        declarations += "#define vertexDistance flashlightVertexDistance\n#define FogStart FogEnvironmentalStart\n#define FogEnd FogEnvironmentalEnd\n"
                        declarations += "float linear_fog_fade(float d, float a, float b) { return 1.0-clamp((d-a)/max(.000001,b-a),0.0,1.0); }\n"
                    else:
                        declarations += "uniform mat4 ProjMat;\n"
                    declarations += f"{directive} <flashlight:beam_fragment.glsl>\n"
                    wrapper = "\nvoid main() {\n    gl_FragDepth = gl_FragCoord.z;\n    vec4 flashlightColor;\n    if (flashlightFragment(flashlightColor)) {\n"
                    if explicit:
                        wrapper += "#ifdef OIT_ALPHA_ONLY\n        executeAlphaOnlyPhase(gl_FragDepth, flashlightColor.a);\n#else\n#ifdef OIT_ACCUMULATE\n        flashlightColor = sampleColorForAccumulation(flashlightColor);\n#endif\n        fragColor = flashlightColor;\n#endif\n"
                    else:
                        wrapper += "        fragColor = flashlightColor;\n"
                    wrapper += "        return;\n    }\n    flashlightVanillaFragment();\n}\n"
                function = "flashlightVanillaVertex" if is_vertex else "flashlightVanillaFragment"
                match = MAIN.search(source)
                source = source[:match.start()] + declarations + f"void {function}() {{" + source[match.end():] + wrapper
                save(output, relative, source)

        # The optical controller still uses the native outline pass. Its ordinary
        # item pass is discarded by the private marker hook above.
        for stage in ("vsh", "fsh"):
            path = f"assets/minecraft/shaders/core/rendertype_outline.{stage}"
            source = read(BASE, path)
            if modern:
                source = re.sub(r"uniform (?:mat4 (?:ModelViewMat|ProjMat)|vec4 ColorModulator|vec2 ScreenSize);\n", "", source)
                imports = include("dynamictransforms") + include("projection")
                if stage == "vsh":
                    imports += include("globals")
                source = source.replace("#version 150\n", "#version 150\n" + imports, 1)
            if explicit:
                if stage == "vsh":
                    for location, name in enumerate(("Position", "Color", "UV0")):
                        source = re.sub(rf"^in (\w+) {name};", rf"layout(location = {location}) in \1 {name};", source, flags=re.M)
                mapping = {"vertexColor": 0, "texCoord0": 1, "flOriginalPosition": 2, "flControlUV": 3, "flIsControl": 4,
                           "flControlKind": 5, "flControlParams": 6, "fragColor": 0}
                source = locations(source, mapping=mapping)
            save(output, path, modern_source(source, explicit) if modern else source)

        for name in ("control_copy", "outline", "spotlight"):
            for stage in ("vsh", "fsh"):
                path = f"assets/flashlight/shaders/post/{name}.{stage}"
                source = read(BASE, path)
                if stage == "vsh":
                    source = re.sub(r"^in vec4 Position;\n|^uniform (?:mat4 ProjMat|vec2 OutSize|vec2 InSize);\n", "", source, flags=re.M)
                    if not triangle:
                        source = source.replace("void main() {", "in vec4 Position;\nvoid main() {\n    vec2 uv = Position.xy;", 1)
                    else:
                        source = source.replace("void main() {", "void main() {\n    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);", 1)
                    source = source.replace("vec4 clip=ProjMat*vec4(Position.xy,0.0,1.0);", "vec4 clip=vec4(uv*2.0-1.0,0.0,1.0);")
                    source = source.replace("Position.xy/OutSize", "uv")
                    source = source.replace("oneTexel=1.0/InSize;", "oneTexel=1.0/vec2(textureSize(InSampler,0));")
                    if name == "outline":
                        source = source.replace("void main() {", "uniform sampler2D InSampler;\nvoid main() {", 1)
                if explicit:
                    if name == "spotlight" and stage == "fsh":
                        source = source.replace("vec4 p=inverseProjection*vec4(uv*2.0-1.0,depth*2.0-1.0,1.0);", "#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n    float ndcDepth=depth;\n#else\n    float ndcDepth=depth*2.0-1.0;\n#endif\n    vec4 p=inverseProjection*vec4(uv*2.0-1.0,ndcDepth,1.0);")
                    mapping = {"texCoord": 0, "oneTexel": 1, "fragColor": 0, "flActiveLights": 1, "flFirstLight": 2,
                               "flProjection": 3, "flInverseProjection": 7, "flOriginPower": 11, "flAxisRange": 12, "flOptics": 13,
                               "flActiveShadows": 14, "flShadowCenters": 15, "flShadowAxes": 23}
                    source = locations(source, mapping=mapping)
                save(output, path, modern_source(source, explicit) if modern else source)

        # Use each version's actual post-effect schema and native blur settings.
        vanilla = json.loads(read(reference, "assets/minecraft/post_effect/entity_outline.json"))
        custom = json.loads(read(BASE, "assets/minecraft/post_effect/entity_outline.json"))
        graph = {"targets": custom["targets"], "passes": []}
        for index, entry in enumerate(custom["passes"]):
            if index in (2, 3):
                graph["passes"].append(vanilla["passes"][index - 1])
                continue
            program = entry.pop("program")
            entry["vertex_shader"] = program
            entry["fragment_shader"] = program
            graph["passes"].append(entry)
        save(output, "assets/minecraft/post_effect/entity_outline.json", json.dumps(graph, indent=2) + "\n")
        print("Generated", version)


if __name__ == "__main__":
    main()
