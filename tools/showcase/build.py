#!/usr/bin/env python3
"""Builds the showcase add-on, the content the mod's gallery pictures are taken of.

Everything it ships is written in this repository: the JSON, the `.lang` file and the script live
under `pack/`, and every texture is painted by `icons.py` and `skins.py`. It holds two mobs with
their own geometry, animations and render controllers, two blocks, four items, two particles, a
biome with its own fog and colour grade, a camera preset, a script opening a form, and settings in
both manifests.

    python3 tools/showcase/build.py                  # build/showcase/bedbug-showcase.mcaddon
    python3 tools/showcase/build.py --version 26.2   # and into that version's addons/
"""

from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "testpack"))

from build import ROOT, main, pack_files, png  # noqa: E402
from icons import ICONS  # noqa: E402
from skins import SKINS  # noqa: E402

PACK_DIRECTORIES = (("behaviour", "Bedbug Showcase BP"), ("resources", "Bedbug Showcase RP"))


def addon_contents() -> dict[str, bytes]:
    packed = {f"{inside}/{name}": body
              for source, inside in PACK_DIRECTORIES
              for name, body in pack_files(HERE / "pack" / source).items()}
    painted = {f"{PACK_DIRECTORIES[1][1]}/{name}": png(size, size, paint)
               for name, size, paint in ICONS + SKINS}
    return packed | painted


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "showcase" / "bedbug-showcase.mcaddon",
                  addon_contents()))
