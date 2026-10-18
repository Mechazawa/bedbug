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
names, and to every component whose body is handed to a type of its own, so a field this census calls
unread is one no reader mentions anywhere.

`--strict` fails on a field eight or more of Mojang's entities declare that has neither a reader nor
a reason in `REASONS`. Everything with fewer declarations is counted and can be listed, because that
tail is a reading list rather than a regression.

    python3 tools/census/fields.py                     # the table and the gated gaps
    python3 tools/census/fields.py --strict            # non-zero while a gated field has no reader
    python3 tools/census/fields.py --min-declarations 1  # and the whole tail below the gate
    python3 tools/census/fields.py --component minecraft:teleport
"""

import argparse
import collections
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "scratchpad" / "bedrock" / "bedrock-samples"
SOURCES = ROOT / "src" / "main" / "java"
IMPLEMENTED_LIST = SOURCES / "at" / "ioexception" / "mcaddon" / "registry" / "AddonEntities.java"

INDEX = "Entity component definitions.json"

# How many of Mojang's own entities have to declare a field before `--strict` gates on it.
#
# Every field with a declaration behind it is reported whatever this is set to; what it decides is
# which of them fails the build. Eight is where the census's own argument sits: a field a handful of
# entities declare is worth acting on, and the long tail of fields one entity declares is a reading
# list rather than a regression. `--min-declarations 1` gates on the whole tail.
GATE = 8

# Reasons several navigation components share.
DOOR_ANNOTATION = ("Not in the published schema. Mojang writes it beside `minecraft:annotation.open_door`, "
                   "and that component is what decides whether the pathfinder plans through a closed door.")
AMPHIBIOUS = ("Walking the bottom under water: a Java mob that may not float sinks and walks it, and water is "
              "priced at nothing unless `avoid_water` says otherwise, so nothing is left to set.")
FROM_AIR = "Java's flying navigator starts a path from wherever the entity is, the air included."

TEMPT_SOUND = ("Mojang's documentation counts the gap between tempt sounds in ticks, and its cat writes "
               "[0, 100] where its strider writes 2 to 5, which no one unit fits; the sound is left unmade "
               "rather than made at one of the two rates.")

BOBBING = ("The shape of the rise and fall on the surface. The lift above gives a floating entity Java's own "
           "bob, and nothing in Java lets an entity choose another.")

SLEEPING_BOX = ("From 1.21 Java's `LivingEntity.getDimensions` is final and answers a fixed box of a fifth of a "
                "block for the sleeping pose, which is the pose a sleeping entity takes here, so a box of a "
                "pack's own has nowhere to go.")

# A field a reader may skip, and why. Keyed by component, then by field; the key "*" covers every
# field of that component that no reader names.
REASONS = {
    # Components whose whole body describes something Java holds nowhere.
    "minecraft:breathable": {
        "suffocate_time": "Documented as seconds between suffocation damage with a default of -20, which no "
                          "count of seconds can be, and written as 0 or -1 by every vanilla entity. Java's "
                          "own two damage a second once the air is gone is what applies.",
        "generates_bubbles": "The bubbles a Bedrock client draws around a mob breathing under water, "
                             "which Java draws for no mob.",
        "totalSupply": "An older spelling the schemas no longer describe; the default stands in for it.",
        "suffocateTime": "An older spelling the schemas no longer describe; the default stands in for it.",
    },
    "minecraft:conditional_bandwidth_optimization": {
        "*": "How often Bedrock's own server resends an entity, which Java decides for itself.",
    },
    "minecraft:physics": {
        "has_gravity": "Java can switch gravity off per entity, but Mojang's fish declare it off and still "
                       "fall back when they leap out of water, so the engine does not apply it as written; "
                       "a mob that flies is held up by its move control either way.",
        "has_collision": "Java can switch collision off per entity, as its own vex does every tick, but every "
                         "vanilla mob declaring it also declares the gravity left alone above, and one without "
                         "the other falls through the floor.",
        "push_towards_closest_space": "The one vanilla entity declaring it is the player, which this mod "
                                      "applies nothing to.",
    },
    "minecraft:push_through": {"*": "A crowd spacing per entity, which Java holds nowhere."},
    "minecraft:can_climb": {"*": "Carries no fields; declaring it is the whole statement."},
    "minecraft:inventory": {
        "container_type": "The screen a player opens onto the entity's slots, which Java builds for its own "
                          "horses, chest minecarts and hoppers by class. The entity holds the slots here and "
                          "has no screen onto them, so a player cannot open them.",
        "can_be_siphoned_from": "A Java hopper draws from an entity that is a container, which only Java's "
                                "own minecarts and boats are.",
        "additional_slots_per_strength": "Only Mojang's llama writes it, beside an `inventory_size`, and the "
                                         "documentation leaves open how the two add up; its "
                                         "`minecraft:strength` sizes a container alone at three slots a point.",
    },
    "minecraft:equipment": {
        "*": "Names a loot table Java rolls for a mob's gear at spawn, which the loot reader covers.",
    },
    "minecraft:dweller": {
        "*": "Village membership. Java's point of interest manager would let any mob claim a bed or a job "
             "site, but the village those claims make up, its gossip, its golems and its raids, is kept by "
             "Java's villager brain, which a pack's entity has none of; the restriction radius is read.",
    },
    "minecraft:offspring": {
        "blend_attributes": "Mojang's documentation names no attribute and no rule this blends by; the "
                            "attributes `parent_centric_attribute_blending` lists are blended, by Java's "
                            "own horse breeding.",
    },
    "minecraft:preferred_path": {
        "*": "Per-block path costs. Java's walk node evaluator prices each node by the path type it "
             "classes the block as and keeps no price per block, so the prices need an evaluator of this "
             "mod's own, which is not written; `max_fall_blocks` is read.",
    },

    # Fields read somewhere other than through a field helper.
    "minecraft:type_family": {"family": "Read where the families are collected."},
    "minecraft:behavior.random_look_around": {
        "look_distance": "Java's random look aims at a point one block away at the eye's own height, so the "
                         "distance along that bearing changes no angle the head turns through.",
    },
    "minecraft:rail_sensor": {
        "tick_command_block_on_activate": "Java's `activateMinecart` is overridden by `MinecartCommandBlock` "
                                          "alone, and a pack's entity is not one, so it has no command block "
                                          "to start ticking.",
        "tick_command_block_on_deactivate": "Java's `activateMinecart` is overridden by `MinecartCommandBlock` "
                                            "alone, and a pack's entity is not one, so it has no command block "
                                            "to stop ticking.",
    },
    "minecraft:player.exhaustion": {
        "*": "Read by `PlayerDefinition.Attribute`, which takes `value` and `max` of the five player "
             "attributes from one helper. `value` is written into `FoodData`'s exhaustion at join and "
             "respawn and `max` holds it under the ceiling.",
    },
    "minecraft:player.level": {
        "*": "Read by `PlayerDefinition.Attribute`: `value` sets `Player.experienceLevel` and `max` holds "
             "the level under the ceiling, which Java does not state.",
    },
    "minecraft:player.experience": {
        "*": "Read by `PlayerDefinition.Attribute`: `value` sets `Player.experienceProgress` and `max` "
             "holds it under the ceiling.",
    },
    "minecraft:exhaustion_values": {
        "*": "Read by `PlayerDefinition.Action`, whose key is the field name. Each price scales the "
             "amount Java passes to `Player.causeFoodExhaustion` at that call site.",
    },
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

    # Navigation. Java asks its node evaluator about doors and floating and the mob what each kind of
    # ground costs; what is left here is what it has no question for.
    "minecraft:navigation.walk": {
        "using_door_annotation": DOOR_ANNOTATION,
        "is_amphibious": AMPHIBIOUS,
        "avoid_portals": "Java's pathfinder classes a portal block as open ground, so there is no kind of "
                         "ground to price it by.",
        "blocks_to_avoid": "Java prices a step by the kind of ground it is rather than by the block, so a "
                           "list of blocks would take a node evaluator of this mod's own.",
    },
    "minecraft:navigation.generic": {
        "using_door_annotation": DOOR_ANNOTATION,
        "is_amphibious": AMPHIBIOUS,
        "can_jump": "Java's ground navigator plans every step up a block as a jump and has no switch for "
                    "a mob that may not; the one vanilla entity saying no is a dolphin, which swims.",
    },
    "minecraft:navigation.float": {"using_door_annotation": DOOR_ANNOTATION},
    "minecraft:navigation.hover": {"can_path_from_air": FROM_AIR},
    "minecraft:navigation.fly": {"can_path_from_air": FROM_AIR},

    # Movement styles.
    "minecraft:movement.sway": {
        "*": "The side to side sway of a swimming fish, which is how its body is drawn moving rather than "
             "where it goes; Java's swimming move control steers it straight.",
    },

    "minecraft:buoyant": {
        "apply_gravity": "The lift is added on top of the gravity Java applies in a liquid, so an entity "
                         "saying no to gravity still has it; every vanilla mob declaring this says yes.",
        "movement_type": BOBBING,
        "big_wave_probability": BOBBING,
        "big_wave_speed": BOBBING,
        "can_auto_step_from_liquid": "Java lets a mob pushing against the bank climb out of water already.",
        "drag_down_on_buoyancy_removed": "A boat's sinking when it stops floating; the two vanilla entities "
                                         "writing it are Mojang's boats.",
    },

    # Goals. Java's goal of the same name takes fewer parameters than Bedrock states.
    "minecraft:behavior.mount_pathing": {
        "*": "A mob riding another steers it in Java: the rider plans with the mount's navigator and the "
             "mount's own movement goals are switched off while it is ridden, so the chase and the "
             "distance kept belong to the rider's attack goal.",
    },
    "minecraft:behavior.share_items": {"*": "Java's stroll goal has no arrival radius of its own."},
    "minecraft:behavior.sleep": {
        "sleep_collider_width": SLEEPING_BOX,
        "sleep_collider_height": SLEEPING_BOX,
        "sleep_y_offset": SLEEPING_BOX,
    },
    "minecraft:behavior.float": {
        "*": "Java's float goal swims a mob up with no chance of its own and no passengers to drop.",
    },
    "minecraft:behavior.tempt": {
        "tempt_sound": TEMPT_SOUND,
        "sound_interval": TEMPT_SOUND,
    },
    "minecraft:behavior.avoid_mob_type": {
        "*": "Java's avoid goal flees whatever it is given at the speed it is given, with no sound, no "
             "probability and no event when it gets away.",
    },
    "minecraft:behavior.follow_owner": {
        "ignore_vibration": "Whether a vibration interrupts the following. Only the allay says it does, and "
                            "its note block goals sit above this one in priority, which is what interrupts it.",
    },
    "minecraft:behavior.drink_milk": {"filters": "Drinking clears what is harmful whenever there is any."},
    "minecraft:behavior.drink_potion": {
        "speed_modifier": "The potion's effects arrive the moment it is chosen, so there is no time spent "
                          "drinking for the entity to be slowed through.",

        "priority": "Drinking runs beside the entity's goals on the sensor pass rather than as one of "
                    "them, so it has no place among them to take.",
    },
    "minecraft:behavior.trade_interest": {
        "*": "Java's merchant screen opens on a click rather than after a mob has shown interest.",
    },
    "minecraft:behavior.trade_with_player": {"filters": "Java's merchant screen opens for whoever clicks."},

    # The rest, one component at a time.
    "minecraft:home": {
        "home_block_list": "Bedrock checks the list against the block an event set the home at; the home here "
                           "is where the entity stood as the component arrived, which is no such block, so "
                           "the list has nothing to be checked against.",
    },
    "minecraft:rideable": {
        "interact_text": "Java shows no prompt of its own for using a mob.",
    },
    "minecraft:is_dyeable": {"interact_text": "Java shows no prompt of its own for using a mob."},
    "minecraft:tamemount": {
        "feed_text": "Java shows no prompt of its own for using a mob.",
        "ride_text": "Java shows no prompt of its own for using a mob.",
    },
}

# The shapes a field name reaches the JSON through. Each captures the field it asks for.
READS = [
    re.compile(r"\.has\(\s*\"([^\"]+)\"\s*\)"),
    re.compile(r"\.get\(\s*\"([^\"]+)\"\s*\)"),
    re.compile(r"\.getAsJson(?:Object|Array|Primitive)\(\s*\"([^\"]+)\"\s*\)"),
    # A helper handed the object and then the field, with the component's own name between the two
    # when the caller is a switch over component names.
    re.compile(r"\b\w+\(\s*[A-Za-z_][\w.]*\s*(?:,\s*[A-Za-z_][\w.]*\s*)?,\s*\"([^\"]+)\""),
]

# A helper handed the component's own name and then the field inside it, which pins the pair exactly.
PAIR = re.compile(r"\"(minecraft:[a-z0-9_.]+)\"\s*\)?\s*,\s*\"([A-Za-z0-9_]+)\"")

# A type built out of one component's body, which is how this mod keeps a component's own reading
# together. Every field that type's own file asks for belongs to the component the caller named.
TYPE = re.compile(r"\b([A-Z]\w+)\s*[.(]")

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


def direct(text: str) -> set[str]:
    """The field names one piece of source asks a JSON object for."""
    return {
        field
        for pattern in READS
        for field in pattern.findall(text)
        if not field.startswith("minecraft:") and re.fullmatch(r"[A-Za-z0-9_]+", field)
    }


def harvest(text: str, methods: dict[str, list[str]], seen: set[str], depth: int = 0,
            types: dict[str, set[str]] | None = None) -> set[str]:
    """The fields a piece of a reader asks for, following the helpers it calls to get them.

    A reader hands the object to a helper as often as it reads a field itself, so a helper called
    from the reader counts as part of it. Helpers anywhere in the mod are followed, because the ones
    that turn a component into a value live in their own files.
    """
    fields = direct(text)
    if depth == 0 and types:
        for named in set(TYPE.findall(text)) & set(types):
            fields.update(types[named])

    if depth >= HELPER_DEPTH:
        return fields

    for called in set(CALL.findall(text)) & set(methods):
        if called in seen:
            continue
        seen.add(called)
        for body in methods[called]:
            fields.update(harvest(body, methods, seen, depth + 1))
    return fields


def reads(source: str, methods: dict[str, list[str]],
          types: dict[str, set[str]]) -> dict[str, set[str]]:
    """The field names each component's reader takes out of its JSON object."""
    taken: dict[str, set[str]] = collections.defaultdict(set)

    for overloads in bodies(source).values():
        for body in overloads:
            for component, field in PAIR.findall(body):
                taken[component].add(field)

            for labels, text in segments(body):
                # An arm naming another component in its body is reading that one too: a goal is
                # often built out of the plain component that sits beside it.
                named = set(COMPONENT.findall(labels)) | set(COMPONENT.findall(text))
                if not named:
                    continue

                fields = harvest(text, methods, set(), 0, types)
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
    parser.add_argument("--min-declarations", type=int, default=GATE,
                        help="how many of Mojang's entities must declare a field for it to be gated"
                             " (default: %(default)s; 1 lists every field with a declaration)")
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

    types = {path.stem: direct(source) for path, source in java.items()}

    taken: dict[str, set[str]] = collections.defaultdict(set)
    for source in java.values():
        for component, fields in reads(source, methods, types).items():
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
            # Every field with a declaration behind it is collected; the gate decides which of them
            # fails, so the tail below it can still be counted and named.
            if field in taken.get(component, set()) or uses < 1:
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
    gated = [gap for gap in open_gaps if gap[0] >= arguments.min_declarations]
    print(f"{len(gated)} fields declared by {arguments.min_declarations} or more of Mojang's "
          f"entities have no reader and no recorded reason; {len(excused)} carry one.")

    for uses, component, field, _, _ in sorted(gated, reverse=True):
        print(f"  x{uses:<4} {component} {field}")

    tail = len(open_gaps) - len(gated)
    if tail:
        print(f"  and {tail} more below the gate, listed by --min-declarations 1.")

    return 1 if arguments.strict and gated else 0


if __name__ == "__main__":
    raise SystemExit(main())
