#!/usr/bin/env python3
"""Checks the mod against a real add-on and prints a pass or fail verdict.

Two modes. `run` starts a dev server for one or more versions, types a scripted sequence of console
commands into it, and reads the output back for the failures that only appear at runtime: a pack's
JavaScript hitting a missing API, a mixin that did not apply, the script engine entered from a
second thread, a converted function Java refused, a crash report being written. `convert` compares
what the converter wrote under a version's run directory against what the source pack holds, and
needs no game.

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
from dataclasses import dataclass, field
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from threading import Thread

USAGE = (__doc__ or "").splitlines()[0]

VERSIONS = ("1.20.1", "1.21.1", "26.1.2", "26.2")
JAVA_HOME = "/opt/homebrew/opt/openjdk@25"

IMAGE_SUFFIXES = (".png", ".tga", ".jpg", ".jpeg")

# The maps a `*.texture_set.json` can name. Each points at an image the converter folds into a
# labPBR sidecar instead of copying, so none of them counts as a texture of its own.
NORMAL_MAPS = ("normal", "heightmap")
SPECULAR_MAPS = ("metalness_emissive_roughness", "metalness_emissive_roughness_subsurface")

# What makes a run fail, most specific first: a line is reported under the first name it matches.
FAILURES = (
    ("java compile error", r"\.java:\d+: error:"),
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
    takes_commands=False)


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
    """Lists an add-on's files and returns a reader for them, for a directory or an archive."""
    if path.is_dir():
        names = tuple(str(file.relative_to(path).as_posix()) for file in path.rglob("*") if file.is_file())
        return names, lambda name: (path / name).read_bytes()

    archive = zipfile.ZipFile(path)
    return tuple(name for name in archive.namelist() if not name.endswith("/")), archive.read


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
    """What the source pack holds, counted the way the converter counts it."""
    images = [name for name in names
              if "/textures/" in f"/{name}" and name.lower().endswith(IMAGE_SUFFIXES)]
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
        "entity types": len(declared(names, "entities")),
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
    """The entity ids a behaviour pack declares, in the spelling the mod registers them under."""
    identifiers = []
    for name in declared(names, "entities"):
        description = json.loads(read(name)).get("minecraft:entity", {}).get("description", {})
        identifier = description.get("identifier")
        if identifier and ":" in identifier:
            namespace, path = identifier.split(":", 1)
            identifiers.append(f"{sanitise(namespace)}:{sanitise(path)}")
    return sorted(identifiers)


def addons_in(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted(one for one in directory.iterdir()
                  if one.suffix.lower() in (".mcaddon", ".mcpack") or one.is_dir())


def console_script(identifiers: list[str], summons: int) -> list[tuple[float, str]]:
    """The commands to type, each with the seconds to wait before it.

    A command in the same tick as a `summon` cannot see the entity, so every step that reads the
    world back waits for ticks to pass first.
    """
    chosen = identifiers[:summons]
    steps = [(2.0, "mcaddon packs"), (1.0, "mcaddon entities"), (1.0, "mcaddon scripts")]
    steps += [(0.3, f"summon {identifier} 0 100 0") for identifier in chosen]
    steps += [(2.0, f"kill @e[type={identifier}]") for identifier in chosen]
    steps += [(2.0, "gametest runall"), (4.0, "gametest clearall")]
    steps += [(2.0, "mcaddon scripts")]
    return steps


def running(launch: Launch, version: str) -> set[int]:
    """Every game of this kind and this version running on this machine, whoever started it.

    The version matters because the four are checked at the same time: each runs out of its own
    directory, so one version's run must neither wait for another's nor kill it.
    """
    found = subprocess.run(["pgrep", "-f", launch.signature], check=False,
                           capture_output=True, text=True)
    pids = {int(one) for one in found.stdout.split() if one.isdigit()}
    return {pid for pid in pids if version in commandOf(pid)}


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


def givePort(root: Path, version: str) -> None:
    """Gives this version's server a port of its own, so four of them can be up at once.

    A dedicated server binds one port and refuses to start when it is taken, and nothing here needs
    a particular number: the harness never connects to it.
    """
    properties = root / "versions" / version / "run" / "server.properties"
    port = 25565 + VERSIONS.index(version)
    lines = []
    if properties.is_file():
        lines = [one for one in properties.read_text(encoding="utf-8").splitlines()
                 if not one.startswith("server-port=")]
    lines.append(f"server-port={port}")
    properties.parent.mkdir(parents=True, exist_ok=True)
    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")


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

    existing = next((one.name for one in sorted(saves.iterdir()) if (one / "level.dat").is_file()), None)
    if existing is not None:
        return existing

    served = root / "versions" / version / "run" / "world"
    if not (served / "level.dat").is_file():
        return None

    copied = saves / "Scene World"
    shutil.copytree(served, copied, dirs_exist_ok=True)
    shutil.rmtree(copied / "datapacks", ignore_errors=True)
    return copied.name


def play(root: Path, version: str, launch: Launch, ready: re.Pattern,
         script: list[tuple[float, str]], timeout: float, hold: float,
         verbose: bool, quick_play: str | None = None) -> tuple[list[str], Path, str]:
    """Starts one game, waits for it to load, and always ends it by the deadline.

    A server is asked to stop and killed if it will not; a client has nothing to ask, so it is held
    open for a moment once the mod has loaded and then killed.
    """
    log = root / "build" / "verify" / f"{version}-{launch.name}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    if launch.takes_commands:
        givePort(root, version)

    lines: list[str] = []
    deadline = time.monotonic() + timeout
    started_before = running(launch, version)
    command = ["./gradlew", f":{version}:{launch.task}", "--console=plain"]
    if quick_play:
        command.append(f"-PquickPlay={quick_play}")
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
    it. Only games that appeared after this run began are killed, which leaves one somebody else is
    running alone.
    """
    for stage in (signal.SIGTERM, signal.SIGKILL):
        if process.poll() is not None:
            break
        try:
            os.killpg(os.getpgid(process.pid), stage)
        except (ProcessLookupError, PermissionError):
            break
        time.sleep(2)

    for pid in running(launch, version) - started_before:
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


def addon_source(root: Path, version: str, verdict: Verdict) -> tuple[list[Path], list[str], dict[str, int]]:
    """What the add-ons installed for one version declare, counted before the game starts."""
    addons = addons_in(root / "versions" / version / "run" / "addons")
    identifiers: list[str] = []
    expected: dict[str, int] = {}

    for addon in addons:
        names, read = open_source(addon)
        identifiers += entity_identifiers(names, read)
        for key, value in source_counts(names, read).items():
            expected[key] = expected.get(key, 0) + value

    verdict.notes.append(
        f"{len(addons)} add-on(s) in versions/{version}/run/addons" if addons
        else f"no add-on in versions/{version}/run/addons, nothing to test against")
    return addons, identifiers, expected


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


def check_server(root: Path, version: str, timeout: float, summons: int, verbose: bool) -> Verdict:
    verdict = Verdict(f"{version} server")
    if occupied(SERVER, verdict, version):
        return verdict

    addons, identifiers, expected = addon_source(root, version, verdict)
    free_world_lock(root, version)
    script = console_script(identifiers, summons)
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

    addons, _, expected = addon_source(root, version, verdict)
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
                     "which is what a screen waiting to be answered looks like")
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
    parser.add_argument("mode", choices=("run", "client", "convert", "all"),
                        help="`run` drives a dev server, `client` a dev client, "
                             "`convert` compares a conversion offline, `all` does the three in turn")
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
    parser.add_argument("--verbose", action="store_true", help="mirror the server output")
    arguments = parser.parse_args()

    root = Path(__file__).resolve().parents[2]
    versions = arguments.version or list(VERSIONS)
    destination = arguments.report or root / "build" / "verify" / "verdict.json"
    verdicts: list[Verdict] = []

    # One lock per version, because a server and a client of the same version share a world, and
    # the four versions share nothing: four directories, four ports, four sets of processes. They
    # are checked at the same time, which is most of what a whole run costs.
    if arguments.mode in ("run", "client", "all"):
        def checkVersion(version: str) -> list[Verdict]:
            found = []
            with exclusive(root, arguments.lock_wait, version):
                if arguments.mode in ("run", "all"):
                    found.append(check_server(
                        root, version, arguments.timeout, arguments.summons, arguments.verbose))
                if arguments.mode in ("client", "all"):
                    found.append(check_client(
                        root, version, arguments.timeout, arguments.hold, arguments.verbose))
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
