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

from components import (DEFAULT_SAMPLES, JAVA, lenient, literal_set, text,  # noqa: E402
                        unbacked_claims)

# How a reader reaches a component, widest first. The order is the ranking: a component read only
# from the top level cannot see a group, and one read only as a group arrives cannot see the top.
LIVE = "live"
ANY_GROUP = "any group"
ON_ADD = "on add"
DECLARED = "declared"
ELSEWHERE = "elsewhere"

# What each call in the source means about the reach of the read it performs.
REACH = (
    (LIVE, ("declaresNow(", "liveComponent(", "liveText(", "liveNumber(")),
    (ANY_GROUP, ("componentBody(",)),
    (DECLARED, ("declares(", "components().get(", "components().containsKey(",
                "number(definition,", "text(", "anyDeclares(", "anyoneDeclares(")),
)

# A goal is collected from the top level and from every group at once, because a pack switching a
# goal on writes it into a group and Java has to build the whole list before the entity exists.
GOALS = ("minecraft:behavior.", "minecraft:memory_behavior.", "minecraft:annotation.")

# A method taking the component name as a parameter, which is what a helper forwarding to one of the
# calls above looks like, and a loop walking a list of names and reading each in turn. A statement
# opening a block is spelt the same way and names a keyword rather than a method.
KEYWORDS = ("for", "if", "while", "switch", "catch", "synchronized", "do", "else", "try", "return")
METHOD = re.compile(r'\b(\w+)\s*\(([^);]*\bString\s+\w+[^);]*)\)\s*\{')
LOOP = re.compile(r'\bfor\s*\(\s*(?:final\s+)?String\s+(\w+)\s*:\s*'
                  r'(?:new\s+String\s*\[\]\s*|List\.of|Set\.of)\s*[{(]([^})]*)[})]\s*\)')


def block(body, start):
    """The braced block beginning at the first brace at or after `start`."""
    opened = body.index("{", start)
    depth = 0
    for index in range(opened, len(body)):
        if body[index] == "{":
            depth += 1
        elif body[index] == "}":
            depth -= 1
            if depth == 0:
                return body[opened:index + 1]
    return body[opened:]


def without(body, declaration):
    """The source with one parenthesised declaration taken out, the rest of the file left alone."""
    start = body.index(declaration)
    opened = body.index("(", start)
    depth = 0
    for index in range(opened, len(body)):
        if body[index] == "(":
            depth += 1
        elif body[index] == ")":
            depth -= 1
            if depth == 0:
                return body[:start] + body[index + 1:]
    return body[:start]


def reads_with(body, variable, calls):
    """Whether one of these calls is handed this variable as the component it reads."""
    return any(re.search(r'%s\s*%s\s*[,)]' % (re.escape(call), re.escape(variable)), body)
               for call in calls)


def forwarders(bodies, reach):
    """The helpers that read whatever component name they are handed, and how widely each reads it.

    `REACH` names the calls that reach a component by name. A helper taking that name as a parameter
    and passing it to one of them reads it exactly as widely, and the call site shows none of that:
    `raise("minecraft:on_hurt", ...)` is a live read and looks like a bare string. Each is found by
    its own parameter reaching a recognised call inside its body, so a helper written in the source
    counts the moment it is written rather than when somebody remembers to declare it.
    """
    found = []
    known = {call for _, calls in reach for call in calls}
    for body in bodies:
        for match in METHOD.finditer(body):
            if match.group(1) in KEYWORDS:
                continue

            name = match.group(1) + "("
            if name in known:
                continue

            parameters = re.findall(r'\bString\s+(\w+)', match.group(2))
            inner = block(body, match.end() - 1)
            for kind, calls in reach:
                if any(reads_with(inner, parameter, calls) for parameter in parameters):
                    found.append((kind, name))
                    break
    return found


def loops(body, reach):
    """Per component, the reads a loop over a list of component names performs on each of them.

    A name walked over in a list is read through the loop's own variable, so the line carrying the
    name carries no call and the line carrying the call carries no name. What decides is the loop
    body: every name in the list is read however the calls taking that variable read it.
    """
    found = {}
    for match in LOOP.finditer(body):
        names = re.findall(r'"([^"]+)"', match.group(2))
        inner = block(body, match.end() - 1)
        for kind, calls in reach:
            if not reads_with(inner, match.group(1), calls):
                continue
            for name in names:
                found.setdefault(name, set()).add(kind)
    return found


def free():
    """The implemented components no reader names, which is where a declaration cannot be read wrong.

    Each of these is a component Java does for every mob anyway, which is why the implemented
    declaration carries it and why nothing reads it: a pack saying its entity has physics or can be
    named is describing what a Java mob does regardless. There is no read to widen, so where the
    pack wrote it decides nothing. `components.py` already works the set out from the source, and
    its own docblock is where the audit of each one lives.
    """
    return unbacked_claims().get("entity", set())


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


def reaches(source, name, reach):
    """Every kind of read the source performs on this component, as a set."""
    found = set()
    quoted = '"%s"' % name
    for line in source:
        if quoted not in line:
            continue
        if re.search(r'case\s+"[^"]*"(\s*,\s*"[^"]*")*\s*->', line) and quoted in line:
            found.add(ON_ADD)
        for kind, calls in reach:
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
    unread = free()
    top, grouped = declarations(arguments.samples)

    # The implemented declaration is a list of names rather than a read of any of them, so the list
    # itself is cut out before the source is searched for what reaches each name. Only the list: the
    # rest of that file reads components like any other.
    bodies = []
    for path in sorted(JAVA.rglob("*.java")):
        body = text(path)
        if path.name == "AddonEntities.java":
            body = without(body, "IMPLEMENTED = Set.of")
        bodies.append(body)

    source = [line for body in bodies for line in body.splitlines()]

    # A helper handed the name reads it as widely as whatever it forwards to, so the call sites of
    # one are reads in their own right and the ranking has to know that before it reads a line.
    reach = REACH
    for kind, call in forwarders(bodies, REACH):
        reach = tuple((named, calls + (call,)) if named == kind else (named, calls)
                      for named, calls in reach)

    through_loops = {}
    for body in bodies:
        for name, kinds in loops(body, reach).items():
            through_loops.setdefault(name, set()).update(kinds)

    markers = marker_names()

    gaps = []
    for name in sorted(implemented):
        if name in unread:
            continue

        in_groups = grouped.get(name, 0)
        at_top = top.get(name, 0)
        if in_groups == 0:
            continue

        if name.startswith(GOALS) or name in markers:
            continue

        found = reaches(source, name, reach) | through_loops.get(name, set())
        if widest(found) in (DECLARED, ELSEWHERE):
            gaps.append((in_groups, at_top, name, widest(found)))

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
        print("reading it at all; the column ranks the work rather than deciding it.")
        print()
        print("A component whose value the entity type was built from is still on this list, because")
        print("the type being frozen bounds where the value may be applied rather than whether it")
        print("can move: Java holds a size, an attribute and a navigator on the entity as well as on")
        print("its type, and a group changing one reaches the entity through whichever of those the")
        print("value belongs to. A name with no read at all is left off, there being nothing to widen.")

    return 1 if arguments.strict and gaps else 0


if __name__ == "__main__":
    sys.exit(main())
