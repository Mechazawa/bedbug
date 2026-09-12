# Working on this repository

A Fabric mod that loads Bedrock `.mcaddon` files at runtime, built for three Minecraft versions from
one source tree. Read `README.md` first for what the mod does; this file is about how to change it.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # required: the 26.2 target needs Gradle on Java 25

./gradlew build                 # all three versions, plus checkstyle and the tool parse gate
./gradlew check                 # verification only
./gradlew :26.2:compileJava     # one version, for fast iteration
./gradlew :26.2:runGameTest     # the in-game tests, headless, part of check
./gradlew :26.2:runServer       # loads the mod headless
./gradlew :26.2:runClient       # opens a window
```

Jars land in `versions/<minecraft>/build/libs/`. Gradle downloads the JDKs each version needs.

Running two `:<version>:compileJava` tasks back to back in one shell hits a Loom cache lock and
reports a failure that is not real. Use `./gradlew build`, or one version at a time.

To try a real pack, drop a `.mcaddon` into `versions/<minecraft>/run/addons/`.

## Linting, and why there is no formatter

**Checkstyle**, configured in `config/checkstyle/checkstyle.xml`, running as part of `check`.

**There is deliberately no auto-formatter.** Stonecutter rewrites `src/main/java` in place for
whichever version is active, so a tool that rewrote source would produce different output depending
on which version happened to be active, and the committed form has to stay the 26.2 one.

Two consequences, both already handled in the config:

- **Checkstyle only runs for the checked-out version.** Linting a generated form reports an import
  the 26.x branch needs as unused in the 1.20.1 form, and back again.
- **`UnusedImports` is suppressed for files carrying a whole alternative class body**, listed in
  `config/checkstyle/suppressions.xml`. Checkstyle reads a comment as nothing, so an import only the
  commented branch needs looks unused.

Mixin injectors are named `mcaddon$doThing`, which Mixin requires and the `MethodName` pattern
allows.

## Stonecutter

`settings.gradle.kts` lists the versions. `vcsVersion` names the form committed to git, currently
26.2, so **write source against the newest names** and let the string replacements in
`build.remap.gradle.kts` rename downward.

```java
//? if >=26 {
return level.getOverworldClockTime();
//?} else {
/*return level.getDayTime();
*///?}
```

The closing `*/` is glued to `//?}`. `else if` chains work.

Two traps:

- **`stonecutter.gradle.kts` does not execute its body.** Shared build logic goes in
  `gradle/mod.gradle.kts`, which both buildscripts apply.
- **Switching the active version dirties the tree and switching back does not restore it.** Run
  `git checkout -- src/` afterwards and check `git status` is clean.

Anything whose spelling differs between versions belongs in `platform/Platform.java`. A whole class
that differs (the entity renderer, the network channels) carries two bodies instead.

## Verifying a change

These are the checks that catch things:

- **`./gradlew build`** compiles all three versions, which is what catches a call that exists on only
  one of them.
- **`./gradlew :26.2:test`** runs the unit tests in `src/test/java/`, for the checked-out version
  only, for the same reason linting is. They cover what is a function of its input rather than of a
  running game: the Molang evaluator, the Targa and Ogg readers, the command translation, the labPBR
  channel arithmetic, manifest reading and pack order. Anything needing a world belongs in the game
  tests instead. A test needing the registry calls `SharedConstants.tryDetectVersion()` and
  `Bootstrap.bootStrap()` in a `@BeforeAll`, which is enough without launching the game.
- **`./gradlew :26.2:runGameTest`** runs the Mojang game tests in
  `src/main/java/at/ioexception/mcaddon/gametest/`, headless, against a real world. `check` depends
  on it, so a failing one fails `./gradlew build`, and it is gated to the checked-out version like
  everything else. What it covers is the half a unit test cannot reach: that `/playanimation` and
  `/event entity` still parse and run, that an entity event no pack declares is answered instead of
  thrown, that delivering a tick's data-driven triggers ends, and that a script failing inside the
  tick costs the tick rather than the server.

  The run directory is `versions/<minecraft>/build/run/gameTest/`. No pack content is committed, so
  the two tests that need one succeed when none is loaded; dropping a `.mcaddon` into that
  directory's `addons/` gives them something to assert against.

  Fabric's game tests carry Mojang's annotation before 26 and Fabric's own from 26 on. The two
  spellings are one pair of string replacements in `build.remap.gradle.kts`, which is why `@GameTest`
  is written with empty parentheses.
- **`tools/verify/verify.py`** does that same server run without a human watching the log. It starts
  the dev server, types a scripted sequence of console commands into it, reads the output back for
  the failures that only appear at runtime, writes `build/verify/verdict.json`, and exits non-zero
  when anything failed. It kills the server on a deadline it keeps itself, because a `stop` typed
  during startup is discarded. It kills only the servers it started, holds `scratchpad/verify.lock`
  while a server is up so two runs cannot collide on the port, and refuses to start beside a dev
  server somebody else is already running.

  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25
  python3 tools/verify/verify.py all                     # conversion counts, then a server per version
  python3 tools/verify/verify.py run --version 26.2      # one version
  python3 tools/verify/verify.py convert --source <extracted pack directory>   # no game
  ```

  A green `run` proves the mod started, every mixin applied, the packs converted and registered, the
  server reached `Done (`, and no pack script hit a missing `@minecraft/server` member while the
  pack's own entities were summoned, read back and killed. Each of `ERROR`, `Caused by`, a crash
  report, a mixin apply failure, `Script error:`, `Failed to load function`, `Multi threaded access
  requested by thread`, `Could not initialize class` and `StackOverflowError` fails the run.
  `No data fixer registered for` and the Realms authorisation failure are filtered, being normal in a
  dev environment. It proves nothing about rendering.

  A green `convert` proves the output under `versions/<version>/run/.mcaddon/converted/` holds one
  texture per texture the pack ships, one sound event per definition, and an `_n` and `_s` sidecar
  per texture set that asks for one. A stale cache shows up here as a count that moved.

  With no `.mcaddon` in `versions/<version>/run/addons/`, both modes report that there was nothing to
  test and still pass.

- **`./gradlew :26.2:runServer`** for the same check by hand: grep the log for `Bedrock Add-ons`,
  `Done (`, `ERROR` and `Caused by`. Mixin failures fail the server, so a clean start proves
  registration, conversion and mixins applied.
- **`tools/molang/MolangCheck.java`** parses every expression a real pack contains and checks the
  answers that can be worked out by hand. Molang fails silently by design, so zero throws is not the
  measurement; the assertions are.

  ```bash
  mkdir -p /tmp/molang && cp -r src/main/java/at/ioexception/mcaddon/bedrock/molang \
    /tmp/molang/at/ioexception/mcaddon/bedrock/ 2>/dev/null
  javac -d /tmp/molang/out $(find src/main/java/at/ioexception/mcaddon/bedrock/molang -name '*.java') \
    tools/molang/MolangCheck.java
  java -cp /tmp/molang/out MolangCheck <expressions.txt>
  ```

- **`tools/preview/render.py`** bakes a geometry with the same conversion the mod uses and paints a
  front and a side view to a PNG, so model work can be checked without launching the game. It reads
  a resource pack directly: geometries from `models/**/*.json` in both the 1.12 and the 1.8 shape,
  and a texture from the `.png` or the `.tga` at the path a pack names.

  ```bash
  python3 tools/preview/render.py --assets "<pack>/Villager News 1.0 Add-On RP" \
    --geometry geometry.oreville_vn.-754165646 --texture textures/oreville/vn/dix \
    --out /tmp/villager.png --size 512
  ```

  Pass a geometry name it does not have and it lists the ones it does.
- **`/mcaddon packs`** and **`/mcaddon scripts`** in game report what loaded and what a pack asked
  for that is not implemented.

## Conventions

**No add-on content is committed.** No textures, sounds, models or text from any pack. The test pack
lives outside the repository and `addons/` is gitignored. Keep that line.

**Bedrock names survive.** A pack's own namespace becomes the Java namespace, sanitised to what a
resource location accepts, so `vn:reporter` stays `vn:reporter`. Nothing is renamed into a mod
namespace, and nothing is flattened to a basename: two packs may use the same file name.

**Nothing global holds one pack's data.** Every geometry, animation and controller map belongs to the
pack it came from, because two add-ons can name a geometry the same thing.

**A pack's failures are reported, not swallowed.** An unimplemented script member, a sound file that
is missing, a command that does not parse: each is counted and shown, never thrown and never
silently dropped.

**Comments state a present truth about the code.** No edit narration, no "now" or "previously", no
pointers to files a reader cannot open, no restating the signature.

**Prose that leaves the repository stands on its own.** Commit messages, the README and code comments
are read by people who know nothing about how the change came about.

## Layout

```
settings.gradle.kts          Version list and vcsVersion
stonecutter.gradle.kts       Controller. Its body does NOT execute.
build.remap.gradle.kts       Obfuscated targets (1.20.1, 1.21.1) and the string replacements
build.named.gradle.kts       26.2, which Mojang ships unobfuscated
gradle/mod.gradle.kts        Shared build logic: toolchain, GraalJS bundling, checkstyle, checkTools
config/checkstyle/           Lint rules and the Stonecutter suppressions

tools/verify/verify.py       End to end harness: drives a dev server and checks a conversion
tools/preview/render.py      Offline model renderer
tools/molang/MolangCheck.java  Molang conformance harness

src/main/java/at/ioexception/mcaddon/
  pack/                      Discovery, manifests, dependency order
  convert/                   Runtime conversion: Targa, Ogg repair, sounds, text, models, functions
  resource/                  The converted packs and how the game is told about them
  registry/                  Sounds, entities, items, blocks, spawns and the creative tab
  bedrock/molang/            Molang parser, evaluator, maths. Trig is in degrees.
  bedrock/model/             Geometry parsing, baking, render controllers
  bedrock/animation/         Animations, channels, player, controllers
  bedrock/entity/            Client entity definitions and the animator that runs them
  bedrock/behaviour/         Entities, items, blocks, recipes, loot, trading, spawn rules, filters
  script/                    GraalJS host, the bridge, pack-declared commands, coverage
  entity/                    The one mob class every add-on entity uses
  block/                     The block class that carries a pack's own states
  server/                    Server lifecycle, commands, sensors, entity events, property sync
  gametest/                  The Mojang game tests, reached through the fabric-gametest entrypoint
  client/render/             Renderer, attachable layer, pass resolution, query answering
  client/screen/             The screen a Bedrock form becomes
  mixin/                     Pack repository source, and the attachable layer on living entities
  platform/Platform.java     Every call whose spelling differs across versions
  network/Channels.java      Payloads, in both the 1.21+ and the older form

src/main/resources/assets/mcaddon/script/
  minecraft_server.js        The @minecraft/server surface, in JavaScript
  minecraft_server_ui.js     The @minecraft/server-ui surface
```
