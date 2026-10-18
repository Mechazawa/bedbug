#!/usr/bin/env python3
"""Builds the add-on a test server hands to a client that never installed it.

The client game tests share one process and one `addons/` between the server and the client, so a
client lacking one of the server's add-ons cannot be made by installing less. Instead the server is
pointed at a directory of archives it never loaded and hands those out as well, which is what this
writes: one entity, one item and one block with three states, under the namespace `sv`.

Two variants give the tests a second set. `reshaped` is a later release of the same add-on whose
block has a fourth state, which a client that registered the first release has to refuse. `other`
is the same content as a second add-on, under the namespace `sw` and with identities of its own.

Everything it ships is written in this repository, under `served/`, and the textures are painted
here.

    python3 tools/testpack/build_served.py                 # build/testpack/served-addon.mcaddon
    python3 tools/testpack/build_served.py --out <file>
    python3 tools/testpack/build_served.py --variant reshaped --out <file>
"""

import argparse
import re
import sys
import uuid
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
UUID = re.compile(rb"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
HEADER_VERSION = b'"version": [1, 0, 0],\n    "min_engine_version"'


def reshaped(name: str, body: bytes) -> bytes:
    """A later release whose crate has one more `sv:stack` value."""
    if name.endswith("blocks/crate.json"):
        return body.replace(b'"sv:stack": [0, 1, 2]', b'"sv:stack": [0, 1, 2, 3]')
    if name.endswith("manifest.json"):
        return body.replace(HEADER_VERSION, HEADER_VERSION.replace(b"[1, 0, 0]", b"[1, 0, 1]"))
    return body


def other(name: str, body: bytes) -> bytes:
    """The same content as a second add-on: namespace `sw`, a name and identities of its own."""
    renamed = (body.replace(b"sv:", b"sw:").replace(b".sv.", b".sw.").replace(b'"sv"', b'"sw"')
               .replace(b'"Served Add-On"', b'"Other Served Add-On"'))
    return UUID.sub(lambda found: str(uuid.uuid5(uuid.NAMESPACE_OID, found.group().decode())).encode(), renamed)


VARIANTS = {"reshaped": reshaped, "other": other}


def contents(variant: str | None) -> dict[str, bytes]:
    edit = VARIANTS.get(variant, lambda name, body: body)
    packed = {f"{inside}/{name}": edit(name, body)
              for source, inside in PACKS
              for name, body in pack_files(SOURCE / source).items()}
    painted = {f"{PACKS[1][1]}/{name}": png(16, 16, paint) for name, paint in TEXTURES}
    return packed | painted


if __name__ == "__main__":
    chosen = argparse.ArgumentParser(add_help=False)
    chosen.add_argument("--variant", choices=sorted(VARIANTS))
    known, rest = chosen.parse_known_args()
    sys.argv[1:] = rest
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "served-addon.mcaddon",
                  contents(known.variant)))
