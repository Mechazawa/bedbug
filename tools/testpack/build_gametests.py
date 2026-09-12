#!/usr/bin/env python3
"""Builds the add-on that proves a pack's own `@minecraft/server-gametest` tests run.

The pack registers two tests, one that passes and one that fails, and both name the structure the
pack ships. `/gametest run probe:floor_is_stone` has to report a pass and
`/gametest run probe:diamond_is_missing` has to report the failure with its message.

Everything it ships is written here: a manifest and one script under `gametests/`, and the
`.mcstructure` is written by this script rather than exported from the game, so no file in this
repository comes from anybody's pack.

    python3 tools/testpack/build_gametests.py                  # build/testpack/gametest-probe.mcaddon
    python3 tools/testpack/build_gametests.py --version 26.2   # and into that version's addons/
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

from build import ROOT, ZIP_TIMESTAMP, pack_files

SOURCE = Path(__file__).resolve().parent / "gametests"

# The name the pack takes inside the archive, spelled the way a Bedrock author names one.
INSIDE = "GameTest Probe BP"

# The structure both tests build: a stone floor with air above it, in the palette order below.
STRUCTURE_PATH = "structures/probe/floor.mcstructure"
STRUCTURE_SIZE = (3, 3, 3)
STRUCTURE_PALETTE = ("minecraft:air", "minecraft:stone")

# The game version Bedrock stamps every palette entry with. Nothing reads it; a real file has one.
BLOCK_VERSION = 18168865


# The tag types this writes, by the number NBT gives each of them.
INT, STRING, LIST, COMPOUND = 3, 8, 9, 10


def named(tag_type: int, name: str, payload: bytes) -> bytes:
    """A tag inside a compound, which carries its type and its name before its payload."""
    encoded = name.encode("utf-8")
    return bytes([tag_type]) + struct.pack("<H", len(encoded)) + encoded + payload


def text(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack("<H", len(encoded)) + encoded


def compound(body: bytes) -> bytes:
    return body + b"\x00"


def listed(element_type: int, payloads: list[bytes]) -> bytes:
    """A list payload. Its elements carry neither a type nor a name, only the values."""
    return struct.pack("<bi", element_type, len(payloads)) + b"".join(payloads)


def ints(values: list[int]) -> bytes:
    return listed(INT, [struct.pack("<i", value) for value in values])


def mcstructure() -> bytes:
    """One `.mcstructure`: little endian NBT, uncompressed, x major then y then z."""
    width, height, depth = STRUCTURE_SIZE
    floor = [1 if y == 0 else 0
             for _ in range(width) for y in range(height) for _ in range(depth)]
    untouched = [-1] * (width * height * depth)

    palette = [compound(named(STRING, "name", text(name))
                        + named(COMPOUND, "states", compound(b""))
                        + named(INT, "version", struct.pack("<i", BLOCK_VERSION)))
               for name in STRUCTURE_PALETTE]

    default = compound(named(LIST, "block_palette", listed(COMPOUND, palette))
                       + named(COMPOUND, "block_position_data", compound(b"")))
    structure = compound(named(LIST, "block_indices", listed(LIST, [ints(floor), ints(untouched)]))
                         + named(LIST, "entities", listed(COMPOUND, []))
                         + named(COMPOUND, "palette", compound(named(COMPOUND, "default", default))))

    return named(COMPOUND, "", compound(
        named(INT, "format_version", struct.pack("<i", 1))
        + named(LIST, "size", ints(list(STRUCTURE_SIZE)))
        + named(COMPOUND, "structure", structure)
        + named(LIST, "structure_world_origin", ints([0, 0, 0]))))


def build(destination: Path) -> tuple[Path, int]:
    if not SOURCE.is_dir():
        raise SystemExit(f"missing pack sources under {SOURCE}")

    contents = {f"{INSIDE}/{name}": body for name, body in pack_files(SOURCE).items()}
    contents[f"{INSIDE}/{STRUCTURE_PATH}"] = mcstructure()

    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(contents):
            entry = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, contents[name])

    return destination, len(contents)


def main() -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--out", type=Path,
                        default=ROOT / "build" / "testpack" / "gametest-probe.mcaddon",
                        help="where to write the archive")
    parser.add_argument("--version", action="append", default=[],
                        help="also copy it into versions/<version>/run/addons; repeatable")
    arguments = parser.parse_args()

    written, count = build(arguments.out)
    print(f"{written} ({count} files, {written.stat().st_size} bytes)")

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
    sys.exit(main())
