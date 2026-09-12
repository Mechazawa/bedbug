#!/usr/bin/env python3
"""Checks the script modules for the two mistakes that cost a pack its whole script.

A JavaScript module is linked before any of it runs, so both of these are failures at load time
rather than where the mistake is written, and both switch off every pack script at once:

  - one name declared twice in a module, which the engine rejects outright
  - a module importing a name another one does not export

Parsing catches neither: `node --check` reads one file at a time and is happy with both. Loading the
modules in Node does catch them, but Node cannot resolve a bare `@minecraft/server` the way the game
does. Reading the declarations and the imports is what is left, and it is enough for both.
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ROOT / "src" / "main" / "resources" / "assets" / "mcaddon" / "script"

# The file each specifier a module may import from is served by.
SERVED = {
    "@minecraft/server": "minecraft_server.js",
    "@minecraft/server-ui": "minecraft_server_ui.js",
    "@minecraft/common": "minecraft_common.js",
    "@minecraft/math": "minecraft_math.js",
    "@minecraft/vanilla-data": "minecraft_vanilla_data.js",
    "@minecraft/server-gametest": "minecraft_server_gametest.js",
}

DECLARED = re.compile(r"^export\s+(?:class|const|let|var|function\*?|async\s+function)\s+(\w+)", re.M)
RENAMED = re.compile(r"^export\s*\{([^}]*)\}", re.M)
IMPORTED = re.compile(r"^import\s*\{([^}]*)\}\s*from\s*['\"]([^'\"]+)['\"]", re.M)


def exported(source: str) -> list[str]:
    """Every name a module offers, including the ones renamed on the way out."""
    names = DECLARED.findall(source)
    for group in RENAMED.findall(source):
        for part in group.split(","):
            piece = part.strip()
            if not piece:
                continue
            names.append(piece.split(" as ")[-1].strip())
    return names


def imported(source: str) -> list[tuple[str, str]]:
    """Every name a module asks another for, as the name and the specifier it came from."""
    asked = []
    for group, specifier in IMPORTED.findall(source):
        for part in group.split(","):
            piece = part.strip()
            if piece:
                asked.append((piece.split(" as ")[0].strip(), specifier))
    return asked


def main() -> int:
    parser = argparse.ArgumentParser(description="Checks the script modules link.")
    parser.add_argument("--modules", default=str(MODULES), help="where the modules live")
    arguments = parser.parse_args()

    directory = pathlib.Path(arguments.modules)
    sources = {path.name: path.read_text(encoding="utf-8") for path in sorted(directory.glob("*.js"))}
    offers = {name: exported(source) for name, source in sources.items()}

    broken = []
    for name, names in offers.items():
        for offered in sorted({one for one in names if names.count(one) > 1}):
            broken.append(f"{name} declares {offered} more than once")

    for name, source in sources.items():
        for wanted, specifier in imported(source):
            served = SERVED.get(specifier)
            if served is None:
                broken.append(f"{name} imports from {specifier}, which nothing serves")
            elif served not in offers:
                broken.append(f"{name} imports from {specifier}, whose file is missing")
            elif wanted not in offers[served]:
                broken.append(f"{name} imports {wanted} from {specifier}, which does not export it")

    for problem in broken:
        print(problem, file=sys.stderr)
    if broken:
        return 1

    print(f"Linked {len(sources)} script modules, {sum(len(n) for n in offers.values())} exports.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
