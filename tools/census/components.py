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
from urllib.parse import unquote

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


def lenient(path):
    """One of Mojang's JSON files, whose comments and trailing commas `json` alone refuses.

    Half of the vanilla entity files carry a `//` comment beside a value or a comma before a
    closing brace. The game reads them, so a census that skipped them would measure a smaller
    vocabulary than the one Mojang ships.
    """
    body = text(path)
    out = []
    index = 0
    while index < len(body):
        character = body[index]
        if character == '"':
            end = index + 1
            while end < len(body):
                if body[end] == "\\":
                    end += 2
                    continue
                if body[end] == '"':
                    break
                end += 1
            out.append(body[index:end + 1])
            index = end + 1
        elif body.startswith("//", index):
            index = body.find("\n", index)
            if index < 0:
                break
        elif body.startswith("/*", index):
            index = body.find("*/", index) + 2
        else:
            out.append(character)
            index += 1

    return json.loads(re.sub(r",\s*([}\]])", r"\1", "".join(out)))


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
    """Names an IMPLEMENTED declaration lists whose literal appears nowhere else in the source.

    Two things look the same here and only a reader can tell them apart: a component Java does for
    every mob anyway, which is why the declaration carries it, and a component nothing acts on,
    which stops being counted as a gap for no reason. Audit each one against the declaration's own
    docblock.
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


def block_doc_names(samples):
    """The `Block Components` list in the block documentation module.

    The block component schema leaves `minecraft:custom_components` out and the documentation
    carries it, so a published side read from the schema alone calls that name stale. Reading both
    is what the entity side already does.
    """
    document = json.loads(text(samples / "metadata/doc_modules/blocks.json"))
    for node in document["nodes"]:
        for child in node.get("nodes") or []:
            if child.get("name") == "Block Components":
                return doc_names(child.get("nodes") or [])
    return set()


def vanilla_components(samples, directory, root):
    """Every component name the vanilla behaviour pack's own files under `directory` declare.

    The published metadata is not complete: `minecraft:pushable` is in no schema and no doc list
    and 54 vanilla entities declare it. Reading the packs is what closes that hole, and it is also
    the whole vocabulary when the metadata is missing, where it reads as a lower bound: a name no
    vanilla file happens to use is invisible.
    """
    names = set()
    for path in (samples / directory).glob("*.json"):
        try:
            body = lenient(path)[root]
        except (KeyError, json.JSONDecodeError):
            continue
        bodies = [body.get("components", {})] + list(body.get("component_groups", {}).values())
        names.update(key for group in bodies for key in group)
    return names


def unreferenced_schema_titles(directory):
    """Components that have a schema file of their own and are in no master property list.

    `Entity component definitions.json` is the list every other reading of the schemas starts
    from, and it leaves three of Mojang's own component schemas out. A schema another schema
    points at with `$ref` is a piece of some component's body rather than a component, so what
    counts here is a `minecraft_*.json` nothing refers to.
    """
    referenced = set()

    def refs(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "$ref" and isinstance(value, str):
                    referenced.add(unquote(value.rsplit("/", 1)[-1]))
                refs(value)
        elif isinstance(node, list):
            for item in node:
                refs(item)

    schemas = {path: json.loads(text(path)) for path in directory.rglob("*.json")}
    for path, body in schemas.items():
        if path.name != "Entity component definitions.json":
            refs(body)
    return {body.get("title") for path, body in schemas.items()
            if path.name.startswith("minecraft_") and path.name not in referenced
            and str(body.get("title", "")).startswith("minecraft:")}


def published_entity_names(samples):
    vanilla = vanilla_components(samples, "behavior_pack/entities", "minecraft:entity")
    metadata = samples / "metadata"
    if not metadata.is_dir():
        return vanilla, "vanilla behaviour pack entities"

    schemas = metadata / "json_schemas/server/entity"
    names = schema_properties(schemas.glob("*/Entity component definitions.json"))
    names |= unreferenced_schema_titles(schemas)
    sections = doc_sections(samples)
    for section in ("Components", "AI Goals", "Properties", "Attributes"):
        names |= doc_names(sections.get(section, []))
    return names | vanilla, "the entity schemas, the entity docs and the vanilla entities"


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
    queries = JAVA / "client/render/AddonQueries.java"
    found.append(Surface(
        "molang queries",
        {query["name"].split(".", 1)[1]
         for query in json.loads(text(metadata / "molang_modules/mojang-molang-queries.json"))["queries"]},
        case_labels(queries, "MolangValue query(") - literal_set(queries, "CONTEXT = Set.of"),
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
            (metadata / "json_schemas/server/block_components").glob("*/Block Components.json"))
        | block_doc_names(samples)
        | vanilla_components(samples, "behavior_pack/blocks", "minecraft:block"),
        literal_set(JAVA / "registry/AddonBlocks.java", "IMPLEMENTED = List.of"),
        "the block component schema, the block docs and the vanilla blocks"))
    found.append(Surface(
        "item components",
        schema_properties((metadata / "json_schemas/server/item").glob("*/Components.json"))
        | vanilla_components(samples, "behavior_pack/items", "minecraft:item"),
        literal_set(JAVA / "registry/AddonItems.java", "IMPLEMENTED = List.of"),
        "the item component schemas and the vanilla items"))
    return found


# --- output --------------------------------------------------------------------------------------

def print_table(found, title="Coverage of the published Bedrock vocabulary"):
    width = max(len(surface.title) for surface in found)
    print(title)
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
        print_names(f"Listed as implemented with no reader of their own, {kind} components", names)

    return 1 if arguments.strict and any(surface.missing for surface in found) else 0


if __name__ == "__main__":
    sys.exit(main())
