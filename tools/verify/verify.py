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
import signal
import subprocess
import sys
import time
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from threading import Thread

USAGE = (__doc__ or "").splitlines()[0]

VERSIONS = ("1.20.1", "1.21.1", "26.2")
JAVA_HOME = "/opt/homebrew/opt/openjdk@25"
SERVER_PROCESS = "net.fabricmc.devlaunchinjector.Main"

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
     r"|MixinError|InjectionError|Critical injection failure"),
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
    r"RealmsClient|RealmsAvailability|realmsclient",
    r"Failed to load properties from file: server\.properties",
)

# What has to be in the log of a run that worked.
EXPECTED = (
    ("server reached done", r"Done \("),
)
EXPECTED_WITH_ADDON = (
    ("mod logged its startup", r"\(Bedrock Add-ons\)"),
    ("a pack was read", r"\(Bedrock Add-ons\) (?:resources|behaviour) "),
)

# What the console prints for a `summon` that worked, which is how a registered entity type is
# told apart from one the game does not know.
SUMMONED = re.compile(r"Summoned new ")

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
        "entity types": len([name for name in names if "/entities/" in name and name.endswith(".json")]),
        "items": len([name for name in names if "/items/" in name and name.endswith(".json")]),
        "blocks": len([name for name in names if "/blocks/" in name and name.endswith(".json")]),
    }


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
    for name in [one for one in names if "/entities/" in one and one.endswith(".json")]:
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
    steps += [(2.0, "mcaddon scripts")]
    return steps


def dev_servers() -> set[int]:
    """Every dev server running on this machine, whoever started it."""
    found = subprocess.run(["pgrep", "-f", SERVER_PROCESS], check=False,
                           capture_output=True, text=True)
    return {int(one) for one in found.stdout.split() if one.isdigit()}


@contextmanager
def exclusive(root: Path, wait: float):
    """Holds the harness lock, so two runs cannot fight over the port and the world lock.

    The lock lives under `scratchpad/`, which git ignores, and is released when the process ends
    however it ends.
    """
    scratchpad = root / "scratchpad"
    scratchpad.mkdir(exist_ok=True)
    ignore = scratchpad / ".gitignore"
    if not ignore.exists():
        ignore.write_text("*\n")

    path = scratchpad / "verify.lock"
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


def free_world_lock(root: Path, version: str) -> None:
    """Drops the world lock a killed run of this harness left behind."""
    (root / "versions" / version / "run" / "world" / "session.lock").unlink(missing_ok=True)


def wait_for(process: subprocess.Popen, lines: list[str], pattern: re.Pattern, deadline: float) -> bool:
    """Waits for a line, giving up on the deadline or as soon as the server has gone."""
    while time.monotonic() < deadline:
        if any(pattern.search(line) for line in lines):
            return True
        if process.poll() is not None:
            return False
        time.sleep(0.25)
    return False


def run_server(root: Path, version: str, script: list[tuple[float, str]],
               timeout: float, verbose: bool) -> tuple[list[str], Path, str]:
    """Starts one dev server, types the script into it, and always kills it by the deadline."""
    log = root / "build" / "verify" / f"{version}-server.log"
    log.parent.mkdir(parents=True, exist_ok=True)

    lines: list[str] = []
    deadline = time.monotonic() + timeout
    started_before = dev_servers()
    process = subprocess.Popen(
        ["./gradlew", f":{version}:runServer", "--console=plain"],
        cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, start_new_session=True,
        env={**os.environ, "JAVA_HOME": JAVA_HOME})
    output = process.stdout
    if output is None:
        raise RuntimeError("the server was started without a readable output pipe")

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

    outcome = "stopped cleanly"
    if not wait_for(process, lines, re.compile(r"Done \("), deadline):
        outcome = "ended before reaching done" if process.poll() is not None else "never reached done"
    else:
        for wait, command in script:
            if time.monotonic() + wait > deadline:
                outcome = "script cut short by the deadline"
                break
            time.sleep(wait)
            type_into(process, command)

    type_into(process, "stop")
    if not wait_until_exit(process, min(deadline, time.monotonic() + 30)):
        outcome = "killed on the deadline"
        kill(process, started_before)

    close_input(process)
    reader.join(timeout=5)

    # The next version's check refuses to start beside a running dev server, so wait for this one
    # to leave the process table rather than have it read as somebody else's.
    settle = time.monotonic() + 15
    while dev_servers() - started_before and time.monotonic() < settle:
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


def kill(process: subprocess.Popen, started_before: set[int]) -> None:
    """Ends this run's process group, then the game the Gradle daemon forked on its behalf.

    The Gradle daemon outlives a build and owns the forked game, so the group alone does not reach
    it. Only dev servers that appeared after this run began are killed, which leaves a server
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

    for pid in dev_servers() - started_before:
        try:
            os.kill(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            continue


def scan(lines: list[str], verdict: Verdict, with_addon: bool) -> None:
    """Files every log line under the first failure it matches, benign ones aside."""
    for number, line in enumerate(lines, start=1):
        if BENIGN_PATTERN.search(line):
            continue
        for name, pattern in FAILURE_PATTERNS:
            if pattern.search(line):
                verdict.fail(name, "matched a known failure signature", [f"{number}: {line.strip()}"])
                break

    expected = EXPECTED + (EXPECTED_WITH_ADDON if with_addon else ())
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


def check_version(root: Path, version: str, timeout: float, summons: int, verbose: bool) -> Verdict:
    verdict = Verdict(f"{version} server")
    addons = addons_in(root / "versions" / version / "run" / "addons")

    running = dev_servers()
    if running:
        verdict.fail("a dev server is already running",
                     f"pid(s) {', '.join(str(one) for one in sorted(running))} hold the port; "
                     "this harness does not kill a server it did not start")
        return verdict

    identifiers: list[str] = []
    expected: dict[str, int] = {}
    if addons:
        for addon in addons:
            names, read = open_source(addon)
            identifiers += entity_identifiers(names, read)
            for key, value in source_counts(names, read).items():
                expected[key] = expected.get(key, 0) + value
        verdict.notes.append(f"{len(addons)} add-on(s) in versions/{version}/run/addons")
    else:
        verdict.notes.append(f"no add-on in versions/{version}/run/addons, nothing to test against")

    free_world_lock(root, version)
    script = console_script(identifiers, summons)
    lines, log, outcome = run_server(root, version, script, timeout, verbose)

    verdict.notes.append(f"server {outcome}, {len(lines)} lines in {log.relative_to(root)}")
    scan(lines, verdict, with_addon=bool(addons))

    # Gradle also reports a failure for a server this harness killed, so that outcome says nothing.
    if outcome != "killed on the deadline":
        failed = [line for line in lines if "FAILURE: Build failed" in line]
        if failed:
            verdict.fail("gradle failed", "the version did not build or the run task failed", failed)

    attempted = len([one for _, one in script if one.startswith("summon ")])
    summoned = len([one for one in lines if SUMMONED.search(one)])
    if attempted:
        verdict.notes.append(f"{summoned} of {attempted} summoned add-on entities reached the world")
    if summoned < attempted:
        verdict.fail("entity would not summon",
                     f"{attempted} summon commands, {summoned} entities")

    reported = reported_counts(lines)
    verdict.counts = {"source": expected, "reported by the mod": reported}
    for key in ("entity types", "items", "blocks"):
        if expected.get(key) and reported.get(key, 0) == 0:
            verdict.fail("nothing registered",
                         f"the packs declare {expected[key]} {key}, the mod registered none")

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
    parser.add_argument("mode", choices=("run", "convert", "all"))
    parser.add_argument("--version", action="append", choices=VERSIONS,
                        help="a Minecraft version to check; repeatable, all three by default")
    parser.add_argument("--source", type=Path,
                        help="the extracted add-on directory or archive to compare a conversion against")
    parser.add_argument("--timeout", type=float, default=300.0,
                        help="seconds before the server is killed, whatever it is doing")
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

    if arguments.mode in ("convert", "all"):
        found = {one.name: one for version in versions
                 for one in addons_in(root / "versions" / version / "run" / "addons")}
        sources = [arguments.source] if arguments.source else list(found.values())
        if not sources:
            print("No add-on to compare a conversion against, nothing to test.")
        verdicts += [check_conversion(root, source, version)
                     for version in versions for source in sources]

    if arguments.mode in ("run", "all"):
        with exclusive(root, arguments.lock_wait):
            verdicts += [
                check_version(root, version, arguments.timeout, arguments.summons, arguments.verbose)
                for version in versions]

    return 0 if report(verdicts, destination) else 1


if __name__ == "__main__":
    sys.exit(main())
