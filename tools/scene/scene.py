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
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time
from threading import Thread

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA_HOME = "/opt/homebrew/opt/openjdk@25"

# Big enough that what a scene placed can be made out in the picture it leaves behind.
WINDOW = "1600x900"

# What a captured window is scaled down to, which is wide enough to read and small enough to open.
READABLE_WIDTH = 1400

# The flat lit stage every scene is set on, well above whatever the world generated.
FLOOR_Y = 150
# Only commands every supported version spells the same way. 26 renamed the game rules, so setting
# one by name here would fail to parse and take the whole function with it.
STAGE = (
    f"fill -60 {FLOOR_Y} -115 -20 {FLOOR_Y} -80 minecraft:white_concrete",
    f"fill -60 {FLOOR_Y + 1} -115 -20 {FLOOR_Y + 12} -80 minecraft:air",
    "time set noon",
    "weather clear",
    # Whatever the player is holding is drawn over the corner of the window the scene is in.
    "clear @a",
)

# Where a scene puts what it wants looked at, and where it stands to look: a row along z at a fixed
# x, with the camera off to the side facing the middle of it.
ROW_X = -50
ROW_Z = -108
CAMERA_X = -38

# What every scene says when its commands have all run, and what the runner waits for.
DONE = "SCENE-DONE"


def scene(name: str, commands: list[str], wants: list[str]) -> dict:
    """One scene: what to run, and the log lines that have to appear for it to have worked."""
    return {"name": name, "commands": commands, "wants": wants}


def addonEntities(version: str) -> list[str]:
    """Every entity a pack in this version's run directory declares, read from the packs themselves.

    No pack content belongs in this repository, so the names are taken from whatever the person
    running this has installed. With nothing installed the entity scene has nothing to place and
    says so rather than failing.
    """
    extracted = ROOT / "versions" / version / "run" / ".mcaddon" / "extracted"
    if not extracted.is_dir():
        return []

    found = []
    for entity in sorted(extracted.glob("*/*/entities/*.json")):
        try:
            body = json.loads(entity.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        identifier = body.get("minecraft:entity", {}).get("description", {}).get("identifier")
        if identifier and ":" in identifier and not identifier.startswith("minecraft:"):
            found.append(identifier)
    return sorted(set(found))


def scenes(version: str) -> list[dict]:
    """Every scene this tool knows, each standing on its own.

    A scene says what it is checking through `say`, because the game's own output is the record the
    runner reads. Anything a scene asserts has to be visible in the log rather than on the screen.
    """
    entities = addonEntities(version)

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
    counting = [
        "execute store result score #placed mcaddon run execute if entity @e[type=!player]",
        f"say PLACED {len(entities)} kinds",
    ]

    return [
        scene("stage", ["say STAGE-BUILT"], ["STAGE-BUILT"]),
        scene(
            "entities",
            ["kill @e[type=!player]"] + placing + looking + counting + ["say ENTITIES-PLACED"],
            ["ENTITIES-PLACED"],
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


def datapack(world: pathlib.Path, commands: list[str]) -> pathlib.Path:
    """Writes the commands into the world as a function the game runs when it loads it.

    The pack format is deliberately low: a number the game does not recognise is refused outright,
    and every supported version reads a pack that claims an older one.
    """
    pack = world / "datapacks" / "mcaddon-scene"
    if pack.exists():
        shutil.rmtree(pack)

    functions = pack / "data" / "mcaddon" / "function"
    functions.mkdir(parents=True, exist_ok=True)
    (pack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 15, "description": "mcaddon scene"},
    }), encoding="utf-8")

    body = list(STAGE) + list(commands) + [f"say {DONE}"]
    (functions / "scene.mcfunction").write_text("\n".join(body) + "\n", encoding="utf-8")

    # Both spellings of the directory, because the game renamed it and reads only the one it wants.
    for spelling in ("function", "functions"):
        target = pack / "data" / "mcaddon" / spelling
        target.mkdir(parents=True, exist_ok=True)
        (target / "scene.mcfunction").write_text("\n".join(body) + "\n", encoding="utf-8")

    for spelling in ("tags/function", "tags/functions"):
        tags = pack / "data" / "minecraft" / spelling
        tags.mkdir(parents=True, exist_ok=True)
        (tags / "load.json").write_text(json.dumps({"values": ["mcaddon:scene"]}), encoding="utf-8")

    return pack


def windowBounds() -> tuple[int, int, int, int] | None:
    """Where the game's window sits, so a capture holds the game and not the whole desktop."""
    asking = ('tell application "System Events" to tell (first process whose name is "java") '
              'to get {position of window 1, size of window 1}')
    found = subprocess.run(["osascript", "-e", asking], capture_output=True, text=True, check=False)
    numbers = [one.strip() for one in found.stdout.strip().split(",")]
    if len(numbers) != 4 or not all(one.lstrip("-").isdigit() for one in numbers):
        return None
    left, top, across, down = (int(one) for one in numbers)
    return (left, top, across, down)


def hideHud() -> None:
    """Takes the chat, the hotbar and the crosshair off the screen, which is what F1 does.

    The game keeps no setting for this, so the key is sent once. It is the only key this tool
    presses, and the scene itself still runs without one.
    """
    pressing = ('tell application "System Events" to tell (first process whose name is "java") '
                'to key code 122')
    subprocess.run(["osascript", "-e",
                    'tell application "System Events" to set frontmost of '
                    '(first process whose name is "java") to true'], check=False)
    time.sleep(0.8)
    subprocess.run(["osascript", "-e", pressing], check=False)
    time.sleep(0.8)


def capture(target: pathlib.Path) -> None:
    """Photographs the game's window and leaves one picture that is ready to look at.

    The whole desktop is what `screencapture` takes by default, and a window on a display that draws
    two pixels per point comes back at twice the size it was asked for. Taking the window's own
    rectangle and scaling the result down means the file can be opened and read as it is.
    """
    hideHud()
    bounds = windowBounds()
    region = ["-R", ",".join(str(one) for one in bounds)] if bounds else []
    subprocess.run(["screencapture", "-x", "-o", *region, str(target)], check=False)

    try:
        from PIL import Image
    except ImportError:
        return

    with Image.open(target) as picture:
        if picture.width <= READABLE_WIDTH:
            return
        scaled = picture.convert("RGB").resize(
            (READABLE_WIDTH, round(picture.height * READABLE_WIDTH / picture.width)))
        scaled.save(target)


def world_of(version: str) -> pathlib.Path | None:
    """The saved world a scene can be run in, or None when this version has none."""
    saves = ROOT / "versions" / version / "run" / "saves"
    if not saves.is_dir():
        return None
    return next((one for one in sorted(saves.iterdir()) if (one / "level.dat").is_file()), None)


def run(version: str, one: dict, timeout: float, keep: bool) -> tuple[bool, list[str]]:
    """Opens the world with the scene in it, waits for its marker, and photographs the window."""
    world = world_of(version)
    if world is None:
        return (False, [f"{version} has no saved world to open"])

    datapack(world, one["commands"])
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

    if finished:
        # The window is up and the scene has run, so what is drawn is worth keeping.
        time.sleep(2.5)
        capture(logs / f"{version}-{one['name']}.png")

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
    return (not problems, problems)


def main() -> int:
    parser = argparse.ArgumentParser(description="Runs scenes inside the game with nobody watching.")
    parser.add_argument("--version", default="26.2", help="which version to open")
    parser.add_argument("--scene", action="append", default=[], help="run only these, repeatable")
    parser.add_argument("--timeout", type=float, default=420.0, help="seconds one scene may take")
    parser.add_argument("--keep", action="store_true", help="leave the last game open")
    arguments = parser.parse_args()

    chosen = [one for one in scenes(arguments.version)
              if not arguments.scene or one["name"] in arguments.scene]
    if not chosen:
        print("no scene by that name", file=sys.stderr)
        return 2

    failed = []
    for one in chosen:
        passed, problems = run(arguments.version, one, arguments.timeout, arguments.keep)
        print(f"{'PASS' if passed else 'FAIL'}  {arguments.version} {one['name']}")
        for problem in problems:
            print(f"      {problem}")
        failed += problems

    print(f"{'PASS' if not failed else 'FAIL'}  {len(chosen) - len(set(failed))} of {len(chosen)} scenes")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
