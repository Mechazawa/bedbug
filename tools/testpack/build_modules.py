#!/usr/bin/env python3
"""Builds the add-on that checks every `@minecraft/*` module resolves.

A JavaScript module is linked before any of it runs, so an import of one the mod does not provide
costs a pack its whole script rather than the line that asked for it. This pack imports each module
the mod provides and calls one thing from each, printing a `MODULEPROBE` line per answer, so a run
carrying all of them says every module resolved and answered.

Everything it ships is written in this repository: a manifest and one script, both under `modules/`.

    python3 tools/testpack/build_modules.py                  # build/testpack/module-probe.mcaddon
    python3 tools/testpack/build_modules.py --version 26.2   # and into that version's addons/
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

from build import ROOT, ZIP_TIMESTAMP, pack_files

SOURCE = Path(__file__).resolve().parent / "modules"

# The name the pack takes inside the archive, spelled the way a Bedrock author names one.
INSIDE = "Module Probe BP"


def build(destination: Path) -> tuple[Path, int]:
    if not SOURCE.is_dir():
        raise SystemExit(f"missing pack sources under {SOURCE}")

    contents = {f"{INSIDE}/{name}": body for name, body in pack_files(SOURCE).items()}

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
                        default=ROOT / "build" / "testpack" / "module-probe.mcaddon",
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
