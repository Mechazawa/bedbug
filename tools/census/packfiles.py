#!/usr/bin/env python3
"""Coverage of the file kinds a Bedrock pack may ship against the ones this mod opens.

The other censuses measure the vocabulary inside a file kind the mod already reads: components,
script members, geometry fields, commands, particles, loot and recipes. None of them can see a
whole directory an add-on ships that nothing here opens, because a directory nobody reads asks no
questions. This measures that.

A name is a path relative to the pack root: a top level directory, a top level file, or an index
file inside a directory whose other contents are not JSON, such as `sounds/sound_definitions.json`
beside a pack's audio.

The published side is Mojang's own pack pair in `bedrock-samples`, widened by the material in the
same checkout that names a kind Mojang's pair happens not to ship. The mod's side is the `READ` and
`DEFINED` declarations in `pack/PackFiles.java`, so a reader added there counts the moment it is
added and the same declaration is what `/mcaddon entities` reports against a loaded pack.

    bash tools/census/fetch.sh
    python3 tools/census/packfiles.py
    python3 tools/census/packfiles.py --strict   # exits non-zero while a pack kind goes unnamed
"""

import argparse
import sys
from pathlib import Path

from components import literal_set, print_names, text
from data import lenient

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
PACK_FILES = JAVA / "pack/PackFiles.java"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"

# Mojang's own pair, by the role each half plays.
VANILLA = {"behaviour": "behavior_pack", "resource": "resource_pack"}

# A kind Bedrock defines that Mojang's own pair does not ship, and the material in the same checkout
# that says so. The count behind such a name is the declarations that material carries: a
# vanilladata module's `data_items`, or the schema documents describing the kind. `None` is a kind
# Bedrock has and publishes nothing about, which the run names in a list of its own so the reader
# knows which names here rest on no published material at all.
WIDENED = {
    "behaviour": {
        "animations": "metadata/doc_modules/animations.json",
        "animation_controllers": "metadata/doc_modules/animations.json",
        "blocks": "metadata/vanilladata_modules/mojang-blocks.json",
        "camera": None,
        "cameras": "metadata/vanilladata_modules/mojang-camera-presets.json",
        "dialogue": "metadata/json_schemas/client_server/entity",
        "dimensions": "metadata/vanilladata_modules/mojang-dimensions.json",
        "feature_rules": "metadata/doc_modules/features.json",
        "features": "metadata/vanilladata_modules/mojang-features.json",
        "functions": "metadata/command_modules/mojang-commands.json",
        "item_catalog": "metadata/json_schemas/server/crafting_catalog",
        "scripts": "metadata/script_modules/@minecraft",
        "structures": "metadata/json_schemas/client_server/structure",
        "subpacks": "metadata/json_schemas/client_server/packaging/3.0.0/Subpack.json",
        "texts": "resource_pack/texts",
        "texts/language_names.json": "resource_pack/texts/language_names.json",
        "texts/languages.json": "resource_pack/texts/languages.json",
    },
    "resource": {
        "font": None,
        "materials": None,
        "subpacks": "metadata/json_schemas/client_server/packaging/3.0.0/Subpack.json",
    },
}


# --- the published side ---------------------------------------------------------------------------

def declarations(node):
    """How many names a JSON index declares.

    A top level key whose value is a map of objects is a heading rather than a declaration, so its
    entries are counted instead: `sounds.json` groups its events under `block_sounds` and
    `entity_sounds`, and `biomes_client.json` puts every biome under `biomes`. Anything else counts
    as one, which is what makes `blocks.json` the number of blocks it names.
    """
    if isinstance(node, list):
        return len(node)
    if not isinstance(node, dict):
        return 1

    total = 0
    for name, value in node.items():
        if name in ("format_version", "__comment__"):
            continue
        if isinstance(value, dict) and value and all(isinstance(one, dict) for one in value.values()):
            total += len(value)
        else:
            total += 1
    return total


def index_count(path):
    try:
        return declarations(lenient(path))
    except (ValueError, OSError):
        return 0


def indexes(directory):
    """The JSON files directly inside a directory that holds content of another kind.

    `textures/` holds images and `sounds/` holds audio, so a JSON among them indexes the rest. A
    compound extension such as `.texture_set.json` marks one file per texture, which is content and
    is answered by the directory itself.
    """
    if all(one.suffix == ".json" for one in directory.rglob("*") if one.is_file()):
        return {}
    return {f"{directory.name}/{entry.name}": index_count(entry)
            for entry in directory.iterdir()
            if entry.is_file() and entry.suffix == ".json" and entry.name.count(".") == 1}


def shipped(pack):
    """Every name Mojang's own half of a pack carries, with the declarations behind each."""
    found = {}
    for entry in sorted(pack.iterdir()):
        if entry.is_dir():
            found[entry.name] = sum(1 for one in entry.rglob("*") if one.is_file())
            found.update(indexes(entry))
        elif entry.suffix == ".json":
            found[entry.name] = index_count(entry)
        else:
            found[entry.name] = 1
    return found


def widened(samples, role):
    """The kinds Mojang's pair does not ship, each counted out of the material naming it."""
    found = {}
    for name, evidence in WIDENED[role].items():
        path = samples / evidence if evidence else None
        if path is None or not path.exists():
            found[name] = 0
        elif path.is_dir():
            found[name] = sum(1 for one in path.rglob("*") if one.is_file())
        elif path.suffix == ".json":
            body = lenient(path)
            items = body.get("data_items") if isinstance(body, dict) else None
            found[name] = len(items) if isinstance(items, list) else declarations(body)
        else:
            found[name] = 1
    return found


def missing_evidence(samples, role):
    return {f"{name} ({evidence or 'published nowhere'})"
            for name, evidence in WIDENED[role].items()
            if not evidence or not (samples / evidence).exists()}


def published(samples, role):
    found = shipped(samples / VANILLA[role])
    for name, count in widened(samples, role).items():
        found.setdefault(name, count)
    return found


# --- the mod's side -------------------------------------------------------------------------------

def declared(role, kind):
    marker = f"{role.upper()}_{kind} = Set.of"
    return literal_set(PACK_FILES, marker)


def unbacked(names):
    """A declared path whose own name appears as a literal nowhere else in the source.

    `READ` is a claim that some reader opens that path. With the name in no other file, nothing
    opens it and the claim is the whole of the coverage.
    """
    sources = [text(path) for path in JAVA.rglob("*.java") if path != PACK_FILES]
    return {name for name in names
            if not any(f'"{name}"' in body or f'"{name}/' in body for body in sources)}


class Role:
    """One half of an add-on: what a pack may ship, and what this mod opens of it."""

    def __init__(self, title, role, available):
        self.title = title
        self.role = role
        self.published = available
        self.defined = declared(role, "DEFINED")
        self.read = declared(role, "READ")

    @property
    def covered(self):
        return set(self.published) & self.read

    @property
    def reported(self):
        """Shipped, unread, and named to the player by `/mcaddon entities`."""
        return (set(self.published) & self.defined) - self.read

    @property
    def silent(self):
        """Shipped, unread, and named nowhere. The failure this census exists to end."""
        return set(self.published) - self.defined - self.read

    @property
    def stale(self):
        """A path this mod declares that Bedrock's own material carries no sign of."""
        return (self.defined | self.read) - set(self.published)

    @property
    def percent(self):
        return 100.0 * len(self.covered) / len(self.published) if self.published else 0.0


def roles(samples):
    return [Role("behaviour pack", "behaviour", published(samples, "behaviour")),
            Role("resource pack", "resource", published(samples, "resource"))]


# --- output ---------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(role.title) for role in found)
    print("Coverage of the file kinds a Bedrock pack may ship")
    print()
    print(f"  {'role':<{width}}   read   opened  reported   silent")
    for role in found:
        counted = f"{len(role.covered)}/{len(role.published)}"
        print(f"  {role.title:<{width}}  {counted:>6}  {role.percent:5.1f}%"
              f"  {len(role.reported):>8}  {len(role.silent):>7}")

    covered = sum(len(role.covered) for role in found)
    total = sum(len(role.published) for role in found)
    print()
    print(f"  {'both roles':<{width}}  {f'{covered}/{total}':>6}  {100.0 * covered / total:5.1f}%"
          f"  {sum(len(role.reported) for role in found):>8}"
          f"  {sum(len(role.silent) for role in found):>7}")


def print_counted(title, names, counts):
    if not names:
        return
    print()
    print(f"{title} ({len(names)}), with the declarations Mojang's own material carries:")
    for name in sorted(names, key=lambda one: (-counts.get(one, 0), one)):
        print(f"    {counts.get(name, 0):>6}  {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while a pack kind is neither read nor reported")
    arguments = parser.parse_args()

    if not arguments.samples.is_dir():
        print(f"No bedrock-samples checkout at {arguments.samples}. Fetch it with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = roles(arguments.samples)
    print_table(found)
    for role in found:
        print_counted(f"Reported rather than read, {role.title}", role.reported, role.published)
        print_counted(f"Dropped in silence, {role.title}", role.silent, role.published)
    for role in found:
        print_names(f"Declared here and absent from Bedrock's own material, {role.title}", role.stale)
        print_names(f"Declared as read with no reader naming it, {role.title}", unbacked(role.read))
        print_names(f"Widened with material this checkout has not got, {role.title}",
                    missing_evidence(arguments.samples, role.role))

    return 1 if arguments.strict and any(role.silent for role in found) else 0


if __name__ == "__main__":
    sys.exit(main())
