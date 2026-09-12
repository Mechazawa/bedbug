#!/usr/bin/env python3
"""Builds the structures the in-game tests run inside.

A game test runs against a saved scene, and the framework reads one as SNBT: the size, a list of
positions with the block at each, and the palette those blocks come from. Writing them here rather
than saving them from a running game keeps them readable, reviewable and reproducible, and means no
binary blob has to live in the repository.

The path the framework reads them from changed spelling: 1.20.1 looks under `structures`, everything
after it under `structure`. Both are written, because the game only reads the one it looks for.
"""

import argparse
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMESPACE = "mcaddon"

# The data version the oldest supported game reads. A newer game upgrades a structure it reads,
# so the lowest of the four is the one that works everywhere.
DATA_VERSION = 3465

AIR = "minecraft:air"
FLOOR = "minecraft:stone"


def platform(size: int = 8) -> dict:
    """Stone underfoot and air above, so a test can put something down and have it stay."""
    blocks = {}
    for x in range(size):
        for z in range(size):
            blocks[(x, 0, z)] = FLOOR
    return {"size": (size, size, size), "blocks": blocks}


def chest_room(size: int = 8) -> dict:
    """A platform with a chest already on it, for the container a test does not have to place."""
    scene = platform(size)
    scene["blocks"][(1, 1, 1)] = "minecraft:chest"
    return scene


def pen(size: int = 8) -> dict:
    """A platform walled on all four sides, so an entity put inside it stays inside it."""
    scene = platform(size)
    for step in range(size):
        for height in (1, 2):
            scene["blocks"][(step, height, 0)] = FLOOR
            scene["blocks"][(step, height, size - 1)] = FLOOR
            scene["blocks"][(0, height, step)] = FLOOR
            scene["blocks"][(size - 1, height, step)] = FLOOR
    return scene


def snbt(scene: dict) -> str:
    """One scene as the text the framework reads, with every position named even when it is air."""
    width, height, depth = scene["size"]
    palette = sorted({AIR, *scene["blocks"].values()})

    entries = []
    for x in range(width):
        for y in range(height):
            for z in range(depth):
                state = scene["blocks"].get((x, y, z), AIR)
                entries.append(f'        {{pos: [{x}, {y}, {z}], state: "{state}"}}')

    listed = ",\n".join(entries)
    named = ",\n".join(f'        "{block}"' for block in palette)
    return (
        "{\n"
        f"    DataVersion: {DATA_VERSION},\n"
        f"    size: [{width}, {height}, {depth}],\n"
        "    data: [\n"
        f"{listed}\n"
        "    ],\n"
        "    entities: [],\n"
        "    palette: [\n"
        f"{named}\n"
        "    ]\n"
        "}\n"
    )


SCENES = {"platform": platform(), "chest_room": chest_room(), "pen": pen()}


def main() -> int:
    parser = argparse.ArgumentParser(description="Builds the game test structures.")
    parser.add_argument("--out", default=str(ROOT / "src" / "main" / "resources"),
                        help="the resource root to write under")
    arguments = parser.parse_args()

    written = []
    for spelling in ("structure", "structures"):
        directory = pathlib.Path(arguments.out) / "data" / NAMESPACE / "gametest" / spelling
        directory.mkdir(parents=True, exist_ok=True)
        for name, scene in SCENES.items():
            target = directory / f"{name}.snbt"
            target.write_text(snbt(scene), encoding="utf-8")
            written.append(target)

    for target in written:
        print(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
