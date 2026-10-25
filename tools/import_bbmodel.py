"""Reconstruct the user's triangulated cuboids as lossless Java 1.21.4 cuboids.

The source uses mesh geometry and unrestricted Euler rotations. Each mesh is a
rectangular cuboid, so a signed axis permutation + one legal Java rotation can
represent it exactly. Original triangle UVs are baked into a compact face atlas.
"""
from __future__ import annotations

import base64
import copy
import io
import json
import math
from pathlib import Path
import uuid

import numpy as np
from PIL import Image

SCALE = 1.95
OFFSET = np.array([8.0, 8.0, 14.8])
ATLAS_SIZE = 1024
TILE = 64
PADDING = 2
CONTENT = TILE - PADDING*2
ANGLES = (0, -22.5, 22.5, -45, 45)


def rotate(axis, degrees):
    angle = math.radians(degrees)
    c, s = math.cos(angle), math.sin(angle)
    if axis == "x": return np.array([[1,0,0],[0,c,-s],[0,s,c]])
    if axis == "y": return np.array([[c,0,s],[0,1,0],[-s,0,c]])
    return np.array([[c,-s,0],[s,c,0],[0,0,1]])


def legal_decomposition(matrix):
    """Find C = legal_rotation @ axis_permutation, without distorting vertices."""
    for angle in ANGLES:
        for axis in ("x", "y", "z"):
            legal = rotate(axis, angle)
            permutation = legal.T @ matrix
            rounded = np.round(permutation)
            if np.max(np.abs(permutation-rounded)) < 1e-6 and np.all(np.abs(rounded).sum(0) == 1):
                return axis, angle, rounded, legal
    raise ValueError("The model contains an orientation that cannot be represented exactly in Java 1.21.4.")


def face_coordinates(points, minimum, maximum, name):
    size = np.maximum(maximum-minimum, 1e-12)
    normalized = (points-minimum)/size
    x, y, z = normalized.T
    return {
        "north": np.column_stack([1-x,1-y]), "south": np.column_stack([x,1-y]),
        "east": np.column_stack([1-z,1-y]), "west": np.column_stack([z,1-y]),
        "up": np.column_stack([x,z]), "down": np.column_stack([x,1-z]),
    }[name]


def face_name(points, minimum, maximum):
    flat = np.ptp(points, axis=0)
    axis = int(np.argmin(flat))
    if flat[axis] > 1e-6:
        raise ValueError("A mesh face is not parallel to a reconstructed cuboid face.")
    is_max = abs(points[0,axis]-maximum[axis]) < abs(points[0,axis]-minimum[axis])
    return (("west","east"),("down","up"),("north","south"))[axis][int(is_max)]


def bake_face(triangles, minimum, maximum, name, textures):
    yy, xx = np.mgrid[0:CONTENT,0:CONTENT]
    sx, sy = (xx+.5)/CONTENT, (yy+.5)/CONTENT
    result = np.zeros((CONTENT,CONTENT,4), dtype=np.uint8)
    covered = np.zeros((CONTENT,CONTENT), dtype=bool)
    for points, original_uv, texture in triangles:
        a,b,c = face_coordinates(points, minimum, maximum, name)
        denominator = (b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(denominator) < 1e-10:
            continue
        w0 = ((b[1]-c[1])*(sx-c[0])+(c[0]-b[0])*(sy-c[1]))/denominator
        w1 = ((c[1]-a[1])*(sx-c[0])+(a[0]-c[0])*(sy-c[1]))/denominator
        w2 = 1-w0-w1
        weights = np.stack([w0,w1,w2], axis=-1)
        mask = np.min(weights,axis=-1) >= -1e-5
        original = textures[str(texture)]
        h,w,_ = original.shape
        uv = weights @ original_uv
        tx = np.clip((uv[...,0]/16*w).astype(int),0,w-1)
        ty = np.clip((uv[...,1]/16*h).astype(int),0,h-1)
        result[mask] = original[ty,tx][mask]
        covered |= mask
    if not covered.all():
        raise ValueError(f"The UV conversion does not cover the entire {name} face.")
    return np.pad(result,((PADDING,PADDING),(PADDING,PADDING),(0,0)),mode="edge")


def adapt(source_path: Path, assets: Path, display: dict):
    source = json.loads(source_path.read_text(encoding="utf-8"))
    if any(isinstance(node,dict) for node in source.get("outliner",[])):
        raise ValueError("This importer expects the root meshes from the supplied Flashlight.bbmodel file.")
    textures = {}
    for texture in source["textures"]:
        encoded = base64.b64decode(texture["source"].split(",",1)[1])
        textures[str(texture["id"])] = np.asarray(Image.open(io.BytesIO(encoded)).convert("RGBA"))
    canonical = rotate("x",-90)
    converted = []
    atlas = np.zeros((ATLAS_SIZE,ATLAS_SIZE,4),dtype=np.uint8)
    lens_mask = np.zeros((ATLAS_SIZE,ATLAS_SIZE),dtype=bool)
    tile_index = 0
    largest_error = 0
    seen = {}
    duplicates = []
    for index, mesh in enumerate(source["elements"]):
        if mesh.get("export",True) is False or mesh.get("visibility",True) is False:
            continue
        keys = list(mesh["vertices"])
        vertices = np.array([mesh["vertices"][key] for key in keys],dtype=float)
        if len(vertices) != 8 or any(len(np.unique(vertices[:,axis])) != 2 for axis in range(3)):
            raise ValueError(f"Part {index} is no longer a rectangular cuboid and requires another conversion.")
        rx,ry,rz = mesh.get("rotation",[0,0,0])
        transform = canonical @ rotate("z",rz) @ rotate("y",ry) @ rotate("x",rx)
        axis, angle, permutation, legal = legal_decomposition(transform)
        origin = canonical @ np.array(mesh["origin"])*SCALE + OFFSET
        base_points = vertices @ permutation.T
        minimum,maximum = base_points.min(0),base_points.max(0)
        start,end = minimum*SCALE+origin,maximum*SCALE+origin
        reconstructed = (base_points*SCALE) @ legal.T + origin
        expected = vertices @ transform.T*SCALE + origin
        error = float(np.max(np.abs(reconstructed-expected)))
        largest_error = max(largest_error,error)
        if error > 1e-6:
            raise ValueError(f"The shape of part {index} changed: error {error}.")
        signature = tuple(sorted(tuple(np.round(vertex,6)) for vertex in reconstructed))
        if signature in seen:
            duplicates.append(seen[signature])
            converted[seen[signature]]["skip_duplicate"] = True
        seen[signature] = len(converted)
        grouped = {name:[] for name in ("north","south","east","west","up","down")}
        by_key = {key:base_points[i] for i,key in enumerate(keys)}
        for face in mesh["faces"].values():
            if len(face["vertices"]) != 3:
                raise ValueError("A triangulated mesh was expected.")
            points = np.array([by_key[key] for key in face["vertices"]])
            uv = np.array([face["uv"][key] for key in face["vertices"]])
            name = face_name(points,minimum,maximum)
            grouped[name].append((points,uv,face["texture"]))
        faces = {}
        # The source reflector is the eight very thin cuboids at its front.
        is_lens = float(np.min(np.ptp(vertices,axis=0))) < .005
        for name,triangles in grouped.items():
            tile = bake_face(triangles,minimum,maximum,name,textures)
            column,row = tile_index % 16,tile_index // 16
            if row >= 16:
                raise ValueError("The conversion atlas has insufficient space.")
            px,py = column*TILE,row*TILE
            atlas[py:py+TILE,px:px+TILE] = tile
            if is_lens:
                lens_mask[py:py+TILE,px:px+TILE] = True
            faces[name] = {"uv":[round(value/ATLAS_SIZE*16,7) for value in
                                      (px+PADDING,py+PADDING,px+TILE-PADDING,py+TILE-PADDING)],"texture":"#imported"}
            tile_index += 1
        element = {"name":f"user_model_{index:02d}","from":np.round(start,6).tolist(),
                   "to":np.round(end,6).tolist(),"faces":faces,"source_lens":is_lens}
        if angle:
            element["rotation"] = {"origin":np.round(origin,6).tolist(),"axis":axis,"angle":angle}
        converted.append(element)
    kept = [entry for entry in converted if not entry.get("skip_duplicate")]
    settings = copy.deepcopy(display)
    for side in ("righthand","lefthand"):
        settings[f"thirdperson_{side}"]["scale"] = [.32,.32,.32]
    settings["gui"]["scale"] = [.60,.60,.60]
    settings["ground"]["scale"] = [.30,.30,.30]
    outputs = {}
    texture_dir = assets/"textures/item"
    texture_dir.mkdir(parents=True,exist_ok=True)
    for state,multiplier in (("on",1.0),("low",.55),("off",.12)):
        state_atlas = atlas.copy()
        state_atlas[lens_mask,:3] = np.clip(atlas[lens_mask,:3]*multiplier,0,255)
        Image.fromarray(state_atlas).save(texture_dir/f"imported_{state}.png")
        if state != "off":
            emissive = np.zeros_like(atlas)
            emissive[lens_mask] = state_atlas[lens_mask]
            Image.fromarray(emissive).save(texture_dir/f"imported_{state}_e.png")
        elements = copy.deepcopy(kept)
        for element in elements:
            if element.pop("source_lens") and state != "off":
                element["light_emission"] = 15
        outputs[state] = {"credit":"Adapted from the user-provided Flashlight.bbmodel",
                          "texture_size":[ATLAS_SIZE,ATLAS_SIZE],"ambientocclusion":False,"gui_light":"front",
                          "textures":{"imported":f"flashlight:item/imported_{state}","particle":f"flashlight:item/imported_{state}"},
                          "display":settings,"elements":elements}
    model_dir = source_path.parents[1]
    plain_models = copy.deepcopy(outputs)
    export_editable(outputs["on"],texture_dir/"imported_on.png",model_dir/"Flashlight-adaptada.bbmodel")
    report = {"source":source_path.name,"source_meshes":len(source["elements"]),"source_cuboids":len(kept),
              "java_cuboids":len(outputs["on"]["elements"]),
              "removed_duplicate_meshes":duplicates,"uniform_scale":SCALE,"maximum_vertex_error":largest_error,
              "length_model_units":round(11*SCALE,3),"first_person_scale":settings["firstperson_righthand"]["scale"],
              "third_person_scale":settings["thirdperson_righthand"]["scale"],
              "notes":"Original shapes and colours preserved. Baked triangular UVs; reflector with light states."}
    (model_dir/"conversion-report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    print(f"Imported model: {len(kept)} Java cuboids, {len(duplicates)} duplicates removed, geometry error {largest_error:.2g}.")
    outputs["plain"] = plain_models
    return outputs


def export_editable(model, texture_path, destination):
    elements = []
    for element in model["elements"]:
        cube = copy.deepcopy(element)
        cube.update(type="cube",uuid=str(uuid.uuid5(uuid.NAMESPACE_URL,"nightfall:"+cube["name"])),
                    box_uv=False,rescale=False,locked=False,export=True,visibility=True)
        rotation = cube.pop("rotation",None)
        cube["origin"] = rotation["origin"] if rotation else [8,8,8]
        cube["rotation"] = [0,0,0]
        if rotation:
            cube["rotation"][("x","y","z").index(rotation["axis"])] = rotation["angle"]
        for face in cube["faces"].values():
            face["uv"] = [v/16*ATLAS_SIZE for v in face["uv"]]
            face["texture"] = 0
        elements.append(cube)
    texture = {"name":"imported_on.png","id":"0","uuid":str(uuid.uuid5(uuid.NAMESPACE_URL,"nightfall:imported_on")),
               "width":ATLAS_SIZE,"height":ATLAS_SIZE,"uv_width":ATLAS_SIZE,"uv_height":ATLAS_SIZE,
               "source":"data:image/png;base64,"+base64.b64encode(texture_path.read_bytes()).decode(),"internal":True,"saved":False}
    editable = {"meta":{"format_version":"5.0","model_format":"java_block","box_uv":False},
                "name":"Adapted Flashlight · Minecraft Java","resolution":{"width":ATLAS_SIZE,"height":ATLAS_SIZE},
                "elements":elements,"outliner":[cube["uuid"] for cube in elements],"textures":[texture],
                "ambientocclusion":False,"front_gui_light":True,"display":model["display"]}
    destination.write_text(json.dumps(editable,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8")
