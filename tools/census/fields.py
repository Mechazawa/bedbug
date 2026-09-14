#!/usr/bin/env python3
"""Counts the fields inside each entity component, rather than the components themselves.

Every other census here counts names: which component, which query, which command a pack may write
and this mod knows about. A name is a coarse measure. A component whose name is handled and whose
body is never opened reads as implemented and does nothing, and so does a component whose one field
is read in the wrong unit.

So this one counts the inside of the component. The published side is the field list in Mojang's own
JSON schemas under `metadata/json_schemas/server/entity`, together with how many of Mojang's own
entities declare each field, which is what says whether a gap matters. The mod's side is the field
names that reach a `has`, a `get` or one of the JSON helpers inside the reader that names the
component.

The mod's side is an upper bound: a read is credited to every component its switch arm or its method
names, so a field this census calls unread is one no reader mentions anywhere.

    python3 tools/census/fields.py                  # the whole table
    python3 tools/census/fields.py --strict         # non-zero while a used field has no reader
    python3 tools/census/fields.py --component minecraft:teleport
"""

import argparse
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "scratchpad" / "bedrock" / "bedrock-samples"
SOURCES = ROOT / "src" / "main" / "java"
IMPLEMENTED_LIST = SOURCES / "at" / "ioexception" / "mcaddon" / "registry" / "AddonEntities.java"

INDEX = "Entity component definitions.json"

# A field a reader may skip, and why. Keyed by component, then by field; the key "*" covers every
# field of that component that no reader names.
REASONS = {
    # Components whose whole body describes something Java holds nowhere.
    "minecraft:breathable": {
        "*": "Java drowns a mob on a timer of its own with no way to restate it per entity.",
    },
    "minecraft:conditional_bandwidth_optimization": {
        "*": "How often Bedrock's own server resends an entity, which Java decides for itself.",
    },
    "minecraft:physics": {
        "*": "Java applies gravity and collision to every mob, so asking for them asks for what is there.",
    },
    "minecraft:pushable": {"*": "Java pushes every mob apart and offers no per-entity switch."},
    "minecraft:pushable_by_entity": {"*": "Java pushes every mob apart and offers no per-entity switch."},
    "minecraft:push_through": {"*": "A crowd spacing per entity, which Java holds nowhere."},
    "minecraft:nameable": {"*": "Java lets a name tag name any mob, which is what the component asks for."},
    "minecraft:can_climb": {"*": "Carries no fields; declaring it is the whole statement."},
    "minecraft:inventory": {
        "*": "Bedrock gives a mob a container of its own; a Java mob has hands and armour and no chest.",
    },
    "minecraft:equipment": {
        "*": "Names a loot table Java rolls for a mob's gear at spawn, which the loot reader covers.",
    },
    "minecraft:dweller": {
        "*": "Village membership: the points of interest, the professions and the migration are written "
             "into Java's own villager brain and cannot be given to another mob.",
    },
    "minecraft:offspring": {
        "*": "Inheritance between two parents. Java's own animals breed by class, so a child has no "
             "attributes, colours or variants to blend and nothing to mutate them with.",
    },
    "minecraft:preferred_path": {
        "*": "Per-block path costs. Java's navigator prices a route by path type rather than by block, "
             "so a list of blocks and their prices has nothing to be applied to.",
    },

    # Fields read somewhere other than through a field helper.
    "minecraft:type_family": {"family": "Read where the families are collected."},
    "minecraft:loot": {"table": "Read by the loot table converter, which files the table under this path."},
    "minecraft:trade_table": {
        "table": "Read where the trades are loaded, while the pack is still open.",
        "*": "Bedrock's own trading screen: Java's merchant screen has no display name of its own and "
             "no economy to convert the trades into.",
    },
    "minecraft:economy_trade_table": {
        "table": "Read where the trades are loaded, while the pack is still open.",
        "*": "The cure discounts and the persistence belong to Bedrock's villager economy, which Java's "
             "merchant screen has no counterpart for.",
    },

    # Attributes. Bedrock states a value and a ceiling; Java's attributes carry a fixed ceiling of
    # their own that a mob cannot raise.
    "minecraft:follow_range": {"max": "Java fixes the attribute's own ceiling."},
    "minecraft:knockback_resistance": {"max": "Java fixes the attribute's own ceiling."},
    "minecraft:movement": {"max": "Java fixes the attribute's own ceiling."},
    "minecraft:jump.static": {"jump_power": "Java jumps a mob at a fixed height with no attribute to set."},

    # Navigation. Java asks its navigator a smaller set of questions than Bedrock states.
    "minecraft:navigation.walk": {
        "*": "Java's navigator already refuses a damaging block, a portal and lava for every mob, and "
             "asks nothing about breaking doors or sinking.",
    },
    "minecraft:navigation.generic": {
        "*": "Java's navigator asks nothing about breaching, jumping or sinking.",
    },
    "minecraft:navigation.float": {"*": "Java's navigator already refuses a damaging block for every mob."},
    "minecraft:navigation.hover": {
        "*": "Java's flying navigator asks nothing about sinking or damaging blocks.",
    },
    "minecraft:navigation.fly": {"*": "Java's flying navigator starts a path from the air already."},

    # Movement styles. Java moves a mob through one controller with no turn rate or sway of its own.
    "minecraft:movement.basic": {"*": "Java's move control turns a mob at a rate it fixes itself."},
    "minecraft:movement.amphibious": {"*": "Java's move control turns a mob at a rate it fixes itself."},
    "minecraft:movement.sway": {"*": "Java has no swimming sway to give an arbitrary mob."},
    "minecraft:movement.jump": {"*": "Java's jump control has no delay of its own."},

    # Goals. Java's goal of the same name takes fewer parameters than Bedrock states.
    "minecraft:behavior.random_stroll": {"*": "Java's stroll goal picks its own distance."},
    "minecraft:behavior.explore_outskirts": {"*": "Java's stroll goal picks its own distance and wait."},
    "minecraft:behavior.stroll_towards_village": {"*": "Java's stroll goal has no village to aim at."},
    "minecraft:behavior.move_indoors": {"*": "Java's goal has no cooldown of its own."},
    "minecraft:behavior.move_outdoors": {"*": "Java's goal has no cooldown of its own."},
    "minecraft:behavior.mount_pathing": {"*": "Java's stroll goal has no target to path to."},
    "minecraft:behavior.share_items": {"*": "Java's stroll goal has no arrival radius of its own."},
    "minecraft:behavior.play": {"*": "Java writes playing into its own villager, with no friends to name."},
    "minecraft:behavior.hide": {
        "*": "Java writes hiding into its own villager, with no point of interest to run to.",
    },
    "minecraft:behavior.sleep": {
        "*": "Sleeping is a pose here rather than a bed, so there is no collider to resize and no offset "
             "to lie at.",
    },
    "minecraft:behavior.float": {
        "*": "Java's float goal swims a mob up with no chance of its own and no passengers to drop.",
    },
    "minecraft:behavior.panic": {
        "*": "Java's panic goal runs from whatever hurt the mob, with no sound, no damage list and no "
             "preference for water.",
    },
    "minecraft:behavior.look_at_player": {
        "angle_of_view_horizontal": "Java's look goal has no field of view.",
        "angle_of_view_vertical": "Java's look goal has no field of view.",
        "look_time": "Java's look goal picks how long it looks.",
    },
    "minecraft:behavior.look_at_trading_player": {"look_time": "Java's look goal picks how long it looks."},
    "minecraft:behavior.tempt": {
        "*": "Java's tempt goal takes the items and the speed and decides the rest: it has no sound, no "
             "vertical reach and no fright.",
    },
    "minecraft:behavior.melee_attack": {
        "reach_multiplier": "Java reaches as far as the mob's own size allows.",
    },
    "minecraft:behavior.melee_box_attack": {
        "*": "Java's melee goal swings on its own cooldown inside its own reach, and raises no event of "
             "its own when it lands or kills.",
    },
    "minecraft:behavior.nearest_attackable_target": {
        "*": "Java's target goal rescans on the interval it is given and keeps a target until it is "
             "invalid, with no probability, no memory of sight and no search height.",
    },
    "minecraft:behavior.hurt_by_target": {
        "entity_types": "Java's goal turns on whatever hurt the mob rather than on a list.",
        "hurt_owner": "A tamed mob's owner, which an add-on entity has none of.",
    },
    "minecraft:behavior.avoid_mob_type": {
        "*": "Java's avoid goal flees whatever it is given at the speed it is given, with no sound, no "
             "probability and no event when it gets away.",
    },
    "minecraft:behavior.avoid_block": {
        "*": "Java has no goal that flees a block, so what carries across is the fleeing.",
    },
    "minecraft:behavior.follow_owner": {"*": "A tamed mob's owner, which an add-on entity has none of."},
    "minecraft:behavior.pickup_items": {
        "*": "Picking up happens on this entity's own clock rather than through a path, so there is no "
             "arrival radius, no search height and no cooldown to honour.",
    },
    "minecraft:behavior.drink_milk": {"filters": "Drinking clears what is harmful whenever there is any."},
    "minecraft:behavior.drink_potion": {
        "*": "Drinking clears what is harmful rather than applying a potion Java would have to hold.",
    },
    "minecraft:behavior.trade_interest": {
        "*": "Java's merchant screen opens on a click rather than after a mob has shown interest.",
    },
    "minecraft:behavior.trade_with_player": {"filters": "Java's merchant screen opens for whoever clicks."},

    # The rest, one component at a time.
    "minecraft:angry": {
        "*": "The anger spreads to the same definition within the range, which is what a pack means by "
             "it. Sounds, the finer broadcast rules and the filters have no Java counterpart.",
    },
    "minecraft:breedable": {
        "*": "Taming, sitting and pregnancy are states Java writes into its own animals, and an add-on "
             "entity is none of them.",
    },
    "minecraft:home": {
        "restriction_type": "Java holds a home as a position and a radius, with no kind.",
        "home_block_list": "Java's home is a position rather than a block to stand on.",
    },
    "minecraft:leashable": {"presets": "Java pulls a leashed mob at a distance it fixes itself."},
    "minecraft:rideable": {
        "*": "The rider sits at the seat the pack gave and gets off where Java puts it: there is no "
             "dismount rule, no prompt text and no pulling riders in.",
    },
    "minecraft:shareables": {"all_items_max_amount": "A Java mob holds one stack in its hand."},
    "minecraft:is_dyeable": {"interact_text": "Java shows no prompt of its own for using a mob."},
    "minecraft:teleport": {
        "*": "Teleporting towards a target is a goal rather than a component here, and the entity has "
             "no target to chase.",
    },
    "minecraft:transformation": {
        "*": "The change happens at once, so there is no delay to wait out and no sound to play on the "
             "way in or out of it.",
    },
}

# The shapes a field name reaches the JSON through. Each captures the field it asks for.
READS = [
    re.compile(r"\.has\(\s*\"([^\"]+)\"\s*\)"),
    re.compile(r"\.get\(\s*\"([^\"]+)\"\s*\)"),
    re.compile(r"\.getAsJson(?:Object|Array|Primitive)\(\s*\"([^\"]+)\"\s*\)"),
    re.compile(r"\b\w+\(\s*[A-Za-z_][\w.]*\s*,\s*\"([^\"]+)\""),
]

# A helper handed the component's own name and then the field inside it, which pins the pair exactly.
PAIR = re.compile(r"\"(minecraft:[a-z0-9_.]+)\"\s*,\s*\"([A-Za-z0-9_]+)\"")

COMPONENT = re.compile(r"\"(minecraft:[a-z0-9_.]+)\"")
METHOD = re.compile(
    r"^[ \t]*(?:(?:public|private|protected|static|final|abstract|synchronized|default)\s+)+"
    r"[\w.<>,\[\]?\s]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w.,\s]+)?\{",
    re.M,
)
CALL = re.compile(r"(\w+)\s*\(")

# How far a reader is followed into the helpers it calls. Two steps reach the value types that turn
# a component into numbers; more than that reaches the whole mod through one chain of calls.
HELPER_DEPTH = 2
ARM = re.compile(r"\b(?:case\b([^;{}]*?)|default\s*)->")


def lenient(text: str) -> str:
    """Mojang's own JSON carries line comments and trailing commas, which `json` refuses."""
    out = []
    index = 0
    inside = False
    while index < len(text):
        letter = text[index]
        if inside:
            out.append(letter)
            if letter == "\\":
                index += 1
                if index < len(text):
                    out.append(text[index])
            elif letter == '"':
                inside = False
            index += 1
            continue
        if letter == '"':
            inside = True
            out.append(letter)
        elif text.startswith("//", index):
            index = text.find("\n", index)
            if index < 0:
                break
            continue
        elif text.startswith("/*", index):
            index = text.find("*/", index)
            if index < 0:
                break
            index += 2
            continue
        else:
            out.append(letter)
        index += 1
    joined = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", joined)


def read_json(path: pathlib.Path) -> dict:
    raw = path.read_text(encoding="utf-8-sig")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return json.loads(lenient(raw))


def published(samples: pathlib.Path) -> tuple[dict[str, set[str]], set[str]]:
    """Every field each component's schema declares, and the components that have a schema at all.

    Mojang files a schema per format version and leaves the ones it did not touch in the older
    directory, so the index of each version is walked and the field lists are merged.
    """
    fields: dict[str, set[str]] = collections.defaultdict(set)
    described: set[str] = set()

    root = samples / "metadata" / "json_schemas" / "server" / "entity"
    for index in sorted(root.glob("*/" + INDEX)):
        for name, body in read_json(index).get("properties", {}).items():
            fields.setdefault(name, set())
            reference = body.get("$ref") if isinstance(body, dict) else None
            if not reference:
                continue

            target = (index.parent / reference).resolve()
            if not target.is_file():
                continue
            described.add(name)
            fields[name].update(read_json(target).get("properties", {}).keys())
    return fields, described


def declared(samples: pathlib.Path) -> tuple[dict[tuple[str, str], int], dict[str, int]]:
    """How many of Mojang's own entities declare each field, and each component.

    A component group counts as a declaration: a pack switches those on at runtime and what is
    inside them is as real as what sits at the top level. An entity declaring the same field in two
    of its groups still counts once, because the measure is how many entities would notice.
    """
    per_field: collections.Counter = collections.Counter()
    per_component: collections.Counter = collections.Counter()

    for path in sorted((samples / "behavior_pack" / "entities").glob("*.json")):
        body = read_json(path).get("minecraft:entity")
        if not isinstance(body, dict):
            continue

        bodies = [body.get("components")]
        groups = body.get("component_groups")
        if isinstance(groups, dict):
            bodies.extend(groups.values())

        seen: set[tuple[str, str]] = set()
        components: set[str] = set()
        for holder in bodies:
            if not isinstance(holder, dict):
                continue
            for name, value in holder.items():
                components.add(name)
                if isinstance(value, dict):
                    seen.update((name, field) for field in value)

        per_field.update(seen)
        per_component.update(components)
    return per_field, per_component


def uncommented(source: str) -> str:
    """Java with its comments blanked, so a Stonecutter branch cannot be read as live source."""
    out = []
    index = 0
    inside = None
    while index < len(source):
        letter = source[index]
        if inside:
            out.append(letter)
            if letter == "\\":
                index += 1
                if index < len(source):
                    out.append(source[index])
            elif letter == inside:
                inside = None
            index += 1
            continue
        if letter in "\"'":
            inside = letter
            out.append(letter)
        elif source.startswith("//", index):
            end = source.find("\n", index)
            end = len(source) if end < 0 else end
            out.append(" " * (end - index))
            index = end
            continue
        elif source.startswith("/*", index):
            end = source.find("*/", index)
            end = len(source) if end < 0 else end + 2
            out.append("".join(letter if letter == "\n" else " " for letter in source[index:end]))
            index = end
            continue
        else:
            out.append(letter)
        index += 1
    return "".join(out)


def bodies(source: str) -> dict[str, list[str]]:
    """Every method in a file, by name, which is the widest scope a field read may be credited to."""
    found: dict[str, list[str]] = collections.defaultdict(list)
    for match in METHOD.finditer(source):
        depth = 0
        for index in range(match.end() - 1, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    found[match.group(1)].append(source[match.end():index])
                    break
    return found


def segments(body: str) -> list[tuple[str, str]]:
    """A method body split into its switch arms, as the arm's labels and the arm's own text.

    A switch over component names is how this mod writes most of its readers, so an arm is the
    scope that says which component a read inside it belongs to. What sits before the first arm is
    the method's own text and carries the method's own labels.
    """
    arms = list(ARM.finditer(body))
    if not arms:
        return [(body, body)]

    pieces = [(body, body[: arms[0].start()])]
    for index, arm in enumerate(arms):
        end = arms[index + 1].start() if index + 1 < len(arms) else len(body)
        pieces.append((arm.group(1) or "", body[arm.end(): end]))
    return pieces


def harvest(text: str, methods: dict[str, list[str]], seen: set[str], depth: int = 0) -> set[str]:
    """The fields a piece of a reader asks for, following the helpers it calls to get them.

    A reader hands the object to a helper as often as it reads a field itself, so a helper called
    from the reader counts as part of it. Helpers anywhere in the mod are followed, because the ones
    that turn a component into a value live in their own files.
    """
    fields = {
        field
        for pattern in READS
        for field in pattern.findall(text)
        if not field.startswith("minecraft:") and re.fullmatch(r"[A-Za-z0-9_]+", field)
    }
    if depth >= HELPER_DEPTH:
        return fields

    for called in set(CALL.findall(text)) & set(methods):
        if called in seen:
            continue
        seen.add(called)
        for body in methods[called]:
            fields.update(harvest(body, methods, seen, depth + 1))
    return fields


def reads(source: str, methods: dict[str, list[str]]) -> dict[str, set[str]]:
    """The field names each component's reader takes out of its JSON object."""
    taken: dict[str, set[str]] = collections.defaultdict(set)

    for overloads in bodies(source).values():
        for body in overloads:
            for component, field in PAIR.findall(body):
                taken[component].add(field)

            for labels, text in segments(body):
                named = set(COMPONENT.findall(labels)) or set(COMPONENT.findall(text))
                if not named:
                    continue

                fields = harvest(text, methods, set())
                for component in named:
                    taken[component].update(fields)
    return taken


def implemented(path: pathlib.Path) -> set[str]:
    """The components the mod claims to act on, read from the list it reports against."""
    source = uncommented(path.read_text(encoding="utf-8"))
    start = source.find("IMPLEMENTED = Set.of(")
    if start < 0:
        return set()
    return set(COMPONENT.findall(source[start: source.find(");", start)]))


def reason(component: str, field: str) -> str | None:
    recorded = REASONS.get(component, {})
    return recorded.get(field) or recorded.get("*")


def main() -> int:
    parser = argparse.ArgumentParser(description="Counts the fields inside each entity component.")
    parser.add_argument("--samples", default=str(SAMPLES), help="the bedrock-samples checkout")
    parser.add_argument("--sources", default=str(SOURCES), help="the mod's Java sources")
    parser.add_argument("--component", help="report one component rather than the table")
    parser.add_argument("--min-declarations", type=int, default=1,
                        help="how many of Mojang's entities must declare a field for it to count")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while a used field has no reader and no reason")
    arguments = parser.parse_args()

    samples = pathlib.Path(arguments.samples)
    if not samples.is_dir():
        # A checkout is allowed to have no copy of Mojang's own pack, the way it is allowed to have
        # no add-on. There is then nothing to measure against and nothing to report.
        print(f"No bedrock-samples at {samples}: nothing to measure.")
        return 0

    schema, described = published(samples)
    per_field, per_component = declared(samples)
    sources = pathlib.Path(arguments.sources)
    java = {path: uncommented(path.read_text(encoding="utf-8"))
            for path in sorted(sources.rglob("*.java"))}

    methods: dict[str, list[str]] = collections.defaultdict(list)
    for source in java.values():
        for name, overloads in bodies(source).items():
            methods[name].extend(overloads)

    taken: dict[str, set[str]] = collections.defaultdict(set)
    for source in java.values():
        for component, fields in reads(source, methods).items():
            taken[component].update(fields)
    handled = implemented(next(sources.rglob("AddonEntities.java")))

    known = sorted(set(schema) | {name for name, _ in per_field} | set(per_component))
    gaps = []
    for component in known:
        if arguments.component and component != arguments.component:
            continue
        fields = schema.get(component, set()) | {
            field for name, field in per_field if name == component}
        for field in sorted(fields):
            uses = per_field.get((component, field), 0)
            if field in taken.get(component, set()) or uses < arguments.min_declarations:
                continue
            gaps.append((uses, component, field, component in handled, reason(component, field)))

    if arguments.component:
        fields = schema.get(arguments.component, set()) | {
            field for name, field in per_field if name == arguments.component}
        print(f"{arguments.component}: {len(fields)} published, "
              f"{len(taken.get(arguments.component, set()) & fields)} read, "
              f"{per_component.get(arguments.component, 0)} of Mojang's entities declare it")
        for field in sorted(fields):
            mark = "read" if field in taken.get(arguments.component, set()) else "----"
            print(f"  {mark}  {field} x{per_field.get((arguments.component, field), 0)}")
        return 0

    counted = [name for name in known if name in handled]
    fields_of = {name: schema.get(name, set()) | {field for held, field in per_field if held == name}
                 for name in counted}
    total = sum(len(fields) for fields in fields_of.values())
    read = sum(len(fields & taken.get(name, set())) for name, fields in fields_of.items())

    print(f"{len(known)} entity components published, {len(counted)} of them implemented.")
    print(f"{sum(len(schema[name]) for name in schema)} fields published across "
          f"{len(described)} components with a schema.")
    print(f"{read} of the {total} fields those {len(counted)} components publish reach a reader.")

    open_gaps = [gap for gap in gaps if gap[3] and gap[4] is None]
    excused = [gap for gap in gaps if gap[3] and gap[4] is not None]
    print(f"{len(open_gaps)} fields of an implemented component have no reader "
          f"and no recorded reason; {len(excused)} carry one.")

    for uses, component, field, _, _ in sorted(open_gaps, reverse=True):
        print(f"  x{uses:<4} {component} {field}")

    return 1 if arguments.strict and open_gaps else 0


if __name__ == "__main__":
    raise SystemExit(main())
