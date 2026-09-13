#!/usr/bin/env python3
"""Measures the script surface this mod offers against the one Mojang publishes.

`/mcaddon scripts` reports what a loaded pack asked for and did not get, which can only ever see the
part of the API that pack happens to touch. A pack that never opens a form says nothing about forms.
This reads Mojang's own type definitions instead and compares them against the modules under
`assets/mcaddon/script/`, so the answer does not depend on which add-on is installed.

The definitions are the published `@minecraft/*` packages. Fetch them once with:

    cd scratchpad/mcapi && npm pack @minecraft/server @minecraft/server-ui \\
        @minecraft/server-gametest @minecraft/common @minecraft/math @minecraft/vanilla-data

and extract each `package/index.d.ts` as `<module>.d.ts`. The directory is not tracked by git.

A name counts as offered when the module exports it. A member counts as offered when it is written
in that name's body, inherited from a class there, or named in a table the module builds members
from. None of that says the member behaves correctly, which is what the game tests are for; it says
only whether a pack calling it reaches something rather than nothing.

Two limits worth knowing. A member built from a table is recognised by its name appearing as a key,
so a short name that is also a coordinate would read as offered everywhere; names of three
characters or fewer are therefore left out of that reading. And a member that reports itself through
`unsupported(...)` counts as offered, because the pack reaches it and learns it is not implemented,
which is the behaviour this project asks for over a silent wrong answer.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ROOT / "src" / "main" / "resources" / "assets" / "mcaddon" / "script"
DEFINITIONS = ROOT / "scratchpad" / "mcapi"

# The file each published module is served from, and the name of its type definitions.
SERVED = {
    "server": "minecraft_server.js",
    "server-ui": "minecraft_server_ui.js",
    "server-gametest": "minecraft_server_gametest.js",
    "common": "minecraft_common.js",
    "math": "minecraft_math.js",
    "vanilla-data": "minecraft_vanilla_data.js",
}

# A published top level name that exists once the TypeScript is compiled away. An `interface` and a
# `type` describe a shape for a checker and leave nothing behind to import, so a module offering
# neither is still complete; only a class, an enum, a function and a constant are things at runtime.
DECLARED = re.compile(
    r"^export (?:declare )?(?:abstract )?(class|enum|function|const) (\w+)", re.M)

# A member inside a published class body: a method or a property, but not a comment or a brace.
MEMBER = re.compile(r"^    (?:static )?(?:readonly )?(\w+)\s*[(?:<]", re.M)

# The four ways the mod's own modules write a member: a method, an accessor, a class field, and a
# property the constructor assigns. Counting only the first would call a getter missing.
WRITTEN = (
    re.compile(r"^    (?:static\s+)?(?:async\s+)?(\w+)\s*\(", re.M),
    re.compile(r"^    (?:static\s+)?(?:get|set)\s+(\w+)\s*\(", re.M),
    re.compile(r"^    (?:static\s+)?(\w+)\s*=", re.M),
    re.compile(r"^\s*this\.(\w+)\s*=", re.M),
)

# Words the patterns above pick up that name no member of the class.
NOT_MEMBERS = frozenset({"constructor", "if", "for", "while", "return", "switch", "catch"})

# The definitions list a constructor to mark it private. A pack never calls one as a member.
PUBLISHED_SKIP = frozenset({"constructor"})

# What the mod's own modules export, in either of the two spellings they use.
OFFERED = re.compile(r"^export\s+(?:class|const|let|var|function\*?|async\s+function)\s+(\w+)", re.M)
RENAMED = re.compile(r"^export\s*\{([^}]*)\}", re.M)


def published(text: str) -> dict[str, set[str]]:
    """Every name the definitions declare, against the members written inside each one."""
    names: dict[str, set[str]] = {}
    for match in DECLARED.finditer(text):
        kind, name = match.group(1), match.group(2)
        body = text[match.end():]
        closing = body.find("\n}")
        names[name] = set() if kind in ("function", "const", "type") \
            else set(MEMBER.findall(body[:closing if closing > 0 else 0])) - PUBLISHED_SKIP
    return names


def members(body: str) -> set[str]:
    """Every member a class body writes, however it spells it."""
    found: set[str] = set()
    for pattern in WRITTEN:
        found.update(pattern.findall(body))
    return found - NOT_MEMBERS


def offered(text: str) -> dict[str, set[str]]:
    """Every name the mod's module offers, against the members each one carries.

    A class carries what its own body writes and what it inherits, because a pack calling an
    inherited member reaches it either way. A base class the module keeps to itself counts for the
    subclasses that export it, so it is read here even though it is not exported.
    """
    own: dict[str, set[str]] = {}
    parents: dict[str, str] = {}
    for match in re.finditer(r"^(?:export\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?", text, re.M):
        body = text[match.end():]
        closing = body.find("\n}")
        own[match.group(1)] = members(body[:closing if closing > 0 else 0])
        if match.group(2):
            parents[match.group(1)] = match.group(2)

    names: dict[str, set[str]] = {}
    for name in own:
        inherited = set(own[name])
        parent = parents.get(name)
        seen = {name}
        while parent in own and parent not in seen:
            inherited |= own[parent]
            seen.add(parent)
            parent = parents.get(parent)
        names[name] = inherited

    exported = set(re.findall(r"^export\s+class\s+(\w+)", text, re.M))
    names = {name: found for name, found in names.items() if name in exported}

    for name in OFFERED.findall(text):
        names.setdefault(name, set())
    for group in RENAMED.findall(text):
        for part in group.split(","):
            piece = part.strip()
            if piece:
                names.setdefault(piece.split(" as ")[-1].strip(), set())
    return names


def compare(module: str, definitions: pathlib.Path, source: pathlib.Path) -> tuple[int, int, list[str]]:
    """One module's counts, and the names and members it does not offer."""
    theirs = published(definitions.read_text(encoding="utf-8"))
    text = source.read_text(encoding="utf-8")
    ours = offered(text)

    # Some of the surface is built at runtime rather than written out: the event groups are a proxy
    # over a list of names, the vanilla tables are read from the registries, and the members that all
    # answer the same way are installed from one table. A name spelled as a string or as a key of an
    # object literal counts as offered, because that is what building a member looks like here.
    quoted = set(re.findall(r'"([A-Za-z_]\w*)"', text))
    keys = set(re.findall(r"^\s*([A-Za-z_]\w*):\s", text, re.M))
    keys |= set(re.findall(r"[{,]\s*([A-Za-z_]\w*):\s", text))

    # A key short enough to be a coordinate is one every vector literal in the file would offer, so
    # the reading is taken only for names long enough to be the member they look like.
    quoted |= {one for one in keys if len(one) > 3}

    missing = []
    members_total = 0
    members_offered = 0
    for name, members in sorted(theirs.items()):
        if name not in ours:
            missing.append(f"{module}: {name}")
            members_total += len(members)
            continue
        for member in sorted(members):
            members_total += 1
            if member in ours[name] or member in quoted:
                members_offered += 1
            else:
                missing.append(f"{module}: {name}.{member}")

    names_offered = len([one for one in theirs if one in ours])
    return (names_offered + members_offered, len(theirs) + members_total, missing)


def main() -> int:
    parser = argparse.ArgumentParser(description="Compares the script surface against Mojang's own.")
    parser.add_argument("--strict", action="store_true", help="exit non-zero when anything is missing")
    parser.add_argument("--list", action="store_true", help="name everything that is missing")
    arguments = parser.parse_args()

    if not DEFINITIONS.is_dir():
        print(f"no type definitions under {DEFINITIONS}; see this file's docstring", file=sys.stderr)
        return 2

    total_offered = 0
    total_published = 0
    everything = []
    for module, filename in SERVED.items():
        definitions = DEFINITIONS / f"{module}.d.ts"
        source = MODULES / filename
        if not definitions.is_file() or not source.is_file():
            print(f"{module:<16} no type definitions saved")
            continue

        count, published_count, missing = compare(module, definitions, source)
        total_offered += count
        total_published += published_count
        everything.extend(missing)
        share = 100.0 * count / published_count if published_count else 100.0
        print(f"{module:<16} {count:>5} of {published_count:<5} {share:5.1f}%")

    share = 100.0 * total_offered / total_published if total_published else 100.0
    print(f"{'all modules':<16} {total_offered:>5} of {total_published:<5} {share:5.1f}%")

    if arguments.list:
        for one in everything:
            print(f"    {one}")

    return 1 if arguments.strict and everything else 0


if __name__ == "__main__":
    raise SystemExit(main())
