"""Validate the shipped 1.21.4 model graph and its actual PNG/ZIP assets."""
import json
from pathlib import Path
import sys
import zipfile
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resource-pack"
errors = []
models = list((PACK / "assets/flashlight/models").rglob("*.json"))


def check(condition, message):
    if not condition:
        errors.append(message)


def asset(identifier, folder, extension):
    namespace, path = identifier.split(":", 1)
    return PACK / "assets" / namespace / folder / (path + extension)


for path in models:
    model = json.loads(path.read_text(encoding="utf-8"))
    if model.get("parent", "").startswith("flashlight:"):
        check(asset(model["parent"],"models",".json").is_file(),f"Unresolved parent model: {path.name}")
    for key, texture in model.get("textures", {}).items():
        check(asset(texture, "textures", ".png").is_file(), f"Missing texture: {path.name} -> {texture}")
        check(texture.split(":", 1)[1].startswith(("item/", "block/")),
              f"Texture outside Minecraft's item atlas: {path.name} -> {texture}")
    check("particle" in model.get("textures", {}), f"Missing particle texture reference: {path.name}")
    for element in model.get("elements", []):
        check(all(-16 <= v <= 32 for v in element["from"] + element["to"]), f"Invalid 1.21.4 element bounds: {path.name}")
        check(all(a <= b for a, b in zip(element["from"], element["to"])), f"Inverted cuboid: {path.name}")
        check(element.get("rotation", {}).get("angle", 0) in [-45, -22.5, 0, 22.5, 45], f"Unsupported element rotation: {path.name}")
        for face in element.get("faces", {}).values():
            check(all(0 <= value <= 16 for value in face["uv"]), f"Invalid UV: {path.name}")
            check(face["texture"][1:] in model["textures"], f"Unresolved texture slot: {path.name}")
            check(face.get("rotation", 0) in (0, 90, 180, 270), f"Invalid UV rotation: {path.name}")

def check_item(node, path):
    if node["type"] == "minecraft:model":
        check(asset(node["model"], "models", ".json").is_file(), f"Unresolved model: {path.name}")
    elif node["type"] == "minecraft:select":
        check(node["property"] == "minecraft:display_context", f"Unsupported selector: {path.name}")
        for case in node["cases"]: check_item(case["model"], path)
        check_item(node["fallback"], path)
    else: check(False, f"Unsupported item graph: {path.name}")

for path in (PACK / "assets/flashlight/items").rglob("*.json"):
    check_item(json.loads(path.read_text(encoding="utf-8"))["model"], path)

check(not list(PACK.rglob("arm_*.json")), "Custom arm geometry must not be shipped")

for path in PACK.rglob("*.png"):
    with Image.open(path) as image:
        image.verify()
check(json.loads((PACK / "pack.mcmeta").read_text(encoding="utf-8"))["pack"]["pack_format"] == 46, "Wrong pack version")
check(not (PACK / "assets/minecraft/items").exists(), "The pack must not override vanilla item definitions")
check(not (PACK / "assets/minecraft/models").exists(), "The pack must not override vanilla item models")

for path in (ROOT / "build/distributions").glob("FlashLight-ResourcePack-*.zip"):
    with zipfile.ZipFile(path) as zip_file:
        check(zip_file.testzip() is None, f"Broken ZIP: {path.name}")
        check("pack.mcmeta" in zip_file.namelist(), "pack.mcmeta must be in ZIP root")
        check(len(zip_file.namelist()) == len(set(zip_file.namelist())), "Duplicate ZIP paths")

check((PACK / "assets/minecraft/shaders/core/entity.vsh").is_file(), "Missing Minecraft 1.21.4 entity shader")
check((PACK / "assets/flashlight/shaders/include/beam_fragment.glsl").is_file(), "The beam shader must be INSIDE the resource pack")
check(not (PACK / "shaders").exists(), "A separate OptiFine shaderpack must not be bundled")

# Minecraft 1.21.4 exposes only these two targets to the outline chain.
# Using transparency-only targets here makes the entire chain fail to load.
outline = PACK / "assets/minecraft/post_effect/entity_outline.json"
check(outline.is_file(), "Missing resource-pack spotlight pass")
if outline.is_file():
    graph = json.loads(outline.read_text(encoding="utf-8"))
    check(graph["targets"].get("flashlight_data") == {"width": 27, "height": 160},
          "Optical metadata must not allocate another full-resolution render target")
    targets = set(graph["targets"]) | {"minecraft:main", "minecraft:entity_outline"}
    for stage in graph["passes"]:
        check(stage["output"] in targets, f"Unknown outline output: {stage['output']}")
        check(stage["output"] != "minecraft:main", "Outline post passes must never clear the main depth buffer")
        for entry in stage["inputs"]:
            check(entry["target"] in targets, f"Unknown outline input: {entry['target']}")
            check(entry["target"] != stage["output"], "A post pass cannot sample its own output")
        if stage["program"].startswith("flashlight:"):
            program_path = asset(stage["program"], "shaders", ".json")
            check(program_path.is_file(), f"Missing program: {stage['program']}")
            if program_path.is_file():
                program = json.loads(program_path.read_text(encoding="utf-8"))
                for kind, suffix in [("vertex", ".vsh"), ("fragment", ".fsh")]:
                    check(asset(program[kind], "shaders", suffix).is_file(), f"Missing {kind}: {program[kind]}")
                samplers = {entry["name"] for entry in program["samplers"]}
                check(all(entry["sampler_name"] + "Sampler" in samplers for entry in stage["inputs"]),
                      f"Unbound sampler: {stage['program']}")
check(not (PACK / "assets/minecraft/post_effect/transparency.json").exists(),
      "Keep TRP/StrobeLights' transparency graph intact")

test_count = 0
reports = list((ROOT / "build/test-results/test").glob("TEST-*.xml"))

for report in reports:
    suite = ET.parse(report).getroot()
    test_count += int(suite.get("tests", "0"))
    check(int(suite.get("skipped", "0")) == 0, f"Skipped tests in {report.name}")
    check(int(suite.get("failures", "0")) == 0 and int(suite.get("errors", "0")) == 0,
          f"Failing tests in {report.name}")

if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"Valid pack: {len(models)} models without added arms; valid UVs, textures and base format 46.")
if test_count:
    print(f"Tests: {test_count} passed, none skipped.")
