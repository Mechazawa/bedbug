#!/usr/bin/env python3
"""Coverage of Bedrock's loot table, recipe and trade table vocabulary against what this mod reads.

`/mcaddon entities` measures demand: what a loaded pack asked for and did not get. This measures
supply, so it needs no pack and no running game. The published side comes from `bedrock-samples`:
the vanilla behaviour pack's own loot tables, recipes and trade tables, the recipe documentation
module, and the trade table JSON schemas. The mod's side is read out of the Java source, so a name
added to a reader counts the moment it is added.

    bash tools/census/fetch.sh
    python3 tools/census/data.py
"""

import argparse
import json
import re
import sys
from pathlib import Path

from components import (Surface, case_labels, lenient, literal_set, print_names, print_table,
                        text)

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
BEHAVIOUR = JAVA / "bedrock/behaviour"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"


# --- reading Bedrock's own JSON ------------------------------------------------------------------

def documents(samples, directory):
    for path in sorted((samples / "behavior_pack" / directory).rglob("*.json")):
        try:
            yield lenient(path)
        except (ValueError, OSError) as error:
            print(f"skipping {path.name}: {error}", file=sys.stderr)


def labelled(node, key):
    """Every value of `key` in the tree, which is where a loot function or condition names itself."""
    if isinstance(node, list):
        for child in node:
            yield from labelled(child, key)
    elif isinstance(node, dict):
        name = node.get(key)
        if isinstance(name, str):
            yield name.split(":")[-1]
        for child in node.values():
            yield from labelled(child, key)


# --- the published side --------------------------------------------------------------------------

def published_loot(samples, key):
    return {name for table in documents(samples, "loot_tables") for name in labelled(table, key)}


def members(value):
    """The field names one level inside a value, looking through lists and through keyed maps.

    A recipe's `key` is a map from a pattern character to an item reference, so the names worth
    counting sit one level further down than they do anywhere else. A single character and anything
    else that does not read as a field name is descended into rather than counted.
    """
    if isinstance(value, list):
        for item in value:
            yield from members(item)
    elif isinstance(value, dict):
        for name, inner in value.items():
            if re.fullmatch(r"[a-z][a-z_]+", name):
                yield name
            else:
                yield from members(inner)


def recipe_fields(kind, body):
    """A recipe body's fields, each qualified by the recipe kind that carries it."""
    names = set()
    for name, value in body.items():
        names.add(f"{kind}.{name}")
        names.update(f"{kind}.{name}.{sub}" for sub in members(value))
    return names


def kind_of(recipe_type):
    return recipe_type.removeprefix("minecraft:recipe_")


def recipe_documentation(samples):
    """The recipe kinds and parameter names the documentation module publishes.

    Each section names its kind only inside its own example, so the type is read back out of that
    rather than guessed from the section title.
    """
    path = samples / "metadata/doc_modules/recipes.json"
    if not path.is_file():
        return set(), set()

    kinds = set()
    fields = set()
    for section in json.loads(text(path))["nodes"]:
        for node in section.get("nodes") or []:
            example = " ".join(line for one in node.get("examples") or []
                               for line in one.get("text") or [])
            found = re.findall(r"minecraft:recipe_[a-z_]+", example)
            if not found:
                continue
            kind = kind_of(found[0])
            kinds.add(found[0])
            for child in node.get("nodes") or []:
                if child.get("name") == "Parameters":
                    fields.update(f"{kind}.{one['name']}" for one in child.get("nodes") or []
                                  if one.get("name"))
    return kinds, fields


def published_recipes(samples):
    types, fields = recipe_documentation(samples)
    for recipe in documents(samples, "recipes"):
        for name, body in recipe.items():
            if not name.startswith("minecraft:recipe_") or not isinstance(body, dict):
                continue
            types.add(name)
            fields |= recipe_fields(kind_of(name), body)
    return types, fields


def trade_schema_fields(samples):
    """The Trade, TradeTier, TradeGroup and TradeItem schema properties, by the level each sits at."""
    levels = {"TradeTier": "tier", "TradeGroup": "group", "Trade": "trade", "TradeItem": "item"}
    fields = set()
    for path in (samples / "metadata/json_schemas/client_server/entity").rglob("*.json"):
        schema = json.loads(text(path))
        level = levels.get(schema.get("title"))
        if level:
            fields.update(f"{level}.{name}" for name in schema.get("properties", {}))
    return fields


def published_trades(samples):
    """Every field a vanilla trade table writes, qualified by the level it sits at."""
    fields = trade_schema_fields(samples)
    functions = set()

    for table in documents(samples, "trading"):
        fields.update(f"table.{name}" for name in table)
        for tier in table.get("tiers") or []:
            fields.update(f"tier.{name}" for name in tier)
            trades = list(tier.get("trades") or [])
            for group in tier.get("groups") or []:
                fields.update(f"group.{name}" for name in group)
                trades += list(group.get("trades") or [])
            for trade in trades:
                fields.update(f"trade.{name}" for name in trade)
                for side in ("wants", "gives"):
                    for entry in trade.get(side) or []:
                        fields.update(f"item.{name}" for name in entry)
                        functions.update(labelled(entry.get("functions") or [], "function"))
    return fields, functions


# --- the mod's side ------------------------------------------------------------------------------

def surfaces(samples):
    recipe_types, recipe_field_names = published_recipes(samples)
    trade_field_names, trade_functions = published_trades(samples)

    return [
        Surface("loot table functions", published_loot(samples, "function"),
                case_labels(BEHAVIOUR / "LootTableDefinition.java", "JsonObject function("),
                "the vanilla loot tables"),
        Surface("loot table conditions", published_loot(samples, "condition"),
                case_labels(BEHAVIOUR / "LootTableDefinition.java", "JsonObject condition("),
                "the vanilla loot tables"),
        Surface("recipe types", recipe_types,
                literal_set(BEHAVIOUR / "RecipeDefinition.java", "CONVERTED = Set.of"),
                "the recipe docs and the vanilla recipes"),
        Surface("recipe fields", recipe_field_names,
                literal_set(BEHAVIOUR / "RecipeDefinition.java", "HANDLES = Set.of"),
                "the recipe docs and the vanilla recipes"),
        Surface("trade table fields", trade_field_names,
                literal_set(BEHAVIOUR / "TradeTable.java", "HANDLES = Set.of"),
                "the trade schemas and the vanilla trade tables"),
        Surface("trade item functions", trade_functions,
                case_labels(BEHAVIOUR / "TradeTable.java", "Built applyFunction("),
                "the vanilla trade tables"),
    ]


def unbacked_claims():
    """Declared field names whose own literal appears nowhere else in the source.

    A qualified name such as `trade.max_uses` is a claim that something reads `max_uses`. With that
    literal in no other file, the claim has nothing behind it.
    """
    declarations = {
        "recipe fields": (BEHAVIOUR / "RecipeDefinition.java", "HANDLES = Set.of"),
        "trade table fields": (BEHAVIOUR / "TradeTable.java", "HANDLES = Set.of"),
    }
    sources = {path: text(path) for path in JAVA.rglob("*.java")}

    unbacked = {}
    for kind, (path, marker) in declarations.items():
        for name in literal_set(path, marker):
            field = name.rsplit(".", 1)[-1]
            if not any(f'"{field}"' in body for body in sources.values()):
                unbacked.setdefault(kind, set()).add(name)
    return unbacked


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while anything is missing")
    arguments = parser.parse_args()

    if not arguments.samples.is_dir():
        print(f"No bedrock-samples checkout at {arguments.samples}. Fetch it with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = surfaces(arguments.samples)
    print_table(found, "Coverage of Bedrock's loot, recipe and trade vocabulary")
    for surface in found:
        print_names(f"Missing, {surface.title}", surface.missing)
        print_names(f"Read by the mod and used by no vanilla file, {surface.title}",
                    surface.unpublished)
    for kind, names in sorted(unbacked_claims().items()):
        print_names(f"Declared as handled with no reader of their own, {kind}", names)

    return 1 if arguments.strict and any(surface.missing for surface in found) else 0


if __name__ == "__main__":
    sys.exit(main())
