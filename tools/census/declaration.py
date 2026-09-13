#!/usr/bin/env python3
"""Where a pack declares an entity component, against where this mod reads it from.

Every other census here counts names: a component is answered or it is not. None of them can see a
component this mod reads off the top level of a definition while every published pack writes it
inside a component group, because the name is present on both sides and the count is right. That
gap is invisible to a count and visible in a screenshot.

Bedrock puts a component in a group whenever it is something the entity becomes rather than
something it is: `minecraft:is_saddled` arrives with the saddle, `minecraft:ageable` with being
young, `minecraft:explode` with being primed. Of the thirty-five vanilla entities that age, one
writes `minecraft:ageable` at the top level. A reader that asks the definition rather than the
entity therefore answers for almost no published pack.

    bash tools/census/fetch.sh
    python3 tools/census/declaration.py            # the table, then what is read too narrowly
    python3 tools/census/declaration.py --strict   # exits non-zero while a gap stands
"""

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from components import DEFAULT_SAMPLES, JAVA, lenient, literal_set, text  # noqa: E402

# How a reader reaches a component, widest first. The order is the ranking: a component read only
# from the top level cannot see a group, and one read only as a group arrives cannot see the top.
LIVE = "live"
ANY_GROUP = "any group"
ON_ADD = "on add"
DECLARED = "declared"
ELSEWHERE = "elsewhere"

# What each call in the source means about the reach of the read it performs.
REACH = (
    (LIVE, ("declaresNow(", "liveComponent(")),
    (ANY_GROUP, ("componentBody(",)),
    (DECLARED, ("declares(", "components().get(", "components().containsKey(",
                "number(definition,", "text(", "anyDeclares(", "anyoneDeclares(")),
)

# A goal is collected from the top level and from every group at once, because a pack switching a
# goal on writes it into a group and Java has to build the whole list before the entity exists.
GOALS = ("minecraft:behavior.", "minecraft:memory_behavior.", "minecraft:annotation.")

# A name in this set is a component Java does for every mob anyway, so no reader names it and the
# question of where it was declared does not arise.
FREE = {
    "minecraft:physics", "minecraft:pushable", "minecraft:pushable_by_entity",
    "minecraft:pushable_by_block", "minecraft:push_through", "minecraft:is_collidable",
    "minecraft:conditional_bandwidth_optimization", "minecraft:tick_world",
    "minecraft:free_camera_controlled", "minecraft:input_ground_controlled",
    "minecraft:not_pickable_from_inside", "minecraft:transient",
}


def declarations(samples):
    """Per component, how many vanilla entities write it at the top level and how many in a group."""
    top = {}
    grouped = {}
    folder = samples / "behavior_pack" / "entities"
    for path in sorted(folder.glob("*.json")):
        try:
            body = lenient(path)["minecraft:entity"]
        except (KeyError, ValueError):
            continue

        declared = set((body.get("components") or {}).keys())
        in_groups = {name for group in (body.get("component_groups") or {}).values()
                     for name in group}
        for name in declared:
            top[name] = top.get(name, 0) + 1
        for name in in_groups - declared:
            grouped[name] = grouped.get(name, 0) + 1
    return top, grouped


def marker_names():
    """The names of the flag components, which are published together off one list of their own."""
    body = text(JAVA / "entity/AddonEntity.java")
    start = body.index("MARKERS = {")
    return set(re.findall(r'"([^"]+)"', body[start:body.index("};", start)]))


def reaches(source, name):
    """Every kind of read the source performs on this component, as a set."""
    found = set()
    quoted = '"%s"' % name
    for line in source:
        if quoted not in line:
            continue
        if re.search(r'case\s+"[^"]*"(\s*,\s*"[^"]*")*\s*->', line) and quoted in line:
            found.add(ON_ADD)
        for kind, calls in REACH:
            if any(call in line for call in calls):
                found.add(kind)
    return found


def widest(found):
    for kind in (LIVE, ANY_GROUP, ON_ADD, DECLARED):
        if kind in found:
            return kind
    return ELSEWHERE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES)
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while a component is read too narrowly to be seen")
    arguments = parser.parse_args()

    if not (arguments.samples / "behavior_pack" / "entities").is_dir():
        print("No vanilla behaviour pack under %s; run tools/census/fetch.sh." % arguments.samples)
        return 0

    implemented = literal_set(JAVA / "registry/AddonEntities.java", "IMPLEMENTED = Set.of")
    top, grouped = declarations(arguments.samples)

    # The implemented declaration is a list of names rather than a read of any of them, so it is
    # taken out before the source is searched for what reaches each name.
    source = []
    for path in sorted(JAVA.rglob("*.java")):
        body = text(path)
        if path.name == "AddonEntities.java":
            body = body[:body.index("IMPLEMENTED = Set.of")]
        source.extend(body.splitlines())

    markers = marker_names()

    gaps = []
    for name in sorted(implemented):
        if name in FREE:
            continue

        in_groups = grouped.get(name, 0)
        at_top = top.get(name, 0)
        if in_groups == 0:
            continue

        if name.startswith(GOALS) or name in markers:
            continue

        reach = widest(reaches(source, name))
        if reach in (DECLARED, ELSEWHERE):
            gaps.append((in_groups, at_top, name, reach))

    published = sum(1 for name in implemented if grouped.get(name, 0))
    print("Components a vanilla entity declares inside a component group: %d" % published)
    print("Of those, reached by no read that can see a group:             %d" % len(gaps))

    if gaps:
        print()
        print("%-46s%9s%9s%11s" % ("component", "grouped", "top", "read"))
        for in_groups, at_top, name, reach in sorted(gaps, reverse=True):
            print("%-46s%9d%9d%11s" % (name, in_groups, at_top, reach))
        print()
        print("`declared` is a read of the definition rather than of the entity, so a pack switching")
        print("the component on the way Bedrock says to is told it is off. `elsewhere` is a name no")
        print("recognised read names, which is either a reader reaching it some other way or nothing")
        print("reading it at all; the column ranks the work rather than deciding it. A component read")
        print("as the entity type is built, such as its size, can only be read that way.")

    return 1 if arguments.strict and gaps else 0


if __name__ == "__main__":
    sys.exit(main())
