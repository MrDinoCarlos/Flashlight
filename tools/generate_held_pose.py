"""Derive the emitter from the actual lens and Minecraft's 1.21.4 held-item pose."""
import json
import numpy as np
from preview_model import rotation


def generate_pose(model, destination):
    points = []
    rim_points = []
    for element in model["elements"]:
        if not element.get("light_emission"):
            continue
        centre = (np.array(element["from"]) + np.array(element["to"])) / 2
        if "rotation" in element:
            r = element["rotation"]
            pivot = np.array(r["origin"])
            centre = rotation(r["axis"], r["angle"]) @ (centre - pivot) + pivot
        points.append(centre)
        start, end = np.array(element["from"]), np.array(element["to"])
        for x in (start[0], end[0]):
            for y in (start[1], end[1]):
                vertex = np.array([x, y, (start[2]+end[2])/2])
                if "rotation" in element:
                    vertex = rotation(r["axis"], r["angle"]) @ (vertex - pivot) + pivot
                rim_points.append(vertex)
    if not points:
        raise ValueError("The flashlight model has no identifiable emitting lens")
    lens = np.mean(points, axis=0)
    poses = {}
    for side, hand in ((1, "right"), (-1, "left")):
        display = model["display"][f"firstperson_{hand}hand"]
        rx, ry, rz = display["rotation"]
        matrix = rotation("x", rx) @ rotation("y", ry * side) @ rotation("z", rz * side)
        translation = np.array(display["translation"], dtype=float) / 16
        translation[0] *= side
        # ItemInHandRenderer.applyItemArmTransform, with equip/swing at rest.
        base = np.array([.56 * side, -.52, -.72])
        origin = base + translation + matrix @ ((lens / 16 - .5) * display["scale"])
        direction = matrix @ np.array([0, 0, -1])
        poses[hand] = {"origin": origin.tolist(), "direction": direction.tolist()}
    radius = max(np.linalg.norm((vertex-lens)[:2]) for vertex in rim_points) / 16 * model["display"]["firstperson_righthand"]["scale"][0]
    destination.write_text(json.dumps({"lens_model": lens.tolist(), "lens_radius": radius, "hands": poses}, indent=2) + "\n", encoding="utf-8")
    include = destination.parent / "shaders/include/optics.glsl"
    include.parent.mkdir(parents=True, exist_ok=True)
    content=f"// Physical lens and camera-space poses generated from the supplied model.\nconst float FLASHLIGHT_LENS_RADIUS = {radius:.9f};\n"
    for hand in ('right','left'):
        for field in ('origin','direction'):
            values=', '.join(f'{value:.9f}' for value in poses[hand][field])
            content+=f'const vec3 FLASHLIGHT_{hand.upper()}_{field.upper()} = vec3({values});\n'
    include.write_text(content, encoding="utf-8")
