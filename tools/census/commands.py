#!/usr/bin/env python3
"""Coverage of Bedrock's published command vocabulary against what this mod translates.

Every Bedrock line reaches the game through `/bedrock`: a function file's lines, a script's, an
entity event's and an operator's. Each is translated by `convert/Functions.java` and read against the
tree `server/BedrockTree.java` builds, which holds Java's own commands beside the Bedrock commands
this mod registers there, and a line that cannot be translated does not run. `/mcaddon packs`
reports what a loaded pack asked for, which only ever sees the commands that pack happens to use.
This reads Mojang's own command metadata instead and compares it against the translation and the
commands registered in that tree, so the answer needs no pack and no running game.

The published side is `metadata/command_modules/mojang-commands.json` from `bedrock-samples`. Fetch
it once with:

    bash tools/census/fetch.sh
    python3 tools/census/commands.py

Three surfaces are measured. The **names** are every command the metadata lists, with the aliases
folded in, because a function may write either spelling. The **keywords** are the literal words that
pick one form of a command over another, `music play` against `music stop`: the metadata writes each
as an enum, and an enum of at most sixteen values is a set of keywords while a longer one is an open
vocabulary of blocks, items, entities or biomes, which is data rather than grammar. The **game
rules** are the two rule enums, which matter because Bedrock writes a rule name in lower case and
Java's parser is case sensitive.

A name counts as translated when this mod rewrites it, registers a command of its own for it in the
Bedrock tree, or declares that Java's parser reads the Bedrock line under the same name or under
another one. That
last group is a claim rather than a measurement, the same kind `AddonEntities.IMPLEMENTED` makes, so
the commands resting on it are listed at the end for a reader to audit.

Every keyword is sorted into three groups, and the first two are what the percentage counts:

* **passed through**: the whole Bedrock line reaches Java's own parser, or the rewrite names the
  front of the line only and leaves the rest as the pack wrote it. `Functions.TAIL_REACHES_JAVA`
  declares the second kind, because such a keyword is answered by Java without its literal appearing
  anywhere in the translation.
* **rewritten**: the translation spells the literal out and decides what to do with it.
* **dropped**: its command is named in `Functions.BEDROCK_ONLY`, so the keyword goes with it, or
  `Functions.BEDROCK_ONLY_KEYWORDS` names this one form of a command that otherwise translates. The
  line carrying one does not run and the form is reported where the line was run from. A dropped
  keyword never counts as covered, whatever literals the source happens to carry.

Nothing else counts. The report ends with the check that says so, for names, keywords and game rules
alike: one that is missing and is not named as having no Java counterpart would be a gap nobody
decided on, and that is what `--strict` fails on. A named one still counts against the percentage.

Selector arguments have no surface of their own because the command metadata names none: a selector
is one parameter type there and its contents are never spelled out. They are listed from the source
instead, as rewritten or passed through. Every argument Bedrock writes is one or the other: the
three Java's selector has no form of, `hasitem`, `haspermission` and `has_property`, are each answered
with a tag for one run of the line, which is a rewrite.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
FUNCTIONS = JAVA / "convert/Functions.java"
# `CameraView`, `HudElement` and `InputPermission` are here because the preset words `/camera set`
# accepts, the pieces `/hud` names and the categories `/inputpermission` names are their enums rather
# than literals in the command tree, and those words are keywords the metadata counts.
REGISTRATIONS = (JAVA / "server/BedrockCommands.java", JAVA / "server/Dialogues.java",
                 JAVA / "server/Rides.java", JAVA / "server/Summons.java",
                 JAVA / "server/StructureCommand.java", JAVA / "server/TickingAreas.java",
                 JAVA / "server/AreaSchedules.java", JAVA / "server/FeatureRules.java",
                 JAVA / "server/Jigsaws.java", JAVA / "server/PlacementWords.java",
                 JAVA / "server/Structures.java",
                 JAVA / "server/ServerAdmin.java", JAVA / "server/GameTestCommand.java",
                 JAVA / "server/MobEvents.java", JAVA / "server/Music.java",
                 JAVA / "server/BedrockRules.java", JAVA / "server/PlayerAimAssists.java",
                 JAVA / "bedrock/aim/AimAssistSettings.java",
                 JAVA / "bedrock/camera/CameraView.java", JAVA / "bedrock/hud/HudElement.java",
                 JAVA / "bedrock/input/InputPermission.java")
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"

# Beyond this an enum is a vocabulary of blocks, items, entities or biomes rather than grammar.
KEYWORD_ENUM_LIMIT = 16


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


def literals(path, marker):
    """The string literals of an `X = Set.of(...)` or `Map.of(...)` declaration, in order."""
    body = text(path)
    at = body.index(marker)
    return re.findall(r'"([^"]+)"', balanced(body, body.index(".of", at)))


def literal_set(path, marker):
    return set(literals(path, marker))


def literal_keys(path, marker):
    """The keys of a `Map.of(...)` declaration, which alternate with their values."""
    return set(literals(path, marker)[::2])


def case_labels(path, marker):
    """The `case "..."` labels of the switch that follows `marker`, that switch only."""
    body = text(path)
    at = body.index("{", body.index("switch", body.index(marker)))
    depth = 0
    for i in range(at, len(body)):
        if body[i] == "{":
            depth += 1
        elif body[i] == "}":
            depth -= 1
            if depth == 0:
                groups = re.findall(r'case\s+((?:"[^"]*"\s*,?\s*)+)->', body[at:i])
                return {name for group in groups for name in re.findall(r'"([^"]*)"', group)}
    raise ValueError("unterminated switch")


def mod_commands():
    """The four ways a Bedrock command reaches Java's parser, and the names of each."""
    return {
        "shared": literal_set(FUNCTIONS, "SHARED_WITH_JAVA = Set.of"),
        "renamed": literal_keys(FUNCTIONS, "RENAMED = Map.of"),
        "registered": literal_set(FUNCTIONS, "REGISTERED_HERE = Set.of"),
        "rewritten": case_labels(FUNCTIONS, "String rewrite("),
    }


# A Java string literal, escaped quotes included, so a `"\""` cannot pair the quotes after it wrongly.
STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def mod_keywords():
    """Every string literal the command handling writes, which is where a keyword would be."""
    sources = [FUNCTIONS, *REGISTRATIONS]
    return {name.lower() for path in sources
            for name in STRING.findall(text(path))}


def unregistered(names):
    """Names claimed as registered by this mod that no `Commands.literal` in the source builds."""
    bodies = [text(path) for path in JAVA.rglob("*.java")]
    return {name for name in names
            if not any(f'Commands.literal("{name}")' in body for body in bodies)}


def selector_arguments():
    return {
        "rewritten": case_labels(FUNCTIONS, "String arguments("),
        "passed through": literal_set(FUNCTIONS, "SHARED_SELECTOR_ARGUMENTS = Set.of"),
    }


# --- the published side: read out of the metadata Mojang ships -----------------------------------

def commands_document(samples):
    return json.loads(text(samples / "metadata/command_modules/mojang-commands.json"))


def published_names(document):
    """Every command name, with the aliases folded in: a function may write either spelling."""
    names = set()
    for command in document["commands"]:
        names.add(command["name"])
        names.update(alias["name"] for alias in command.get("aliases", []))
    return names


def keyword_enums(document):
    """The enums small enough to be grammar, by the upper-case name a parameter refers to them by."""
    return {enum["name"].upper(): [value["value"] for value in enum["values"]]
            for enum in document["command_enums"]
            if 0 < len(enum["values"]) <= KEYWORD_ENUM_LIMIT}


def published_keywords(document):
    """`<command> <keyword>` for every keyword one of a command's overloads accepts."""
    enums = keyword_enums(document)
    pairs = set()
    for command in document["commands"]:
        for overload in command["overloads"]:
            for parameter in overload["params"]:
                for value in enums.get(parameter["type"]["name"], []):
                    pairs.add(f"{command['name']} {value}")
    return pairs


def published_game_rules(document):
    enums = {enum["name"]: enum["values"] for enum in document["command_enums"]}
    return {value["value"] for name in ("BoolGameRule", "IntGameRule")
            for value in enums.get(name, [])}


# --- the two sides put together ------------------------------------------------------------------

def keyword_groups(document):
    """Every published `<command> <keyword>` pair, by what the translation does with it."""
    mod = mod_commands()
    names = mod["shared"] | mod["renamed"] | mod["registered"] | mod["rewritten"]
    whole = mod["shared"] | mod["renamed"]
    tail = literal_set(FUNCTIONS, "TAIL_REACHES_JAVA =")
    dropped = literal_set(FUNCTIONS, "BEDROCK_ONLY_KEYWORDS = Set.of")
    spelled = mod_keywords()

    bedrock_only = literal_set(FUNCTIONS, "BEDROCK_ONLY = Set.of")

    groups = {kind: set() for kind in ("rewritten", "passed through", "dropped", "missing")}
    for pair in published_keywords(document):
        command, keyword = pair.split(" ", 1)
        if pair in dropped or command in bedrock_only:
            kind = "dropped"
        elif command not in names:
            kind = "missing"
        elif command in whole:
            kind = "passed through"
        elif keyword.lower() in spelled:
            kind = "rewritten"
        elif command in tail:
            kind = "passed through"
        else:
            kind = "missing"
        groups[kind].add(pair)
    return groups


def surfaces(samples):
    document = commands_document(samples)
    mod = mod_commands()
    names = mod["shared"] | mod["renamed"] | mod["registered"] | mod["rewritten"]
    groups = keyword_groups(document)
    rules = {name.lower() for name in
             literal_set(FUNCTIONS, "RULES_JAVA_SHARES = Set.of")
             | literal_set(FUNCTIONS, "RULES_NEWER_JAVA_HAS = Set.of")
             | literal_set(FUNCTIONS, "RULES_HELD_HERE = Set.of")
             | literal_keys(FUNCTIONS, "RULES_JAVA_FOLDS =")}

    return [
        Surface("command names", published_names(document), names, "the command metadata"),
        Surface("command keywords", published_keywords(document),
                groups["rewritten"] | groups["passed through"],
                "the enums the command metadata gives each overload"),
        Surface("game rules", published_game_rules(document), rules,
                "the two game rule enums"),
    ]


# --- output --------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(surface.title) for surface in found)
    print("Coverage of the published Bedrock command vocabulary")
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
                        help="exit non-zero when anything is missing without a decision naming it")
    arguments = parser.parse_args()

    metadata = arguments.samples / "metadata/command_modules/mojang-commands.json"
    if not metadata.is_file():
        print(f"No command metadata at {metadata}. Fetch it with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = surfaces(arguments.samples)
    groups = keyword_groups(commands_document(arguments.samples))
    print_table(found)

    print()
    print("Command keywords, by what the translation does with each:")
    for kind in ("rewritten", "passed through", "dropped", "missing"):
        print(f"    {kind:<14}  {len(groups[kind]):>3}")

    for surface in found:
        print_names(f"Missing, {surface.title}", groups["missing"]
                    if surface.title == "command keywords" else surface.missing)

    mod = mod_commands()
    named = literal_set(FUNCTIONS, "BEDROCK_ONLY = Set.of")
    print_names("Dropped and named as having no Java counterpart", named)
    print_names("Dropped and named as a form of a translated command Java has no way to say",
                groups["dropped"] & literal_set(FUNCTIONS, "BEDROCK_ONLY_KEYWORDS = Set.of"))
    named_rules = {name.lower() for name in literal_set(FUNCTIONS, "RULES_BEDROCK_ONLY = Set.of")}
    print_names("Game rules named as having no Java counterpart", named_rules)
    print_names("Missing from the published commands but named in the source",
                (named | mod["shared"] | mod["renamed"] | mod["registered"]) - found[0].published)
    print_names("Translated by a claim that Java's parser reads the line, with nothing to check it",
                mod["shared"] | mod["renamed"])
    print_names("Claimed as registered by this mod with no command of that name built",
                unregistered(mod["registered"]))

    print()
    print("Selector arguments, read from the source: the command metadata names none.")
    for kind, names in selector_arguments().items():
        print(f"    {kind:<14}  {', '.join(sorted(names))}")

    unnamed = (found[0].missing - named) | groups["missing"] | (found[2].missing - named_rules)
    print_names("Missing and not named, so dropped without anybody deciding to", unnamed)

    return 1 if arguments.strict and unnamed else 0


if __name__ == "__main__":
    sys.exit(main())
