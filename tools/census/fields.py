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

SUFFOCATION = ("Mojang documents seconds between suffocation damage with a default of -20, and writes 0 on "
               "every vanilla mob and -1 on the player. The Minecraft Wiki's damage page names one drowning "
               "rate and no edition difference: a hit of 2 when the air reaches -20, which then goes back to "
               "0, and a mob's first hit sixteen seconds under water, where a floor of 0 would hit a pig at "
               "fifteen and then every tick. So the value sets no rate a pack can see, and Java's "
               "`LivingEntity.baseTick` hurts at that rate once `shouldTakeDrowningDamage` finds the air at -20.")

TOUCH_TEXT = ("Mojang documents it as the label of the interact button shown with touch-screen controls. "
              "Java has no touch-screen controls and no interact button, and Bedrock shows the label to no "
              "keyboard player either.")

# A field a reader may skip, and why. Keyed by component, then by field; the key "*" covers every
# field of that component that no reader names.
REASONS = {
    # Components whose whole body describes something Java holds nowhere.
    "minecraft:breathable": {
        "suffocate_time": SUFFOCATION,
        "suffocateTime": SUFFOCATION,
    },
    "minecraft:conditional_bandwidth_optimization": {
        "*": "Which position updates Bedrock's server may skip to save bandwidth, by distance and by filter, "
             "a saving Mojang describes as changing nothing a player sees. Java's `ServerEntity.sendChanges` "
             "sends on the type's own `updateInterval` and on every impulse, so the entity is kept in step "
             "either way.",
    },
    "minecraft:dweller": {
        "dwelling_type": "`village` is the one type published, and Java keeps a village as the points of "
                         "interest in `PoiManager`, which is where `Dwelling` takes its claims, so there "
                         "is no second kind of dwelling to choose.",
        "can_migrate": "Moving between villages. Java keeps no village an entity belongs to: "
                       "`ServerLevel.isVillage` asks only whether points of interest are near a place, so "
                       "there is no first village to stay in and no second one to move to.",
        "first_founding_reward": "Reputation a player earns as a village is first founded. Java founds no "
                                 "village, it has points of interest and no moment one starts, and keeps "
                                 "reputation per villager in `Villager.gossips` rather than per village.",
    },

    # Fields read somewhere other than through a field helper.
    "minecraft:behavior.random_look_around": {
        "look_distance": "Java's `RandomLookAroundGoal.tick` aims the look control at a point on the chosen "
                         "bearing at the eye's own height, and how far along that bearing the point lies "
                         "changes no angle the head turns through.",
    },
    "minecraft:rail_sensor": {
        "tick_command_block_on_activate": "Only an entity carrying a command block has one to tick: in Bedrock "
                                          "that is the engine's own command block minecart, whose command no "
                                          "pack can write, and in Java `MinecartCommandBlock.getCommandBlock`, "
                                          "which an `AddonEntity` has not got.",
        "tick_command_block_on_deactivate": "Only an entity carrying a command block has one to tick: in "
                                            "Bedrock that is the engine's own command block minecart, whose "
                                            "command no pack can write, and in Java "
                                            "`MinecartCommandBlock.getCommandBlock`, which an `AddonEntity` has "
                                            "not got.",
    },
    "minecraft:trade_table": {
        "new_screen": "Picks between Bedrock's two trading layouts. A merchant opens Java's one "
                      "`MenuType.MERCHANT` screen, `MerchantScreen`, so there is no second layout to pick.",
    },
    "minecraft:economy_trade_table": {
        "new_screen": "Picks between Bedrock's two trading layouts. A merchant opens Java's one "
                      "`MenuType.MERCHANT` screen, `MerchantScreen`, so there is no second layout to pick.",
    },

    # Attributes. Bedrock states a value and a ceiling; Java's attributes carry a fixed ceiling of
    # their own that a mob cannot raise.
    "minecraft:follow_range": {"max": "Java fixes the attribute's own ceiling."},
    "minecraft:knockback_resistance": {"max": "Java fixes the attribute's own ceiling."},
    "minecraft:movement": {"max": "Java fixes the attribute's own ceiling."},

    # Goals. Java's goal of the same name takes fewer parameters than Bedrock states.
    "minecraft:behavior.avoid_mob_type": {
        "river_speed_multiplier": "Documented only as the speed 'when following river', and the goal "
                                  "follows nothing: no vanilla entity states it and nothing says what "
                                  "a river is to a flight.",
    },
    "minecraft:behavior.trade_with_player": {"filters": "Java's merchant screen opens for whoever clicks."},
    "minecraft:behavior.swim_wander": {
        "look_ahead": "The wander is a path `WaterBoundPathNavigation` plans through the water to a spot "
                      "`BehaviorUtils.getRandomSwimmablePos` picks, around whatever is in the way, before "
                      "the entity moves, so there is no distance ahead to look for an obstacle.",
    },
    "minecraft:behavior.player_ride_tamed": {
        "priority": "Mojang's goal holds no controls by default, so its priority orders it against "
                    "nothing, and Java moves a player-ridden mount in LivingEntity.travelRidden rather "
                    "than through any goal, so what it would place has no effect here. The behaviour "
                    "is answered as the rule on who may get on.",
    },

    # The rest, one component at a time.
    "minecraft:rideable": {"interact_text": TOUCH_TEXT},
    "minecraft:is_dyeable": {"interact_text": TOUCH_TEXT},
    "minecraft:tamemount": {"feed_text": TOUCH_TEXT, "ride_text": TOUCH_TEXT},
    "minecraft:behavior.dragondeath": {
        "priority": "The long death runs in LivingEntity.tickDeath, and LivingEntity.aiStep skips "
                    "serverAiStep, where the goals run, while isImmobile answers isDeadOrDying, so no "
                    "goal is left for a priority to order against.",
    },
    "minecraft:projectile": {
        "is_dangerous": "Mojang's schema says it affects AI reactions and client behaviour and names none, and "
                        "no published goal or client field mentions it. In Java only `WitherSkull.getInertia`, "
                        "`WitherSkull.getBlockExplosionResistance` and `WitherSkullRenderer` read "
                        "`isDangerous`, and Mojang's dangerous skull states its inertia, its blast's "
                        "`max_resistance` and its texture in fields of their own.",
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
    r"(?:[\w.<>,\[\]?\s]+\s+)?(\w+)\s*\([^;{]*\)\s*(?:throws [\w.,\s]+)?\{",
    re.M,
)

# An enum whose constants name the fields they stand for, read by walking `values()`: the constant's
# first argument is the field.
ENUM = re.compile(r"\benum\s+(\w+)\s*\{([^;]*);")
ENUM_KEY = re.compile(r"^\s*[A-Z][A-Z0-9_]*\(\s*\"([a-z][a-z0-9_]*)\"", re.M)
VALUES = re.compile(r"\b([A-Z]\w*)\.values\(\)")
ENUM_FIELDS: dict[str, set[str]] = collections.defaultdict(set)
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
    for walked in VALUES.findall(text):
        fields.update(ENUM_FIELDS.get(walked, set()))
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
    for source in java.values():
        for name, constants in ENUM.findall(source):
            ENUM_FIELDS[name].update(ENUM_KEY.findall(constants))

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
