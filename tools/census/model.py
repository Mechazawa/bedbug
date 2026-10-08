#!/usr/bin/env python3
"""Coverage of the drawing vocabulary, against what the geometry and animation readers act on.

The component census measures the behaviour pack side, where Mojang publishes a JSON schema per
component. Nothing publishes one for geometry, animations, animation controllers, render controllers
or client entities, so this measures against the files instead: the resource pack in
`bedrock-samples` is Mojang's own, and it carries every shape the engine accepts for every vanilla
entity. A field it uses that no reader here names is a field this mod silently drops.

The mod's side is read out of the Java source, one reader method per surface, taking the field name
out of each `has`, `get` and helper call in that method's body. Missing fields are listed with the
number of published declarations that use them, so the cost of a gap is visible rather than implied.

A dropped field a reader names in one of its `UNREAD` constants is reported through
`AddonEntities.unsupportedFields` as a pack declares it, which is a decision rather than a field
passed over without a word: such a field is listed apart, and `--strict` fails only on the rest.

    bash tools/census/fetch.sh
    python3 tools/census/model.py
"""

import argparse
import collections
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"

GEOMETRY = JAVA / "bedrock/model/GeometryParser.java"
ASSETS = JAVA / "bedrock/BedrockAssets.java"
ANIMATION = JAVA / "bedrock/animation/AnimationParser.java"
CONTROLLER = JAVA / "bedrock/animation/AnimationControllerParser.java"
RENDER = JAVA / "bedrock/model/RenderControllerParser.java"
COLOUR = JAVA / "bedrock/model/ControllerColour.java"
CLIENT = JAVA / "bedrock/entity/ClientEntityParser.java"
EGG = JAVA / "convert/SpawnEggAppearance.java"


class Surface:
    """One kind of declaration, the fields the published files use, and the fields a reader reads."""

    def __init__(self, title, published, implemented, declarations, source, reported=()):
        self.title = title
        self.published = published
        self.implemented = set(implemented)
        self.declarations = declarations
        self.source = source
        self.reported = set(reported)

    @property
    def covered(self):
        return set(self.published) & self.implemented

    @property
    def missing(self):
        return {name: count for name, count in self.published.items()
                if name not in self.implemented}

    @property
    def silent(self):
        """Dropped fields no reader reports, which is what `--strict` fails on."""
        return {name: count for name, count in self.missing.items() if name not in self.reported}

    @property
    def unpublished(self):
        """Fields the reader names that no published file declares."""
        return self.implemented - set(self.published)

    @property
    def percent(self):
        return 100.0 * len(self.covered) / len(self.published) if self.published else 0.0


# --- the mod's side: the field names one reader method acts on -----------------------------------

ACCESSORS = (
    r'{receiver}\.has\("([^"]+)"\)',
    r'{receiver}\.get\("([^"]+)"\)',
    r'{receiver}\.getAsJsonObject\("([^"]+)"\)',
    r'{receiver}\.getAsJsonArray\("([^"]+)"\)',
    # A helper taking the object and one or more field names, such as
    # `optionalInt(description, "size", 64)` or `optionalVector(json, "rotation", "bind_pose")`.
    r'\w+\(\s*{receiver}\s*,\s*((?:"[^"]*"\s*,\s*)*"[^"]*")',
)


def method_body(source, signature):
    """The text between the opening brace of the named method and its match."""
    at = source.index("{", source.index(signature))
    depth = 0
    for index in range(at, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[at:index]
    raise ValueError(f"unterminated method body for {signature}")


def fields(*readers):
    """Every field name the given readers take out of a JSON object.

    A reader is a `(file, signature)` pair, optionally followed by the names of the variables holding
    the object to count accessors on, for a method reading two objects into separate surfaces. A
    reader written as a loop over a list of field names rather than as a call per field is asked for
    its bare string literals instead, which is what `literals:` in front of the signature means.
    """
    found = set()
    for path, signature, *receivers in readers:
        bare = signature.startswith("literals:")
        body = method_body(path.read_text(encoding="utf-8"), signature.removeprefix("literals:"))
        if bare:
            found |= set(re.findall(r'"([^"]+)"', body))
            continue
        for receiver in (receivers or [r"\w+"]):
            for pattern in ACCESSORS:
                for group in re.findall(pattern.format(receiver=receiver), body):
                    found |= set(re.findall(r'"([^"]*)"', group)) or {group}
    return found


# --- the published side: the fields Mojang's own resource pack declares ---------------------------

def read(path):
    """Reads a pack file in the JSON dialect Bedrock allows, with comments and trailing commas."""
    text = path.read_text(encoding="utf-8-sig")
    out = []
    index = 0
    in_string = False
    while index < len(text):
        character = text[index]
        if in_string:
            out.append(character)
            if character == "\\":
                out.append(text[index + 1])
                index += 2
                continue
            in_string = character != '"'
            index += 1
            continue
        if character == '"':
            in_string = True
            out.append(character)
            index += 1
            continue
        if text.startswith("//", index):
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            index = len(text) if end < 0 else end + 2
            continue
        out.append(character)
        index += 1
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", "".join(out)))


def files(samples, directory):
    for path in sorted((samples / "resource_pack" / directory).rglob("*.json")):
        try:
            body = read(path)
        except (ValueError, OSError) as error:
            print(f"could not read {path.name}: {error}", file=sys.stderr)
            continue
        if isinstance(body, dict):
            yield body


class Tally:
    """Counts how many declarations of each kind carry each field."""

    def __init__(self):
        self.fields = collections.defaultdict(collections.Counter)
        self.counts = collections.Counter()

    def add(self, kind, body):
        if not isinstance(body, dict):
            return
        self.counts[kind] += 1
        self.also(kind, body)

    def also(self, kind, body):
        """Fields of an object nested inside one already counted, such as a `description`."""
        if not isinstance(body, dict):
            return
        for field in body:
            self.fields[kind][field] += 1


def geometries(samples, tally):
    """Both geometry shapes: the 1.12 array of described objects, and the 1.8 top level keys.

    The 1.12 shape splits a geometry over the object and the `description` inside it, and one reader
    walks both, so both are counted as the same surface.
    """
    for root in files(samples, "models"):
        bodies = []
        if isinstance(root.get("minecraft:geometry"), list):
            for body in root["minecraft:geometry"]:
                tally.add("geometry description", body)
                tally.also("geometry description", (body or {}).get("description"))
                bodies.append(body)
        else:
            for name, body in root.items():
                if name.startswith("geometry.") and isinstance(body, dict):
                    tally.add("geometry body", body)
                    bodies.append(body)

        for body in bodies:
            for bone in (body.get("bones") or []):
                tally.add("geometry bone", bone)
                for mesh in (bone.get("texture_meshes") or []):
                    tally.add("texture mesh", mesh)
                for cube in (bone.get("cubes") or []):
                    tally.add("geometry cube", cube)
                    if isinstance(cube.get("uv"), dict):
                        for face in cube["uv"].values():
                            tally.add("cube face uv", face)


def animations(samples, tally):
    for root in files(samples, "animations"):
        for body in (root.get("animations") or {}).values():
            tally.add("animation", body)
            if not isinstance(body, dict):
                continue
            for bone in (body.get("bones") or {}).values():
                tally.add("animation bone", bone)
                if not isinstance(bone, dict):
                    continue
                for channel in ("rotation", "position", "scale"):
                    for frame in keyframes(bone.get(channel)):
                        tally.add("animation keyframe", frame)


def keyframes(channel):
    """The `{pre, post}` bodies of a channel written as a map of times, which not every channel is."""
    if not isinstance(channel, dict) or {"pre", "post", "vector", "lerp_mode"} & set(channel):
        return
    for value in channel.values():
        if isinstance(value, dict):
            yield value


def controllers(samples, tally):
    for root in files(samples, "animation_controllers"):
        for body in (root.get("animation_controllers") or {}).values():
            tally.add("animation controller", body)
            if not isinstance(body, dict):
                continue
            for state in (body.get("states") or {}).values():
                tally.add("animation controller state", state)
                if not isinstance(state, dict):
                    continue
                for kind in ("sound_effects", "particle_effects"):
                    for effect in (state.get(kind) or []):
                        tally.also("animation controller state", effect)


def render_controllers(samples, tally):
    for root in files(samples, "render_controllers"):
        for body in (root.get("render_controllers") or {}).values():
            tally.add("render controller", body)
            tally.also("render controller", (body or {}).get("uv_anim"))


def client_entities(samples, tally):
    for directory, section in (("entity", "minecraft:client_entity"),
                               ("attachables", "minecraft:attachable")):
        for root in files(samples, directory):
            body = (root.get(section) or {}).get("description")
            tally.add("client entity", body)
            tally.also("client entity", (body or {}).get("spawn_egg"))
            if isinstance(body, dict):
                tally.add("client entity scripts", body.get("scripts"))


def surfaces(samples):
    tally = Tally()
    for collect in (geometries, animations, controllers, render_controllers, client_entities):
        collect(samples, tally)

    readers = {
        "geometry description": ([(GEOMETRY, "BedrockGeometry readGeometry"),
                                  (ASSETS, "void readGeometries", "description")],
                                 "models, the 1.12 shape"),
        "geometry body": ([(ASSETS, "literals:JsonObject legacyGeometry")], "models, the 1.8 shape"),
        # A bone of the 1.8 shape is also read where one geometry is folded into the one it extends,
        # which is the only place a field about what a bone inherits means anything.
        "geometry bone": ([(GEOMETRY, "BedrockBone readBone"),
                           (ASSETS, "JsonArray mergeBones")], "the bones of both shapes"),
        "geometry cube": ([(GEOMETRY, "BedrockCube readCube")], "the cubes of both shapes"),
        "texture mesh": ([(GEOMETRY, "BedrockTextureMesh readTextureMesh")], "bones built from a texture"),
        "cube face uv": ([(GEOMETRY, "CubeUv readUv")], "per face textured cubes"),
        "animation": ([(ANIMATION, "BedrockAnimation readAnimation")], "animations"),
        "animation bone": ([(ANIMATION, "BoneAnimation readBone")], "the bones of an animation"),
        "animation keyframe": ([(ANIMATION, "Keyframe readKeyframe")], "keyframes written as objects"),
        "animation controller": ([(CONTROLLER, "AnimationController readController")],
                                 "animation_controllers"),
        "animation controller state": ([(CONTROLLER, "AnimationController.State readState")],
                                       "the states of a controller"),
        "render controller": ([(RENDER, "RenderController read"),
                               (RENDER, "MolangExpression uvAnim"),
                               (RENDER, "Map<String, List<MolangValue>> readArrays"),
                               (COLOUR, "ControllerColour tryFrom")],
                              "render_controllers"),
        "client entity": ([(CLIENT, "ClientEntityDefinition read", "json"),
                           (ASSETS, "void readDescribed", "description"),
                           (EGG, "SpawnEggAppearance tryFrom")], "entity and attachables"),
        "client entity scripts": ([(CLIENT, "ClientEntityDefinition read", "scripts")],
                                  "their scripts block"),
    }

    found = []
    for title, (methods, source) in readers.items():
        found.append(Surface(title, tally.fields[title], fields(*methods),
                             tally.counts[title], source,
                             reported(*{method[0] for method in methods})))
    return found


def reported(*paths):
    """The field names the readers in these files report through an `UNREAD` constant."""
    names = set()
    for path in paths:
        body = path.read_text(encoding="utf-8")
        for match in re.finditer(r"UNREAD\w*\s*=\s*List\.of\(([^)]*)\)", body):
            names.update(re.findall(r'"([^"]+)"', match.group(1)))
    return names


# --- output ---------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(surface.title) for surface in found)
    print("Coverage of the fields Mojang's own resource pack declares")
    print()
    print(f"  {'surface':<{width}}  fields   read   declared  where")
    for surface in found:
        counted = f"{len(surface.covered)}/{len(surface.published)}"
        print(f"  {surface.title:<{width}}  {counted:>6}  {surface.percent:5.1f}%"
              f"  {surface.declarations:>8}  {surface.source}")

    covered = sum(len(surface.covered) for surface in found)
    published = sum(len(surface.published) for surface in found)
    print()
    print(f"  {'all surfaces':<{width}}  {f'{covered}/{published}':>6}"
          f"  {100.0 * covered / published:5.1f}%")


def print_missing(surface):
    if not surface.missing:
        return
    print()
    print(f"Dropped, {surface.title} ({len(surface.missing)}), with the declarations using each:")
    for name, count in sorted(surface.missing.items(), key=lambda item: (-item[1], item[0])):
        print(f"    {count:>5}  {name}{'' if name in surface.silent else '  (reported)'}")


def print_unpublished(surface):
    if not surface.unpublished:
        return
    print()
    print(f"Read but absent from the published {surface.title} ({len(surface.unpublished)}):")
    for name in sorted(surface.unpublished):
        print(f"           {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero when a published field is dropped without a report")
    arguments = parser.parse_args()

    if not (arguments.samples / "resource_pack").is_dir():
        print(f"No bedrock-samples resource pack at {arguments.samples}. Fetch it with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = surfaces(arguments.samples)
    print_table(found)
    for surface in found:
        print_missing(surface)
    for surface in found:
        print_unpublished(surface)

    return 1 if arguments.strict and any(surface.silent for surface in found) else 0


if __name__ == "__main__":
    sys.exit(main())
