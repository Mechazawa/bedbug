#!/usr/bin/env python3
"""Builds the add-on that checks every `@minecraft/*` module resolves.

A JavaScript module is linked before any of it runs, so an import of one the mod does not provide
takes a pack's whole script down with it. This pack imports each module the mod provides and calls
one thing from each, printing a `MODULEPROBE` line per answer, so a run carrying all of them says
every module resolved and answered.

Everything it ships is written in this repository: a manifest and one script, both under `modules/`.

    python3 tools/testpack/build_modules.py                  # build/testpack/module-probe.mcaddon
    python3 tools/testpack/build_modules.py --version 26.2   # and into that version's addons/
"""

import sys
from pathlib import Path

from build import ROOT, main, pack_files

SOURCE = Path(__file__).resolve().parent / "modules"

# The name the pack takes inside the archive, spelled the way a Bedrock author names one.
INSIDE = "Module Probe BP"


if __name__ == "__main__":
    sys.exit(main((__doc__ or "").splitlines()[0],
                  ROOT / "build" / "testpack" / "module-probe.mcaddon",
                  {f"{INSIDE}/{name}": body for name, body in pack_files(SOURCE).items()}))
