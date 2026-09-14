#!/usr/bin/env python3
"""Coverage of the fields a pack's `manifest.json` may carry against the ones this mod reads.

Every pack has exactly one of these and no pack loads without it, so it is the one file kind every
other census here takes for granted. `packfiles.py` counts `manifest.json` as a kind that is opened
and says nothing about what is inside it.

The published side is Mojang's own schema pair for the manifest: `Manifest.json` and the documents
it points at with `$ref`, under `metadata/json_schemas/client_server/packaging/`. A field is named
by the level that carries it, so `header.name` and `subpack.name` are two names rather than one, and
the four setting shapes are read as one `setting` level because a pack writes them in one array.

The mod's side is `pack/PackManifest.java`: the fields its reader names, found by the object each
call is given, and the `*_DROPPED` declarations, which are what `/mcaddon entities` reports against
the pack that stated one.

    bash tools/census/fetch.sh
    python3 tools/census/manifest.py
    python3 tools/census/manifest.py --strict   # exits non-zero while a manifest field goes unnamed
"""

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

from components import lenient, lenient_text, literal_set, print_names, text

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/at/ioexception/mcaddon"
MANIFEST_READER = JAVA / "pack/PackManifest.java"
DEFAULT_SAMPLES = ROOT / "scratchpad/bedrock/bedrock-samples"
SCHEMAS = "metadata/json_schemas/client_server/packaging"
INSTALLED = ROOT / "versions"
ADDONS = "*/run/addons"

# The level each published schema document describes, which is what a field name is qualified by.
# Both dependency shapes are one level because a pack writes them in one array and the reader tells
# them apart by the fields themselves. The four setting shapes are one level for the same reason.
SURFACES = {
    "PackManifestDocument": "manifest",
    "Header": "header",
    "Module": "module",
    "PackDependency": "dependency",
    "NativeModuleDependency": "dependency",
    "Subpack": "subpack",
    "Metadata": "metadata",
    "LabelSetting": "setting",
    "SliderSetting": "setting",
    "ToggleSetting": "setting",
    "DropdownSetting": "setting",
    "DropdownOption": "setting.options",
}

ORDER = ["manifest", "header", "module", "dependency", "subpack", "metadata", "setting",
         "setting.options"]

# The object a reader call is given, and the level that object is. A call on anything else names no
# field of Bedrock's, which is what keeps the loop variables of the reporting walk out of the read
# side.
LEVELS = {
    "json": "manifest",
    "header": "header",
    "module": "module",
    "dependency": "dependency",
    "subpack": "subpack",
    "metadata": "metadata",
    "setting": "setting",
}

# `Json.text(header, "name", ...)` and `header.get("name")` are the two shapes every read in the
# manifest reader is written in.
CALLS = [re.compile(r'Json\.\w+\(\s*(\w+),\s*"([^"]+)"'),
         re.compile(r'\b(\w+)\.get(?:As\w+)?\(\s*"([^"]+)"\s*\)')]

RECORD = re.compile(r'\brecord\s+\w+\(([^)]*)\)')
LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')


# --- the published side ---------------------------------------------------------------------------

def published(samples):
    """Every field Mojang's manifest schemas declare, by the level carrying it."""
    directory = samples / SCHEMAS
    found = {}
    for schema in sorted(directory.rglob("*.json")):
        body = lenient(schema)
        level = SURFACES.get(body.get("title"))
        if level is None:
            continue
        for field in body.get("properties", {}):
            found.setdefault(f"{level}.{field}", 0)
    return found


def manifests(samples):
    """Mojang's own manifests, which is the vanilla half of what stands behind a field."""
    return [lenient(path) for path in sorted(samples.rglob("manifest.json"))]


def installed(addons):
    """Every manifest in the add-ons the run directories carry, one copy of each.

    The same archive sits in one run directory per Minecraft version, so identical bytes are read
    once. A checkout no game has ever run in has no such directory, counts nothing, and still runs;
    `--addons` points the count at a checkout that has one.
    """
    seen = set()
    found = []
    for entry in sorted(path for directory in addons for path in sorted(directory.glob("*"))):
        for name, body in archive(entry):
            digest = hashlib.sha256(body).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            try:
                found.append(lenient_text(body.decode("utf-8-sig")))
            except (UnicodeDecodeError, ValueError):
                print(f"  unreadable manifest in {entry.name}: {name}", file=sys.stderr)
    return found


def archive(entry):
    """The `manifest.json` files one dropped-in add-on holds, as raw bytes."""
    if entry.is_dir():
        return [(str(path), path.read_bytes()) for path in sorted(entry.rglob("manifest.json"))]
    if entry.suffix.lower() not in (".mcaddon", ".mcpack", ".zip"):
        return []

    try:
        with zipfile.ZipFile(entry) as opened:
            return [(name, opened.read(name)) for name in sorted(opened.namelist())
                    if name.endswith("manifest.json")]
    except (OSError, zipfile.BadZipFile):
        return []


def counted(documents):
    """How many declarations in a body of manifests carry each field.

    A manifest holds one header and one metadata block and any number of modules, dependencies,
    subpacks and settings, so a repeated level counts once per entry rather than once per file.
    """
    found = {}

    def tally(body, level):
        if not isinstance(body, dict):
            return
        for field in body:
            found[f"{level}.{field}"] = found.get(f"{level}.{field}", 0) + 1

    for document in documents:
        if not isinstance(document, dict):
            continue
        tally(document, "manifest")
        tally(document.get("header"), "header")
        tally(document.get("metadata"), "metadata")
        for module in listed(document, "modules"):
            tally(module, "module")
        for dependency in listed(document, "dependencies"):
            tally(dependency, "dependency")
        for subpack in listed(document, "subpacks"):
            tally(subpack, "subpack")
        for setting in listed(document, "settings"):
            tally(setting, "setting")
            for option in listed(setting, "options"):
                tally(option, "setting.options")
    return found


def listed(body, key):
    value = body.get(key) if isinstance(body, dict) else None
    if isinstance(value, list):
        return [one for one in value if isinstance(one, dict)]
    return [value] if isinstance(value, dict) else []


# --- the mod's side -------------------------------------------------------------------------------

def read():
    """The fields the manifest reader names, qualified by the object each call is given."""
    body = text(MANIFEST_READER)
    found = set()
    for pattern in CALLS:
        for variable, field in pattern.findall(body):
            level = LEVELS.get(variable)
            if level:
                found.add(f"{level}.{field}")
    return found


def dropped():
    """The fields the reader names to the pack's author instead of acting on them."""
    return declared("DROPPED")


def declared(kind):
    """One `<LEVEL>_<KIND> = Set.of(...)` declaration per level, qualified by that level."""
    body = text(MANIFEST_READER)
    found = set()
    for level in ORDER:
        marker = f"{level.replace('.', '_').upper()}_{kind} = Set.of"
        if marker in body:
            found.update(f"{level}.{field}" for field in literal_set(MANIFEST_READER, marker))
    return found


def legacy():
    """The older spellings the reader answers on purpose, which Mojang's newest schema has dropped."""
    return declared("LEGACY")


def unbacked():
    """A value the reader parses out of a manifest that nothing in the mod ever asks for.

    A record component is the whole of the claim that a field is read: parsed into a component
    nobody reads, a field is as ignored as one nobody parses. String literals are blanked before
    the search so a field name spelt in the reader cannot stand in for its own value being used.
    """
    declaration = text(MANIFEST_READER)
    components = set()
    for parameters in RECORD.findall(declaration):
        components.update(part.split()[-1] for part in parameters.split(",") if part.split())

    bodies = [LITERAL.sub('""', RECORD.sub("record ()", declaration))]
    bodies += [LITERAL.sub('""', text(path)) for path in JAVA.rglob("*.java")
               if path != MANIFEST_READER]
    return {name for name in components
            if not any(re.search(rf"\b{name}\b", body) for body in bodies)}


def at(level, fields):
    """The fields of one level, which is everything before the last dot of a qualified name."""
    return {field for field in fields if field.rsplit(".", 1)[0] == level}


class Level:
    """One level of a manifest: what Bedrock declares there and what this mod does with it."""

    def __init__(self, name, available, read_here, dropped_here, legacy_here):
        self.name = name
        self.published = at(name, available)
        self.read = at(name, read_here)
        self.dropped = at(name, dropped_here)
        self.legacy = at(name, legacy_here)

    @property
    def covered(self):
        return self.published & self.read

    @property
    def reported(self):
        """Stated by a pack, unread, and named to its author by `/mcaddon entities`."""
        return (self.published & self.dropped) - self.read

    @property
    def silent(self):
        """Stated by a pack, unread, and named nowhere. The failure this census exists to end."""
        return self.published - self.dropped - self.read

    @property
    def stale(self):
        """A field this mod names that Mojang's own schemas carry no sign of."""
        return (self.read | self.dropped) - self.published - self.legacy

    @property
    def percent(self):
        return 100.0 * len(self.covered) / len(self.published) if self.published else 0.0


def levels(samples):
    available = published(samples)
    read_here = read()
    dropped_here = dropped()
    legacy_here = legacy()
    return [Level(name, available, read_here, dropped_here, legacy_here) for name in ORDER
            if at(name, available)]


# --- output ---------------------------------------------------------------------------------------

def print_table(found):
    width = max(len(level.name) for level in found)
    print("Coverage of the fields a pack's manifest.json may carry")
    print()
    print(f"  {'level':<{width}}   read  answered  reported   silent")
    for level in found:
        counted_here = f"{len(level.covered)}/{len(level.published)}"
        print(f"  {level.name:<{width}}  {counted_here:>6}    {level.percent:5.1f}%"
              f"  {len(level.reported):>8}  {len(level.silent):>7}")

    covered = sum(len(level.covered) for level in found)
    total = sum(len(level.published) for level in found)
    print()
    print(f"  {'every level':<{width}}  {f'{covered}/{total}':>6}    {100.0 * covered / total:5.1f}%"
          f"  {sum(len(level.reported) for level in found):>8}"
          f"  {sum(len(level.silent) for level in found):>7}")


def print_counted(title, names, vanilla, packs):
    if not names:
        return
    print()
    print(f"{title} ({len(names)}), with the declarations behind each:")
    print("    vanilla  installed  field")
    for name in sorted(names, key=lambda one: (-packs.get(one, 0), -vanilla.get(one, 0), one)):
        print(f"    {vanilla.get(name, 0):>7}  {packs.get(name, 0):>9}  {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=DEFAULT_SAMPLES,
                        help="a bedrock-samples checkout (default: %(default)s)")
    parser.add_argument("--addons", type=Path, action="append",
                        help="a directory of installed add-ons (default: versions/*/run/addons)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero while a manifest field is neither read nor reported")
    arguments = parser.parse_args()

    if not (arguments.samples / SCHEMAS).is_dir():
        print(f"No manifest schemas under {arguments.samples / SCHEMAS}. Fetch them with:\n"
              f"    bash tools/census/fetch.sh", file=sys.stderr)
        return 2

    found = levels(arguments.samples)
    vanilla = counted(manifests(arguments.samples))
    packs = counted(installed(arguments.addons or sorted(INSTALLED.glob(ADDONS))))

    print_table(found)
    for level in found:
        print_counted(f"Reported rather than read, {level.name}", level.reported, vanilla, packs)
        print_counted(f"Dropped in silence, {level.name}", level.silent, vanilla, packs)

    print_names("Named here and absent from Mojang's own schemas",
                {field for level in found for field in level.stale})
    print_names("Read and reported at once, which cannot both be true",
                {field for level in found for field in level.read & level.dropped})
    print_names("Read under an older spelling Mojang's newest schema has dropped", legacy())
    print_names("Parsed out of a manifest with nothing asking for the value", unbacked())

    return 1 if arguments.strict and any(level.silent for level in found) else 0


if __name__ == "__main__":
    sys.exit(main())
