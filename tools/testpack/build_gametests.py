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

import sys
from pathlib import Path

from build import ROOT, main, pack_files
from mcstructure import structure

SOURCE = Path(__file__).resolve().parent / "gametests"

# The name the pack takes inside the archive, spelled the way a Bedrock author names one.
INSIDE = "GameTest Probe BP"

# The structure both tests build: a stone floor with air above it, in the palette order below.
STRUCTURE_PATH = "structures/probe/floor.mcstructure"
STRUCTURE_SIZE = (3, 3, 3)
STRUCTURE_PALETTE = [("minecraft:air", {}), ("minecraft:stone", {})]


def mcstructure() -> bytes:
    return structure(STRUCTURE_SIZE, STRUCTURE_PALETTE, lambda x, y, z: 1 if y == 0 else 0)


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "gametest-probe.mcaddon",
                  {f"{INSIDE}/{name}": body for name, body in pack_files(SOURCE).items()}
                  | {f"{INSIDE}/{STRUCTURE_PATH}": mcstructure()}))
