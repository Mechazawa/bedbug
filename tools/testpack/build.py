#!/usr/bin/env python3
"""Builds the synthetic add-on the harness checks the mod against.

Every file it ships is written here: the JSON, the `.lang` files and the `.mcfunction` live under
`pack/`, and the textures are painted by this script rather than stored, so nothing in the
repository comes from anybody's pack. The one binary in `pack/` is a sine tone.

The pack is deliberately wide instead of deep. It carries one of each thing the mod has a reader
for, in the spelling that reader accepts, so a run that registers all of them says every reader
still works:

    an item icon in each of the three shapes a pack may write it in
    a block with states and permutations, drawn with its own geometry
    a shaped and a shapeless recipe
    two entities, one spawn egg naming a texture and one giving two colours
    an animation firing a sound and a particle off its timeline
    three particle definitions in the 1.10.0 format, one of them carrying a curve and events
    a texture set whose emissive channel has pixels in it
    a function using the commands and selector arguments that need translating

    python3 tools/testpack/build.py                  # build/testpack/test-addon.mcaddon
    python3 tools/testpack/build.py --version 26.2   # and into that version's addons/
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = HERE / "pack"

# The source directory each pack is written from, and the name it takes inside the archive. The
# space and the hyphen are deliberate: a Java resource location accepts neither, so a pack directory
# named the way Bedrock authors name one puts the sanitiser to work.
PACK_DIRECTORIES = (("behaviour", "Test Add-On BP"), ("resources", "Test Add-On RP"))

# A fixed timestamp, so two builds of the same sources produce the same bytes.
ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)

TRANSPARENT = (0, 0, 0, 0)


def png(width: int, height: int, paint) -> bytes:
    """Encodes an 8-bit RGBA image, one call of `paint(x, y)` per pixel."""
    raw = b"".join(
        b"\x00" + bytes(channel for x in range(width) for channel in paint(x, y))
        for y in range(height))

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def ring(x: int, y: int, size: int, thickness: int = 1) -> bool:
    """True on the border band of a square of this size."""
    edge = min(x, y, size - 1 - x, size - 1 - y)
    return edge < thickness


def disc(x: int, y: int, centre: float, radius: float) -> bool:
    return (x - centre) ** 2 + (y - centre) ** 2 <= radius ** 2


def wand(x: int, y: int) -> tuple[int, int, int, int]:
    if abs((15 - y) - x) <= 1 and y > 3:
        return (122, 86, 54, 255)
    if disc(x, y, 3.5, 2.5) and y < 6:
        return (255, 236, 140, 255)
    return TRANSPARENT


def shard(x: int, y: int) -> tuple[int, int, int, int]:
    distance = abs(x - 7.5) + abs(y - 7.5)
    if distance > 6.5:
        return TRANSPARENT
    return (110, 205, 230, 255) if distance > 4.5 else (196, 240, 250, 255)


def dust(x: int, y: int) -> tuple[int, int, int, int]:
    # A fixed scatter rather than a random one, so the file does not change between builds.
    return (214, 168, 255, 255) if (x * 7 + y * 3) % 11 < 3 else TRANSPARENT


def pouch(x: int, y: int) -> tuple[int, int, int, int]:
    if y < 4:
        return (168, 128, 84, 255) if 5 <= x <= 10 else TRANSPARENT
    if not 2 <= x <= 13:
        return TRANSPARENT
    return (206, 164, 108, 255) if y < 6 else (150, 110, 70, 255)


def sprite_egg(x: int, y: int) -> tuple[int, int, int, int]:
    if ((x - 7.5) / 4.5) ** 2 + ((y - 8.0) / 6.0) ** 2 > 1.0:
        return TRANSPARENT
    return (58, 157, 35, 255) if (x + y) % 5 else (201, 226, 101, 255)


def lamp_frame(x: int, y: int) -> tuple[int, int, int, int] | None:
    """The part of the lamp that looks the same lit or unlit."""
    return (74, 68, 60, 255) if ring(x, y, 16, 3) else None


def lamp_off(x: int, y: int) -> tuple[int, int, int, int]:
    return lamp_frame(x, y) or (46, 44, 52, 255)


def lamp_on(x: int, y: int) -> tuple[int, int, int, int]:
    return lamp_frame(x, y) or (255, 214, 96, 255)


def lamp_on_mer(x: int, y: int) -> tuple[int, int, int, int]:
    """Metalness, emissive and roughness, in that order: only the glowing centre emits."""
    if ring(x, y, 16, 3):
        return (168, 0, 60, 255)
    return (0, 235, 190, 255)


def lamp_on_normal(x: int, y: int) -> tuple[int, int, int, int]:
    """Flat except for a bevel where the frame meets the centre."""
    edge = min(x, y, 15 - x, 15 - y)
    if edge == 3:
        return (128, 178, 235, 255)
    return (128, 128, 255, 255)


def sprite_skin(x: int, y: int) -> tuple[int, int, int, int]:
    if x >= 32 or y >= 32:
        return TRANSPARENT
    if disc(x % 16, y % 16, 7.5, 6.0):
        return (96, 214, 120, 255)
    return (38, 96, 58, 255)


def ember_skin(x: int, y: int) -> tuple[int, int, int, int]:
    if x >= 32 or y >= 32:
        return TRANSPARENT
    warmth = 255 - min(255, (x + y) * 5)
    return (255, warmth, 48, 255)


def particle_atlas(x: int, y: int) -> tuple[int, int, int, int]:
    """A 128x128 sheet of 8x8 cells, the layout the billboard components address by texel.

    The top row holds the four flipbook frames, each a disc one pixel smaller than the last, so a
    frame that fails to advance is visible.
    """
    cell_x, cell_y = x // 8, y // 8
    inside_x, inside_y = x % 8, y % 8
    if cell_y == 0 and cell_x < 4:
        return (255, 212, 128, 255) if disc(inside_x, inside_y, 3.5, 3.5 - cell_x) else TRANSPARENT
    if cell_y == 1 and cell_x == 0:
        return (188, 226, 255, 255) if disc(inside_x, inside_y, 3.5, 3.0) else TRANSPARENT
    if cell_y == 1 and cell_x == 1:
        return (255, 255, 255, 255) if inside_x in (3, 4) or inside_y in (3, 4) else TRANSPARENT
    return TRANSPARENT


TEXTURES = (
    ("textures/items/wand.png", 16, wand),
    ("textures/items/shard.png", 16, shard),
    ("textures/items/dust.png", 16, dust),
    ("textures/items/pouch.png", 16, pouch),
    ("textures/items/sprite_egg.png", 16, sprite_egg),
    ("textures/blocks/lamp_off.png", 16, lamp_off),
    ("textures/blocks/lamp_on.png", 16, lamp_on),
    ("textures/blocks/lamp_on_mer.png", 16, lamp_on_mer),
    ("textures/blocks/lamp_on_normal.png", 16, lamp_on_normal),
    ("textures/entity/sprite.png", 64, sprite_skin),
    ("textures/entity/ember.png", 64, ember_skin),
    ("textures/particle/particles.png", 128, particle_atlas),
)


def pack_files(directory: Path) -> dict[str, bytes]:
    """Everything one pack ships, keyed by its path inside that pack."""
    if not directory.is_dir():
        raise SystemExit(f"missing pack sources: {directory}")
    return {str(file.relative_to(directory).as_posix()): file.read_bytes()
            for file in sorted(directory.rglob("*")) if file.is_file()}


def addon_contents() -> dict[str, bytes]:
    """Both packs, each under the name it takes inside the archive, with the textures painted in."""
    packed = {f"{inside}/{name}": body
              for source, inside in PACK_DIRECTORIES
              for name, body in pack_files(SOURCE / source).items()}
    return packed | {f"{PACK_DIRECTORIES[1][1]}/{name}": png(size, size, paint)
                     for name, size, paint in TEXTURES}


def write_addon(contents: dict[str, bytes], destination: Path) -> None:
    """Writes one archive. The timestamp and the mode are fixed, so equal contents give equal bytes."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(contents):
            entry = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, contents[name])


def main(description: str, default_out: Path, contents: dict[str, bytes]) -> int:
    """The command line every builder here shares: write the archive, then place the copies asked for."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--out", type=Path, default=default_out,
                        help="where to write the archive")
    parser.add_argument("--version", action="append", default=[],
                        help="also copy it into versions/<version>/run/addons; repeatable")
    arguments = parser.parse_args()

    written = arguments.out
    write_addon(contents, written)
    print(f"{written} ({len(contents)} files, {written.stat().st_size} bytes)")

    for version in arguments.version:
        if not (ROOT / "versions" / version).is_dir():
            raise SystemExit(f"no such version: {version}")
        addons = ROOT / "versions" / version / "run" / "addons"
        addons.mkdir(parents=True, exist_ok=True)
        target = addons / written.name
        target.write_bytes(written.read_bytes())
        print(f"{target}")

    return 0


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "test-addon.mcaddon",
                  addon_contents()))
