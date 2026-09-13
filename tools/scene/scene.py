#!/usr/bin/env python3
"""Runs a scene inside the game and says what it found, with nobody at the keyboard.

`verify.py` starts a server and types into its console, which reaches everything a server can be
asked but nothing a player does. The other half of the mod only runs when somebody is in a world:
the models, the attachables, the forms, and every script path that carries a player. Driving that by
hand does not scale and does not repeat.

A scene is a list of commands and the things that have to be true afterwards. The commands are
written into a datapack in the world's own `datapacks/` directory, in a function tagged
`minecraft:load`, so the game runs them itself the moment the world opens. Nothing is typed and no
window has to be focused. The game's log is read back for what each scene asked about, and a picture
of the window is kept beside it so a change in what is drawn can be looked at afterwards.

    python3 tools/scene/scene.py --version 26.2            # every scene
    python3 tools/scene/scene.py --version 26.2 --scene entities
    python3 tools/scene/scene.py --version 26.2 --keep     # leave the game open at the end

Each scene ends by printing its own marker, so the run knows the commands finished rather than
waiting a fixed time. A scene that names an add-on entity is skipped when no pack provides it, the
same way the game tests are, because no pack content belongs in this repository.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from threading import Lock, Thread

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA_HOME = "/opt/homebrew/opt/openjdk@25"

# Big enough that what a scene placed can be made out in the picture it leaves behind.
WINDOW = "1600x900"

# What a captured window is scaled down to, which is wide enough to read and small enough to open.
READABLE_WIDTH = 1400

# The flat lit stage every scene is set on, well above whatever the world generated.
FLOOR_Y = 150

# Where a scene puts what it wants looked at, and where it stands to look: a row along z at a fixed
# x, with the camera off to the side facing the middle of it.
ROW_X = -50
ROW_Z = -108
CAMERA_X = -38

# Only commands every supported version spells the same way. 26 renamed the game rules, so setting
# one by name here would fail to parse and take the whole function with it.
STAGE = (
    # Creative first and the stage underfoot second: a player left where the world put them falls,
    # drowns or suffocates, and a picture of a death screen says nothing about what was drawn.
    "gamemode creative @a",
    f"fill -60 {FLOOR_Y} -115 -20 {FLOOR_Y} -80 minecraft:white_concrete",
    f"fill -60 {FLOOR_Y + 1} -115 -20 {FLOOR_Y + 12} -80 minecraft:air",
    f"tp @a {CAMERA_X} {FLOOR_Y + 4} {ROW_Z}",
    "time set noon",
    "weather clear",
    # Whatever the player is holding is drawn over the corner of the window the scene is in.
    "clear @a",
)

# How long the world is given to load the chunks the stage is built in before anything is built.
SETTLE_TICKS = 60

# What every scene says when its commands have all run, and what the runner waits for.
DONE = "SCENE-DONE"


def scene(name: str, commands: list[str], wants: list[str], checks: list[str] | None = None) -> dict:
    """One scene: what to run, what to ask a second later, and the log lines that have to appear.

    A check runs a second after the commands because a selector answers on the world as the game
    last saw it, so a summon and a question about what was summoned do not belong in one tick.

    Every scene is photographed, so every scene wants a player who is alive to photograph it with.
    """
    return {"name": name, "commands": commands, "wants": list(wants) + ["ALIVE"],
            "checks": checks or []}


def declared(version: str, directory: str, key: str) -> list[str]:
    """Every name a pack installed for this version declares under one of its directories.

    Read out of `addons/` itself rather than out of the cache the mod unpacks into, because that
    cache is written by a game and a scene would otherwise place whatever the previous run happened
    to leave there. A name that no longer exists takes the whole scene function down with it, since
    the game parses every line of a function before running any of it.

    No pack content belongs in this repository, so the names are taken from whatever the person
    running this has installed. With nothing installed a scene has nothing to place.
    """
    addons = ROOT / "versions" / version / "run" / "addons"
    if not addons.is_dir():
        return []

    found = []
    for entry in sorted(addons.iterdir()):
        for body in declarations(entry, directory):
            identifier = body.get(key, {}).get("description", {}).get("identifier")
            if identifier and ":" in identifier and not identifier.startswith("minecraft:"):
                found.append(identifier)
    return sorted(set(found))


def declarations(entry: pathlib.Path, directory: str) -> list[dict]:
    """Every JSON body one installed pack holds under a named directory, archived or unpacked."""
    bodies = []
    if entry.is_dir():
        for declaration in sorted(entry.glob(f"**/{directory}/*.json")):
            try:
                bodies.append(json.loads(declaration.read_text(encoding="utf-8")))
            except (ValueError, OSError):
                continue
        return bodies

    if entry.suffix.lower() not in (".mcaddon", ".mcpack", ".zip"):
        return bodies
    try:
        with zipfile.ZipFile(entry) as archive:
            for name in sorted(archive.namelist()):
                parts = name.split("/")
                if len(parts) < 2 or parts[-2] != directory or not name.endswith(".json"):
                    continue
                try:
                    bodies.append(json.loads(archive.read(name).decode("utf-8-sig")))
                except (ValueError, UnicodeDecodeError):
                    continue
    except (OSError, zipfile.BadZipFile):
        return bodies
    return bodies


def scenes(version: str) -> list[dict]:
    """Every scene this tool knows, each standing on its own.

    A scene says what it is checking through `say`, because the game's own output is the record the
    runner reads. Anything a scene asserts has to be visible in the log rather than on the screen.
    """
    entities = declared(version, "entities", "minecraft:entity")
    items = declared(version, "items", "minecraft:item")

    # One of each, spread along a line on the stage, then counted back. An entity that vanished as it
    # spawned is the difference between the two numbers, which is how a pack's own despawn rule and a
    # component that discards on sight both show up.
    placing = [f"summon {name} {ROW_X} {FLOOR_Y + 1} {ROW_Z + index * 2}"
               for index, name in enumerate(entities)]
    middle = ROW_Z + max(len(entities) - 1, 0)
    looking = [
        # Looking slightly down from above puts the row in the upper half, clear of the chat the
        # game draws over the bottom of the window.
        f"tp @a {CAMERA_X} {FLOOR_Y + 4} {middle}",
        f"tp @a {CAMERA_X} {FLOOR_Y + 4} {middle} facing {ROW_X} {FLOOR_Y + 2} {middle}",
    ]
    counting = [f"say PLACED {len(entities)} kinds"]

    # An item is only drawn once something is holding it, and a stand wears whatever is put on its
    # head whether or not it is armour. That is what shows an icon, a tint and a model all at once.
    holding = []
    for index, name in enumerate(items):
        z = ROW_Z + index * 2
        holding.append(f"summon minecraft:armor_stand {ROW_X} {FLOOR_Y + 1} {z}")
        holding.append(f"execute positioned {ROW_X} {FLOOR_Y + 1} {z} run item replace entity "
                       f"@e[type=minecraft:armor_stand,limit=1,sort=nearest] armor.head with {name}")
    itemMiddle = ROW_Z + max(len(items) - 1, 0)
    atItems = [
        f"tp @a {CAMERA_X} {FLOOR_Y + 3} {itemMiddle}",
        f"tp @a {CAMERA_X} {FLOOR_Y + 3} {itemMiddle} facing {ROW_X} {FLOOR_Y + 2} {itemMiddle}",
    ]

    return [
        scene("stage", ["say STAGE-BUILT"], ["STAGE-BUILT"]),
        scene(
            "entities",
            ["kill @e[type=!player]"] + placing + looking + counting + ["say ENTITIES-PLACED"],
            ["ENTITIES-PLACED"] + (["COUNTED entities"] if entities else []),
            # Read as "at least one is standing there" rather than as the number summoned, because a
            # pack's own despawn rule may take one away and that is the pack working.
            ["execute if entity @e[type=!player,limit=1] run say COUNTED entities"],
        ),
        scene(
            "items",
            ["kill @e[type=!player]"] + holding + atItems + [f"say SHOWN {len(items)} items"],
            [f"SHOWN {len(items)} items"] + (["COUNTED stands"] if items else []),
            ["execute if entity @e[type=minecraft:armor_stand,limit=1] run say COUNTED stands"],
        ),
        scene(
            "player",
            [
                # Everything a pack does with a player is untouched by a title screen: the player
                # surface, the forms, and every event carrying somebody who used something.
                "gamemode creative @p",
                "give @p minecraft:stone",
                "effect give @p minecraft:night_vision 9999 0 true",
                "say PLAYER-READY",
            ],
            ["PLAYER-READY"],
        ),
    ]


def packFormat(version: str) -> int:
    """The pack format this version reads without calling a world experimental.

    A world holding a pack whose format the game does not recognise opens behind a screen asking
    whether to load it anyway, and nothing typed or written reaches a game sitting on that screen.
    The mod writes its own converted packs with the number this version uses, so the answer is
    already on disk beside the world.
    """
    converted = ROOT / "versions" / version / "run" / ".mcaddon" / "converted"
    for meta in sorted(converted.glob("*/pack.mcmeta")):
        try:
            return int(json.loads(meta.read_text(encoding="utf-8"))["pack"]["pack_format"])
        except (ValueError, OSError, KeyError, TypeError):
            continue
    return 15


def datapack(world: pathlib.Path, commands: list[str], checks: list[str], format: int) -> pathlib.Path:
    """Writes the commands into the world as a function the game runs once a player is in it.

    A world loads before anybody joins it, so a scene run off `minecraft:load` would aim every one
    of its commands at a player who is not there yet: the stage gets built and nobody is ever put on
    it. The body is run off `minecraft:tick` instead, behind a score that says it has already run,
    so it happens exactly once and not before there is somebody to happen to.
    """
    pack = world / "datapacks" / "mcaddon-scene"
    if pack.exists():
        shutil.rmtree(pack)
    pack.mkdir(parents=True)

    # From pack format 82 the game wants the range spelled out as well as the single number, and
    # reads a pack that gives only the number through a fallback it complains about first.
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {
            "pack_format": format,
            "min_format": format,
            "max_format": format,
            "description": "mcaddon scene",
        },
    }), encoding="utf-8")

    # Held over the stage while the chunks under it load: a fill reaches only what is loaded, and a
    # player put down a moment too early falls through a floor that is not there yet. A spectator
    # is what waits there, because a spectator cannot fall, drown or suffocate, and a picture of a
    # death screen says nothing about what was drawn.
    waiting = [
        "execute unless score #ran mcaddon matches 1 run scoreboard players add #wait mcaddon 1",
        "execute unless score #ran mcaddon matches 1 if entity @a run gamemode spectator @a",
        f"execute unless score #ran mcaddon matches 1 if entity @a run "
        f"tp @a {CAMERA_X} {FLOOR_Y + 4} {ROW_Z}",
        f"execute if entity @a if score #wait mcaddon matches {SETTLE_TICKS}.. "
        "unless score #ran mcaddon matches 1 run function mcaddon:scene",
    ]
    bodies = {
        "ready": ["scoreboard objectives add mcaddon dummy",
                  "scoreboard objectives add mcaddonhp health",
                  "scoreboard players set #ran mcaddon 0",
                  "scoreboard players set #wait mcaddon 0"],
        "watch": waiting,
        # A second between the two, so what the checks ask about is a world the game has ticked
        # since it was built. The marker the runner waits for is said by the checks, which is what
        # keeps the picture from being taken before they have answered.
        "scene": ["scoreboard players set #ran mcaddon 1"]
        + list(STAGE) + list(commands) + ["schedule function mcaddon:checked 20t"],
        # A dead player is behind a screen the game draws over everything, so a picture taken then
        # says nothing about what the world looks like. Every scene asks.
        "checked": list(checks)
        + ["execute if score @p mcaddonhp matches 1.. run say ALIVE", f"say {DONE}"],
    }

    # Both spellings of the directory, because the game renamed it and reads only the one it wants.
    for spelling in ("function", "functions"):
        target = pack / "data" / "mcaddon" / spelling
        target.mkdir(parents=True, exist_ok=True)
        for name, body in bodies.items():
            (target / f"{name}.mcfunction").write_text("\n".join(body) + "\n", encoding="utf-8")

    for spelling in ("tags/function", "tags/functions"):
        tags = pack / "data" / "minecraft" / spelling
        tags.mkdir(parents=True, exist_ok=True)
        (tags / "load.json").write_text(json.dumps({"values": ["mcaddon:ready"]}), encoding="utf-8")
        (tags / "tick.json").write_text(json.dumps({"values": ["mcaddon:watch"]}), encoding="utf-8")

    return pack


# F1 takes the heads-up display off the screen, F2 makes the game save a picture of itself.
HIDE_HUD = 122
SCREENSHOT = 120


def dontPauseOnLostFocus(version: str) -> None:
    """Tells this version's client to keep playing when another window takes the focus.

    Four games are photographed at the same time and each picture is taken by bringing one of them
    to the front, which takes the focus from the other three. A single player game stops and opens
    its menu when that happens, and a picture of the menu says nothing about what was drawn.
    """
    options = ROOT / "versions" / version / "run" / "options.txt"
    lines = []
    if options.is_file():
        lines = [one for one in options.read_text(encoding="utf-8").splitlines()
                 if not one.startswith("pauseOnLostFocus:")]
    lines.append("pauseOnLostFocus:false")
    options.parent.mkdir(parents=True, exist_ok=True)
    options.write_text("\n".join(lines) + "\n", encoding="utf-8")


# One picture at a time, whichever version is being photographed: taking one means bringing a window
# to the front, and two of those at once would each leave the other's picture showing the wrong game.
PHOTOGRAPHING = Lock()


@contextmanager
def photographing():
    """The front of the screen, held against every scene run on this machine rather than only ours.

    A run started from another checkout brings its own window forward, and a picture taken while it
    does is of that game. The file lock reaches those runs; the thread lock is what orders the four
    versions inside one run.
    """
    with PHOTOGRAPHING:
        handle = (lockDirectory() / "mcaddon-scene-screen.lock").open("a+")
        try:
            fcntl.flock(handle, fcntl.LOCK_EX)
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)
            handle.close()


def gamePid(version: str) -> int | None:
    """The game this version is running, picked out from however many are up at once.

    Four versions are photographed at the same time, so a key sent to whichever java process came
    first would reach the wrong window. Each game carries the directory it was started from in the
    paths it was given, which tells apart both the versions and the checkouts.
    """
    found = subprocess.run(["pgrep", "-f", "fabric.dli.env=client"],
                           check=False, capture_output=True, text=True)
    for line in found.stdout.split():
        if not line.isdigit():
            continue
        command = subprocess.run(["ps", "-o", "command=", "-p", line],
                                 check=False, capture_output=True, text=True).stdout
        if f"{ROOT}/versions/{version}/" in command:
            return int(line)
    return None


def press(pid: int, code: int) -> None:
    """Sends one key to one game, which is the only interaction this tool has with it."""
    subprocess.run(["osascript", "-e",
                    "tell application \"System Events\" to set frontmost of "
                    f"(first process whose unix id is {pid}) to true"], check=False)
    time.sleep(0.6)
    subprocess.run(["osascript", "-e",
                    "tell application \"System Events\" to tell "
                    f"(first process whose unix id is {pid}) to key code {code}"], check=False)
    time.sleep(0.8)


def capture(version: str, target: pathlib.Path) -> str | None:
    """Has the game photograph itself, and leaves one picture that is ready to look at.

    The game's own screenshot holds what it drew and nothing else: no title bar, no cursor, and no
    doubling from a display that draws two pixels per point. It also does not care whether the window
    is in front, which photographing the screen does.

    Answers why there is no picture, or nothing when there is one. A scene whose whole purpose is to
    be looked at has not run if nobody can look at it, so the caller fails on what comes back.
    """
    shots = ROOT / "versions" / version / "run" / "screenshots"
    before = set(shots.glob("*.png")) if shots.is_dir() else set()

    with photographing():
        pid = gamePid(version)
        if pid is None:
            return "no game of this version was running to photograph"
        press(pid, HIDE_HUD)
        press(pid, SCREENSHOT)
        time.sleep(1.5)

    taken = sorted(set(shots.glob("*.png")) - before) if shots.is_dir() else []
    if not taken:
        return "the game wrote no screenshot, so the key never reached it"
    shutil.move(str(taken[-1]), target)

    try:
        from PIL import Image
    except ImportError:
        return None

    with Image.open(target) as picture:
        if picture.width <= READABLE_WIDTH:
            return None
        scaled = picture.convert("RGB").resize(
            (READABLE_WIDTH, round(picture.height * READABLE_WIDTH / picture.width)))
        scaled.save(target)


def lockDirectory() -> pathlib.Path:
    """Where the locks live, which is one place for every checkout of this repository.

    A worktree is a checkout of its own with a `scratchpad/` of its own, so a lock kept there locks
    nothing against a run started from somewhere else, and a picture is taken by bringing a window
    to the front, which every game on the machine shares. Git's common directory is the same path
    from every worktree.
    """
    try:
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=ROOT,
                                capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        common = ""

    shared = (ROOT / common).resolve() if common else ROOT / "scratchpad"
    shared.mkdir(parents=True, exist_ok=True)
    return shared


@contextmanager
def exclusive(wait: float, version: str):
    """Holds this version's scene lock, so two runs cannot fight over its world and its pictures.

    One saved world takes one game at a time, and both runs would write their picture over the
    other's. One lock per version, because the versions share neither.
    """
    handle = (lockDirectory() / f"mcaddon-scene-{version}.lock").open("a+")
    deadline = time.monotonic() + wait
    announced = False
    while True:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except OSError:
            if time.monotonic() > deadline:
                handle.close()
                raise SystemExit(f"another scene run holds the lock after {wait:.0f}s of waiting")
            if not announced:
                print("another scene run holds the lock, waiting")
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


def world_of(version: str) -> pathlib.Path | None:
    """The saved world a scene runs in, or None when this version has none to run in.

    A world is a fixture rather than something anybody made by hand, so one is taken from the
    dedicated server's own. `verify.py run` leaves one behind, and a server writes a world nobody has
    to answer a question about before it opens.

    A fresh copy is taken for every scene. A client writes its player into `level.dat` as it leaves,
    so a scene whose player died leaves a world that opens behind a death screen, and every picture
    taken from then on is of that screen. Copying again is what keeps one run from deciding what the
    next one sees.
    """
    saves = ROOT / "versions" / version / "run" / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    served = ROOT / "versions" / version / "run" / "world"
    copied = saves / "Scene World"

    if not (served / "level.dat").is_file():
        existing = next((one for one in sorted(saves.iterdir()) if (one / "level.dat").is_file()), None)
        return existing

    # The game before this one may still be writing as it closes, so the old copy is asked to go
    # several times before the new one is written over whatever is left.
    for attempt in range(10):
        shutil.rmtree(copied, ignore_errors=attempt < 9)
        if not copied.exists():
            break
        time.sleep(0.5)
    shutil.copytree(served, copied, dirs_exist_ok=True)
    shutil.rmtree(copied / "datapacks", ignore_errors=True)
    # A player who died on the server would open this world already dead, behind a
    # screen waiting to be answered.
    shutil.rmtree(copied / "players", ignore_errors=True)
    return copied


def run(version: str, one: dict, timeout: float, keep: bool) -> tuple[bool, list[str]]:
    """Opens the world with the scene in it, waits for its marker, and photographs the window."""
    world = world_of(version)
    if world is None:
        return (False, [f"{version} has no saved world to open"])

    dontPauseOnLostFocus(version)
    datapack(world, one["commands"], one["checks"], packFormat(version))
    logs = ROOT / "build" / "scene"
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / f"{version}-{one['name']}.log"

    process = subprocess.Popen(
        ["./gradlew", f":{version}:runClient", "--console=plain",
         f"-PquickPlay={world.name}", f"-PwindowSize={WINDOW}"],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
        start_new_session=True, env={**os.environ, "JAVA_HOME": JAVA_HOME})

    # The output is drained on its own thread so a game that goes quiet still ends on the deadline.
    # Reading the pipe inline would wait for a line that a hung game never writes.
    lines: list[str] = []
    reading = True

    def drain() -> None:
        with log.open("w", encoding="utf-8") as sink:
            for line in process.stdout or []:
                if not reading:
                    return
                sink.write(line)
                sink.flush()
                lines.append(line)

    reader = Thread(target=drain, daemon=True)
    reader.start()

    deadline = time.monotonic() + timeout
    finished = False
    while time.monotonic() < deadline:
        text = "".join(lines)
        if DONE in text:
            finished = True
            break
        if "BUILD FAILED" in text or process.poll() is not None:
            break
        time.sleep(0.5)
    reading = False

    unphotographed = None
    if finished:
        # The window is up and the scene has run, so what is drawn is worth keeping.
        time.sleep(2.5)
        unphotographed = capture(version, logs / f"{version}-{one['name']}.png")

    if not keep:
        for stop in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(os.getpgid(process.pid), stop)
                process.wait(timeout=20)
                break
            except (ProcessLookupError, subprocess.TimeoutExpired):
                continue

    shutil.rmtree(world / "datapacks" / "mcaddon-scene", ignore_errors=True)

    text = "".join(lines)
    problems = [] if finished else [f"{one['name']} never reached {DONE}"]
    problems += [f"{one['name']} never said {want}" for want in one["wants"] if want not in text]
    problems += [f"{one['name']}: {found}" for found in set(re.findall(r"Script error: .*", text))]
    # A scene exists to be looked at, so one that left no picture has not done its job however
    # cleanly its commands ran.
    if finished and unphotographed:
        problems.append(f"{one['name']} left no picture: {unphotographed}")
    return (not problems, problems)


def runOne(version: str, one: dict, timeout: float, keep: bool) -> bool:
    """Runs one scene and prints its verdict."""
    passed, problems = run(version, one, timeout, keep)
    print(f"{'PASS' if passed else 'FAIL'}  {version} {one['name']}")
    for problem in problems:
        print(f"      {problem}")
    return passed


def runVersion(version: str, arguments: argparse.Namespace) -> tuple[int, int]:
    """Every chosen scene for one version, in turn, and how many of them passed.

    One version at a time inside itself, because its scenes share one saved world, and the versions
    themselves know nothing of each other.
    """
    chosen = [one for one in scenes(version)
              if not arguments.scene or one["name"] in arguments.scene]
    if not chosen:
        return (0, 0)

    with exclusive(arguments.wait, version):
        passed = [one for one in chosen
                  if runOne(version, one, arguments.timeout, arguments.keep)]
    return (len(passed), len(chosen))


def main() -> int:
    parser = argparse.ArgumentParser(description="Runs scenes inside the game with nobody watching.")
    parser.add_argument("--version", action="append", default=[],
                        help="which version to open; repeatable, and all four are run together")
    parser.add_argument("--scene", action="append", default=[], help="run only these, repeatable")
    parser.add_argument("--timeout", type=float, default=420.0, help="seconds one scene may take")
    parser.add_argument("--keep", action="store_true", help="leave the last game open")
    parser.add_argument("--wait", type=float, default=900.0,
                        help="seconds to wait for another scene run to finish")
    arguments = parser.parse_args()
    versions = arguments.version or ["26.2"]

    with ThreadPoolExecutor(max_workers=len(versions)) as together:
        counted = list(together.map(lambda version: runVersion(version, arguments), versions))

    passed = sum(one for one, _ in counted)
    chosen = sum(one for _, one in counted)
    if chosen == 0:
        print("no scene by that name", file=sys.stderr)
        return 2

    print(f"{'PASS' if passed == chosen else 'FAIL'}  {passed} of {chosen} scenes")
    return 0 if passed == chosen else 1


if __name__ == "__main__":
    raise SystemExit(main())
