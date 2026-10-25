"""TRP-style six-face carriers and a soft flashlight footprint. No vanilla items."""
import json
import math
from PIL import Image


def generate(pack):
    assets = pack / "assets/flashlight"
    models = assets / "models/beam"
    # Minecraft's blocks/item atlas loads item/ and block/ directories only.
    textures = assets / "textures/item/beam"
    items = assets / "items/beam"
    for folder in (models, textures, items):
        folder.mkdir(parents=True, exist_ok=True)
    # The flashlight no longer needs TRP's wide proxy and 50% end extrusion:
    # every receiving plane now owns its bounded projection.
    half_width, min_z, max_z = .51, -.01, 1.01
    (assets / "shaders/include/beam_proxy.glsl").write_text(
        f"const float TRP_PROXY_HALF_WIDTH = {half_width};\n"
        f"const float TRP_PROXY_MIN_Z = {min_z};\n"
        f"const float TRP_PROXY_MAX_Z = {max_z};\n", encoding="utf-8")

    def model(name, value):
        (models / f"{name}.json").write_text(json.dumps(value, separators=(",", ":")) + "\n", encoding="utf-8")
        (items / f"{name}.json").write_text(json.dumps({"model": {
            "type": "minecraft:model", "model": f"flashlight:beam/{name}",
            "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]
        }}, separators=(",", ":")) + "\n", encoding="utf-8")

    # Legacy private IDs resolve to the joined model without loading duplicate
    # volume sprites into Minecraft's atlas.
    for inward, open_air, reach in ((i, o, r) for i in (False, True) for o in (False, True) for r in range(4, 33)):
            name = f"volume_{int(inward)}_{int(open_air)}_{reach}"
            model(name,{"parent":f"flashlight:beam/volume_0_both_{reach}",
                        "textures":{"particle":f"flashlight:item/beam/volume_marker_0_{reach}"}})
            stale=(textures / f"{name}.png").resolve()
            assert stale.parent==textures.resolve() and stale.is_relative_to(pack.resolve())
            stale.unlink(missing_ok=True)

    # Both windings in one model/entity. Six thin planes have an outward and
    # inward face; UV flips preserve the same positive-handed local coordinates.
    directions=("down","up","north","south","west","east")
    opposites=("up","down","south","north","east","west")
    for reach in range(4,33):
        for inside in (0,1):
            image=Image.new("RGBA",(192,128))
            alpha=106+(reach-4)*4+inside*2+1
            for y in range(image.height):
                for x in range(image.width):
                    r=round(max(0,min(1,(x%64-8)/48))*255)
                    g=round(max(0,min(1,(y%64-8)/48))*255)
                    # Keep the established TRP marker band. It is already
                    # recognized reliably by Minecraft/OptiFine entity shaders.
                    image.putpixel((x,y),(r,g,208+(y//64)*3+x//64,alpha))
            image.save(textures / f"volume_marker_{inside}_{reach}.png")
        elements=[]
        low=[8-16*half_width,8-16*half_width,8+16*min_z]
        high=[8+16*half_width,8+16*half_width,8+16*max_z]
        for face,direction in enumerate(directions):
            col,row=face%3,face//3
            uv=[col*16/3+2/3,row*8+1,(col+1)*16/3-2/3,(row+1)*8-1]
            flipped=[uv[0],uv[3],uv[2],uv[1]] if face<2 else [uv[2],uv[1],uv[0],uv[3]]
            start,end=low.copy(),high.copy()
            axis=1 if face<2 else 2 if face<4 else 0
            start[axis]=end[axis]=low[axis] if face%2==0 else high[axis]
            elements.append({"from":start,"to":end,"shade":False,"faces":{
                direction:{"uv":uv,"texture":"#outer","tintindex":0},
                opposites[face]:{"uv":flipped,"texture":"#inner","tintindex":0}}})
        model(f"volume_0_both_{reach}",{"ambientocclusion":False,"textures":{
            "outer":f"flashlight:item/beam/volume_marker_0_{reach}",
            "inner":f"flashlight:item/beam/volume_marker_1_{reach}",
            "particle":f"flashlight:item/beam/volume_marker_0_{reach}"},"elements":elements})

    # Older plugin copies can still resolve their private item IDs while packs update.
    for inward in (0, 1):
        for open_air in (0, 1):
            name = f"volume_{inward}_{open_air}"
            (items / f"{name}.json").write_text(json.dumps({"model": {
                "type": "minecraft:model", "model": f"flashlight:beam/{name}_20",
                "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]
            }}, separators=(",", ":")) + "\n", encoding="utf-8")

    image = Image.new("RGBA", (256, 256))
    for y in range(256):
        for x in range(256):
            radius = math.hypot((x-127.5)/127.5, (y-127.5)/127.5)
            edge = max(0, min(1, (1-radius)/.30))
            hotspot = math.exp(-radius*radius*7)
            mask = round(255 * edge * edge * (.20 + .58 * hotspot))
            # OptiFine removes very low-alpha sprite pixels. Transport the smooth
            # mask in RGB with opaque alpha, and restore transparency in GLSL.
            image.putpixel((x, y), (mask, mask, mask, 255))
    # These pixels identify only this projection, and are hidden by the shader.
    for i in range(4):
        for j in range(256):
            for x, y in ((i,j), (255-i,j), (j,i), (j,255-i)):
                image.putpixel((x,y), (23,41,251,48))
    image.save(textures / "impact.png")
    (textures / "impact.png.mcmeta").write_text('{"texture":{"blur":true,"clamp":true}}\n', encoding="utf-8")
    model("impact", {"ambientocclusion": False, "textures": {"beam": "flashlight:item/beam/impact", "particle": "flashlight:item/beam/impact"},
                     "elements": [{"from": [0,0,8], "to": [16,16,8], "shade": False,
                                   "faces": {direction: {"uv": [.0625,.0625,15.9375,15.9375], "texture": "#beam", "tintindex": 0}
                                             for direction in ("north", "south")}}]})

    # Tiny coordinate sprites encode the ellipse's aspect and orientation.
    # Geometry is clipped to real collision faces; the shader projects one
    # coherent spotlight across them instead of drawing a disc on every block.
    for kind, aspect in ((kind,aspect) for kind in (0,1) for aspect in range(16)):
        for angle in range(32):
            name = f"surface_{'h_' if kind else ''}{aspect}_{angle}"
            image = Image.new("RGBA", (16, 16))
            for y in range(16):
                for x in range(16):
                    image.putpixel((x,y),(round(17+222*x/15),round(17+222*y/15),64+kind*16+aspect,224+angle))
            image.save(textures / f"{name}.png")
            model(name,{"ambientocclusion":False,
                        "textures":{"marker":f"flashlight:item/beam/{name}","particle":f"flashlight:item/beam/{name}"},
                        "elements":[{"from":[0,0,8],"to":[16,16,8],"shade":False,"faces":{
                            "south":{"uv":[.5,.5,15.5,15.5],"texture":"#marker","tintindex":0},
                            "north":{"uv":[15.5,.5,.5,15.5],"texture":"#marker","tintindex":0}}}]})
