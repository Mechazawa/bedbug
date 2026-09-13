#!/usr/bin/env python3
"""Coverage of Bedrock's published particle vocabulary against what this mod reads.

A pack's own `particles/*.json` is read by `bedrock/particle/` and drawn by `client/render/`.
Nothing in the game can say how much of the format that covers: a report built from a loaded pack
sees the components that pack happens to use and nothing about the rest. This reads Mojang's own
particle schemas and documentation instead, so it needs no pack and no running game.

The published side is `bedrock-samples`. Fetch it once with:

    bash tools/census/fetch.sh
    python3 tools/census/particles.py

Four surfaces are measured. The **components** are the ones the effect schema lists, together with
every component the vanilla resource pack's own effects declare, because the packs are the check on
the schema being complete. The **fields** are every property those component schemas carry, read as
one flat vocabulary: the same word means the same thing wherever it appears, so `radius` on a sphere
and on a disc is one name. The **curve types** are the four the documentation names, which turn a
Molang input into a Molang variable a component can then read. The **Molang variables** are the ones
the documentation says the particle system answers by itself.

A name counts as read when the source spells it. That says a pack writing it reaches something
rather than nothing; whether the answer is right is what the game tests and the offline renderer are
for. A component this mod does not read is reported through `AddonEntities.unsupported` as the
effect is read, so a pack using one is told rather than left wondering.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
PARTICLE = JAVA / "bedrock/particle"
RENDER = JAVA / "client/render"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"


class Surface:
    """One vocabulary, the names the mod reads, and where each side was read."""

    def __init__(self, title, published, implemented, source):
        self.title = title
        self.published = set(published)
        self.implemented = set(implemented)
        self.source = source

    @property
    def covered(self):
        return self.published & self.implemented

    @property
    def missing(self):
        return self.published - self.implemented

    @property
    def unpublished(self):
        """Names the mod reads that the published vocabulary does not contain."""
        return self.implemented - self.published

    @property
    def percent(self):
        return 100.0 * len(self.covered) / len(self.published) if self.published else 0.0


# --- the mod's side: read out of the Java source -------------------------------------------------

def text(path):
    return path.read_text(encoding="utf-8")


def string_literals(*directories):
    return {name for directory in directories for path in directory.rglob("*.java")
            for name in re.findall(r'"([^"]*)"', text(path))}


def mod_components():
    return {name for name in string_literals(PARTICLE) if name.startswith("minecraft:")}


def mod_fields():
    return {name for name in string_literals(PARTICLE, RENDER)
            if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name)}


def mod_variables(published):
    """The Molang variables the emitter and the particle answer.

    Most are set under their own name. The four random ones are set in a loop from a prefix, which
    is the literal the call site passes, so a published name starting with such a prefix counts too.
    """
    bodies = [text(path) for directory in (PARTICLE, RENDER) for path in directory.rglob("*.java")]
    named = {name for body in bodies for name in re.findall(r'setVariable\(\s*"([^"]+)"', body)}
    prefixes = {name for body in bodies
                for name in re.findall(r'randomise\([^,]+,\s*"([^"]+)"', body)}
    return named | {name for name in published
                    if any(name.startswith(prefix) for prefix in prefixes)}


# --- the published side: read out of the metadata and the packs Mojang ships ----------------------

def schema_directories(samples):
    root = samples / "metadata/json_schemas/client/particles"
    return sorted(path for path in root.iterdir() if path.is_dir())


def schemas(samples):
    return [json.loads(text(path)) for directory in schema_directories(samples)
            for path in sorted(directory.glob("*.json"))]


def published_components(samples):
    """Every component the effect schema lists, plus every one the vanilla effects declare."""
    named = set()
    for schema in schemas(samples):
        named.update(key for key in schema.get("properties", {}) if key.startswith("minecraft:"))
    return named | vanilla_components(samples)


def published_fields(samples):
    """Every property a particle schema carries, with the components themselves left out."""
    named = set()
    for schema in schemas(samples):
        named.update(key for key in schema.get("properties", {}) if not key.startswith("minecraft:"))
    return named


def vanilla_effects(samples):
    """The effects the vanilla resource pack ships, skipping the ones that are not plain JSON."""
    for path in sorted((samples / "resource_pack/particles").glob("*.json")):
        try:
            yield json.loads(text(path))["particle_effect"]
        except (KeyError, TypeError, json.JSONDecodeError):
            continue


def vanilla_components(samples):
    return {key for effect in vanilla_effects(samples)
            for key in effect.get("components", {})}


def documentation(samples):
    return json.loads(text(samples / "metadata/doc_modules/particles.json"))


def section(nodes, name):
    for node in nodes:
        if node.get("name") == name:
            return node
        found = section(node.get("nodes") or [], name)
        if found:
            return found
    return None


def published_curves(samples):
    """The curve types, which the documentation names in the one line that lists them."""
    curves = section(documentation(samples)["nodes"], "Curves") or {}
    lines = [line for example in curves.get("examples", []) for line in example.get("text", [])]
    named = {name for line in lines if "type can be" in line
             for name in re.findall(r'"([a-z_]+)"', line)}
    return named | {effect["curves"][name].get("type") for effect in vanilla_effects(samples)
                    for name in effect.get("curves", {})
                    if isinstance(effect["curves"][name], dict)}


def published_variables(samples):
    """The Molang variables the particle system sets, named under `Molang integration`."""
    molang = section(documentation(samples)["nodes"], "Molang integration") or {}
    names = [child.get("name", "") for node in molang.get("nodes", [])
             for child in (node.get("nodes") or [])]
    return {name.split(".", 1)[1] for name in names if name.startswith("variable.")}


def surfaces(samples):
    variables = published_variables(samples)
    return [
        Surface("particle components", published_components(samples), mod_components(),
                "the effect schema and the vanilla effects"),
        Surface("component fields", published_fields(samples), mod_fields(),
                "the particle schemas"),
        Surface("curve types", published_curves(samples), string_literals(PARTICLE),
                "the curve documentation and the vanilla effects"),
        Surface("molang variables", variables, mod_variables(variables),
                "the particle molang documentation"),
    ]


# --- output --------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(surface.title) for surface in found)
    print("Coverage of the published Bedrock particle vocabulary")
    print()
    for surface in found:
        print(f"  {surface.title:<{width}}  {len(surface.covered):>3}/{len(surface.published):<3}"
              f"  {surface.percent:5.1f}%   read from {surface.source}")
    total_covered = sum(len(surface.covered) for surface in found)
    total_published = sum(len(surface.published) for surface in found)
    print()
    print(f"  {'all surfaces':<{width}}  {total_covered:>3}/{total_published:<3}"
          f"  {100.0 * total_covered / total_published:5.1f}%")


def print_names(title, names):
    if not names:
        return
    print()
    print(f"{title} ({len(names)}):")
    for name in sorted(names):
        print(f"    {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero when anything is missing")
    arguments = parser.parse_args()

    if not (arguments.samples / "metadata/json_schemas/client/particles").is_dir():
        print(f"No particle schemas under {arguments.samples}. Fetch them with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = surfaces(arguments.samples)
    print_table(found)
    for surface in found:
        print_names(f"Missing, {surface.title}", surface.missing)
    print_names("Component names in the source that no published effect has",
                found[0].unpublished)

    return 1 if arguments.strict and any(surface.missing for surface in found) else 0


if __name__ == "__main__":
    sys.exit(main())
