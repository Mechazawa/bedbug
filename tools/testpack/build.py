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
    a block and an item declaring what only a mixin answers, each with one form that is reported
    a recipe of each of the seven kinds that convert, and the tags deciding what each becomes
    a named voxel shape, and the block that culls its faces against it
    a loot table and a trade table, each using every function and condition that converts
    two entities, one spawn egg naming a texture and one giving two colours
    an animation firing a sound and a particle off its timeline
    three particle definitions in the 1.10.0 format, one of them carrying a curve and events
    a fog whose air and water blocks are written differently from each other
    a client biome naming a sound of the pack's, two of the engine's, a track and a density
    a texture set whose emissive channel has pixels in it, for a block and for an entity
    an entity whose render controller picks everything it draws by Molang, and the clips and
    controllers that time, weigh and cross-fade its bones
    two subpacks of the resource pack, each repainting the sprite, so the chosen one is readable
    the pack settings a manifest declares, at the values the Molang queries read back
    an entity texture with a band written the way Bedrock marks a cut-out texel
    a bone built from nothing but a texture mesh over a texture drawing an L
    a page of the pack's own glyphs, and the locale list naming the languages it ships
    a function using the commands and selector arguments that need translating
    a structure naming a block of the pack's own, one Bedrock spells differently and air

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

from mcstructure import structure

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

# The alpha Bedrock marks a drawn but not fully opaque texel with, which Java's cutout shader
# discards and a conversion therefore has to raise.
MARKED = 3


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


def record_icon(x: int, y: int) -> tuple[int, int, int, int]:
    if not disc(x, y, 7.5, 6.5):
        return TRANSPARENT
    return (198, 132, 74, 255) if disc(x, y, 7.5, 2.0) else (36, 32, 40, 255)


def pouch(x: int, y: int) -> tuple[int, int, int, int]:
    if y < 4:
        return (168, 128, 84, 255) if 5 <= x <= 10 else TRANSPARENT
    if not 2 <= x <= 13:
        return TRANSPARENT
    return (206, 164, 108, 255) if y < 6 else (150, 110, 70, 255)


def visor(x: int, y: int) -> tuple[int, int, int, int]:
    if not 2 <= x <= 13 or not 4 <= y <= 11:
        return TRANSPARENT
    return (120, 190, 220, 255) if 6 <= y <= 9 else (96, 104, 116, 255)


def berry(x: int, y: int) -> tuple[int, int, int, int]:
    if disc(x, y, 7.5, 1.5) and y < 6:
        return (70, 140, 60, 255)
    return (180, 30, 60, 255) if disc(x, y, 7.5, 5.0) and y >= 4 else TRANSPARENT


def pebble(x: int, y: int) -> tuple[int, int, int, int]:
    if ((x - 7.5) / 5.5) ** 2 + ((y - 8.5) / 4.0) ** 2 > 1.0:
        return TRANSPARENT
    return (150, 146, 140, 255) if (x + 2 * y) % 7 else (112, 108, 104, 255)


def sprite_egg(x: int, y: int) -> tuple[int, int, int, int]:
    if ((x - 7.5) / 4.5) ** 2 + ((y - 8.0) / 6.0) ** 2 > 1.0:
        return TRANSPARENT
    return (58, 157, 35, 255) if (x + y) % 5 else (201, 226, 101, 255)


def ember_egg(x: int, y: int) -> tuple[int, int, int, int]:
    """The other half of the egg list, so a wrong `texture_index` shows a different egg."""
    if ((x - 7.5) / 4.5) ** 2 + ((y - 8.0) / 6.0) ** 2 > 1.0:
        return TRANSPARENT
    return (217, 79, 30, 255) if (x + y) % 5 else (255, 208, 96, 255)


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


# The body colour each form of the sprite is painted in, which is what says which of the three the
# renderer read: the pack's own, the low subpack's, and the high one's.
SPRITE_BODY = (38, 96, 58, 255)
PLAIN_SPRITE_BODY = (38, 58, 128, 255)
BRIGHT_SPRITE_BODY = (235, 150, 32, 255)


def sprite_skin(body: tuple[int, int, int, int]):
    """The sprite's skin, with the top four rows written the way Bedrock marks a cut-out texel.

    Bedrock's alpha test draws every texel carrying any alpha and drops only the fully transparent
    ones, so Mojang's own entity textures mark whole regions with an alpha of three: the face and
    the bare skin of the vanilla sheep are written that way. Java's cutout shader drops everything
    under a tenth, so the band is what a conversion has to raise for the model to keep it.

    Each of the pack's three forms paints the body in a colour of its own and leaves the marked band
    and the empty corner where they are, so the subpack that won is readable off one texel and the
    cut-out assertion holds whichever form was loaded.
    """
    def paint(x: int, y: int) -> tuple[int, int, int, int]:
        if x >= 32 or y >= 32:
            return TRANSPARENT
        if y < 4:
            return (214, 96, 120, MARKED)
        if disc(x % 16, y % 16, 7.5, 6.0):
            return (96, 214, 120, 255)
        return body

    return paint


def ember_skin(x: int, y: int) -> tuple[int, int, int, int]:
    if x >= 32 or y >= 32:
        return TRANSPARENT
    warmth = 255 - min(255, (x + y) * 5)
    return (255, warmth, 48, 255)


def prism_skin(body: tuple[int, int, int, int]):
    """The prism's skin: two copies of one layout, the lower one darker.

    Its render controller halves the texture and slides it down by half a sheet per variant, so
    each variant draws from a half of its own. The mark's patch is painted white beside the body.
    """
    def paint(x: int, y: int) -> tuple[int, int, int, int]:
        if x >= 40:
            return TRANSPARENT
        if x >= 32:
            return (250, 250, 250, 255)
        return body if y < 32 else tuple(channel // 2 for channel in body[:3]) + (255,)

    return paint


# The tailor's base is one colour all over, and its outfit is four bands of sixteen columns each.
TAILOR_BASE = (40, 70, 200, 255)
TAILOR_OUTFIT = (200, 40, 40, 255)


def tailor_base(x: int, y: int) -> tuple[int, int, int, int]:
    return TAILOR_BASE


def tailor_outfit(x: int, y: int) -> tuple[int, int, int, int]:
    """The outfit a masked multitexture material draws over the base.

    Bedrock keeps the base wherever the outfit holds a colour that is not fully opaque, so the four
    bands are drawn as the outfit's red, the base under a clear white, nothing under a clear black,
    and the base under a faint green.
    """
    return (TAILOR_OUTFIT, (255, 255, 255, 0), TRANSPARENT, (60, 200, 60, 64))[x // 16]


def prism_mer(x: int, y: int) -> tuple[int, int, int, int]:
    """Metalness, emissive and roughness: only the mark's patch of the sheet glows."""
    if 32 <= x < 40 and y < 8:
        return (0, 235, 190, 255)
    return (0, 0, 220, 255)


# The texels the vane's texture draws: an L standing in column 1 from row 1 to row 5 and running along
# row 5 to column 3, on an otherwise clear eight by eight field. The vane's texture mesh is built
# from exactly these, so `TextureMeshClientGameTest` names the same seven.
VANE_TEXELS = {(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (2, 5), (3, 5)}


def vane(x: int, y: int) -> tuple[int, int, int, int]:
    """The L, each texel coloured by where it is, so a face sampling the wrong texel shows."""
    return (40 * x, 40 * y, 200, 255) if (x, y) in VANE_TEXELS else TRANSPARENT


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


def glyph_page(x: int, y: int) -> tuple[int, int, int, int]:
    """A 128x128 page of 8x8 glyph cells, which is the grid Bedrock paints its own glyphs on.

    Each cell holds a block as wide as its column and as tall as its row, so a glyph taken out of
    the wrong cell is a different shape rather than the same shape in another place. Every cell has
    at least the one pixel, because a glyph with no pixels at all has no width to advance by.
    """
    if x % 8 > x // 8 or y % 8 > y // 8:
        return TRANSPARENT
    return (255, 255, 255, 255)


TEXTURES = (
    ("font/glyph_E1.png", 128, glyph_page),
    ("textures/items/wand.png", 16, wand),
    ("textures/items/shard.png", 16, shard),
    ("textures/items/dust.png", 16, dust),
    ("textures/items/pouch.png", 16, pouch),
    ("textures/items/visor.png", 16, visor),
    ("textures/items/record.png", 16, record_icon),
    ("textures/items/berry.png", 16, berry),
    ("textures/items/pebble.png", 16, pebble),
    ("textures/items/sprite_egg.png", 16, sprite_egg),
    ("textures/items/ember_egg.png", 16, ember_egg),
    ("textures/blocks/lamp_off.png", 16, lamp_off),
    ("textures/blocks/lamp_on.png", 16, lamp_on),
    ("textures/blocks/lamp_on_mer.png", 16, lamp_on_mer),
    ("textures/blocks/lamp_on_normal.png", 16, lamp_on_normal),
    ("textures/entity/sprite.png", 64, sprite_skin(SPRITE_BODY)),
    ("subpacks/plain/textures/entity/sprite.png", 64, sprite_skin(PLAIN_SPRITE_BODY)),
    ("subpacks/bright/textures/entity/sprite.png", 64, sprite_skin(BRIGHT_SPRITE_BODY)),
    ("textures/entity/ember.png", 64, ember_skin),
    ("textures/entity/prism.png", 64, prism_skin((70, 120, 200, 255))),
    ("textures/entity/prism_dyed.png", 64, prism_skin((200, 70, 120, 255))),
    ("textures/entity/prism_mer.png", 64, prism_mer),
    ("textures/entity/vane.png", 8, vane),
    ("textures/entity/tailor_base.png", 64, tailor_base),
    ("textures/entity/tailor_outfit.png", 64, tailor_outfit),
    ("textures/particle/particles.png", 128, particle_atlas),
)


# `structure load tp:hut` places this: a stone floor, the pack's own lamp in the middle of the layer
# above it with a jack o'lantern written by its Bedrock name in each corner, and air on top. The rest
# of the middle layer is left as the world has it.
HUT_PATH = "structures/tp/hut.mcstructure"
HUT_PALETTE = [("minecraft:air", {}), ("minecraft:stone", {}), ("tp:lamp", {}),
               ("minecraft:lit_pumpkin", {})]


def hut_cell(x: int, y: int, z: int) -> int:
    if y == 0:
        return 1
    if y == 2:
        return 0
    if x == 1 and z == 1:
        return 2
    return 3 if x != 1 and z != 1 else -1


def pack_files(directory: Path) -> dict[str, bytes]:
    """Everything one pack ships, keyed by its path inside that pack."""
    if not directory.is_dir():
        raise SystemExit(f"missing pack sources: {directory}")
    return {str(file.relative_to(directory).as_posix()): file.read_bytes()
            for file in sorted(directory.rglob("*")) if file.is_file()}


def addon_contents() -> dict[str, bytes]:
    """Both packs, each under the name it takes inside the archive, with the textures painted in
    and the structure written."""
    packed = {f"{inside}/{name}": body
              for source, inside in PACK_DIRECTORIES
              for name, body in pack_files(SOURCE / source).items()}
    painted = {f"{PACK_DIRECTORIES[1][1]}/{name}": png(size, size, paint)
               for name, size, paint in TEXTURES}
    hut = {f"{PACK_DIRECTORIES[0][1]}/{HUT_PATH}": structure((3, 3, 3), HUT_PALETTE, hut_cell)}
    return packed | painted | hut


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
