#!/usr/bin/env python3
"""Builds the add-on a test server hands to a client that never installed it.

The client game tests share one process and one `addons/` between the server and the client, so a
client lacking one of the server's add-ons cannot be made by installing less. Instead the server is
pointed at a directory of archives it never loaded and hands those out as well, which is what this
writes: one entity, one item and one block with three states, under the namespace `sv`.

Everything it ships is written in this repository, under `served/`, and the two textures are
painted here.

    python3 tools/testpack/build_served.py                 # build/testpack/served-addon.mcaddon
    python3 tools/testpack/build_served.py --out <file>
"""

import sys
from pathlib import Path

from build import ROOT, main, pack_files, png

SOURCE = Path(__file__).resolve().parent / "served"
PACKS = (("behaviour", "Served Add-On BP"), ("resources", "Served Add-On RP"))
TEXTURES = (
    ("textures/entity/drifter.png", lambda x, y: (60, 140, 60, 255)),
    ("textures/items/pellet.png", lambda x, y: (220, 220, 90, 255) if 4 <= x < 12 and 4 <= y < 12
     else (0, 0, 0, 0)),
    ("textures/blocks/crate.png", lambda x, y: (150, 100, 50, 255)),
)


def contents() -> dict[str, bytes]:
    packed = {f"{inside}/{name}": body
              for source, inside in PACKS
              for name, body in pack_files(SOURCE / source).items()}
    painted = {f"{PACKS[1][1]}/{name}": png(16, 16, paint) for name, paint in TEXTURES}
    return packed | painted


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "served-addon.mcaddon",
                  contents()))
