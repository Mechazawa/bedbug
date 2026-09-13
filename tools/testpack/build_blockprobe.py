#!/usr/bin/env python3
"""Builds the add-on that checks what a script can ask a block.

The block half of `@minecraft/server` is the part a server run never reaches on its own: permutations,
states, the components a block carries, and the custom components a pack registers and then names on
a block of its own. This pack asks all of it and prints a `BLOCKPROBE` line per answer, so a run
carrying them says the surface answered rather than that nothing asked.

Everything it ships is written in this repository, under `blockprobe/`.

    python3 tools/testpack/build_blockprobe.py                  # build/testpack/block-probe.mcaddon
    python3 tools/testpack/build_blockprobe.py --version 26.2   # and into that version's addons/
"""

import sys
from pathlib import Path

from build import ROOT, main, pack_files

SOURCE = Path(__file__).resolve().parent / "blockprobe"

# The name the pack takes inside the archive, spelled the way a Bedrock author names one.
INSIDE = "Block Probe BP"


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "block-probe.mcaddon",
                  {f"{INSIDE}/{name}": body for name, body in pack_files(SOURCE).items()}))
