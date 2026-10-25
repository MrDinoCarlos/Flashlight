"""Small textured software renderer for the actual JSON cuboids (Pillow + NumPy).

This is a model preview, not a Minecraft screenshot. No player skin is bundled.
"""
from pathlib import Path
import json
import math
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resource-pack"


def rotation(axis, degrees):
    angle = math.radians(degrees)
    c, s = math.cos(angle), math.sin(angle)
    if axis == "x": return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y": return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def render(model_path, width=600, height=460, first_person=False, *,
           geometry_transform=None, view_angles=(24, 135), view_center=(8, 8, 5.5), pixels_per_unit=15):
    model = model_path if isinstance(model_path, dict) else json.loads(model_path.read_text(encoding="utf-8"))
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    for y in range(height):
        canvas[y, :, :] = [15 + int(7*y/height), 20 + int(9*y/height), 21 + int(10*y/height)]
    depth = np.full((height, width), np.inf)
    textures = {}
    for key, value in model["textures"].items():
        namespace, path = value.split(":")
        file = PACK / "assets" / namespace / "textures" / f"{path}.png"
        if file.exists():
            textures[key] = np.asarray(Image.open(file).convert("RGBA")).copy()
            if key.startswith("control_"): textures[key][..., :3] = [73, 51, 74]
        else:
            # Only for a local geometry diagnostic; never put a generic skin in the pack.
            debug = np.full((64, 64, 4), [100, 154, 166, 255], dtype=np.uint8)
            debug[::4, :, :3] = [60, 100, 109]
            textures[key] = debug
    if first_person:
        display = model["display"]["firstperson_righthand"]
        rx, ry, rz = display["rotation"]
        transform = rotation("x", rx) @ rotation("y", ry) @ rotation("z", rz)
        scale = np.array(display["scale"])
        translation = np.array(display["translation"])/16 + [0.56, -.52, -.72]

        def project_space(vertices):
            return ((vertices/16 - .5) * scale) @ transform.T + translation
    else:
        transform = rotation("x", view_angles[0]) @ rotation("y", view_angles[1])

        def project_space(vertices):
            return (vertices - view_center) @ transform.T + [0, 0, -60]

    for element in model["elements"]:
        x1, y1, z1 = element["from"]
        x2, y2, z2 = element["to"]
        faces = {
            "north": [[x2,y1,z1],[x1,y1,z1],[x1,y2,z1],[x2,y2,z1]],
            "south": [[x1,y1,z2],[x2,y1,z2],[x2,y2,z2],[x1,y2,z2]],
            "east": [[x2,y1,z2],[x2,y1,z1],[x2,y2,z1],[x2,y2,z2]],
            "west": [[x1,y1,z1],[x1,y1,z2],[x1,y2,z2],[x1,y2,z1]],
            "up": [[x1,y2,z2],[x2,y2,z2],[x2,y2,z1],[x1,y2,z1]],
            "down": [[x1,y1,z1],[x2,y1,z1],[x2,y1,z2],[x1,y1,z2]],
        }
        for name, face in element["faces"].items():
            vertices = np.array(faces[name], dtype=float)
            if "rotation" in element:
                pivot = np.array(element["rotation"]["origin"])
                rotate = rotation(element["rotation"]["axis"], element["rotation"]["angle"])
                vertices = (vertices - pivot) @ rotate.T + pivot
            if geometry_transform is not None:
                vertices = geometry_transform(vertices, element)
            vertices = project_space(vertices)
            normal = np.cross(vertices[1]-vertices[0], vertices[2]-vertices[0])
            length = np.linalg.norm(normal)
            if length < 1e-9: continue
            normal /= length
            shade = .5 + .5*abs(np.dot(normal, np.array([-.3, .8, .52])))
            if element.get("light_emission", 0) > 0: shade = 1.05
            u1, v1, u2, v2 = face["uv"]
            uv = np.array([[u1,v2],[u2,v2],[u2,v1],[u1,v1]])/16
            uv = np.roll(uv, face.get("rotation", 0)//90, axis=0)
            texture = textures[face["texture"][1:]]
            polygon = [np.concatenate([position, coord]) for position, coord in zip(vertices, uv)]
            if first_person:
                clipped = []
                for previous, current in zip(polygon[-1:] + polygon[:-1], polygon):
                    pin, cin = previous[2] <= -.04, current[2] <= -.04
                    if pin != cin:
                        t = (-.04-previous[2])/(current[2]-previous[2])
                        clipped.append(previous + t*(current-previous))
                    if cin: clipped.append(current)
                polygon = clipped
            for index in range(1, len(polygon)-1):
                tri = np.array([polygon[0], polygon[index], polygon[index+1]])
                distance = -tri[:, 2]
                if first_person:
                    focal = height/(2*math.tan(math.radians(70)/2))
                    screen = np.column_stack([width/2 + tri[:,0]*focal/distance, height/2 - tri[:,1]*focal/distance])
                else:
                    screen = np.column_stack([width/2 + tri[:,0]*pixels_per_unit, height/2 - tri[:,1]*pixels_per_unit])
                minx, miny = np.maximum(0, np.floor(screen.min(axis=0)).astype(int))
                maxx, maxy = np.minimum([width-1, height-1], np.ceil(screen.max(axis=0)).astype(int))
                if minx > maxx or miny > maxy: continue
                a,b,c = screen
                denominator = (b[1]-c[1])*(a[0]-c[0]) + (c[0]-b[0])*(a[1]-c[1])
                if abs(denominator) < 1e-9: continue
                yy, xx = np.mgrid[miny:maxy+1, minx:maxx+1]
                w0 = ((b[1]-c[1])*(xx-c[0]) + (c[0]-b[0])*(yy-c[1]))/denominator
                w1 = ((c[1]-a[1])*(xx-c[0]) + (a[0]-c[0])*(yy-c[1]))/denominator
                w2 = 1-w0-w1
                weights = np.stack([w0,w1,w2], axis=-1)
                mask = (weights.min(axis=-1) >= -1e-5)
                if first_person:
                    adjusted = weights/distance
                    inverse_depth = adjusted.sum(axis=-1)
                    z = 1/np.maximum(inverse_depth, 1e-12)
                    coords = (adjusted @ tri[:,3:5])/np.maximum(inverse_depth[...,None], 1e-12)
                else:
                    z = weights @ distance
                    coords = weights @ tri[:,3:5]
                h,w,_ = texture.shape
                tx = (np.clip(np.nan_to_num(coords[...,0]), 0, .999999)*w).astype(int)
                ty = (np.clip(np.nan_to_num(coords[...,1]), 0, .999999)*h).astype(int)
                color = texture[ty,tx]
                mask &= (color[...,3] > 80) & (z < depth[miny:maxy+1,minx:maxx+1])
                depth[miny:maxy+1,minx:maxx+1][mask] = z[mask]
                canvas[miny:maxy+1,minx:maxx+1][mask] = np.clip(color[...,:3][mask]*shade, 0, 255)
    return Image.fromarray(canvas)


def main():
    if "--first-person" in sys.argv:
        output = ROOT / "build" / "first-person-diagnostic.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        render(PACK / "assets/flashlight/models/item/flashlight_on.json", 960, 540, True).save(output)
    else:
        output = ROOT / "docs" / "model-preview.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGB", (1280, 650), (13, 18, 19))
        image.paste(render(PACK / "assets/flashlight/models/item/flashlight_off.json"), (20, 120))
        image.paste(render(PACK / "assets/flashlight/models/item/flashlight_on.json"), (660, 120))
        draw = ImageDraw.Draw(image)
        try:
            title = ImageFont.truetype("C:/Windows/Fonts/seguisb.ttf", 34)
            regular = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 18)
        except OSError:
            title = regular = ImageFont.load_default()
        draw.text((40, 25), "FlashLight / NF-01", fill=(231, 226, 199), font=title)
        draw.text((40, 74), "Adapted Flashlight.bbmodel · Original shape and colours · Minecraft Java", fill=(151, 164, 158), font=regular)
        draw.text((40, 124), "01 / OFF", fill=(144, 158, 153), font=regular)
        draw.text((680, 124), "02 / ON", fill=(231, 218, 165), font=regular)
        draw.text((40, 607), "Technical model preview. Shaders included in the resource pack. No added arm geometry.", fill=(143, 154, 149), font=regular)
        image.save(output)
    print(output)


if __name__ == "__main__":
    main()
