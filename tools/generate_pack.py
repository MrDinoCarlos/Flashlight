"""Original FlashLight geometry and pixel textures. Python 3 + Pillow.

Run from any directory: python tools/generate_pack.py
The same resource pack includes TRP-style beam models and Minecraft core shaders.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
import random
import re

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resource-pack"
ASSETS = PACK / "assets" / "flashlight"
MODELS = ASSETS / "models" / "item"
TEXTURES = ASSETS / "textures" / "item"
FACES = ("north", "south", "east", "west", "up", "down")
RNG = random.Random(1401214)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def noise_texture(name, color, grain=8, grooves=False):
    image = Image.new("RGBA", (64, 64))
    pixels = image.load()
    for y in range(64):
        for x in range(64):
            noise = RNG.randint(-grain, grain)
            line = -10 if grooves and x % 7 in (0, 1) else 0
            pixels[x, y] = tuple(max(0, min(255, c + noise + line)) for c in color) + (255,)
    draw = ImageDraw.Draw(image)
    for _ in range(34):
        x, y = RNG.randrange(64), RNG.randrange(64)
        draw.line((x, y, min(63, x + RNG.randrange(1, 6)), y), fill=tuple(min(255, c + 28) for c in color) + (255,))
    image.save(TEXTURES / f"{name}.png")
    return image


def make_textures():
    TEXTURES.mkdir(parents=True, exist_ok=True)
    noise_texture("metal", (43, 48, 47), 6)
    noise_texture("rubber", (25, 29, 28), 3, True)
    noise_texture("rim", (104, 109, 100), 9)
    noise_texture("olive", (78, 81, 59), 7)
    noise_texture("switch", (31, 34, 31), 4)
    noise_texture("copper", (142, 86, 48), 10)
    label = noise_texture("label", (184, 173, 133), 8)
    draw = ImageDraw.Draw(label)
    draw.rectangle((1, 1, 62, 62), outline=(52, 55, 43, 255), width=2)
    draw.text((7, 8), "NF-01", fill=(32, 36, 32, 255))
    draw.text((7, 26), "9V", fill=(42, 45, 36, 255))
    for x in range(7, 55, 3):
        draw.line((x, 46, x, 56), fill=(46, 49, 41, 255), width=1 if x % 2 else 2)
    label.save(TEXTURES / "label.png")
    for state, rgb in {"off": (47, 57, 56), "low": (191, 148, 67), "on": (255, 241, 181)}.items():
        lens = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        pixels = lens.load()
        for y in range(64):
            for x in range(64):
                dx, dy = (x - 31.5) / 31.5, (y - 31.5) / 31.5
                radius = math.hypot(dx, dy)
                if max(abs(dx), abs(dy)) > .99 or abs(dx) + abs(dy) > 1.39:
                    continue
                ring = .82 + .13 * math.cos(radius * 42)
                center = max(0, 1 - radius) * .15
                brightness = min(1.0, ring + center)
                pixels[x, y] = tuple(int(c * brightness) for c in rgb) + (255,)
        reflection = (73, 82, 78, 255) if state == "off" else (255, 250, 215, 255)
        ImageDraw.Draw(lens).line((17, 6, 33, 48), fill=reflection, width=1)
        lens.save(TEXTURES / f"lens_{state}.png")
        if state != "off":
            lens.save(TEXTURES / f"lens_{state}_e.png")
        led = Image.new("RGBA", (8, 8), rgb + (255,))
        led.save(TEXTURES / f"led_{state}.png")
        if state != "off":
            led.save(TEXTURES / f"led_{state}_e.png")


def box(name, start, end, texture, uv=None, rotation=None, emission=0):
    result = {
        "name": name, "from": [round(v, 4) for v in start], "to": [round(v, 4) for v in end],
        "faces": {face: {"uv": uv or [0, 0, 16, 16], "texture": f"#{texture}"} for face in FACES}
    }
    if rotation:
        result["rotation"] = rotation
    if emission:
        result["light_emission"] = emission
    return result


def tube(name, radius, z1, z2, texture, thickness=.55):
    elements = []
    width = radius * 2 * math.tan(math.pi / 8) + .035
    for i in range(8):
        angle = i * math.pi / 4
        cx, cy = 8 + math.sin(angle) * radius, 8 + math.cos(angle) * radius
        if i % 2 == 0:
            sx, sy = (width, thickness) if i % 4 == 0 else (thickness, width)
            rotation = None
        else:
            sx, sy = width, thickness
            rotation = {"origin": [round(cx, 4), round(cy, 4), 8], "axis": "z", "angle": -45 if i in (1, 5) else 45}
        elements.append(box(f"{name}_{i}", [cx - sx/2, cy - sy/2, z1], [cx + sx/2, cy + sy/2, z2], texture, rotation=rotation))
    return elements


DISPLAY = {
    # Roll the original underside switch towards the camera; preserve the aiming axis.
    "firstperson_righthand": {"rotation": [2.20, 2.02, -142], "translation": [-2.7, 1.5, -3], "scale": [.5, .5, .5]},
    "firstperson_lefthand": {"rotation": [2.20, 2.02, -142], "translation": [-2.7, 1.5, -3], "scale": [.5, .5, .5]},
    # Cancel the native hand's pitch/roll: lens forward (-Z), switch above the grip.
    "thirdperson_righthand": {"rotation": [90, 0, 180], "translation": [0, 0, 1], "scale": [.48, .48, .48]},
    "thirdperson_lefthand": {"rotation": [90, 0, 180], "translation": [0, 0, 1], "scale": [.48, .48, .48]},
    "gui": {"rotation": [28, 138, 0], "translation": [0, 0, 0], "scale": [.48, .48, .48]},
    "ground": {"rotation": [0, 0, 90], "translation": [0, 2, 0], "scale": [.35, .35, .35]},
    "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [.5, .5, .5]},
}


def flashlight(state):
    textures = {key: f"flashlight:item/{key}" for key in ("metal", "rubber", "rim", "olive", "switch", "label", "copper")}
    textures.update(lens=f"flashlight:item/lens_{state}", led=f"flashlight:item/led_{state}", particle="flashlight:item/metal")
    elements = [box("cuerpo_interior", [6.05, 6.05, -1.5], [9.95, 9.95, 19.2], "metal")]
    elements += tube("cuerpo_octogonal", 2.45, -1.5, 19, "metal", .75)
    elements += tube("agarre", 2.6, 5, 15.6, "rubber", .65)
    for z in (5.4, 7.2, 9, 10.8, 12.6, 14.4):
        elements += tube("anillo_agarre", 2.73, z, z + .28, "rubber", .25)
    elements += tube("tapa_pila", 2.75, 18, 19.3, "olive", .7)
    elements.append(box("tapa_trasera", [5.95, 5.95, 19.1], [10.05, 10.05, 19.4], "rubber"))
    elements += tube("cuello", 3.25, -3.8, -.6, "olive", .85)
    elements += tube("cabezal", 4.1, -7.9, -3.6, "metal", .8)
    elements += tube("bisel", 4.45, -8.7, -7.55, "rim", .6)
    elements += tube("borde_goma", 4.5, -8.9, -8.4, "rubber", .32)
    lens = box("reflector", [3.75, 3.75, -8.76], [12.25, 12.25, -8.71], "lens", emission=0 if state == "off" else 15)
    lens["faces"] = {"north": lens["faces"]["north"]}
    elements.append(lens)
    elements.append(box("base_interruptor", [6.65, 10.35, 1.9], [9.35, 10.95, 5.5], "rim"))
    elements.append(box("interruptor", [7, 10.9, 2.4], [9, 11.25 if state == "off" else 11.08, 4.6], "switch"))
    elements.append(box("indicador", [7.6, 10.96, 4.85], [8.4, 11.05, 5.28], "led", emission=0 if state == "off" else 12))
    elements.append(box("placa_nf01", [10.46, 7, -.2], [10.52, 9, 4.4], "label"))
    for z in (-.4, 4.7):
        elements.append(box("tornillo", [10.5, 7.7, z], [10.7, 8.3, z+.4], "rim"))
    elements.append(box("enganche_correa", [7.25, 6.9, 19.2], [8.75, 9.1, 20.1], "rubber"))
    return {"credit": "MrDino · FlashLight NF-01", "texture_size": [64, 64], "ambientocclusion": False,
            "gui_light": "front", "textures": textures, "display": DISPLAY, "elements": elements}


def make_battery():
    model = {
        "ambientocclusion": False, "gui_light": "front",
        "textures": {**{key: f"flashlight:item/{key}" for key in ("metal", "label", "copper", "rubber")}, "particle": "flashlight:item/label"},
        "display": {"gui": {"rotation": [20, 35, 0], "scale": [.85, .85, .85]},
                    "firstperson_righthand": {"rotation": [0, -15, -10], "translation": [0, 1, 0], "scale": [.7, .7, .7]}},
        "elements": [box("pila_9v", [4.5, 2, 5], [11.5, 13, 11], "label"),
                     box("tapa", [4.4, 12.7, 4.9], [11.6, 13.5, 11.1], "metal"),
                     box("terminal_positivo", [5.2, 13.5, 6.6], [6.8, 14.5, 8.3], "copper"),
                     box("terminal_negativo", [8.7, 13.5, 6.4], [10.7, 14.1, 8.5], "rubber")]
    }
    write_json(MODELS / "battery.json", model)
    write_json(ASSETS / "items" / "battery.json", {"model": {"type": "minecraft:model", "model": "flashlight:item/battery"}})


def pack_icon():
    icon = Image.new("RGB", (128, 128), (13, 18, 18))
    draw = ImageDraw.Draw(icon)
    for radius in range(57, 0, -1):
        value = int(19 + 10*(1-radius/57))
        draw.ellipse((64-radius, 64-radius, 64+radius, 64+radius), fill=(value, value+5, value+2))
    draw.polygon([(21, 27), (35, 20), (91, 76), (77, 90)], fill=(76, 82, 72))
    draw.polygon([(17, 30), (31, 16), (45, 30), (31, 44)], fill=(204, 205, 161))
    draw.polygon([(21, 29), (30, 21), (39, 30), (30, 39)], fill=(255, 243, 181))
    draw.line((42, 44, 82, 84), fill=(134, 133, 99), width=3)
    draw.text((24, 104), "FlashLight", fill=(217, 212, 185))
    icon.save(PACK / "pack.png")


def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    version = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
    write_json(PACK / "pack.mcmeta", {"pack": {"pack_format": 46, "description": f"FlashLight {version} · Directional flashlights · Minecraft Java 1.21.4"}})
    make_textures()
    provided = ROOT / "models/source/Flashlight.bbmodel"
    imported = None
    if provided.is_file():
        from import_bbmodel import adapt
        imported = adapt(provided, ASSETS, DISPLAY)
    for state in ("off", "on", "low"):
        base = imported[state] if imported else flashlight(state)
        write_json(MODELS / f"flashlight_{state}.json", base)
        write_json(MODELS / f"flashlight_plain_{state}.json", imported["plain"][state] if imported else base)
        hand_model = {"type": "minecraft:model", "model": f"flashlight:item/flashlight_{state}"}
        write_json(ASSETS / "items" / f"flashlight_{state}.json", {
            "hand_animation_on_swap": False,
            "model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["firstperson_righthand", "firstperson_lefthand"], "model": hand_model}],
                      "fallback": {"type": "minecraft:model", "model": f"flashlight:item/flashlight_plain_{state}"}}
        })
    from generate_held_pose import generate_pose
    generate_pose(imported["on"] if imported else flashlight("on"), ASSETS / "flashlight_pose.json")
    make_battery()
    from generate_beam_assets import generate
    generate(PACK)
    from generate_spotlight_assets import generate as generate_spotlight
    generate_spotlight(PACK)
    pack_icon()
    optifine = PACK / "assets" / "minecraft" / "optifine"
    optifine.mkdir(parents=True, exist_ok=True)
    (optifine / "emissive.properties").write_text("suffix.emissive=_e\n", encoding="ascii")
    print(f"Generated {len(list(PACK.rglob('*.json')))} models/definitions and {len(list(PACK.rglob('*.png')))} textures.")


if __name__ == "__main__":
    main()
