#!/usr/bin/env python3
"""Checks the mod against a real add-on and prints a pass or fail verdict.

Five modes. `run` starts a dev server for one or more versions, types a scripted sequence of console
commands into it, and reads the output back for the failures that only appear at runtime: a pack's
JavaScript hitting a missing API, a mixin that did not apply, the script engine entered from a
second thread, a converted function Java refused, a crash report being written. `client` opens a
saved world in a dev client, which is the half that draws. `join` puts the two together: a dedicated
server with the packs installed, and a client that connects to it over 127.0.0.1, which is the only
way to reach what a server sends a client and what a pack's registry entries cost to send. With
`--bare`, `--other` or `--reshaped` the joining client is a second one with add-ons of its own, which
checks the add-on sync. `lan` opens a saved world to the LAN and has that second client join it.
`convert` compares what the converter wrote under a version's run directory against what the source pack
holds, and needs no game. `companions` runs the server and the client again and reads the log back
for the other mods in the room as well: that every mod a version pins really loaded, and that no
injector on either side lost its target while the other was there.

The server is always killed on a deadline this script keeps itself. A `stop` typed while the server
is still starting is discarded, so a run that waited for the process to end on its own would hang.
Only the dev servers this run started are killed, and a lock file under `scratchpad/` keeps two runs
from starting one at the same time, because the port and the world lock take one server at a time.

The verdict is written as JSON next to the captured logs, and the exit status is non-zero on a
failure, so a caller can gate on either.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass, field, replace
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from threading import Thread

USAGE = (__doc__ or "").splitlines()[0]

VERSIONS = ("1.20.1", "1.21.1", "26.1.2", "26.2")
ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@25")

IMAGE_SUFFIXES = (".png", ".tga", ".jpg", ".jpeg")

# The maps a `*.texture_set.json` can name. Each points at an image the converter folds into a
# labPBR sidecar instead of copying, so none of them counts as a texture of its own.
NORMAL_MAPS = ("normal", "heightmap")
SPECULAR_MAPS = ("metalness_emissive_roughness", "metalness_emissive_roughness_subsurface")

# What makes a run fail, most specific first: a line is reported under the first name it matches.
FAILURES = (
    ("java compile error", r"\.java:\d+: error:"),
    # A registry entry a data pack could not decode stops the world behind a screen asking the
    # player to fix their packs, which a harness with nobody at the keyboard waits out. The line
    # names the registry and the element, so reading it beats reading the deadline.
    ("a converted data pack would not load",
     r"Registry loading errors:|Failed to parse .+ from pack "),
    ("script error", r"Script error:"),
    ("function java refused", r"Failed to load function"),
    ("script engine entered off thread", r"Multi threaded access requested by thread"),
    ("class failed to initialise", r"Could not initialize class"),
    ("stack overflow", r"StackOverflowError"),
    ("mixin apply failure",
     r"Mixin apply failed|MixinApplyError|MixinTransformerError|MixinPrepareError"
     r"|MixinError|InjectionError|Critical injection failure"
     r"|[Mm]ixin.*(?:FAIL|fail(?:ed|ure))"),
    # Both of these are logged at WARN, so the error check never sees them, and a model that did
    # not load is a hole the player walks into rather than anything the game complains about.
    ("model failed to load", r"Unable to load model"),
    ("model missing textures", r"Missing textures in model"),
    ("crash report", r"[Cc]rash report (?:saved to|has been saved)"),
    ("exception cause", r"Caused by:"),
    ("error", r"/ERROR\]"),
)

# Lines that match a failure above and mean nothing is wrong.
BENIGN = (
    r"No data fixer registered for",
    r"Could not authorize you against Realms server",
    r"Couldn't connect to realms",
    r"Failed to fetch Realms feature flags",
    r"Failed to fetch user properties",
    r"Failed to retrieve profile key pair",
    r"RealmsClient|RealmsAvailability|realmsclient",
    r"Failed to load properties from file: server\.properties",
    # Sodium turns off the mixins Iris replaces, which is how the two are meant to work together.
    r"\(Sodium\) Force-disabling mixin",
    # The narrator's speech library is a system package many Linux machines do not carry, and the
    # game only loses its narrator without it. The library path it prints names `sponge-mixin`.
    r"Error while loading the narrator",
    r"flite",
)

# What has to be in the log of a run that worked.
EXPECTED_WITH_ADDON = (
    ("mod logged its startup", r"\(Bedrock Add-ons\)"),
    ("a pack was read", r"\(Bedrock Add-ons\) (?:resources|behaviour) "),
)

# A pack shipping a script, and the line saying its script engine came up. A module a pack imports is
# linked before any of it runs, so one missing name switches the whole engine off; reading the
# startup line back is what tells that apart from a pack that ships no script at all.
SHIPS_SCRIPT = re.compile(r"\(Bedrock Add-ons\) .*modules \[[^]]*script")
SCRIPTS_RUNNING = re.compile(r"Running scripts from \d+ of \d+ behaviour pack")

# The mod's line saying how much of an add-on reached the client, and the count it opens with.
CLIENT_DEFINITIONS = re.compile(r"(\d+) add-on entities have a client definition")

# The client's own integrated server saying it has started, which is the one line that only appears
# once the world is really open. A client stopped on a screen asking the player a question loads
# every model and texture first, so nothing earlier than this tells the two apart.
CLIENT_IN_WORLD = re.compile(r"Starting integrated minecraft server|Preparing spawn area")

# What a dedicated server writes about a client that reached it. Both are written by the server
# after the client has finished logging in, so neither can be produced by a client that only
# launched: the first names the connection it came in on, the second the world it spawned into.
LOGGED_IN = re.compile(r"logged in with entity id \d+")
JOINED = re.compile(r"(\S+) joined the game")

# Why a client left, which is the line carrying a registry the two halves disagreed about.
LOST_CONNECTION = re.compile(r"lost connection: (.+)")

# The JVM property `runJoinClient` carries, which names the second client in the process table.
GUEST_PROPERTY = "mcaddon.role=guest"

# What a client logs as it takes a server's add-ons: how many the server offered, how many it made
# active, and that it put its own back once the session ended. Dormant add-ons count toward the
# first number and not the second.
RECEIVED = re.compile(r"Received (\d+) add-ons from the server\.")
DOWNLOADING = re.compile(r"Downloading (\d+) add-ons")
ACTIVATED = re.compile(r"Activated the server's add-on set of (\d+) add-ons\.")
RESTORED = re.compile(r"Restored the installed add-on set\.")

# The refusal for a server add-on whose Java shape differs from the installed copy of the same one.
CANNOT_REPLACE = re.compile(r"The server's copy of (.+?) cannot replace yours without a restart\.")

# The mods a version's `gradle.properties` pins, under the id Fabric Loader knows each of them by
# and the half of the game each belongs to. A property is read by its Modrinth slug and a mod names
# itself something else, so the pairing is written out rather than guessed at. A mod whose own
# manifest says it draws is left out of a server with nothing said about it, which is why the side
# is here: a run can only ask for the mods that side was ever going to load.
COMPANION_IDS = {
    "sodium": ("sodium", "client"),
    "iris": ("iris", "client"),
    "indium": ("indium", "client"),
    "entitytexturefeatures": ("entity_texture_features", "client"),
    "entity-model-features": ("entity_model_features", "client"),
    "modmenu": ("modmenu", "client"),
    "lithium": ("lithium", "both"),
    "ferrite-core": ("ferritecore", "both"),
    "c2me-fabric": ("c2me", "both"),
    "lithostitched": ("lithostitched", "both"),
    "terralith": ("terralith", "both"),
}

# One entry of the list Fabric Loader prints as it starts, which is the only statement of what is
# really in the room. A nested mod is written under its parent with a rule in front of it.
MOD_ENTRY = re.compile(r"^[\s|`\\-]*-+\s*([a-z0-9_][a-z0-9_.\-]*)\s+\S")

# Fabric refusing to start because a mod is missing something it depends on, which is a problem with
# what the companion list pins rather than with anything this mod did.
RESOLUTION_FAILED = re.compile(r"Mod resolution failed|Incompatible mods found")

# The mixin configuration a failure line names, which says whose mixin it was.
BLAMED_CONFIG = re.compile(r"([a-z0-9_.\-]+\.mixins\.json)")

# An injector that found nothing, which is how a hook goes off in silence. Mixin logs it at `WARN`,
# so the error check never sees it.
NO_INJECTION = re.compile(r"(?:Critical injection failure|Injection validation failed"
                          r"|@Mixin target \S+ was not found|Could not find any target)")

# A mixin whose whole target class is absent, which every mod that ships an integration with a mod
# the player has not installed logs on every start. Blamed on its owner rather than on this mod.
OPTIONAL_INTEGRATION = re.compile(r"@Mixin target \S+ was not found")

# The console's answer to `give`, which says the item exists and was handed to somebody.
GAVE = re.compile(r"Gave \d+ .* to ")

# The console's answer to `list`, counting who is standing in the world at the moment it is asked.
PLAYERS_ONLINE = re.compile(r"There are (\d+) of a max of")

# Gradle's own failure, which ends a wait: nothing more is coming.
BUILD_FAILED = re.compile(r"FAILURE: Build failed|BUILD FAILED")

# What the console prints for a `summon` that worked, which is how a registered entity type is
# told apart from one the game does not know.
SUMMONED = re.compile(r"Summoned new ")

# The verdict a pack's own `@minecraft/server-gametest` test reaches, and the two the probe pack
# ships. One of them is written to fail, so a run where everything passed means the failure was
# never noticed and the verdict is worth no more than a run where nothing was tested at all.
TEST_PASSED = re.compile(r"([\w.:-]+) passed\.")
TEST_FAILED = re.compile(r"([\w.:-]+) failed: ")
PROBE_PASSES = "probe:floor_is_stone"
PROBE_FAILS = "probe:diamond_is_missing"

# What a dimension a pack declares says back once the server has built a level out of it. A level
# that was never built takes the `execute in` naming it with the parse, so nothing is printed at all.
DIMENSION_REACHED = "MCADDON-DIMENSION"

# Counts the mod reports, pulled back out of the log so they can be checked against the source pack.
REPORTED = (
    ("textures", r"Converted .*?: (\d+) textures"),
    ("sound events", r"Converted .*?: \d+ textures, (\d+) sound events"),
    ("entity types", r"Registered (\d+) entity types from add-ons"),
    ("items", r"Registered (\d+) items from add-ons"),
    ("blocks", r"Registered (\d+) blocks from add-ons"),
    ("script commands", r"Registered (\d+) commands a pack's script declared"),
)

FAILURE_PATTERNS = tuple((name, re.compile(pattern)) for name, pattern in FAILURES)
BENIGN_PATTERN = re.compile("|".join(BENIGN))
EXAMPLES_PER_CHECK = 5


@dataclass(frozen=True)
class Launch:
    """One way of starting the game, and the line that says it has finished loading.

    Both halves run through the same dev launcher, so the environment it is given is what tells one
    process from the other, and a check must never kill or wait on the wrong one.
    """

    name: str
    task: str
    signature: str
    ready: re.Pattern
    ready_without_addon: re.Pattern
    takes_commands: bool
    excluding: str = ""


SERVER = Launch(
    name="server",
    task="runServer",
    signature="fabric.dli.env=server",
    ready=re.compile(r"Done \("),
    ready_without_addon=re.compile(r"Done \("),
    takes_commands=True)

# A client has no console to type into, so it is watched until the mod says it has drawn what the
# pack gave it, held open a moment, and then killed.
CLIENT = Launch(
    name="client",
    task="runClient",
    signature="fabric.dli.env=client",
    ready=CLIENT_DEFINITIONS,
    ready_without_addon=re.compile(r"Sound engine started|Created: \d+x\d+x\d+ .*-atlas"),
    takes_commands=False,
    excluding=GUEST_PROPERTY)

# The second client, `runJoinClient`, has a run directory and an add-on set of its own and stands for
# a player joining somebody else's game. Its JVM property is what tells it from the first client in
# the process table, so that either can be waited on and ended without touching the other.
GUEST = replace(CLIENT, name="join-client", task="runJoinClient", signature=GUEST_PROPERTY,
                excluding="")


@dataclass
class Verdict:
    """What one version's check found, and whether that counts as a pass."""

    subject: str
    notes: list[str] = field(default_factory=list)
    problems: dict[str, dict] = field(default_factory=dict)
    counts: dict[str, dict[str, int]] = field(default_factory=dict)

    @property
    def passed(self) -> bool:
        return not self.problems

    def fail(self, name: str, detail: str, lines: list[str] | None = None) -> None:
        entry = self.problems.setdefault(name, {"detail": detail, "count": 0, "examples": []})
        entry["count"] += 1
        if lines is not None and len(entry["examples"]) < EXAMPLES_PER_CHECK:
            entry["examples"] += lines

    def as_dict(self) -> dict:
        return {
            "subject": self.subject,
            "passed": self.passed,
            "notes": self.notes,
            "counts": self.counts,
            "problems": self.problems,
        }


def sanitise(value: str) -> str:
    """Mirrors `Names.sanitise`: what a Java resource location accepts, anything else underscored."""
    allowed = "abcdefghijklmnopqrstuvwxyz0123456789_.-/"
    return "".join(character if character in allowed else "_" for character in value.lower())


def open_source(path: Path):
    """Lists an add-on's files as the game loads them, and returns a reader for them."""
    if path.is_dir():
        names = tuple(str(file.relative_to(path).as_posix()) for file in path.rglob("*") if file.is_file())

        def read(name: str) -> bytes:
            return (path / name).read_bytes()
    else:
        archive = zipfile.ZipFile(path)
        names = tuple(name for name in archive.namelist() if not name.endswith("/"))
        read = archive.read

    return loaded(names, read), read


def subpack_tier(subpack: dict) -> int:
    """The one to five tier a subpack asks for, whichever of the two spellings it is written in.

    `memory_tier` counts 250 MB of device memory, and Mojang's own table groups those numbers into
    the five platform tiers, whose lowest members are 0, 12, 18, 24 and 32.
    `memory_performance_tier` states the tier outright.
    """
    stated = subpack.get("memory_performance_tier")
    if isinstance(stated, int) and stated > 0:
        return stated

    memory = subpack.get("memory_tier")
    if not isinstance(memory, int) or memory <= 0:
        return 0
    return sum(1 for low in (0, 12, 18, 24, 32) if memory >= low)


def loaded(names: tuple[str, ...], read) -> tuple[str, ...]:
    """The pack's files with its subpacks resolved, which is what the mod converts.

    A pack ships each of its forms under `subpacks/<folder_name>/`, and one of them stands in for
    the pack's own files of the same path. Counting every form would count one texture several
    times over and leave this harness expecting output no game would write. The form taken is the
    one with the highest tier, the last of them where two tie, which is what the mod does.
    """
    kept = list(names)
    for manifest in [name for name in names if name.endswith("manifest.json")]:
        root = manifest[: -len("manifest.json")]
        try:
            subpacks = json.loads(read(manifest)).get("subpacks") or []
        except (ValueError, UnicodeDecodeError):
            continue
        if not isinstance(subpacks, list) or not subpacks:
            continue

        chosen = None
        for offered in [one for one in subpacks if isinstance(one, dict)]:
            if chosen is None or subpack_tier(offered) >= subpack_tier(chosen):
                chosen = offered
        folder = f"{root}subpacks/{chosen.get('folder_name')}/" if chosen else None
        shadowed = {root + name[len(folder):] for name in names if folder and name.startswith(folder)}
        kept = [name for name in kept
                if not (name.startswith(f"{root}subpacks/") and not (folder and name.startswith(folder)))
                and name not in shadowed]
    return tuple(kept)


def texture_set_maps(name: str, body: dict) -> tuple[set[str], bool, bool]:
    """The images a texture set folds in, and whether it asks for a normal and a specular sidecar."""
    root = name[: name.index("textures/")]
    folder = name.rsplit("/", 1)[0]
    companions = set()

    for key in NORMAL_MAPS + SPECULAR_MAPS:
        value = body.get(key)
        if isinstance(value, str):
            companions.add(root + value if value.startswith("textures/") else f"{folder}/{value}")

    return companions, any(body.get(key) for key in NORMAL_MAPS), any(body.get(key) for key in SPECULAR_MAPS)


def source_counts(names, read) -> dict:
    """What the source pack holds, counted the way the converter counts it.

    A pack keeps its glyph pages in `font/` rather than under `textures/`, and each becomes a texture
    of the font it joins, so both directories count here as they do on the other side.
    """
    images = [name for name in names
              if ("/textures/" in f"/{name}" or "/font/" in f"/{name}")
              and name.lower().endswith(IMAGE_SUFFIXES)]
    sets = [name for name in names if name.endswith(".texture_set.json")]

    companions: set[str] = set()
    normals = speculars = 0
    for name in sets:
        body = json.loads(read(name)).get("minecraft:texture_set", {})
        folded, has_normal, has_specular = texture_set_maps(name, body)
        companions |= folded
        normals += has_normal
        speculars += has_specular

    events = 0
    for name in [one for one in names if one.endswith("sound_definitions.json")]:
        body = json.loads(read(name))
        definitions = body.get("sound_definitions", body)
        events += len([key for key in definitions if key != "format_version"])

    return {
        "textures": len([name for name in images if name.rsplit(".", 1)[0] not in companions]),
        "texture sets": len(sets),
        "sound events": events,
        "normal sidecars": normals,
        "specular sidecars": speculars,
        "entity types": len(entity_identifiers(names, read)),
        "items": len(declared(names, "items")),
        "blocks": len(declared(names, "blocks")),
    }


def declared(names, directory: str) -> list[str]:
    """The JSON a behaviour pack keeps in one of its own top level directories.

    Anchored to the pack root, because a resource pack holds `models/blocks` and `textures/items`
    whose files describe how something looks rather than declaring one.
    """
    pattern = re.compile(rf"^(?:[^/]+/)?{directory}/[^/]+\.json$")
    return [name for name in names if pattern.match(name)]


def converted_counts(directories: list[Path]) -> dict:
    """What the converter wrote, counted from the files themselves."""
    images = [image.name for directory in directories
              for image in directory.glob("assets/*/textures/**/*.png")]

    def ending(suffix: str) -> int:
        return len([one for one in images if one.endswith(f"{suffix}.png")])

    events = 0
    for directory in directories:
        for manifest in directory.glob("assets/*/sounds.json"):
            events += len(json.loads(manifest.read_text()))

    return {
        "textures": len(images) - ending("_n") - ending("_s") - ending("_emissive"),
        "sound events": events,
        "normal sidecars": ending("_n"),
        "specular sidecars": ending("_s"),
        "emissive layers": ending("_emissive"),
    }


def converted_directories(names, read, root: Path) -> list[Path]:
    """The converted output belonging to one add-on, matched on the uuid its manifests declare."""
    uuids = set()
    for name in [one for one in names if one.endswith("manifest.json")]:
        header = json.loads(read(name)).get("header", {})
        if header.get("uuid"):
            uuids.add(str(header["uuid"]))

    if not root.is_dir():
        return []
    return sorted(one for one in root.iterdir()
                  if one.is_dir() and any(one.name.startswith(f"{uuid}-") for uuid in uuids))


def entity_identifiers(names, read) -> list[str]:
    """The entity ids a behaviour pack declares, in the spelling the mod registers them under.

    A definition in the `minecraft` namespace redescribes an entity the game already has rather
    than adding one, which the mod reports and does not register, so counting it here would leave
    the two sides one apart and summoning it would ask the game for a second player.
    """
    identifiers = []
    for name in declared(names, "entities"):
        description = json.loads(read(name)).get("minecraft:entity", {}).get("description", {})
        identifier = description.get("identifier")
        if identifier and ":" in identifier and not identifier.startswith("minecraft:"):
            namespace, path = identifier.split(":", 1)
            identifiers.append(f"{sanitise(namespace)}:{sanitise(path)}")
    return sorted(identifiers)


def item_identifiers(names, read) -> list[str]:
    """The item ids a behaviour pack declares, in the spelling the mod registers them under."""
    identifiers = []
    for name in declared(names, "items"):
        description = json.loads(read(name)).get("minecraft:item", {}).get("description", {})
        identifier = description.get("identifier")
        if identifier and ":" in identifier:
            namespace, path = identifier.split(":", 1)
            identifiers.append(f"{sanitise(namespace)}:{sanitise(path)}")
    return sorted(identifiers)


def dimension_identifiers(names, read) -> list[str]:
    """The worlds of its own a behaviour pack declares, narrowed to the ones that convert.

    Bedrock builds a dimension a pack declares as empty space and accepts no other generator, so a
    file naming one is reported rather than written and asking the server for it would fail whatever
    the mod did.
    """
    identifiers = []
    for name in declared(names, "dimensions"):
        body = json.loads(read(name)).get("minecraft:dimension", {})
        identifier = body.get("description", {}).get("identifier")
        generation = body.get("components", {}).get("minecraft:generation", {})
        if not identifier or ":" not in identifier:
            continue
        if generation.get("generator_type", "void") != "void":
            continue
        namespace, path = identifier.split(":", 1)
        identifiers.append(f"{sanitise(namespace)}:{sanitise(path)}")
    return sorted(identifiers)


def addons_in(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted(one for one in directory.iterdir()
                  if one.suffix.lower() in (".mcaddon", ".mcpack") or one.is_dir())


def console_script(identifiers: list[str], summons: int,
                   dimensions: list[str] | None = None) -> list[tuple[float, str]]:
    """The commands to type, each with the seconds to wait before it.

    A command in the same tick as a `summon` cannot see the entity, so every step that reads the
    world back waits for ticks to pass first.
    """
    chosen = identifiers[:summons]
    steps = [(2.0, "mcaddon packs"), (1.0, "mcaddon entities"), (1.0, "mcaddon scripts")]
    # A dimension the pack added is a level only once the server built one out of the entry, and
    # `execute in` is the one command that will not parse without it.
    steps += [(0.5, f"execute in {one} run say {DIMENSION_REACHED} {one}")
              for one in dimensions or []]
    steps += [(0.3, f"summon {identifier} 0 100 0") for identifier in chosen]
    steps += [(2.0, f"kill @e[type={identifier}]") for identifier in chosen]
    steps += [(2.0, "bedrock gametest runall"), (4.0, "bedrock gametest clearall")]
    steps += [(2.0, "mcaddon scripts")]
    return steps


def running(launch: Launch, version: str) -> set[int]:
    """Every game of this kind and this version running on this machine, whoever started it.

    The version matters because the four are checked at the same time: each runs out of its own
    directory, so one version's run must neither wait for another's nor kill it. A client belongs to
    one checkout, because its run directory and world are per checkout, so a client another
    checkout started is not this run's to wait for. A server holds a port, which every checkout on
    the machine shares, so servers are counted from all of them.
    """
    found = subprocess.run(["pgrep", "-f", launch.signature], check=False,
                           capture_output=True, text=True)
    pids = {int(one) for one in found.stdout.split() if one.isdigit()}
    commands = {pid: commandOf(pid) for pid in pids}
    ours = f"{ROOT}/.gradle/"
    return {pid for pid, command in commands.items()
            if version in command and not (launch.excluding and launch.excluding in command)
            and (launch.takes_commands or ours in command)}


def commandOf(pid: int) -> str:
    """The full command a process was started with, or nothing when it has already gone."""
    found = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], check=False,
                           capture_output=True, text=True)
    return found.stdout


def lockDirectory(root: Path) -> Path:
    """Where the locks live, which is one place for every checkout of this repository.

    A worktree is a checkout of its own with a `scratchpad/` of its own, so a lock kept there locks
    nothing against a run started from somewhere else. This harness refuses to start beside any dev
    game on the machine, whichever directory started it, so the lock has to reach as far as that
    refusal does. Git's common directory is the same path from every worktree.
    """
    try:
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=root,
                                capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        common = ""

    shared = (root / common).resolve() if common else root / "scratchpad"
    shared.mkdir(parents=True, exist_ok=True)
    return shared


@contextmanager
def exclusive(root: Path, wait: float, version: str):
    """Holds this version's harness lock, so two runs cannot fight over its world and its port.

    One lock per version rather than one for the whole harness: the four versions run out of four
    directories on four ports, so only two runs of the same version have anything to fight over.
    The lock is released when the process ends however it ends.
    """
    path = lockDirectory(root) / f"mcaddon-verify-{version}.lock"
    # Append mode, so waiting for the lock does not blank the pid of whoever holds it.
    handle = path.open("a+")
    deadline = time.monotonic() + wait
    announced = False
    while True:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except OSError:
            if time.monotonic() > deadline:
                handle.close()
                raise SystemExit(f"another run holds {path} after {wait:.0f}s of waiting")
            if not announced:
                print(f"another run holds {path}, waiting")
                announced = True
            time.sleep(5)

    handle.truncate(0)
    handle.write(f"{os.getpid()}\n")
    handle.flush()
    try:
        yield
    finally:
        fcntl.flock(handle, fcntl.LOCK_UN)
        handle.close()


def givePort(root: Path, version: str) -> int:
    """Gives this version's server a port of its own, so four of them can be up at once.

    A dedicated server binds one port and refuses to start when it is taken. `run` never connects to
    the port it hands out; `join` does, which is why the number is returned.
    """
    port = 25565 + VERSIONS.index(version)
    serverSettings(root, version, {"server-port": str(port)})
    return port


def serverSettings(root: Path, version: str, values: dict[str, str]) -> None:
    """Writes the settings named into this version's `server.properties`, leaving the rest alone."""
    properties = root / "versions" / version / "run" / "server.properties"
    lines = []
    if properties.is_file():
        lines = [one for one in properties.read_text(encoding="utf-8").splitlines()
                 if one.split("=", 1)[0] not in values]
    lines += [f"{key}={value}" for key, value in values.items()]
    properties.parent.mkdir(parents=True, exist_ok=True)
    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")


def takeLocalJoins(root: Path, version: str) -> None:
    """Sets a dev server up to accept the dev client, which has no account to authenticate with.

    A dev client is launched with a made-up name and no session, so a server left in online mode
    refuses it before any of this mod's code is reached, and one enforcing secure profiles refuses
    the chat this run reads back.
    """
    serverSettings(root, version, {
        "online-mode": "false",
        "enforce-secure-profile": "false",
        "pause-when-empty-seconds": "0",
    })
    eula = root / "versions" / version / "run" / "eula.txt"
    eula.parent.mkdir(parents=True, exist_ok=True)
    eula.write_text("eula=true\n", encoding="utf-8")


def skip_onboarding(root: Path, version: str, launch: Launch) -> None:
    """Keeps a client that has never run in its directory from opening the accessibility screen.

    A fresh game directory has no `options.txt`, and the game then shows its onboarding screen in
    place of the quick play it was asked for, so the client sits there and never joins or opens
    anything. A directory with an `options.txt` of its own is left as it is, except for that one flag.
    """
    directory = root / "versions" / version / "run"
    if GUEST_PROPERTY in launch.signature:
        directory = directory / "join-client"
    directory.mkdir(parents=True, exist_ok=True)
    options = directory / "options.txt"
    lines = options.read_text(encoding="utf-8").splitlines() if options.is_file() else []
    lines = [one for one in lines if not one.startswith("onboardAccessibility:")]
    options.write_text("\n".join(lines + ["onboardAccessibility:false"]) + "\n", encoding="utf-8")


def free_world_lock(root: Path, version: str) -> None:
    """Drops the world lock a killed run of this harness left behind."""
    (root / "versions" / version / "run" / "world" / "session.lock").unlink(missing_ok=True)


def wait_for(process: subprocess.Popen, lines: list[str], pattern: re.Pattern, deadline: float) -> bool:
    """Waits for a line, giving up on the deadline, on a Gradle failure, or on the game exiting."""
    seen = 0
    draining = False
    while True:
        while seen < len(lines):
            line = lines[seen]
            seen += 1
            if pattern.search(line):
                return True
            if BUILD_FAILED.search(line):
                return False

        if draining or time.monotonic() > deadline:
            return False
        if process.poll() is not None:
            # Let the reader hand over whatever the game printed on its way out.
            draining = True
        time.sleep(0.25)


def world(root: Path, version: str) -> str | None:
    """A saved world this version can be told to open, or None when it has none to open.

    A client stopped at the title screen has nobody playing, so everything a pack does with a player
    is untouched by the run: the player surface, the forms a pack opens, and every event carrying
    somebody who used something. Opening a world is what puts a player in front of that code.

    A world is a fixture rather than something anybody made by hand, so one is taken from the
    dedicated server's own when there is none saved. A `run` leaves one behind.
    """
    saves = root / "versions" / version / "run" / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    served = root / "versions" / version / "run" / "world"
    copied = saves / "Scene World"

    if not (served / "level.dat").is_file():
        existing = next(
            (one.name for one in sorted(saves.iterdir()) if (one / "level.dat").is_file()), None)
        return existing

    # A fresh copy every run, because a client writes its player into the world as it leaves and a
    # server leaves one under `players/`. Either of those opens the next run behind a screen waiting
    # to be answered, and a checkout that has been run in once would then fail every client check
    # after it for a reason that has nothing to do with the mod.
    shutil.rmtree(copied, ignore_errors=True)
    shutil.copytree(served, copied, dirs_exist_ok=True)
    shutil.rmtree(copied / "datapacks", ignore_errors=True)
    shutil.rmtree(copied / "players", ignore_errors=True)
    return copied.name


def play(root: Path, version: str, launch: Launch, ready: re.Pattern,
         script: list[tuple[float, str]], timeout: float, hold: float,
         verbose: bool, quick_play: str | None = None, join: str | None = None,
         while_loaded=None, serve: Path | None = None) -> tuple[list[str], Path, str]:
    """Starts one game, waits for it to load, and always ends it by the deadline.

    A server is asked to stop and killed if it will not; a client has nothing to ask, so it is held
    open for a moment once the mod has loaded and then killed.

    `while_loaded` is called with this game's process and its lines once it has loaded and before
    anything is typed into it, which is what lets a second game run against a server that is up.
    """
    log = root / "build" / "verify" / f"{version}-{launch.name}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    if launch.takes_commands:
        givePort(root, version)
    else:
        skip_onboarding(root, version, launch)

    lines: list[str] = []
    deadline = time.monotonic() + timeout
    started_before = running(launch, version)
    command = ["./gradlew", f":{version}:{launch.task}", "--console=plain"]
    if quick_play:
        command.append(f"-PquickPlay={quick_play}")
    if join:
        command.append(f"-PjoinServer={join}")
    if serve:
        command.append(f"-Pserved={serve}")
    process = subprocess.Popen(
        command,
        cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, start_new_session=True,
        env={**os.environ, "JAVA_HOME": JAVA_HOME})
    output = process.stdout
    if output is None:
        raise RuntimeError(f"the {launch.name} was started without a readable output pipe")

    def pump() -> None:
        with log.open("w", encoding="utf-8") as sink:
            for line in output:
                lines.append(line.rstrip("\n"))
                sink.write(line)
                sink.flush()
                if verbose:
                    print(line, end="")

    reader = Thread(target=pump, daemon=True)
    reader.start()

    loaded = wait_for(process, lines, ready, deadline)
    outcome = "loaded"
    if loaded and while_loaded is not None:
        while_loaded(process, lines)
    if not loaded:
        outcome = "ended before loading" if process.poll() is not None else "never finished loading"
    elif launch.takes_commands:
        outcome = "stopped cleanly"
        for wait, command in script:
            if time.monotonic() + wait > deadline:
                outcome = "script cut short by the deadline"
                break
            time.sleep(wait)
            type_into(process, command)
        type_into(process, "stop")
    else:
        time.sleep(min(hold, max(0.0, deadline - time.monotonic())))

    if launch.takes_commands and loaded:
        if not wait_until_exit(process, min(deadline, time.monotonic() + 30)):
            outcome = "killed on the deadline"
            kill(process, started_before, launch, version)
    elif process.poll() is None:
        outcome = "killed after loading" if loaded else "killed on the deadline"
        kill(process, started_before, launch, version)

    close_input(process)
    reader.join(timeout=5)

    # The next check refuses to start beside a running game, so wait for this one to leave the
    # process table rather than have it read as somebody else's.
    settle = time.monotonic() + 15
    while running(launch, version) - started_before and time.monotonic() < settle:
        time.sleep(1)

    free_world_lock(root, version)
    return lines, log, outcome


def type_into(process: subprocess.Popen, command: str) -> None:
    if process.stdin is None:
        return
    try:
        process.stdin.write(f"{command}\n")
        process.stdin.flush()
    except (BrokenPipeError, ValueError, OSError):
        pass


def close_input(process: subprocess.Popen) -> None:
    if process.stdin is None:
        return
    try:
        process.stdin.close()
    except (BrokenPipeError, OSError):
        pass


def wait_until_exit(process: subprocess.Popen, deadline: float) -> bool:
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return True
        time.sleep(0.25)
    return False


def kill(process: subprocess.Popen, started_before: set[int], launch: Launch,
         version: str) -> None:
    """Ends this run's process group, then the game the Gradle daemon forked on its behalf.

    The Gradle daemon outlives a build and owns the forked game, so the group alone does not reach
    it. Only games of this checkout that appeared after this run began are killed, which leaves one
    somebody else is running alone.
    """
    for stage in (signal.SIGTERM, signal.SIGKILL):
        if process.poll() is not None:
            break
        try:
            os.killpg(os.getpgid(process.pid), stage)
        except (ProcessLookupError, PermissionError):
            break
        time.sleep(2)

    ours = f"{ROOT}/.gradle/"
    for pid in running(launch, version) - started_before:
        # A server another checkout started during this run, a game test server included, is no
        # business of this run's, though `running` counts it so that the port is waited for.
        if ours not in commandOf(pid):
            continue
        try:
            os.kill(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            continue


def scripts_ran(lines: list[str], verdict: Verdict) -> None:
    """A pack that ships a script has to have had it run, not merely loaded without complaint."""
    text = "\n".join(lines)
    if not SHIPS_SCRIPT.search(text):
        return

    if SCRIPTS_RUNNING.search(text):
        verdict.notes.append("the script engine came up for the packs that ship one")
        return

    verdict.fail("a pack ships a script and the engine never came up",
                 "no 'Running scripts from' line, so every pack script is off")


def scan(lines: list[str], verdict: Verdict, expected: tuple[tuple[str, str], ...]) -> None:
    """Files every log line under the first failure it matches, benign ones aside."""
    for number, line in enumerate(lines, start=1):
        if BENIGN_PATTERN.search(line):
            continue
        for name, pattern in FAILURE_PATTERNS:
            if pattern.search(line):
                verdict.fail(name, "matched a known failure signature", [f"{number}: {line.strip()}"])
                break

    for name, pattern in expected:
        if not any(re.search(pattern, line) for line in lines):
            verdict.fail(name, f"no line matched {pattern!r}")


def reported_counts(lines: list[str]) -> dict[str, int]:
    """The numbers the mod itself printed, so a drop shows up against the source pack."""
    joined = "\n".join(lines)
    found = {}
    for name, pattern in REPORTED:
        matches = re.findall(pattern, joined)
        if matches:
            found[name] = sum(int(one) for one in matches)
    return found


def addon_source(root: Path, version: str,
                 verdict: Verdict) -> tuple[list[Path], list[str], dict[str, int], list[str]]:
    """What the add-ons installed for one version declare, counted before the game starts."""
    addons = addons_in(root / "versions" / version / "run" / "addons")
    identifiers: list[str] = []
    dimensions: list[str] = []
    expected: dict[str, int] = {}

    for addon in addons:
        names, read = open_source(addon)
        identifiers += entity_identifiers(names, read)
        dimensions += dimension_identifiers(names, read)
        for key, value in source_counts(names, read).items():
            expected[key] = expected.get(key, 0) + value

    verdict.notes.append(
        f"{len(addons)} add-on(s) in versions/{version}/run/addons" if addons
        else f"no add-on in versions/{version}/run/addons, nothing to test against")
    return addons, identifiers, expected, dimensions


def occupied(launch: Launch, verdict: Verdict, version: str) -> bool:
    """Whether somebody else's game of this kind and version is up, which this will not disturb."""
    others = running(launch, version)
    if not others:
        return False
    verdict.fail(f"a dev {launch.name} is already running",
                 f"pid(s) {', '.join(str(one) for one in sorted(others))} are up; "
                 f"this harness does not kill a {launch.name} it did not start")
    return True


def gradle_failed(lines: list[str], outcome: str, verdict: Verdict) -> None:
    """Gradle also reports a failure for a game this harness killed, so that outcome says nothing."""
    if outcome.startswith("killed"):
        return
    failed = [line for line in lines if "FAILURE: Build failed" in line]
    if failed:
        verdict.fail("gradle failed", "the version did not build or the run task failed", failed)


def pack_gametests(lines: list[str], verdict: Verdict) -> None:
    """Checks that the tests a pack declares itself ran, and that a failing one was called failing.

    The probe pack ships one test written to pass and one written to fail, so both verdicts have to
    appear. Counting only the passes would call a run green where every test silently never started.
    """
    passed = {match.group(1) for match in (TEST_PASSED.search(line) for line in lines) if match}
    failed = {match.group(1) for match in (TEST_FAILED.search(line) for line in lines) if match}
    if not passed and not failed:
        return

    verdict.notes.append(f"{len(passed)} pack-declared test(s) passed, {len(failed)} failed")
    if PROBE_PASSES not in passed:
        verdict.fail("a pack's own test never passed",
                     f"{PROBE_PASSES} reached no verdict, so nothing proves a pack's tests run")
    if PROBE_FAILS not in failed:
        verdict.fail("a failing test was not reported as failing",
                     f"{PROBE_FAILS} is written to fail and the run did not say so")
    for name in sorted(failed - {PROBE_FAILS}):
        verdict.fail("a pack's own test failed", name)


def pack_dimensions(typed: list[str], lines: list[str], verdict: Verdict) -> None:
    """Checks that every world a pack declared is one the server built a level out of.

    `execute in <dimension>` is refused by the parser when the dimension is not there, so the line
    the command would have printed is the whole of the evidence. A game test server cannot answer
    this: it bakes its dimensions against an empty registry and never sees a data pack's own.
    """
    asked = [one.split()[2] for one in typed if one.startswith("execute in ")]
    if not asked:
        return

    reached = {line.split(DIMENSION_REACHED + " ")[1].split()[0]
               for line in lines if DIMENSION_REACHED + " " in line}
    verdict.notes.append(f"{len(reached)} of {len(asked)} pack-declared dimension(s) are levels")
    for name in sorted(set(asked) - reached):
        verdict.fail("a pack's own dimension is not a level",
                     f"{name} was converted and the server has no world under that name")


def check_server(root: Path, version: str, timeout: float, summons: int, verbose: bool) -> Verdict:
    verdict = Verdict(f"{version} server")
    if occupied(SERVER, verdict, version):
        return verdict

    addons, identifiers, expected, dimensions = addon_source(root, version, verdict)
    # A fresh checkout has no `eula.txt`, and a server without one prints a line about it and
    # stops, which reads as a mod that would not load rather than as a file nobody wrote yet.
    takeLocalJoins(root, version)
    free_world_lock(root, version)
    # A dimension is written from 26 on. 1.20.1 writes no worldgen at all, and 1.21.1 calls a world
    # carrying a fourth dimension experimental whatever pack it came from, so both report instead.
    script = console_script(identifiers, summons,
                            dimensions if version.startswith("26.") else [])
    lines, log, outcome = play(root, version, SERVER, SERVER.ready, script, timeout, 0.0, verbose)

    verdict.notes.append(f"server {outcome}, {len(lines)} lines in {log.relative_to(root)}")
    scripts_ran(lines, verdict)
    scan(lines, verdict, (("server reached done", r"Done \("),)
         + (EXPECTED_WITH_ADDON if addons else ()))
    gradle_failed(lines, outcome, verdict)

    attempted = len([one for _, one in script if one.startswith("summon ")])
    summoned = len([one for one in lines if SUMMONED.search(one)])
    if attempted:
        verdict.notes.append(f"{summoned} of {attempted} summoned add-on entities reached the world")
    if summoned < attempted:
        verdict.fail("entity would not summon",
                     f"{attempted} summon commands, {summoned} entities")

    pack_gametests(lines, verdict)
    pack_dimensions([one for _, one in script], lines, verdict)

    reported = reported_counts(lines)
    verdict.counts = {"source": expected, "reported by the mod": reported}
    for key in ("entity types", "items", "blocks"):
        if expected.get(key) and reported.get(key, 0) == 0:
            verdict.fail("nothing registered",
                         f"the packs declare {expected[key]} {key}, the mod registered none")

    return verdict


def check_client(root: Path, version: str, timeout: float, hold: float, verbose: bool) -> Verdict:
    """Starts the client, which is the only half that loads models, textures and render controllers."""
    verdict = Verdict(f"{version} client")
    if occupied(CLIENT, verdict, version):
        return verdict

    addons, _, expected, _ = addon_source(root, version, verdict)
    opened = world(root, version)
    # Told to open a world, the run waits for the world rather than for the drawing: everything the
    # drawing line proves has already happened by then, and a client stopped on a screen asking the
    # player a question would otherwise read as one that loaded.
    ready = CLIENT_IN_WORLD if opened else (CLIENT.ready if addons else CLIENT.ready_without_addon)
    lines, log, outcome = play(root, version, CLIENT, ready, [], timeout, hold, verbose, opened)

    verdict.notes.append(f"client {outcome}, {len(lines)} lines in {log.relative_to(root)}")
    if not opened:
        verdict.notes.append("no saved world, so nothing a pack does with a player was run")
    elif any(CLIENT_IN_WORLD.search(line) for line in lines):
        verdict.notes.append(f"opened the world {opened!r}, so a player was in it")
    else:
        verdict.fail("the world never opened",
                     f"the client was told to open {opened!r} and never started a server for it, "
                     "which is what a screen waiting to be answered looks like. A checkout no "
                     "client has ever opened a world in fails this way whatever the mod does, so "
                     "check whether the same run passes in the main checkout before reading it as "
                     "a fault of the change under test")
    scan(lines, verdict, EXPECTED_WITH_ADDON if addons else ())
    gradle_failed(lines, outcome, verdict)

    reported = reported_counts(lines)
    verdict.counts = {"source": expected, "reported by the mod": reported}
    if not addons:
        return verdict

    drawn = [CLIENT_DEFINITIONS.search(line) for line in lines]
    definitions = next((int(one.group(1)) for one in drawn if one), None)
    if definitions is None:
        verdict.fail("add-on never reached the client",
                     "the mod did not report how many entities have a client definition")
    elif definitions == 0:
        verdict.fail("no entity has a client definition",
                     f"the packs declare {expected.get('entity types', 0)} entity types "
                     "and the client drew none of them")
    else:
        verdict.notes.append(f"{definitions} add-on entities have a client definition")

    return verdict


def companions_pinned(root: Path, version: str, side: str) -> dict[str, str]:
    """The mods this version's properties ask for on one side, as mod id against the version pinned."""
    settings = root / "versions" / version / "gradle.properties"
    pinned: dict[str, str] = {}
    for line in settings.read_text().splitlines():
        name, _, value = line.partition("=")
        slug = name.strip().removeprefix("deps.")
        if not name.strip().startswith("deps.") or slug not in COMPANION_IDS or not value.strip():
            continue
        identifier, belongs = COMPANION_IDS[slug]
        if belongs == "both" or belongs == side:
            pinned[identifier] = value.strip()
    return pinned


def loaded_mods(lines: list[str]) -> set[str]:
    """Every mod id Fabric Loader listed as it started."""
    found = set()
    for line in lines:
        match = MOD_ENTRY.match(line)
        if match:
            found.add(match.group(1))
    return found


def companions_ran(lines: list[str], pinned: dict[str, str], verdict: Verdict) -> None:
    """Every mod the properties pin has to have loaded, or the run proves nothing about it."""
    if not pinned:
        verdict.notes.append("no companion mod is pinned for this version")
        return

    for line in lines:
        if RESOLUTION_FAILED.search(line):
            verdict.fail("the mod set would not resolve",
                         "Fabric refused the set of mods, so nothing about them was tested",
                         [line.strip()])
            return

    loaded = loaded_mods(lines)
    missing = sorted(name for name in pinned if name not in loaded)
    if missing:
        verdict.fail("a pinned companion never loaded",
                     "pinned in gradle.properties and absent from the loader's own list: "
                     + ", ".join(missing))
    present = sorted(name for name in pinned if name in loaded)
    verdict.notes.append(f"{len(present)} of {len(pinned)} companion mods this side loaded: "
                         + ", ".join(present))
    verdict.notes.append(f"{len(loaded)} mods in the game altogether")


def blamed_configs(lines: list[str], verdict: Verdict) -> None:
    """Which mod's mixins a failure named, so a neighbour breaking is read as loudly as this one.

    A mixin that finds no target is logged at `WARN` and takes a feature off without failing
    anything, so the line is read here rather than left to the error check.

    A mixin whose whole target class is absent belongs to a mod's own optional integration with a
    mod nobody installed, so for a neighbour it is a note. Anything naming this mod's own
    configuration is a failure whichever shape it takes.
    """
    ours = "mcaddon"
    blamed: dict[str, int] = {}
    for number, line in enumerate(lines, start=1):
        if not NO_INJECTION.search(line):
            continue
        for config in BLAMED_CONFIG.findall(line):
            owner = config.split(".")[0]
            blamed[owner] = blamed.get(owner, 0) + 1
            if owner.startswith(ours):
                verdict.fail("an injector of this mod found no target",
                             "the hook is off and nothing else said so", [f"{number}: {line.strip()}"])
            elif not OPTIONAL_INTEGRATION.search(line):
                verdict.fail("an injector of another mod found no target",
                             f"{owner} lost a hook while this mod was in the room",
                             [f"{number}: {line.strip()}"])
    if blamed:
        verdict.notes.append("mixin configurations named in warnings: "
                             + ", ".join(f"{name} x{count}" for name, count in sorted(blamed.items())))


def check_companions(root: Path, version: str, timeout: float, summons: int, hold: float,
                     verbose: bool) -> list[Verdict]:
    """Runs a server and a client beside the mods this one is meant to work with.

    The companions are already on the run classpath, so this starts the same two games `run` and
    `client` do. What it adds is reading the log back for the neighbours as well: that each pinned
    mod really loaded, and that no injector on either side lost its target while the other was
    there.
    """
    found = [check_server(root, version, timeout, summons, verbose),
             check_client(root, version, timeout, hold, verbose)]

    for verdict, name in zip(found, ("server", "client")):
        pinned = companions_pinned(root, version, name)
        verdict.subject = f"{version} {name} beside {len(pinned)} companion mods"
        log = root / "build" / "verify" / f"{version}-{name}.log"
        if not log.is_file():
            verdict.fail("no log to read the companions out of", f"{log} was never written")
            continue

        lines = log.read_text(errors="replace").splitlines()
        companions_ran(lines, pinned, verdict)
        blamed_configs(lines, verdict)
    return found


def zip_copy(source: Path, destination: Path, edit) -> None:
    """Writes a copy of an archive with every entry's bytes passed through `edit(name, body)`."""
    with zipfile.ZipFile(source) as original, \
            zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as copy:
        for entry in original.infolist():
            copy.writestr(entry, edit(entry.filename, original.read(entry)))


def bump_version(name: str, body: bytes) -> bytes:
    """Raises the version a pack's manifest states, which changes the archive and nothing it builds."""
    if not re.fullmatch(r"[^/]+/manifest\.json", name):
        return body
    manifest = json.loads(body)
    manifest["header"]["version"][2] += 1
    return json.dumps(manifest, indent=2).encode("utf-8")


def widen_block(name: str, body: bytes) -> bytes:
    """Adds a value to the lamp's `tp:level` state, which changes the block's Java shape.

    A block with one more state is a different set of block states in the registry, so the server's
    copy of the add-on cannot take the place of this one without a restart.
    """
    if not name.endswith("blocks/lamp.json"):
        return body
    block = json.loads(body)
    levels = block["minecraft:block"]["description"]["states"]["tp:level"]
    levels.append(max(levels) + 1)
    return json.dumps(block, indent=2).encode("utf-8")


def stock_guest(root: Path, version: str, variant: str, server_addons: list[Path],
                verdict: Verdict) -> Path:
    """Gives the second client the add-ons a variant calls for and nothing from an earlier run.

    `bare` leaves it none, which is a player who never installed the add-on. `other` gives it the
    server's archives with a newer version in each manifest, which takes the replace path because
    the Java shape is equal, and an add-on the server does not have, which goes dormant while it is
    away. `reshaped` gives it a copy whose block has one more state, which has to be refused.
    """
    guest = root / "versions" / version / "run" / "join-client"
    shutil.rmtree(guest / "addons", ignore_errors=True)
    shutil.rmtree(guest / ".mcaddon", ignore_errors=True)
    (guest / "addons").mkdir(parents=True)

    edit = {"other": bump_version, "reshaped": widen_block}.get(variant)
    for addon in server_addons if edit else []:
        if addon.is_file():
            zip_copy(addon, guest / "addons" / addon.name, edit)
        else:
            verdict.notes.append(f"{addon.name} is a directory, so the second client has no copy of it")

    if variant == "other":
        subprocess.run([sys.executable, str(root / "tools" / "testpack" / "build_served.py"),
                        "--out", str(guest / "addons" / "extra-addon.mcaddon")],
                       check=True, capture_output=True)
    return guest


def ask_host(directory: Path, *lines: str) -> None:
    """Hands the host client its next `lan.request`, written whole so it is never read half done."""
    pending = directory / "lan.request.part"
    pending.write_text("\n".join(lines) + "\n", encoding="utf-8")
    pending.rename(directory / "lan.request")


def wait_for_file(path: Path, deadline: float) -> str | None:
    while time.monotonic() < deadline:
        if path.is_file() and path.read_text(encoding="utf-8").strip():
            return path.read_text(encoding="utf-8").strip()
        time.sleep(0.5)
    return None


def wait_for_count(lines: list[str], pattern: re.Pattern, count: int, deadline: float) -> bool:
    while time.monotonic() < deadline:
        if sum(1 for one in list(lines) if pattern.search(one)) >= count:
            return True
        time.sleep(0.5)
    return False


def sync_lines(lines: list[str], verdict: Verdict, expected: int, side: str) -> None:
    """Reads a client's log for what taking a server's add-on set looks like, from first to last."""
    received = [int(match.group(1)) for match in map(RECEIVED.search, lines) if match]
    activated = [int(match.group(1)) for match in map(ACTIVATED.search, lines) if match]
    if expected not in received:
        verdict.fail(f"{side} was not offered the server's add-ons",
                     f"no `Received {expected} add-ons from the server.` line, saw {received}")
    if expected not in activated:
        verdict.fail(f"{side} did not activate the server's add-ons",
                     f"no `Activated the server's add-on set of {expected} add-ons.` line, saw {activated}")
    if not any(RESTORED.search(one) for one in lines):
        verdict.fail(f"{side} did not put its own add-ons back",
                     "no `Restored the installed add-on set.` line after the session ended")


def check_join(root: Path, version: str, timeout: float, hold: float, verbose: bool,
               variant: str = "plain") -> Verdict:
    """Starts a dedicated server and connects a client to it, which is what a multiplayer world is.

    Everything the other two modes reach happens inside one process, where the two halves share
    every registry. What only happens between two processes is the sync: the server decides what of
    its registries to send, the client says what it already holds, and a converted pack's entries
    are what the two have to agree about. The answer is read back off both logs.

    Three things have to line up for this to pass, and a client that merely launched satisfies none
    of them: the server says a client logged in and spawned, the server's own player list says
    somebody is standing in the world, and a message typed into the server console comes back out of
    the client's log. The last one is the whole round trip, because only the server can send it and
    only a client in that server's world can print it.

    `plain` runs the first client, which shares its add-ons with the server. The other variants run
    the second client from `run/join-client/`, which has add-ons of its own, and also read what it
    logged about taking the server's set. `bare` has none, so the whole set is downloaded and
    activated. `other` has the server's archives at another version, which are replaced, and one the
    server does not have, which stays installed and goes dormant. `reshaped` has a copy of the
    server's add-on that cannot be replaced without a restart, so the client has to be turned away
    with the add-on's name. The harness ends the session of the first two by stopping the server,
    which is what makes the client put its own set back.
    """
    labels = {"plain": "", "bare": " without the add-on", "other": " with other add-ons",
              "reshaped": " with a reshaped add-on"}
    verdict = Verdict(f"{version} join" + labels[variant])
    guest = variant != "plain"
    joiner = replace(GUEST if guest else CLIENT, name="join-client")
    if occupied(SERVER, verdict, version) or occupied(joiner, verdict, version):
        return verdict

    addons, _, expected, _ = addon_source(root, version, verdict)
    items = [one for addon in addons for one in item_identifiers(*open_source(addon))]
    free_world_lock(root, version)
    port = givePort(root, version)
    takeLocalJoins(root, version)

    # Unique per run, so a line left in an older log cannot pass for this run's round trip.
    marker = f"MCADDONHEARD{os.getpid()}{int(time.time())}"
    heard = re.compile(re.escape(marker))
    client: dict = {"lines": [], "outcome": "never started", "log": None}
    # A refused client never reaches the world, so the refusal is what it is waited on for.
    ready = re.compile(r"cannot replace yours|[Ll]ost connection|Disconnected") \
        if variant == "reshaped" else heard

    def connect(server: subprocess.Popen, served: list[str]) -> None:
        """Runs a client against the server that is up, and talks to it from the server console."""
        deadline = time.monotonic() + timeout
        if guest:
            stock_guest(root, version, variant, addons, verdict)

        def talk() -> None:
            if not wait_for(server, served, JOINED, deadline):
                return
            spoken = ["list"] + [f"give @a {one}" for one in items[:1]] + [f"say {marker}"]
            for command in spoken:
                time.sleep(1.5)
                type_into(server, command)

        def leave(process: subprocess.Popen, lines: list[str]) -> None:
            """Stops the server under a client that is in its world and waits for it to put its set back."""
            type_into(server, "stop")
            wait_for(process, lines, RESTORED, min(deadline, time.monotonic() + 60))

        talking = Thread(target=talk, daemon=True)
        talking.start()
        client["lines"], client["log"], client["outcome"] = play(
            root, version, joiner, ready, [], timeout, hold if variant == "plain" else 0.0, verbose,
            join=f"127.0.0.1:{port}",
            while_loaded=leave if variant in ("bare", "other") else None)
        talking.join(timeout=10)

    served, log, outcome = play(root, version, replace(SERVER, name="join-server"), SERVER.ready,
                                [(1.0, "mcaddon packs")], timeout, 0.0, verbose,
                                while_loaded=connect)

    seen = client["lines"]
    verdict.notes.append(f"server {outcome}, {len(served)} lines in {log.relative_to(root)}")
    verdict.notes.append(f"client {client['outcome']}, {len(seen)} lines in "
                         + (str(client["log"].relative_to(root)) if client["log"] else "no log"))
    for line in served:
        left = LOST_CONNECTION.search(line)
        if left:
            verdict.notes.append(f"the client left saying: {left.group(1).strip()}")

    scan(served, verdict, (("server reached done", r"Done \("),)
         + (EXPECTED_WITH_ADDON if addons else ()))
    scan(seen, verdict, EXPECTED_WITH_ADDON if addons and not guest else ())
    gradle_failed(served, outcome, verdict)
    gradle_failed(seen, client["outcome"], verdict)

    if variant == "reshaped":
        refusal = [one for one in served + seen if CANNOT_REPLACE.search(one)]
        if not refusal:
            verdict.fail("the reshaped add-on was not refused by name",
                         "nothing said `The server's copy of <add-on> cannot replace yours`")
        else:
            named = CANNOT_REPLACE.search(refusal[0])
            verdict.notes.append(f"refused, naming {named.group(1) if named else 'nothing'}")
        if any(JOINED.search(one) for one in served):
            verdict.fail("a client with a reshaped add-on entered the world",
                         "the server wrote a join for a client that had to be turned away")
        return verdict

    if not any(LOGGED_IN.search(one) for one in served):
        verdict.fail("no client logged in",
                     "the server never wrote a login, so nothing connected to it")
    joined = {match.group(1) for match in (JOINED.search(one) for one in served) if match}
    if not joined:
        verdict.fail("the client never entered the world",
                     "the server never wrote a join, so the client got no further than connecting")
    else:
        verdict.notes.append(f"the server put {', '.join(sorted(joined))} in its world")

    standing = [int(match.group(1)) for match in
                (PLAYERS_ONLINE.search(one) for one in served) if match]
    if not standing:
        verdict.fail("the server was never asked who was on it",
                     "no answer to `list`, so nothing says the client was still there")
    elif max(standing) == 0:
        verdict.fail("nobody was on the server",
                     "`list` answered with an empty world while a client was meant to be in it")

    if not any(heard.search(one) for one in seen):
        verdict.fail("the client never heard the server",
                     f"{marker} was typed into the server console and never reached the client, "
                     "so nothing proves the client was in that server's world")

    if items and not any(GAVE.search(one) for one in served):
        verdict.fail("an add-on item would not reach the player",
                     f"`give @a {items[0]}` was refused, so the item the packs declare is not "
                     "something the server can hand to somebody standing in it")

    if guest:
        sync_lines(seen, verdict, len(addons), "the client")
        if variant == "bare":
            received = list((root / "versions" / version / "run" / "join-client" / ".mcaddon"
                             / "received").glob("*.mcaddon"))
            if len(received) != len(addons):
                verdict.fail("the add-ons were not kept in the client's cache",
                             f"{len(received)} archives under `.mcaddon/received` for {len(addons)} add-ons")
        elif not any(DOWNLOADING.search(one) for one in seen):
            verdict.fail("the changed add-on was not downloaded",
                         "no `Downloading N add-ons` line, so the client's own copy was not replaced")
        return verdict

    # Both halves register an add-on's items themselves and the sync then insists the two lists
    # agree, so a count that differs names the cause of a disconnect the reason alone does not.
    byServer = reported_counts(served)
    byClient = reported_counts(seen)
    for key in ("entity types", "items", "blocks"):
        if key in byServer and key in byClient and byServer[key] != byClient[key]:
            verdict.fail("the two halves registered different content",
                         f"the server has {byServer[key]} {key} and the client {byClient[key]}")

    verdict.counts = {"source": expected, "reported by the server": byServer,
                      "reported by the client": byClient}
    return verdict


LOGGED_IN_NAME = re.compile(r"(\S+)\[[^\]]*\] logged in with entity id")


def check_lan(root: Path, version: str, timeout: float, verbose: bool,
              dormant: bool = False) -> Verdict:
    """Opens a singleplayer world to the LAN and has a second client join it as a guest.

    The host is the first client with a saved world. Nobody can press the Open to LAN button, so a
    development-only handler answers a `lan.request` file in its run directory by publishing the
    world and writing the port back, and runs the console commands the same file carries. The guest
    is the second client with no add-ons, which has to receive the host's set and use an item from
    it. The host holds the add-ons in the integrated server it shares with its own client, so what
    it must never do is download, activate or restore anything: those lines in its log fail the run.

    With `dormant` the host first joins a dedicated server that hands out one more add-on than the
    host has installed and leaves again, so the host holds that archive as a dormant add-on. The
    guest is then offered it as an inactive entry and has to reach the world all the same.
    """
    verdict = Verdict(f"{version} lan" + (" with a dormant add-on" if dormant else ""))
    if any(occupied(launch, verdict, version) for launch in (CLIENT, GUEST, SERVER)):
        return verdict

    addons, _, expected, _ = addon_source(root, version, verdict)
    items = [one for addon in addons for one in item_identifiers(*open_source(addon))]
    run = root / "versions" / version / "run"
    free_world_lock(root, version)
    marker = f"MCADDONHEARD{os.getpid()}{int(time.time())}"
    heard = re.compile(re.escape(marker))
    guest: dict = {"lines": [], "outcome": "never started", "log": None}
    offered = len(addons)

    if dormant:
        extra = root / "build" / "verify" / "lan-served"
        shutil.rmtree(extra, ignore_errors=True)
        extra.mkdir(parents=True)
        subprocess.run([sys.executable, str(root / "tools" / "testpack" / "build_served.py"),
                        "--out", str(extra / "dormant-addon.mcaddon")],
                       check=True, capture_output=True)
        offered += 1

    shutil.rmtree(run / "join-client" / "addons", ignore_errors=True)
    (run / "join-client" / "addons").mkdir(parents=True)
    shutil.rmtree(run / "join-client" / ".mcaddon", ignore_errors=True)
    (run / "lan.port").unlink(missing_ok=True)
    (run / "lan.request").unlink(missing_ok=True)

    name = world(root, version)
    if name is None:
        verdict.fail("no world to host",
                     "`run` has not been run in this checkout, so there is no saved world to open")
        return verdict

    deadline = time.monotonic() + timeout

    def host_loaded(host: subprocess.Popen, hosted: list[str]) -> None:
        # Peaceful and creative before anybody joins, because a host or a guest standing in a
        # survival world at night is killed within seconds, and a dead player holds no chunks.
        ask_host(run, "difficulty peaceful", "gamemode creative @a", "publish")
        port = wait_for_file(run / "lan.port", min(deadline, time.monotonic() + 60))
        if port is None:
            verdict.fail("the world was never opened to the LAN",
                         "`lan.request` was not answered with a port")
            return

        def talk() -> None:
            if not wait_for_count(hosted, LOGGED_IN, 2, deadline):
                return
            time.sleep(3)
            ask_host(run, "list", *[f"give @a {one}" for one in items[:1]], f"say {marker}")

        def leave(process: subprocess.Popen, lines: list[str]) -> None:
            names = [match.group(1) for match in map(LOGGED_IN_NAME.search, list(hosted)) if match]
            if len(names) > 1:
                ask_host(run, f"kick {names[-1]}")
            wait_for(process, lines, RESTORED, min(deadline, time.monotonic() + 60))

        talking = Thread(target=talk, daemon=True)
        talking.start()
        guest["lines"], guest["log"], guest["outcome"] = play(
            root, version, GUEST, heard, [], timeout, 0.0, verbose,
            join=f"127.0.0.1:{port}", while_loaded=leave)
        talking.join(timeout=10)

    if dormant:
        hosted, log, outcome = lan_host_after_visit(root, version, timeout, verbose, extra, name,
                                                    host_loaded, verdict)
    else:
        hosted, log, outcome = play(root, version, replace(CLIENT, name="lan-host"), LOGGED_IN, [],
                                    timeout, 0.0, verbose, quick_play=name, while_loaded=host_loaded)
    # What the host logged while it hosted, which is everything after its visit when it made one.
    restored = [index for index, one in enumerate(hosted) if RESTORED.search(one)]
    hosting = hosted[restored[0] + 1:] if dormant and restored else hosted
    seen = guest["lines"]
    verdict.notes.append(f"host {outcome}, {len(hosted)} lines in "
                         + (str(log.relative_to(root)) if log else "no log"))
    verdict.notes.append(f"guest {guest['outcome']}, {len(seen)} lines in "
                         + (str(guest["log"].relative_to(root)) if guest["log"] else "no log"))

    scan(hosted, verdict, EXPECTED_WITH_ADDON if addons else ())
    scan(seen, verdict, ())
    gradle_failed(hosted, outcome, verdict)
    gradle_failed(seen, guest["outcome"], verdict)

    if not (run / "lan.port").is_file():
        return verdict
    if sum(1 for one in hosting if LOGGED_IN.search(one)) < 2:
        verdict.fail("the guest never logged in to the host's world",
                     "the host logged one login, which is its own")
    if not any(heard.search(one) for one in seen):
        verdict.fail("the guest never heard the host",
                     f"{marker} was said in the host's world and never reached the guest")
    if items and not any(GAVE.search(one) for one in hosted):
        verdict.fail("an add-on item would not reach the guest",
                     f"`give @a {items[0]}` was refused in the host's world")

    for pattern, what in ((RECEIVED, "received"), (DOWNLOADING, "downloaded"),
                          (ACTIVATED, "activated"), (RESTORED, "restored")):
        if any(pattern.search(one) for one in hosting):
            verdict.fail("the host took part in the sync",
                         f"the host's log says it {what} add-ons, which only the guest may do")

    received = [int(match.group(1)) for match in map(RECEIVED.search, seen) if match]
    if offered not in received:
        verdict.fail("the guest was not offered the host's add-ons",
                     f"no `Received {offered} add-ons from the server.` line, saw {received}")
    activated = [int(match.group(1)) for match in map(ACTIVATED.search, seen) if match]
    if len(addons) not in activated:
        verdict.fail("the guest did not activate the host's add-ons",
                     f"no `Activated the server's add-on set of {len(addons)} add-ons.` line, "
                     f"saw {activated}")
    if not any(RESTORED.search(one) for one in seen):
        verdict.fail("the guest did not put its own add-ons back",
                     "no `Restored the installed add-on set.` line after the host kicked it")
    return verdict


def lan_host_after_visit(root: Path, version: str, timeout: float, verbose: bool, served: Path,
                         name: str, host_loaded, verdict: Verdict) -> tuple[list[str], Path | None, str]:
    """Has the host client visit a dedicated server that serves an add-on the host lacks, leave, and
    only then open its own world and host it, all in one process.

    A dormant add-on is one this process registered and the installed set does not carry, so the
    visit and the hosting have to share the process: a host started afresh has nothing dormant.
    """
    port = givePort(root, version)
    takeLocalJoins(root, version)
    run = root / "versions" / version / "run"
    result: dict = {"lines": [], "log": None, "outcome": "never started"}

    def connect(server: subprocess.Popen, lines: list[str]) -> None:
        deadline = time.monotonic() + timeout

        def visit_then_host(process: subprocess.Popen, client_lines: list[str]) -> None:
            wait_for(server, lines, JOINED, deadline)
            # The server is stopped only once the client has heard it from inside the world, which
            # is after the activation's resource reload has finished.
            time.sleep(2)
            tag = f"MCADDONSETTLED{os.getpid()}"
            type_into(server, f"say {tag}")
            wait_for(process, client_lines, re.compile(tag), min(deadline, time.monotonic() + 60))
            type_into(server, "stop")
            if not wait_for(process, client_lines, RESTORED, min(deadline, time.monotonic() + 60)):
                verdict.fail("the host did not take the dormant add-on",
                             "its visit to the serving server never restored its own set")
                return
            ask_host(run, f"open {name}")
            if not wait_for(process, client_lines, LOGGED_IN, min(deadline, time.monotonic() + 180)):
                verdict.fail("the host never opened its world after the visit",
                             f"`open {name}` in `lan.request` was not followed by a login")
                return
            host_loaded(process, client_lines)

        result["lines"], result["log"], result["outcome"] = play(
            root, version, replace(CLIENT, name="lan-host"), ACTIVATED, [], timeout, 0.0, verbose,
            join=f"127.0.0.1:{port}", while_loaded=visit_then_host)

    play(root, version, replace(SERVER, name="lan-server"), SERVER.ready, [], timeout, 0.0, verbose,
         while_loaded=connect, serve=served)
    return result["lines"], result["log"], result["outcome"]


def check_conversion(root: Path, source: Path, version: str) -> Verdict:
    verdict = Verdict(f"{version} conversion of {source.name}")
    names, read = open_source(source)
    expected = source_counts(names, read)

    directories = converted_directories(names, read, root / "versions" / version / "run" / ".mcaddon" / "converted")
    if not directories:
        verdict.notes.append(f"no converted output for this add-on under versions/{version}, nothing to test")
        verdict.counts = {"source": expected}
        return verdict

    produced = converted_counts(directories)
    verdict.notes.append(f"{len(directories)} converted pack(s): "
                         + ", ".join(one.name for one in directories))
    verdict.counts = {"source": expected, "converted": produced}

    for key in ("textures", "sound events", "normal sidecars", "specular sidecars"):
        if produced[key] != expected[key]:
            verdict.fail(f"{key} count moved",
                         f"the pack holds {expected[key]}, the converter wrote {produced[key]}")

    return verdict


def report(verdicts: list[Verdict], destination: Path) -> bool:
    passed = all(one.passed for one in verdicts)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(
        {"passed": passed, "verdicts": [one.as_dict() for one in verdicts]}, indent=2))

    for verdict in verdicts:
        print(f"\n{'PASS' if verdict.passed else 'FAIL'}  {verdict.subject}")
        for note in verdict.notes:
            print(f"      {note}")
        for label, counts in verdict.counts.items():
            if counts:
                print(f"      {label}: " + ", ".join(f"{value} {key}" for key, value in counts.items()))
        for name, problem in verdict.problems.items():
            print(f"      {name} x{problem['count']}: {problem['detail']}")
            for example in problem["examples"]:
                print(f"        {example}")

    print(f"\n{'PASS' if passed else 'FAIL'}  verdict written to {destination}")
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(description=USAGE)
    parser.add_argument("mode", choices=("run", "client", "join", "lan", "convert", "companions", "all"),
                        help="`run` drives a dev server, `client` a dev client, `join` connects one "
                             "to the other (`--bare`, `--other` and `--reshaped` give the joining "
                             "client add-ons of its own), `lan` hosts a world to a second client, "
                             "`convert` compares a conversion offline, `companions` "
                             "runs a server and a client beside the mods this one is meant to work "
                             "with and reads their failures back as well as this mod's, "
                             "`all` does run, client, join and convert in turn")
    parser.add_argument("--version", action="append", choices=VERSIONS,
                        help="a Minecraft version to check; repeatable, all three by default")
    parser.add_argument("--source", type=Path,
                        help="the extracted add-on directory or archive to compare a conversion against")
    parser.add_argument("--timeout", type=float, default=600.0,
                        help="seconds before the game is killed, whatever it is doing")
    parser.add_argument("--hold", type=float, default=15.0,
                        help="seconds to leave the client running once the mod has loaded")
    parser.add_argument("--summons", type=int, default=4, help="how many add-on entities to summon")
    parser.add_argument("--report", type=Path, help="where to write the JSON verdict")
    parser.add_argument("--lock-wait", type=float, default=1800.0,
                        help="seconds to wait for another run to release the harness lock")
    parser.add_argument("--bare", action="store_true",
                        help="join: the second client has no add-ons and takes the server's")
    parser.add_argument("--other", action="store_true",
                        help="join: the second client has other versions of the server's add-ons "
                             "and an add-on of its own")
    parser.add_argument("--reshaped", action="store_true",
                        help="join: the second client has a copy of an add-on the server's cannot "
                             "replace without a restart")
    parser.add_argument("--dormant", action="store_true",
                        help="lan: the host holds an add-on it never installed, as a dormant one")
    parser.add_argument("--verbose", action="store_true", help="mirror the server output")
    arguments = parser.parse_args()

    root = Path(__file__).resolve().parents[2]
    versions = arguments.version or list(VERSIONS)
    destination = arguments.report or root / "build" / "verify" / "verdict.json"
    verdicts: list[Verdict] = []

    # One lock per version, because a server and a client of the same version share a world, and
    # the four versions share nothing: four directories, four ports, four sets of processes. They
    # are checked at the same time, which is most of what a whole run costs.
    if arguments.mode in ("run", "client", "join", "lan", "companions", "all"):
        def checkVersion(version: str) -> list[Verdict]:
            found = []
            with exclusive(root, arguments.lock_wait, version):
                if arguments.mode == "companions":
                    return check_companions(root, version, arguments.timeout, arguments.summons,
                                            arguments.hold, arguments.verbose)
                if arguments.mode in ("run", "all"):
                    found.append(check_server(
                        root, version, arguments.timeout, arguments.summons, arguments.verbose))
                if arguments.mode in ("client", "all"):
                    found.append(check_client(
                        root, version, arguments.timeout, arguments.hold, arguments.verbose))
                joins = [name for name in ("bare", "other", "reshaped") if getattr(arguments, name)]
                if arguments.mode == "all":
                    joins = ["plain", "bare", "other", "reshaped"]
                elif arguments.mode == "join" and not joins:
                    joins = ["plain"]
                if arguments.mode in ("join", "all"):
                    found += [check_join(root, version, arguments.timeout, arguments.hold,
                                         arguments.verbose, variant=variant) for variant in joins]
                if arguments.mode == "lan":
                    found.append(check_lan(root, version, arguments.timeout, arguments.verbose,
                                           dormant=arguments.dormant))
                if arguments.mode == "all":
                    found += [check_lan(root, version, arguments.timeout, arguments.verbose, dormant=dormant)
                              for dormant in (False, True)]
            return found

        with ThreadPoolExecutor(max_workers=len(versions)) as running_versions:
            for found in running_versions.map(checkVersion, versions):
                verdicts += found

    # After the games, because the conversion is what one of them wrote. A pack is converted again
    # once anything in it changes, so reading the cache before a game has run reads the last build's
    # answer to the previous question.
    if arguments.mode in ("convert", "all"):
        found = {one.name: one for version in versions
                 for one in addons_in(root / "versions" / version / "run" / "addons")}
        sources = [arguments.source] if arguments.source else list(found.values())
        if not sources:
            print("No add-on to compare a conversion against, nothing to test.")
        verdicts += [check_conversion(root, source, version)
                     for version in versions for source in sources]

    return 0 if report(verdicts, destination) else 1


if __name__ == "__main__":
    sys.exit(main())
