#!/usr/bin/env python3
"""Coverage of Bedrock's published data-driven vocabulary against what this mod implements.

The `/mcaddon` commands measure demand: what a loaded pack asked for and did not get. This
measures supply, so it needs no pack and no running game. The published side comes from the
metadata Mojang ships in `bedrock-samples`; the mod's side is read out of the Java source.

    git clone --depth 1 https://github.com/Mojang/bedrock-samples scratchpad/bedrock/bedrock-samples
    python3 tools/census/components.py
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"

GOAL_PREFIXES = ("minecraft:behavior.", "minecraft:annotation.")


class Surface:
    """One vocabulary, the names the mod acts on, and where each side was read."""

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
        """Names the mod acts on that the published vocabulary does not contain."""
        return self.implemented - self.published

    @property
    def percent(self):
        return 100.0 * len(self.covered) / len(self.published) if self.published else 0.0


# --- the mod's side: read out of the Java source -------------------------------------------------

def text(path):
    return path.read_text(encoding="utf-8")


def balanced(body, start):
    """The text between the parenthesis at or after `start` and its match."""
    open_at = body.index("(", start)
    depth = 0
    for i in range(open_at, len(body)):
        if body[i] == "(":
            depth += 1
        elif body[i] == ")":
            depth -= 1
            if depth == 0:
                return body[open_at + 1:i]
    raise ValueError("unterminated argument list")


def literal_set(path, marker):
    """The string literals of an `X = Set.of(...)` or `List.of(...)` declaration."""
    body = text(path)
    at = body.index(marker)
    return set(re.findall(r'"([^"]+)"', balanced(body, body.index(".of", at))))


def switch_cases(path, marker):
    """The `case "..."` labels of the switch that follows `marker`, that switch only.

    Depth counting stops at the closing brace of the switch, so a nested switch in a helper
    below it contributes nothing.
    """
    body = text(path)
    at = body.index("{", body.index("switch", body.index(marker)))
    depth = 0
    for i in range(at, len(body)):
        if body[i] == "{":
            depth += 1
        elif body[i] == "}":
            depth -= 1
            if depth == 0:
                return set(re.findall(r'case\s+((?:"[^"]*"\s*,?\s*)+)->', body[at:i]))
    raise ValueError("unterminated switch")


def case_labels(path, marker):
    return {name for group in switch_cases(path, marker)
            for name in re.findall(r'"([^"]*)"', group)}


def mod_entity_names():
    declared = literal_set(JAVA / "registry/AddonEntities.java", "IMPLEMENTED = Set.of")
    goals = case_labels(JAVA / "entity/AddonEntity.java", "void addGoals")
    events = case_labels(JAVA / "server/EntityEvents.java", "applyComponent")
    return declared | goals | {name for name in events if name.startswith("minecraft:")}


def unbacked_claims():
    """Names a declaration calls implemented whose literal appears in no other Java file.

    A name listed there and read nowhere means the mod does nothing when a pack sends it, which
    is the shape of a coverage claim that cannot be cashed.
    """
    declarations = {
        "entity": (JAVA / "registry/AddonEntities.java", "IMPLEMENTED = Set.of"),
        "item": (JAVA / "registry/AddonItems.java", "IMPLEMENTED = List.of"),
        "block": (JAVA / "registry/AddonBlocks.java", "IMPLEMENTED = List.of"),
    }
    sources = {path: text(path) for path in JAVA.rglob("*.java")}

    unbacked = {}
    for kind, (path, marker) in declarations.items():
        declaration = balanced(sources[path], sources[path].index(".of", sources[path].index(marker)))
        for name in literal_set(path, marker):
            elsewhere = sum(body.count(f'"{name}"') for body in sources.values())
            if elsewhere - declaration.count(f'"{name}"') == 0:
                unbacked.setdefault(kind, set()).add(name)
    return unbacked


# --- the published side: read out of the metadata Mojang ships -----------------------------------

def doc_sections(samples):
    """The named lists under `Server Entity Documentation` and the top-level `Filters` list."""
    document = json.loads(text(samples / "metadata/doc_modules/entities.json"))
    sections = {}
    for node in document["nodes"]:
        if node.get("name") == "Filters":
            sections["Filters"] = node["nodes"]
        if node.get("name") == "Server Entity Documentation":
            sections.update({child["name"]: child.get("nodes", []) for child in node["nodes"]})
    return sections


def doc_names(nodes):
    """Node names with the `(See JSON Schema since 1.21.120)` aside the docs append stripped."""
    names = (re.sub(r"\s*\(.*", "", node.get("name") or "") for node in nodes)
    return {name for name in names if name}


def schema_properties(paths):
    return {key for path in paths for key in json.loads(text(path)).get("properties", {})}


def vanilla_entity_components(samples):
    """Every component name the vanilla behaviour pack's own entities use.

    This is the lower bound the vocabulary falls back to when the metadata is absent: a name no
    vanilla entity happens to use is invisible to it.
    """
    names = set()
    for path in (samples / "behavior_pack/entities").glob("*.json"):
        try:
            entity = json.loads(text(path))["minecraft:entity"]
        except (KeyError, json.JSONDecodeError):
            continue
        bodies = [entity.get("components", {})] + list(entity.get("component_groups", {}).values())
        names.update(key for body in bodies for key in body)
    return names


def published_entity_names(samples):
    metadata = samples / "metadata"
    if not metadata.is_dir():
        return vanilla_entity_components(samples), "vanilla behaviour pack entities"

    names = schema_properties(
        (metadata / "json_schemas/server/entity").glob("*/Entity component definitions.json"))
    sections = doc_sections(samples)
    for section in ("Components", "AI Goals", "Properties", "Attributes"):
        names |= doc_names(sections.get(section, []))
    return names, "entity component schemas and the entity docs"


def surfaces(samples):
    metadata = samples / "metadata"
    entity_published, entity_source = published_entity_names(samples)
    mod_entity = mod_entity_names()

    def split(names, goals):
        return {n for n in names if n.startswith(GOAL_PREFIXES) == goals}

    found = [
        Surface("entity components", split(entity_published, False), split(mod_entity, False),
                entity_source),
        Surface("entity ai goals", split(entity_published, True), split(mod_entity, True),
                entity_source),
    ]
    if not metadata.is_dir():
        return found

    found.append(Surface(
        "entity filters",
        doc_names(doc_sections(samples)["Filters"]),
        case_labels(JAVA / "bedrock/behaviour/Filter.java", "Boolean evaluate"),
        "the entity filter docs"))
    found.append(Surface(
        "molang queries",
        {query["name"].split(".", 1)[1]
         for query in json.loads(text(metadata / "molang_modules/mojang-molang-queries.json"))["queries"]},
        case_labels(JAVA / "client/render/AddonQueries.java", "MolangValue query("),
        "the engine's molang query list"))
    found.append(Surface(
        "molang math",
        {function["name"].split(".", 1)[1]
         for function in json.loads(text(metadata / "molang_modules/mojang-molang-queries.json"))["math_functions"]},
        case_labels(JAVA / "bedrock/molang/MolangMath.java", "MolangValue call("),
        "the engine's molang function list"))
    found.append(Surface(
        "block components",
        schema_properties(
            (metadata / "json_schemas/server/block_components").glob("*/Block Components.json")),
        literal_set(JAVA / "registry/AddonBlocks.java", "IMPLEMENTED = List.of"),
        "the block component schema"))
    found.append(Surface(
        "item components",
        schema_properties((metadata / "json_schemas/server/item").glob("*/Components.json")),
        literal_set(JAVA / "registry/AddonItems.java", "IMPLEMENTED = List.of"),
        "the item component schemas"))
    return found


# --- output --------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(surface.title) for surface in found)
    print("Coverage of the published Bedrock vocabulary")
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
                        help="exit non-zero when anything is missing or claimed without a reader")
    arguments = parser.parse_args()

    if not arguments.samples.is_dir():
        print(f"No bedrock-samples checkout at {arguments.samples}. Clone it with:\n"
              f"    git clone --depth 1 https://github.com/Mojang/bedrock-samples "
              f"{arguments.samples}", file=sys.stderr)
        return 2

    found = surfaces(arguments.samples)
    print_table(found)
    for surface in found:
        print_names(f"Missing, {surface.title}", surface.missing)
        print_names(f"Named in the source but absent from the published {surface.title}",
                    surface.unpublished)
    for kind, names in sorted(unbacked_claims().items()):
        print_names(f"Claimed implemented but read nowhere, {kind} components", names)

    missing = any(surface.missing for surface in found)
    return 1 if arguments.strict and (missing or unbacked_claims()) else 0


if __name__ == "__main__":
    sys.exit(main())
