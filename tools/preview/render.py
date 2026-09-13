#!/usr/bin/env python3
"""Renders a Bedrock model from a resource pack to a PNG, without starting Minecraft.

This mirrors the conversion the mod does at load time: the same axis flip, the same face mapping,
the same UV rectangles normalised against the geometry's declared texture size. Looking at its
output is how a change to that conversion gets checked, so a mistake shows up here rather than in
the game.

It reads a pack the way the mod does. Geometries come from `models/**/*.json` in either shape a
pack ships: the 1.12 `minecraft:geometry` array whose entries name themselves, or the 1.8 form
where each geometry is a top level `geometry.name` key. A texture is named by its path inside the
pack without an extension, and is read from the `.png` or the `.tga` beside it.
"""

import argparse
import json
import math
import pathlib
import sys

from PIL import Image

MODEL_ORIGIN_HEIGHT = 24.0

# Which end of which axis a Bedrock face name refers to, after the Y flip into Java model space.
FACES = {
    "east": ("x", 1),
    "west": ("x", 0),
    "up": ("y", 0),
    "down": ("y", 1),
    "north": ("z", 0),
    "south": ("z", 1),
}

# The corner order Minecraft uses per face, as indices into the eight corners of a box, where bit 0
# is X, bit 1 is Y and bit 2 is Z, set meaning the maximum end.
CORNERS = {
    ("y", 0): [5, 4, 0, 1],
    ("y", 1): [3, 2, 6, 7],
    ("x", 1): [5, 1, 3, 7],
    ("x", 0): [0, 4, 6, 2],
    ("z", 0): [1, 0, 2, 3],
    ("z", 1): [4, 5, 7, 6],
}

NORMALS = {
    ("x", 0): (-1, 0, 0), ("x", 1): (1, 0, 0),
    ("y", 0): (0, -1, 0), ("y", 1): (0, 1, 0),
    ("z", 0): (0, 0, -1), ("z", 1): (0, 0, 1),
}

# How wide and how tall a box looks from a face on each axis, for a face declaring no uv_size.
FACE_SIZES = {
    "x": lambda size: (size[2], size[1]),
    "y": lambda size: (size[0], size[2]),
    "z": lambda size: (size[0], size[1]),
}

TEXTURE_SUFFIXES = (".png", ".tga")

USAGE = (__doc__ or "").splitlines()[0]


def pixels_of(image):
    """The per-pixel accessor of an image, which Pillow withholds for a format it cannot map."""
    access = image.load()
    if access is None:
        raise RuntimeError(f"Pillow gave no pixel access to a {image.size} {image.mode} image")
    return access


def rotate(point, angles):
    """Applies a ZYX euler rotation in radians, matching Quaternionf.rotationZYX."""
    x, y, z = point
    rx, ry, rz = angles
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return x, y, z


def to_model_space(vector):
    return vector[0], MODEL_ORIGIN_HEIGHT - vector[1], vector[2]


def to_model_rotation(degrees):
    return math.radians(degrees[0]), math.radians(-degrees[1]), math.radians(degrees[2])


def load_geometries(pack):
    """Every geometry under `models/`, in both the 1.12 and the 1.8 shape, keyed by identifier.

    A 1.8 geometry may extend one held in another file, so those are gathered first and folded
    together once every file has been read.
    """
    geometries = {}
    named = {}
    for file in sorted((pack / "models").rglob("*.json")):
        try:
            root = json.loads(file.read_text(encoding="utf-8"))
        except (ValueError, OSError) as error:
            print(f"could not read {file}: {error}", file=sys.stderr)
            continue
        if not isinstance(root, dict):
            continue

        if "minecraft:geometry" in root:
            for body in root["minecraft:geometry"]:
                identifier = body.get("description", {}).get("identifier")
                if identifier:
                    geometries[identifier] = normalise(body.get("description", {}), body)
            continue

        # The 1.8 form keeps the texture size beside the bones, spelled without its underscore, and
        # names what a geometry extends in the key itself as `geometry.child:geometry.parent`.
        named.update({name: body for name, body in root.items()
                      if name.startswith("geometry.") and isinstance(body, dict)})

    for name, body in named.items():
        identifier, _, parent = name.partition(":")
        merged = inherit(body, parent, named) if parent else body
        geometries[identifier] = normalise(
            {"texture_width": merged.get("texturewidth", merged.get("texture_width", 64)),
             "texture_height": merged.get("textureheight", merged.get("texture_height", 64))},
            merged)
    return geometries


def inherit(child, parent_name, named):
    """A 1.8 geometry written as an extension of another, folded into one body.

    The child's own fields win, and a bone it names again replaces the parent's bone of that name.
    """
    for name, body in named.items():
        identifier, _, grandparent = name.partition(":")
        if identifier != parent_name:
            continue
        parent = inherit(body, grandparent, named) if grandparent else body
        bones = {bone.get("name"): bone for bone in parent.get("bones", [])}
        bones.update({bone.get("name"): bone for bone in child.get("bones", [])})
        return {**parent, **child, "bones": list(bones.values())}
    return child


def normalise(description, body):
    return {
        "texture_width": float(description.get("texture_width", 64)),
        "texture_height": float(description.get("texture_height", 64)),
        "bones": body.get("bones", []),
    }


def load_texture(pack, name):
    """Reads a pack texture named without an extension, from the PNG or the Targa on disk."""
    candidates = [pack / f"{name}{suffix}" for suffix in TEXTURE_SUFFIXES]
    if pathlib.Path(name).suffix:
        candidates.insert(0, pack / name)

    for candidate in candidates:
        if not candidate.is_file():
            continue
        if candidate.suffix.lower() == ".tga":
            return read_tga(candidate.read_bytes())
        return Image.open(candidate).convert("RGBA")

    raise FileNotFoundError(f"no {' or '.join(TEXTURE_SUFFIXES)} for {name} under {pack}")


def read_tga(data):
    """Decodes the Targa shapes pack tools write: true colour, colour mapped and grey, raw or run
    length encoded. Pillow rejects several of these, so the mod carries its own reader and so does
    this."""
    if len(data) < 18:
        raise ValueError(f"not a Targa file: {len(data)} bytes")

    id_length, colour_map_type, image_type = data[0], data[1], data[2]
    colour_map_length = int.from_bytes(data[5:7], "little")
    colour_map_depth = data[7]
    width = int.from_bytes(data[12:14], "little")
    height = int.from_bytes(data[14:16], "little")
    depth = data[16]
    descriptor = data[17]
    top_down = bool(descriptor & 0x20)
    right_to_left = bool(descriptor & 0x10)

    if width <= 0 or height <= 0:
        raise ValueError(f"Targa image is {width}x{height}")
    kind = image_type % 8
    if kind not in (1, 2, 3):
        raise ValueError(f"unsupported Targa type {image_type}")

    at = 18 + id_length
    palette = []
    if colour_map_type == 1:
        entry_bytes = max(1, colour_map_depth // 8)
        palette = [tga_pixel(data, at + index * entry_bytes, colour_map_depth)
                   for index in range(colour_map_length)]
        at += colour_map_length * entry_bytes

    pixel_bytes = max(1, depth // 8)
    pixels = []
    while len(pixels) < width * height:
        run, repeat = 1, False
        if image_type >= 9:
            packet = data[at]
            at += 1
            run, repeat = (packet & 0x7F) + 1, bool(packet & 0x80)

        if repeat:
            pixels += [tga_sample(data, at, depth, kind, palette)] * run
            at += pixel_bytes
            continue
        for _ in range(run):
            pixels.append(tga_sample(data, at, depth, kind, palette))
            at += pixel_bytes
    pixels = pixels[:width * height]

    image = Image.new("RGBA", (width, height))
    target = pixels_of(image)
    for y in range(height):
        row = y if top_down else height - 1 - y
        for x in range(width):
            target[width - 1 - x if right_to_left else x, row] = pixels[y * width + x]
    return image


def tga_sample(data, at, depth, kind, palette):
    if kind == 1:
        index = int.from_bytes(data[at:at + 2], "little") if depth == 16 else data[at]
        return palette[index] if index < len(palette) else (0, 0, 0, 0)
    if kind == 3:
        return (data[at], data[at], data[at], 255)
    return tga_pixel(data, at, depth)


def tga_pixel(data, at, depth):
    """Targa stores colour as blue, green, red, so the channels come back in the other order."""
    if depth == 32:
        return (data[at + 2], data[at + 1], data[at], data[at + 3])
    if depth == 24:
        return (data[at + 2], data[at + 1], data[at], 255)
    if depth == 16:
        packed = int.from_bytes(data[at:at + 2], "little")
        return ((packed >> 10 & 0x1F) * 255 // 31, (packed >> 5 & 0x1F) * 255 // 31,
                (packed & 0x1F) * 255 // 31, 255 if packed & 0x8000 else 0)
    return (0, 0, 0, 0)


def box_rectangles(uv, size):
    width, height, depth = size
    u, v = uv
    return {
        ("y", 0): (u + depth, v, u + depth + width, v + depth, 0),
        ("y", 1): (u + depth + width, v + depth, u + depth + width * 2, v, 0),
        ("x", 0): (u, v + depth, u + depth, v + depth + height, 0),
        ("z", 0): (u + depth, v + depth, u + depth + width, v + depth + height, 0),
        ("x", 1): (u + depth + width, v + depth, u + depth * 2 + width, v + depth + height, 0),
        ("z", 1): (u + depth * 2 + width, v + depth, u + depth * 2 + width * 2, v + depth + height, 0),
    }


def face_coordinates(rectangle, texture_size):
    """The four UV corners of a face, normalised against the geometry's declared texture size.

    The winding puts the texture's top right on the first vertex, as vanilla does. A face may
    declare its texture turned in ninety degree steps, which walks that order round.
    """
    texture_width, texture_height = texture_size
    u_min, v_min = rectangle[0] / texture_width, rectangle[1] / texture_height
    u_max, v_max = rectangle[2] / texture_width, rectangle[3] / texture_height

    coordinates = [(u_max, v_min), (u_min, v_min), (u_min, v_max), (u_max, v_max)]
    for _ in range(int(rectangle[4]) // 90 % 4):
        coordinates = coordinates[1:] + coordinates[:1]
    return coordinates


def bake_cube(cube, bone_pivot, bone, texture_size):
    """Returns the quads of one cube, as (corner positions, corner UVs, normal).

    A cube's own `inflate` and `mirror` replace the bone's rather than adding to them.
    """
    origin = cube["origin"]
    size = cube["size"]
    inflate = cube.get("inflate", bone.get("inflate", 0))

    min_x, max_x = origin[0] - inflate, origin[0] + size[0] + inflate
    min_z, max_z = origin[2] - inflate, origin[2] + size[2] + inflate
    min_y = MODEL_ORIGIN_HEIGHT - (origin[1] + size[1] + inflate)
    max_y = MODEL_ORIGIN_HEIGHT - (origin[1] - inflate)

    mirror = cube.get("mirror", bone.get("mirror", False))
    if mirror:
        min_x, max_x = max_x, min_x

    corners = [
        (max_x if index & 1 else min_x, max_y if index & 2 else min_y, max_z if index & 4 else min_z)
        for index in range(8)
    ]

    cube_angles = None
    if any(cube.get("rotation", [0, 0, 0])):
        pivot = to_model_space(cube.get("pivot", [0, 0, 0]))
        cube_angles = to_model_rotation(cube["rotation"])
        corners = [
            tuple(a + b for a, b in zip(rotate([c - p for c, p in zip(corner, pivot)], cube_angles), pivot))
            for corner in corners
        ]
    corners = [tuple(c - p for c, p in zip(corner, bone_pivot)) for corner in corners]

    uv = cube["uv"]
    if isinstance(uv, list):
        rectangles = box_rectangles(uv, size)
    else:
        rectangles = {}
        for name, face in uv.items():
            if name not in FACES:
                continue
            u, v = face["uv"]
            # A face that declares no patch size maps the box as seen from that face.
            w, h = face.get("uv_size", FACE_SIZES[FACES[name][0]](size))
            if w == 0 or h == 0:
                # Bedrock does not draw a face whose uv rectangle has no area.
                continue
            rectangles[FACES[name]] = (u, v, u + w, v + h, face.get("uv_rotation", 0))

    quads = []
    for face, rectangle in rectangles.items():
        points = [corners[index] for index in CORNERS[face]]
        coordinates = face_coordinates(rectangle, texture_size)
        normal = NORMALS[face]
        if mirror:
            # The vertices carry their own UV, so reversing the winding takes the UVs with it.
            points, coordinates = points[::-1], coordinates[::-1]
            if face[0] == "x":
                normal = tuple(-component for component in normal)
        if cube_angles is not None:
            normal = rotate(normal, cube_angles)
        quads.append((points, coordinates, normal))
    return quads


def bake(geometry):
    """Walks the bone tree and returns quads with absolute positions.

    A child bone sits inside its parent's frame, so a parent's rotation turns everything under it.
    """
    texture_size = (geometry["texture_width"], geometry["texture_height"])
    children = {}
    roots = []
    for bone in geometry["bones"]:
        if bone.get("parent") is None:
            roots.append(bone)
        else:
            children.setdefault(bone["parent"], []).append(bone)

    quads = []

    def walk(bone, place, turn, parent_pivot):
        pivot = to_model_space(bone.get("pivot", [0, 0, 0]))
        # The 1.8 shape writes the resting rotation under either name, never a different one
        # under each, so the newer spelling stands in for both wherever it is present.
        angles = to_model_rotation(
            bone.get("rotation", bone.get("bind_pose_rotation", [0, 0, 0])))
        offset = tuple(a - b for a, b in zip(pivot, parent_pivot))

        def place_here(point):
            return place(tuple(a + b for a, b in zip(offset, rotate(point, angles))))

        def turn_here(vector):
            return turn(rotate(vector, angles))

        # A bone marked neverRender still places its children; only its own boxes are left out.
        for cube in (() if bone.get("neverRender") else bone.get("cubes", [])):
            for points, coordinates, normal in bake_cube(cube, pivot, bone, texture_size):
                quads.append(([place_here(point) for point in points], coordinates, turn_here(normal)))

        for child in children.get(bone["name"], []):
            walk(child, place_here, turn_here, pivot)

    for root in roots:
        walk(root, lambda point: point, lambda vector: vector, (0.0, 0.0, 0.0))
    return quads


def draw(quads, texture, size, view, scale, uv_offset=(0.0, 0.0)):
    """Paints the quads with a painter's algorithm, back to front, in an orthographic view."""
    image = Image.new("RGBA", (size, size), (32, 34, 38, 255))
    pixels = pixels_of(image)
    sampler = pixels_of(texture)
    tex_w, tex_h = texture.size

    def project(point):
        x, y, z = point
        # Java model space has Y running downward, so the screen Y is used as it is.
        horizontal = -x if view == "front" else z
        return size / 2 + horizontal * scale, size * 0.82 + (y - MODEL_ORIGIN_HEIGHT) * scale

    # Painter's algorithm: the farthest quad is drawn first so nearer ones cover it.
    depth_of = {"front": lambda p: p[2], "side": lambda p: -p[0]}[view]
    ordered = sorted(quads, key=lambda quad: max(depth_of(point) for point in quad[0]), reverse=True)

    for points, coordinates, normal in ordered:
        screen = [project(point) for point in points]

        min_x = max(0, int(min(point[0] for point in screen)))
        max_x = min(size - 1, int(max(point[0] for point in screen)) + 1)
        min_y = max(0, int(min(point[1] for point in screen)))
        max_y = min(size - 1, int(max(point[1] for point in screen)) + 1)
        if max_x <= min_x or max_y <= min_y:
            continue

        shade = 0.55 + 0.45 * abs(normal[2] if view == "front" else normal[0])
        for py in range(min_y, max_y + 1):
            for px in range(min_x, max_x + 1):
                weights = bilinear(screen, px + 0.5, py + 0.5)
                if weights is None:
                    continue
                u, v = sample_uv(coordinates, weights)
                colour = sampler[int((u + uv_offset[0]) * tex_w) % tex_w,
                                 int((v + uv_offset[1]) * tex_h) % tex_h]
                if len(colour) == 4 and colour[3] < 16:
                    continue
                pixels[px, py] = (
                    int(colour[0] * shade), int(colour[1] * shade), int(colour[2] * shade), 255)
    return image


def sample_uv(coordinates, weights):
    """The UV at a point inside a quad, blended across all four corners so a turned face follows."""
    across, down = weights
    top = [a + (b - a) * across for a, b in zip(coordinates[1], coordinates[0])]
    bottom = [a + (b - a) * across for a, b in zip(coordinates[2], coordinates[3])]
    return [a + (b - a) * down for a, b in zip(top, bottom)]


def bilinear(screen, px, py):
    """Where a pixel sits inside a quad, as two fractions, or None if it is outside."""
    for triangle, (a, b, c) in ((0, (0, 1, 2)), (1, (0, 2, 3))):
        ax, ay = screen[a]
        bx, by = screen[b]
        cx, cy = screen[c]
        area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
        if abs(area) < 1e-9:
            continue
        w1 = ((px - ax) * (cy - ay) - (cx - ax) * (py - ay)) / area
        w2 = ((bx - ax) * (py - ay) - (px - ax) * (by - ay)) / area
        if w1 < -0.001 or w2 < -0.001 or w1 + w2 > 1.001:
            continue
        if triangle == 0:
            return min(max(w1, 0), 1), min(max(w2, 0), 1)
        return min(max(1 - w2, 0), 1), min(max(w1 + w2, 0), 1)
    return None


def main():
    parser = argparse.ArgumentParser(description=USAGE)
    parser.add_argument("--assets", required=True, help="the resource pack root, the folder holding models/")
    parser.add_argument("--geometry", required=True, help="the geometry identifier, or `list` to name them all")
    parser.add_argument("--texture", required=True,
                        help="a texture path inside the pack, without its extension")
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", type=int, default=320)
    parser.add_argument("--scale", type=float, default=7.0)
    parser.add_argument(
        "--uv-offset", nargs=2, type=float, default=[0.0, 0.0], metavar=("U", "V"),
        help="slides the texture under the model, in whole texture widths (Bedrock uv_anim)")
    arguments = parser.parse_args()

    pack = pathlib.Path(arguments.assets)
    geometries = load_geometries(pack)
    if not geometries:
        print(f"no geometry under {pack / 'models'}", file=sys.stderr)
        return 1
    if arguments.geometry not in geometries:
        print(f"no such geometry: {arguments.geometry}", file=sys.stderr)
        print("available:", ", ".join(sorted(geometries)), file=sys.stderr)
        return 1

    geometry = geometries[arguments.geometry]
    quads = bake(geometry)
    texture = load_texture(pack, arguments.texture)

    views = [
        draw(quads, texture, arguments.size, view, arguments.scale, tuple(arguments.uv_offset))
        for view in ("front", "side")
    ]
    combined = Image.new("RGBA", (arguments.size * 2, arguments.size))
    combined.paste(views[0], (0, 0))
    combined.paste(views[1], (arguments.size, 0))
    combined.save(arguments.out)
    print(f"{arguments.out}  quads={len(quads)}  texture={texture.size}"
          f"  declared={int(geometry['texture_width'])}x{int(geometry['texture_height'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
