#!/usr/bin/env python3
"""Renders a converted Bedrock model to a PNG, without starting Minecraft.

This mirrors the conversion the mod does at load time: the same axis flip, the same face mapping,
the same UV rectangles. Looking at its output is how a change to that conversion gets checked, so a
mistake shows up here rather than in the game.
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


def bake_cube(cube, bone_pivot, bone_mirror=False):
    """Returns the quads of one cube, as (corner positions, uv rectangle, normal)."""
    origin = cube["origin"]
    size = cube["size"]
    inflate = cube.get("inflate", 0)

    min_x, max_x = origin[0] - inflate, origin[0] + size[0] + inflate
    min_z, max_z = origin[2] - inflate, origin[2] + size[2] + inflate
    min_y = MODEL_ORIGIN_HEIGHT - (origin[1] + size[1] + inflate)
    max_y = MODEL_ORIGIN_HEIGHT - (origin[1] - inflate)

    mirror = cube.get("mirror", False) or bone_mirror
    if mirror:
        min_x, max_x = max_x, min_x

    corners = [
        (max_x if index & 1 else min_x, max_y if index & 2 else min_y, max_z if index & 4 else min_z)
        for index in range(8)
    ]

    if "rotation" in cube:
        pivot = to_model_space(cube.get("pivot", [0, 0, 0]))
        angles = to_model_rotation(cube["rotation"])
        corners = [
            tuple(a + b for a, b in zip(rotate([c - p for c, p in zip(corner, pivot)], angles), pivot))
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
            w, h = face.get("uv_size", [0, 0])
            if w == 0 or h == 0:
                # Bedrock does not draw a face whose uv rectangle has no area.
                continue
            rectangles[FACES[name]] = (u, v, u + w, v + h, face.get("uv_rotation", 0))

    quads = []
    for face, rectangle in rectangles.items():
        indices = CORNERS[face]
        points = [corners[i] for i in indices]
        normal = NORMALS[face]
        if mirror:
            points = points[::-1]
            normal = (-normal[0], normal[1], normal[2])
        quads.append((points, rectangle, normal))
    return quads


def bake(geometry):
    """Walks the bone tree and returns quads with absolute positions."""
    bones = geometry["bones"]
    children = {}
    roots = []
    for bone in bones:
        if bone.get("parent") is None:
            roots.append(bone)
        else:
            children.setdefault(bone["parent"], []).append(bone)

    quads = []

    def walk(bone, parent_transform):
        pivot = to_model_space(bone.get("pivot", [0, 0, 0]))
        angles = to_model_rotation(bone.get("rotation", [0, 0, 0]))
        offset = tuple(p + t for p, t in zip(pivot, parent_transform))

        for cube in bone.get("cubes", []):
            for points, rectangle, normal in bake_cube(cube, pivot, bone.get("mirror", False)):
                placed = [tuple(a + b for a, b in zip(rotate(point, angles), offset)) for point in points]
                quads.append((placed, rectangle, rotate(normal, angles)))

        for child in children.get(bone["name"], []):
            walk(child, tuple(o - p for o, p in zip(offset, pivot)))

    for root in roots:
        walk(root, (0.0, 0.0, 0.0))
    return quads


def draw(quads, texture, size, view, scale, uv_offset=(0.0, 0.0)):
    """Paints the quads with a painter's algorithm, back to front, in an orthographic view."""
    image = Image.new("RGBA", (size, size), (32, 34, 38, 255))
    pixels = image.load()
    tex = texture.load()
    tex_w, tex_h = texture.size

    def project(point):
        x, y, z = point
        # Java model space has Y running downward, so the screen Y is used as it is.
        horizontal = x if view == "front" else z
        if view == "front":
            horizontal = -horizontal
        return size / 2 + horizontal * scale, size * 0.82 + (y - MODEL_ORIGIN_HEIGHT) * scale

    # Painter's algorithm: the farthest quad is drawn first so nearer ones cover it.
    depth_of = {"front": lambda p: p[2], "side": lambda p: -p[0]}[view]
    ordered = sorted(quads, key=lambda quad: max(depth_of(p) for p in quad[0]), reverse=True)

    for points, rectangle, normal in ordered:
        screen = [project(p) for p in points]
        u0, v0, u1, v1, uv_rotation = rectangle
        corners = [(u1, v0), (u0, v0), (u0, v1), (u1, v1)]
        # A per-face rotation turns the texture under the face in ninety degree steps.
        for _ in range((uv_rotation // 90) % 4):
            corners = corners[1:] + corners[:1]

        min_x = max(0, int(min(p[0] for p in screen)))
        max_x = min(size - 1, int(max(p[0] for p in screen)) + 1)
        min_y = max(0, int(min(p[1] for p in screen)))
        max_y = min(size - 1, int(max(p[1] for p in screen)) + 1)
        if max_x <= min_x or max_y <= min_y:
            continue

        shade = 0.55 + 0.45 * abs(normal[2] if view == "front" else normal[0])
        for py in range(min_y, max_y + 1):
            for px in range(min_x, max_x + 1):
                weights = bilinear(screen, px + 0.5, py + 0.5)
                if weights is None:
                    continue
                s, t = weights
                u = corners[1][0] + (corners[0][0] - corners[1][0]) * s
                v = corners[1][1] + (corners[2][1] - corners[1][1]) * t
                u += uv_offset[0] * tex_w
                v += uv_offset[1] * tex_h
                colour = tex[int(u) % tex_w, int(v) % tex_h]
                if len(colour) == 4 and colour[3] < 16:
                    continue
                pixels[px, py] = (
                    int(colour[0] * shade), int(colour[1] * shade), int(colour[2] * shade), 255)
    return image


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
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", required=True)
    parser.add_argument("--geometry", required=True)
    parser.add_argument("--texture", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", type=int, default=320)
    parser.add_argument("--scale", type=float, default=7.0)
    parser.add_argument(
        "--uv-offset", nargs=2, type=float, default=[0.0, 0.0], metavar=("U", "V"),
        help="slides the texture under the model, in whole texture widths (Bedrock uv_anim)")
    arguments = parser.parse_args()

    assets = pathlib.Path(arguments.assets)
    geometries = json.loads((assets / "bedrock/geometry.json").read_text())
    if arguments.geometry not in geometries:
        print(f"no such geometry: {arguments.geometry}", file=sys.stderr)
        print("available:", ", ".join(sorted(geometries)), file=sys.stderr)
        return 1

    quads = bake(geometries[arguments.geometry])
    texture = Image.open(assets / "textures/entity" / f"{arguments.texture}.png").convert("RGBA")

    views = [
        draw(quads, texture, arguments.size, view, arguments.scale, tuple(arguments.uv_offset))
        for view in ("front", "side")
    ]
    combined = Image.new("RGBA", (arguments.size * 2, arguments.size))
    combined.paste(views[0], (0, 0))
    combined.paste(views[1], (arguments.size, 0))
    combined.save(arguments.out)
    print(f"{arguments.out}  quads={len(quads)}  texture={texture.size}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
