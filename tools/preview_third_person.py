"""Render actual flashlight geometry in the native hand frame, with fist bounds.

This is a technical model comparison, not a Minecraft screenshot. Reference
hands exist only in this image; no arm geometry is added to the resource pack.
"""
import copy
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from preview_model import render, rotation

ROOT = Path(__file__).resolve().parents[1]


def panel(left, previous):
    model = json.loads((ROOT / "resource-pack/assets/flashlight/models/item/flashlight_plain_on.json").read_text())
    display = copy.deepcopy(model["display"]["thirdperson_" + ("left" if left else "right") + "hand"])
    if previous:
        display.update(rotation=[-80, 0, 0], translation=[0, -4, -1.5])
    model["textures"]["reference"] = "flashlight:diagnostic/reference_only"
    model["elements"].append({
        "name": "reference_fist", "from": [-1 if left else -3, 7, -2],
        "to": [3 if left else 1, 10, 2],
        "faces": {name: {"texture": "#reference", "uv": [0, 0, 16, 16]}
                  for name in ("north", "south", "east", "west", "up", "down")}})
    side = -1 if left else 1
    native = rotation("x", -90) @ rotation("y", 180)
    rx, ry, rz = display["rotation"]
    item_rotation = rotation("x", rx) @ rotation("y", ry * side) @ rotation("z", rz * side)
    offset = (np.array([side, 2, -10]) + np.array(display["translation"]) * [side, 1, 1]) / 16

    def transform(vertices, element):
        if element["name"] != "reference_fist":
            vertices = ((vertices / 16 - .5) * display["scale"]) @ item_rotation.T + offset
            vertices = (vertices @ native.T) * 16
        return vertices * [1, -1, 1]

    return render(model, 600, 430, geometry_transform=transform,
                  view_angles=(-10, 100 if not left else 80),
                  view_center=(1 if left else -1, -9, -1.5), pixels_per_unit=30)


def main():
    canvas = Image.new("RGB", (1280, 1080), (13, 18, 19))
    draw = ImageDraw.Draw(canvas)
    try:
        title = ImageFont.truetype("C:/Windows/Fonts/seguisb.ttf", 30)
        regular = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 18)
    except OSError:
        title = regular = ImageFont.load_default()
    draw.text((30, 18), "FlashLight 1.8.1 / Third-person grip correction", font=title, fill=(235, 231, 215))
    draw.text((30, 63), "Native hand frame. Blue reference boxes mark vanilla fist bounds; they are not pack assets.",
              font=regular, fill=(160, 177, 172))
    for row, previous in enumerate((True, False)):
        for column, left in enumerate((False, True)):
            x, y = 20 + column * 640, 105 + row * 450
            canvas.paste(panel(left, previous), (x, y))
            label = ("1.8.0 / BEFORE" if previous else "1.8.1 / CORRECTED") + (" / LEFT HAND" if left else " / RIGHT HAND")
            draw.text((x + 16, y + 10), label, font=regular,
                      fill=(210, 170, 150) if previous else (180, 224, 190))
            draw.line((x + 155, y + 58, x + 35, y + 58), fill=(219, 211, 170), width=2)
            draw.polygon([(x + 35, y + 58), (x + 46, y + 52), (x + 46, y + 64)], fill=(219, 211, 170))
            draw.text((x + 170, y + 45), "PLAYER FORWARD", font=regular, fill=(219, 211, 170))
            draw.text((x + 16, y + 390),
                      "Lens backward; grip below fist" if previous else "Lens forward; switch on top; grip inside fist",
                      font=regular, fill=(166, 182, 176))
    draw.text((30, 1028), "Technical render of the real model, not an in-game screenshot. First-person transforms are unchanged.",
              font=regular, fill=(155, 171, 165))
    path = ROOT / "docs/third-person-grip-preview.png"
    canvas.save(path)
    print(path)


if __name__ == "__main__":
    main()
