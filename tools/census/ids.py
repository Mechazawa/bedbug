#!/usr/bin/env python3
"""Which of the item and block ids Bedrock's own material writes this mod answers with a Java one.

Every other census here counts a vocabulary of components, fields or commands. This one counts the
nouns those are written about. A pack names vanilla items and blocks by Bedrock's ids, and a few
hundred of them are not Java's: `golden_rail` is Java's `powered_rail`, `lit_furnace` is a furnace
with `lit` set, `wool` with a fourteen beside it is `red_wool`, and `muttonRaw` is a spelling Bedrock
still reads. An id nothing answers costs a pack its whole loot table, recipe or function.

The published side is the vanilla data modules under `metadata/vanilladata_modules` in
`bedrock-samples`, every id its behaviour pack writes in a loot table, recipe, trade table, entity,
spawn rule, biome, block, item, feature or feature rule, and the older vanilla data packages under
`scratchpad/mcapi`, which carry the spellings Bedrock used before it gave every family member an id
of its own. The mod's side is the mapping itself: the ids are run through `BedrockItemNames` and
`BedrockBlockNames` on the unit tests' classpath, by `./gradlew :<version>:idCensus`, because which
spelling answers depends on the registry of the version that runs it.

An id is answered, Bedrock's own (an id `BedrockBlockNames.bedrockOnly` declares Java has nothing
for), or unanswered. `--strict` fails while anything is unanswered.

    bash tools/census/fetch.sh
    python3 tools/census/ids.py            # the table, then what is Bedrock's own and what is unanswered
    python3 tools/census/ids.py --strict   # exits non-zero while an id is unanswered
"""

import argparse
import collections
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from components import lenient

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"
OLDER_DATA = ROOT / "scratchpad/mcapi"
WORK = ROOT / "build/census"

# The keys whose value names an item, or holds a list of such names.
ITEM_KEYS = {
    "item", "items", "ingredients", "key", "result", "input", "output", "reagent", "base", "addition",
    "template", "feed_items", "breed_items", "accepted_items", "excluded_items", "craft_into",
    "banned_items", "allowed_items", "repair_items", "use_item", "transform_to_item", "spawn_item",
}

# The keys whose value names a block, a list of them, or a `{"name": ...}` descriptor of one.
BLOCK_KEYS = {
    "minecraft:spawns_on_block_filter", "minecraft:spawns_on_block_prevented_filter", "liquid_blocks",
    "target_blocks", "block_list", "blocks", "block_types", "block_type", "home_block_list",
    "top_material", "mid_material", "sea_floor_material", "foundation_material", "sea_material",
    "clay_material", "hard_clay_material", "floor_materials", "ceiling_materials", "beach_material",
    "non_replaceable_blocks", "block", "minecraft:block", "block_filter", "places_block",
    "may_replace", "may_attach_to", "may_grow_on", "may_grow_through", "base_block",
    "double_slab_block", "source_container_types", "destination_container_types", "breathe_blocks",
}

# A filter names an item or a block in its `value`, depending on what it tests.
FILTER_TESTS = {"is_block": "block", "has_equipment": "item"}

DIRECTORIES = ["loot_tables", "recipes", "trading", "entities", "spawn_rules", "biomes", "blocks",
               "items", "features", "feature_rules"]

ID = re.compile(r"^(minecraft:)?[A-Za-z][A-Za-z0-9_.]*(:-?\d+)?$")


# --- the published side --------------------------------------------------------------------------

def an_id(value):
    """Whether a string reads as a vanilla id rather than a tag, a Molang query or a pack's own name."""
    return bool(ID.match(value)) and "potion_type" not in value


def ids_in(node, found, kind=None, key=None):
    """Every item and block id in one document, with the kind the key it sits under says it is."""
    if isinstance(node, list):
        for child in node:
            ids_in(child, found, kind, key)
    elif isinstance(node, dict):
        tested = FILTER_TESTS.get(node.get("test"))
        if tested and isinstance(node.get("value"), str):
            found.add((tested, node["value"]))
        for child_key, value in node.items():
            child_kind = "item" if child_key in ITEM_KEYS else "block" if child_key in BLOCK_KEYS else None
            if child_key == "name":
                child_kind = "item" if node.get("type") == "item" else kind
            if key == "key" and child_kind is None:
                child_kind = kind
            # An equippable slot names the kind of thing it takes, `saddle` or `nautilusarmor`,
            # and lists the items themselves under `accepted_items`.
            if child_key == "item" and "slot" in node and "accepted_items" in node:
                child_kind = None
            ids_in(value, found, child_kind, child_key)
    elif isinstance(node, str) and kind and an_id(node):
        found.add((kind, node))


def tags_in(node, found):
    """Every item tag a document names, which a shareable or a filter writes where an id would go."""
    if isinstance(node, list):
        for child in node:
            tags_in(child, found)
    elif isinstance(node, dict):
        if str(node.get("test", "")).endswith("_tag") and isinstance(node.get("value"), str):
            found.add(node["value"])
        for child_key, value in node.items():
            if child_key in ("tags", "minecraft:tags") and isinstance(value, list):
                found.update(tag for tag in value if isinstance(tag, str))
            tags_in(value, found)


def behaviour_ids(samples):
    by_source = {}
    tags = set()
    for directory in DIRECTORIES:
        found = set()
        for path in sorted((samples / "behavior_pack" / directory).rglob("*.json")):
            try:
                document = lenient(path)
            except (ValueError, OSError) as error:
                print(f"skipping {path.name}: {error}", file=sys.stderr)
                continue
            ids_in(document, found)
            tags_in(document, tags)
        if found:
            by_source[directory.replace("_", " ")] = found
    return {source: {found for found in ids if found[1] not in tags} for source, ids in by_source.items()}


def vanilla_data(samples):
    modules = samples / "metadata/vanilladata_modules"
    found = {}
    for kind, name in (("block", "mojang-blocks.json"), ("item", "mojang-items.json")):
        path = modules / name
        if path.is_file():
            items = json.loads(path.read_text(encoding="utf-8")).get("data_items", [])
            found.setdefault("vanilla data", set()).update((kind, entry["name"]) for entry in items)
    return found


def older_vanilla_data():
    """The block and item enums of every older `@minecraft/vanilla-data` fetched beside the newest."""
    found = set()
    for path in sorted(OLDER_DATA.glob("vanilla-data-*.d.ts")):
        body = path.read_text(encoding="utf-8")
        for kind, enum in (("block", "MinecraftBlockTypes"), ("item", "MinecraftItemTypes")):
            declared = re.search(r"enum " + enum + r" \{(.*?)\}", body, re.S)
            if declared:
                found.update((kind, value) for value in re.findall(r'"(minecraft:[^"]+)"', declared.group(1)))
    return {"older vanilla data": found} if found else {}


# --- the mod's side ------------------------------------------------------------------------------

def active_version():
    declared = re.search(r'stonecutter active "([^"]+)"', (ROOT / "stonecutter.gradle.kts").read_text())
    return declared.group(1) if declared else "26.2"


def answered(ids):
    """Each id with the Java id the mod's mapping gives it, run on this version's own registry."""
    WORK.mkdir(parents=True, exist_ok=True)
    asked, told = WORK / "ids-in.tsv", WORK / "ids-out.tsv"
    asked.write_text("".join(f"{kind}\t{name}\n" for kind, name in sorted(ids)), encoding="utf-8")
    told.unlink(missing_ok=True)

    command = [str(ROOT / "gradlew"), "-q", "--console=plain", f":{active_version()}:idCensus",
               f"-PidsIn={asked}", f"-PidsOut={told}"]
    run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, env=os.environ)
    if run.returncode != 0 or not told.is_file():
        print(run.stdout + run.stderr, file=sys.stderr)
        raise SystemExit("the id census could not run the mapping")

    result = {}
    for line in told.read_text(encoding="utf-8").splitlines():
        kind, name, java, status = (line.split("\t") + ["", "", "", ""])[:4]
        result[(kind, name)] = (java, status)
    return result


# --- output ---------------------------------------------------------------------------------------

def print_table(sources, result):
    width = max(len(source) for source in sources)
    print("Coverage of the item and block ids Bedrock's own material writes")
    print()
    print(f"  {'source':<{width}}  {'items':>15}  {'blocks':>15}   Bedrock's own  unanswered")
    for source, ids in sources.items():
        cells = []
        for kind in ("item", "block"):
            mine = [found for found in ids if found[0] == kind]
            java = sum(1 for found in mine if result[found][1] == "java")
            share = f"{100.0 * java / len(mine):5.1f}%" if mine else "     -"
            cells.append(f"{java:>5}/{len(mine):<5}{share}")
        own = sum(1 for found in ids if result[found][1] == "bedrock-only")
        missing = sum(1 for found in ids if result[found][1] == "none")
        print(f"  {source:<{width}}  {cells[0]:>15}  {cells[1]:>15}   {own:>13}  {missing:>10}")


def print_listed(title, ids, sources):
    if not ids:
        return
    print()
    print(f"{title} ({len(ids)}):")
    for kind, name in sorted(ids, key=lambda found: (found[0], found[1].lower())):
        where = ", ".join(source for source, found in sources.items() if (kind, name) in found)
        print(f"    {kind:<5}  {name:<48}  {where}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while an id resolves to no Java one")
    parser.add_argument("--own", action="store_true",
                        help="also list the ids declared Bedrock's own")
    arguments = parser.parse_args()

    if not arguments.samples.is_dir():
        # A checkout is allowed to have no copy of Mojang's own material, the way it is allowed to
        # have no add-on. There is then nothing to measure against and nothing to report.
        print(f"No bedrock-samples at {arguments.samples}: nothing to measure.")
        return 0

    sources = {**vanilla_data(arguments.samples), **older_vanilla_data(),
               **behaviour_ids(arguments.samples)}
    every = set().union(*sources.values())
    result = answered(every)

    print_table(sources, result)
    unanswered = {found for found in every if result[found][1] == "none"}
    if arguments.own:
        print_listed("Bedrock's own, which Java has nothing for",
                     {found for found in every if result[found][1] == "bedrock-only"}, sources)
    print_listed("Unanswered", unanswered, sources)

    return 1 if arguments.strict and unanswered else 0


if __name__ == "__main__":
    sys.exit(main())
