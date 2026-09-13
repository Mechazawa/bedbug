# Working on this repository

A Fabric mod that loads Bedrock `.mcaddon` files at runtime, built for four Minecraft versions from
one source tree. Read `README.md` first for what the mod does; this file is about how to change it.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # required: the 26.2 target needs Gradle on Java 25

./gradlew build                 # all four versions, plus checkstyle and the tool parse gate
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

`tools/checkall.sh` runs all of them in the order that fails fastest, which is what to reach for
before saying a change is done.

**Do not build while a game is running.** A dev client and a dev server read their classes out of
`versions/<version>/build/`, and a build that replaces those underneath a running game gives it a
`NoClassDefFoundError` the moment it next needs one of them. That reads as a failure of whatever was
being checked rather than as what it is. `--quick` stops after the build and the censuses; `--scenes` adds the
in-game scenes, which open a window.

These are the checks it runs, and what each one catches:

- **`./gradlew build`** compiles all four versions and runs every test on each of them: the unit
  tests for the checked-out form, and the game tests for all four. The game test task builds
  `tools/testpack` into its own run directory first, so the tests that assert on a loaded add-on have
  one; with no pack each of those succeeds without asserting., which is what catches a call that exists on only
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
- **`tools/testpack/build.py`** builds a small add-on to check the mod against, so a clean checkout
  has something to verify itself with. Put it where the game reads packs from:

  ```bash
  python3 tools/testpack/build.py                 # build/testpack/test-addon.mcaddon
  python3 tools/testpack/build.py --version 26.2  # and into versions/26.2/run/addons/
  ```

  Everything it ships is written in this repository rather than taken from anybody's pack: the JSON,
  the `.lang` files and the `.mcfunction` are under `tools/testpack/pack/`, the textures are painted
  by the script, and the one sound is a sine tone. The archive is a build output, so it is the
  sources and the script that are committed.

  It carries one of everything the mod has a reader for, so a reader that stops working shows up as
  a count that dropped: an item icon in each of the three shapes a pack may write it in, a block with
  states and permutations drawn with its own geometry, a recipe of each kind that converts, two
  entities whose spawn eggs take each of the two forms, an animation firing a sound and a particle
  off its timeline, three `particles/*.json` in the 1.10.0 format, a texture set whose emissive
  channel has pixels in it, and a function using the commands and selector arguments that need
  translating.

  The recipes cover the five kinds and the fields that decide what each becomes: a furnace recipe
  naming four cookers and an unlock item, a shapeless one tagged for the stonecutter and unlocked by
  a context, a shaped one keyed on an item tag and carrying a `group`, a `priority` and an unlock
  naming both an item and a tag, a smithing transform and a smithing trim. The ember's own loot
  table, its equipment table and its trade table are the other three: between them they use each
  loot function and condition that converts, both entry types, a pool condition and an entry
  condition, a payment with two items to choose between, two tiers with the second behind an
  experience threshold, and a trade whose result is enchanted and one whose result is dyed. Each
  also names something this mod reports rather than converts, so a run says both halves of the
  reporting path still work.
- **`tools/testpack/build_modules.py`** builds a second add-on, whose script imports every
  `@minecraft/*` module the mod provides and calls one thing from each. Its sources are a manifest
  and one script under `tools/testpack/modules/`.

  ```bash
  python3 tools/testpack/build_modules.py --version 26.2
  ```

  It prints a `MODULEPROBE` line per answer, so a run carrying all of them says every module
  resolved. A missing module or a missing named export shows up instead as the pack's script
  failing to load, with no `MODULEPROBE` line at all.
- **`tools/testpack/build_gametests.py`** builds a third add-on, which registers two
  `@minecraft/server-gametest` tests and ships the `.mcstructure` both of them build. Its sources
  are a manifest and one script under `tools/testpack/gametests/`; the structure is written by the
  script, in Bedrock's own little endian NBT, so no file here came out of the game.

  ```bash
  python3 tools/testpack/build_gametests.py --version 26.2
  ```

  Started from the server console, `gametest run probe:floor_is_stone` has to answer `passed` and
  `gametest run probe:diamond_is_missing` has to answer with the message the assertion raised. A
  test only advances while the server ticks, so a server left empty past its
  `pause-when-empty-seconds` reaches no verdict at all; set it to `0` to work at that pace by hand.
  `verify.py` runs its tests well inside the default minute.
- **`tools/testpack/build_blockprobe.py`** builds a fourth add-on, which asks a script everything the
  block half of `@minecraft/server` offers and prints a `BLOCKPROBE` line per answer. Its sources are
  a manifest, one script and one block under `tools/testpack/blockprobe/`.

  ```bash
  python3 tools/testpack/build_blockprobe.py --version 26.2
  ```

  The block it ships names a custom component, which the script registers as the world starts up, so
  `BLOCKPROBE customComponentPlaced=bp:scripted` in a run is the whole path answering: the pack
  registered a component, a block of its own named it, and placing that block reached the pack again.
- **`tools/verify/verify.py`** does those game runs without a human watching the log. It starts the
  game, reads the output back for the failures that only appear at runtime, writes
  `build/verify/verdict.json` and a log per run under `build/verify/`, and exits non-zero when
  anything failed. It ends the game on a deadline it keeps itself, because a `stop` typed during
  startup is discarded. It kills only the games it started, matched on `fabric.dli.env=server` or
  `fabric.dli.env=client` so the two halves cannot end each other, holds `scratchpad/verify.lock`
  for the whole run so two of them cannot collide on the port or the world, and refuses to start
  beside a game somebody else is already running.

  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25
  python3 tools/verify/verify.py all                     # conversion, server and client per version
  python3 tools/verify/verify.py run --version 26.2      # a server, one version
  python3 tools/verify/verify.py client --version 1.21.1 # a client, one version
  python3 tools/verify/verify.py convert --source <extracted pack directory>   # no game
  ```

  A green `run` proves the mod started, every mixin applied, the packs converted and registered, the
  server reached `Done (`, and no pack script hit a missing `@minecraft/server` member while the
  pack's own entities were summoned and killed. It also types `gametest runall`, which is why the
  probe pack ships one test written to pass and one written to fail: a run has to report both, so a
  run where every test silently never started fails the same way as one where a test broke. It
  proves nothing about rendering.

  **Never write into a registry a data pack loads.** Loot tables, recipes, advancements, tags and
  functions are safe. A jukebox song, a worldgen feature and anything else the game reads through
  its dynamic registries are not: a world whose packs write into one of those is a world the game
  calls experimental, and it then opens behind a screen asking the player whether to load it at all.
  That screen waits for a click forever, so a run under this harness stops dead on it, and a player
  gets the same question every time they open their world.

  The built-in registries are a different thing and this mod does write into them at startup, which
  is where its entity types, items, blocks and its own loot condition type go. Fabric keeps those
  unfrozen until mod initialisation has run, and nothing about them makes a world experimental.

  A green `client` proves what the server half cannot reach: every mixin applied on the drawing side,
  every model a pack's items and blocks name loaded, every texture those models name was stitched,
  and the mod reported a client definition for more than zero add-on entities. It takes a world from
  the dedicated server's own when the saves directory is empty, so a fresh checkout needs nobody to
  make one by hand, and then waits for that world's own server to start rather than for a fixed time.
  Waiting for the world rather than for the drawing is what tells a client that loaded from one
  stopped on a screen asking the player a question: everything about models and textures has already
  happened by the time such a screen is up. `1.21.1` also loads Mod Menu, Sodium and Iris, so it takes
  noticeably longer than the other two.

  Failing in either: `ERROR`, `Caused by`, a crash report, a mixin apply failure, `Script error:`,
  `Failed to load function`, `Multi threaded access requested by thread`, `Could not initialize
  class`, `StackOverflowError`, `Unable to load model`, `Missing textures in model`, a Java compile
  error and a Gradle build failure. The last two model checks are logged at `WARN`, which is why they
  went unread for so long. Filtered as normal in a dev environment: `No data fixer registered for`,
  the Realms authorisation failure, `Failed to retrieve profile key pair`, and Sodium force-disabling
  the mixins Iris replaces.

  A green `convert` proves the output under `versions/<version>/run/.mcaddon/converted/` holds one
  texture per texture the pack ships, one sound event per definition, and an `_n` and `_s` sidecar
  per texture set that asks for one. A stale cache shows up here as a count that moved.

  It reads what a game wrote rather than converting anything itself, which is why `all` runs it last.
  A pack is converted again as soon as anything in it changes, so `convert` on its own after an edit
  reports on the conversion before that edit until a game has opened the pack.

  With no `.mcaddon` in `versions/<version>/run/addons/`, every mode reports that there was nothing
  to test and still passes.

- **`tools/scene/scene.py`** runs a scene inside the game with nobody at the keyboard. `verify.py`
  types into a server console, which reaches everything a server can be asked and nothing a player
  does; the models, the attachables, the forms and every script path carrying a player only run once
  somebody is in a world.

  ```bash
  python3 tools/scene/scene.py --version 26.2            # every scene
  python3 tools/scene/scene.py --version 26.2 --scene entities
  python3 tools/scene/scene.py --version 26.2 --version 1.21.1   # both at once
  ```

  **The versions run at the same time and the scenes of one version run in turn**, because a version's
  scenes share one saved world. Each version's client is told not to pause when it loses the focus,
  because a picture is taken by bringing one window to the front and a paused game draws its menu
  instead of the world, and only one picture is taken at a time.

  A scene is a list of commands and the lines that have to appear afterwards. The commands are
  written into a datapack in the world's own `datapacks/`, in a function tagged `minecraft:load`, so
  the game runs them itself as the world opens. Nothing is typed and no window has to be focused. A
  picture of the window is kept beside the log, and the datapack is removed again afterwards.

  It takes a world from the dedicated server's own when `versions/<version>/run/saves/` is empty, so
  `verify.py run` once is all the setup there is, and it reads the entity and item names to place out
  of the archives in `addons/` themselves, so a checkout with no add-on still runs and simply places
  nothing. Reading them out of the mod's own cache instead places whatever an earlier run left there.
  **Only commands every supported version spells the same way belong in a scene**: 26 renamed the
  game rules, and one command that will not parse takes the whole function with it, which is what a
  name no longer in any pack is. Each scene asks the world how much of what it placed is standing
  there and says so, so a picture of an empty stage cannot pass.

  The `items` scene stands one armour stand per add-on item and puts the item on its head, which is
  what draws an item icon, its tint and its model in the world. The heads-up display is off while
  the picture is taken, so an item in a hotbar or in a hand would not be in it.

- **`tools/scene/zoom.py`** cuts a piece out of a scene's picture and scales it up without smearing
  the pixels, for looking at one subject rather than the whole window.

  ```bash
  python3 tools/scene/zoom.py build/scene/26.2-items.png --slice 2/11
  python3 tools/scene/zoom.py build/scene/26.2-items.png --box 340 380 560 470 --out /tmp/one.png
  ```

- **`tools/census/script.py`** says how much of the published script API this mod offers, which is
  the one measurement `/mcaddon scripts` cannot make. That report names what a *loaded pack* asked
  for and did not get, so a pack that never opens a form says nothing about forms, and a surface no
  installed add-on touches reads as complete. The census compares against Mojang's own type
  definitions instead, so the answer does not depend on which add-on is installed.

  ```bash
  python3 tools/census/script.py          # a line per module, implemented of published
  python3 tools/census/script.py --list   # and the name of everything missing
  ```

  It measures against material that is not committed, because it is Mojang's rather than ours:
  `bedrock-samples` and the published `@minecraft/*` packages, under `scratchpad/`. Run
  `bash tools/census/fetch.sh` to get them, or just run `tools/checkall.sh`, which fetches what is
  absent and leaves what is there alone.

  **A missing top level name is the serious kind.** A module is linked before any of it runs, so a
  pack importing one name the module does not export loses its whole script rather than the line
  that used it. A missing member costs only the call. Keep the first number at zero: add the name and
  report it through `unsupported(...)` when there is nothing behind it.

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
- **`tools/census/components.py`** measures the other direction. The in-game reports only see what a
  loaded pack happened to ask for, so they can say nothing about a feature no pack used. This reads
  the vocabulary Mojang publishes and the names this mod acts on, and prints coverage per surface
  plus every missing name. No pack, no game.

  ```bash
  git clone --depth 1 https://github.com/Mojang/bedrock-samples scratchpad/bedrock/bedrock-samples
  python3 tools/census/components.py            # prints the table and the gaps
  python3 tools/census/components.py --strict   # exits non-zero while anything is missing
  ```

  The published side comes from `metadata/` in that checkout: the entity component schemas and the
  entity docs, the engine's Molang query and function lists, and the block and item component
  schemas. With `metadata/` absent it falls back to the component names the vanilla behaviour pack's
  own entities use, which is a lower bound rather than the vocabulary. `scratchpad/` is gitignored,
  so the checkout is never committed.

  The mod's side is read out of the source: the `IMPLEMENTED` declarations in `AddonEntities`,
  `AddonItems` and `AddonBlocks`, and the `case "..."` labels in `AddonEntity.addGoals`,
  `EntityEvents.applyComponent`, `Filter.evaluate`, `AddonQueries.query` and `MolangMath.call`. A
  name added to one of those switches counts the moment it is added.

  The run ends with two lists worth reading. Names in the source that the published vocabulary does
  not contain are stale or misspelt. Names an `IMPLEMENTED` declaration carries with no reader of
  their own are either a component Java does for every mob anyway, which is why the declaration
  names it, or a coverage claim nothing backs; each declaration's docblock says which it allows.
- **`tools/census/model.py`** does the same for the drawing side: geometry, animations, animation
  controllers, render controllers and client entity definitions. Mojang publishes a JSON schema per
  behaviour pack component and none for any of these, so this measures against the files instead.
  The resource pack in `bedrock-samples` is Mojang's own and carries every shape the engine accepts,
  for every vanilla entity.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/model.py            # the table, then what is dropped and what is stale
  python3 tools/census/model.py --strict   # exits non-zero while a published field has no reader
  ```

  The published side is one counter per kind of declaration: how many geometries, bones, cubes,
  faces, animations, keyframes, controller states, render controllers and client entities carry each
  field. The mod's side is the field names one named reader method takes out of a JSON object, found
  by reading the `has`, `get` and helper calls in that method's body, so a field starts counting the
  moment a reader names it.

  Missing fields are listed with how many published declarations use each. That number is what makes
  a gap worth ranking: `animation_length` is absent from 337 of 436 vanilla animations, and a reader
  defaulting it to zero holds every one of them on its first frame.

  Anything the census reports as dropped either has a reader now or is named below under what is
  read and what is not. A field read that the published files never declare is a reader being more
  tolerant than vanilla, which is fine, or a misspelling, which is not.

### What the drawing readers do not read

Four of the standing drops are deliberate, and the census will keep naming them:

- **`visible_bounds_width`, `visible_bounds_height` and `visible_bounds_offset`** are the box the
  engine culls a model against. Java builds its own from the entity's bounding box.
- **`rebuild_animation_matrices`, `filter_lighting` and `light_color_multiplier`** describe how
  Bedrock's own renderer schedules and lights a pass. `ignore_lighting` has a Java form, full
  brightness, and is honoured; the other three do not.
- **`texture_meshes` and `binding`** on a bone build geometry from a texture's opaque pixels and
  attach a bone to another entity's skeleton. Neither has a Java counterpart in this renderer.
- **`should_update_effects_offscreen`, `should_update_bones_and_effects_offscreen`,
  `min_engine_version`, `enable_attachables` and `hide_armor`** each ask the Bedrock client for
  behaviour it decides itself. Java decides those the same way for every entity.
- **`tools/census/commands.py`** does the same for the command dialect. A `.mcfunction` is the one
  part of a pack Java refuses by halves, one bad line costing the whole file, so knowing which
  commands survive translation matters more here than anywhere else.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/commands.py            # prints the table and the gaps
  python3 tools/census/commands.py --strict   # exits non-zero while anything is missing
  ```

  The published side is `metadata/command_modules/mojang-commands.json`. Three surfaces come out of
  it. The **names** are every command it lists with the aliases folded in, because a function may
  write either spelling. The **keywords** are the words that pick one form of a command over
  another, `music play` against `music stop`: the file writes each as an enum, and an enum of at
  most sixteen values is grammar while a longer one is an open vocabulary of blocks, items or
  biomes. The **game rules** are the two rule enums, which matter because Bedrock writes a rule name
  in lower case and Java's parser is case sensitive.

  The mod's side is read out of `Functions.java`: the `SHARED_WITH_JAVA` and `RENAMED` declarations,
  which say Java's own parser reads the line, the `REGISTERED_HERE` names, which this mod builds a
  command for, and the `case "..."` labels of `Functions.rewrite`. Everything else is dropped and
  named, and the report ends with the check for that: a published command that is missing and is not
  in `BEDROCK_ONLY` would be a gap nobody decided on.

  **What the keyword number counts.** Every published keyword goes into one of three groups, the
  same three the selector arguments use, and the first two are what the percentage is of:

  - **passed through**: the whole line reaches Java, because the command is shared or renamed, or
    the rewrite names the front of the line and leaves the rest alone. `TAIL_REACHES_JAVA` declares
    the second kind. Such a keyword is answered by Java's own parser with no literal for it anywhere
    in the translation, which is why it has to be declared rather than measured.
  - **rewritten**: the source spells the literal and decides what to do with it.
  - **dropped**: the command is named in `BEDROCK_ONLY`, so the keyword goes with it, or
    `BEDROCK_ONLY_KEYWORDS` names this one form of a command that otherwise translates. A keyword in
    that declaration never counts as covered, whatever literals the source happens to carry, which
    is what keeps a form the translator reports from reading as one it handles.

  Two things the number still does not say. A keyword matches on the literal alone rather than on
  the literal under that command, so a word two commands share counts for both. And the metadata
  writes a boolean argument as an enum of `true` and `false`, which puts a value in a surface that
  is otherwise grammar; both halves of the count carry it.

  The keyword figure is read against the newest supported version, which is the form committed to
  git. A form `Platform` answers for on 26 and not before, such as the slot body armour sits in,
  counts here and is dropped and reported on the version that has no such thing.

  The command metadata names no selector arguments at all, so those are listed from the source
  instead, in the same three groups.
- **`tools/census/particles.py`** measures the effects a pack describes itself.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/particles.py            # prints the table and the gaps
  python3 tools/census/particles.py --strict   # exits non-zero while anything is missing
  ```

  The published side is the particle schemas under `metadata/json_schemas/client/particles/`, the
  particle documentation and the vanilla resource pack's own effects. Four surfaces: the
  **components** the effect schema lists together with every one the vanilla effects declare, the
  **fields** those component schemas carry read as one flat vocabulary, the four **curve types** the
  documentation names, and the **Molang variables** the documentation says the particle system
  answers by itself.

  The mod's side is the string literals under `bedrock/particle/` and `client/render/`, plus the
  prefix `AddonEmitters.randomise` builds the four random variables from. A component this mod does
  not read is reported through `AddonEntities.unsupported` as the effect is read, so the two halves
  agree: what the census calls missing is what a pack is told about in game.
- **`tools/census/data.py`** is the same measurement for the three vocabularies a behaviour pack
  writes as files of their own: loot tables, recipes and trade tables. It shares `components.py`'s
  helpers, so both read the mod's side out of the Java source the same way.

  ```bash
  python3 tools/census/data.py            # prints the table and the gaps
  python3 tools/census/data.py --strict   # exits non-zero while anything is missing
  ```

  The published side is the vanilla behaviour pack itself: every `function` and `condition` name its
  loot tables use, every `minecraft:recipe_*` key its recipes carry and every field inside each of
  them, and every level and field its `trading/*.json` write, widened by the recipe documentation
  module and the `Trade*` JSON schemas under `metadata/`. Several of Mojang's own files carry `//`
  comments and a trailing comma, which `json` refuses, so the reader strips both; a census that
  skipped those files would report a smaller vocabulary than the one Mojang ships.

  The mod's side is the `case "..."` labels in `LootTableDefinition.function`,
  `LootTableDefinition.condition` and `TradeTable.applyFunction`, and the `CONVERTED` and `HANDLES`
  declarations in `RecipeDefinition` and `TradeTable`. A field name is qualified by the level that
  carries it, so `shaped.key.tag` and `trade.max_uses` say which of the two `tag` fields and which
  `max_uses` is meant. The run ends by naming any declared field whose own literal appears nowhere
  else in the source, which is a claim with no reader behind it.

## Conventions

**No add-on content is committed.** No textures, sounds, models or text from any pack. Real packs
live outside the repository and `addons/` is gitignored. What `tools/testpack/` holds is written
here from nothing, which is why it may be committed. Keep that line.

**Bedrock names survive.** A pack's own namespace becomes the Java namespace, sanitised to what a
resource location accepts, so `vn:reporter` stays `vn:reporter`. Nothing is renamed into a mod
namespace, and nothing is flattened to a basename: two packs may use the same file name.

**Nothing global holds one pack's data.** Every geometry, animation and controller map belongs to the
pack it came from, because two add-ons can name a geometry the same thing.

**A pack's failures are reported, not swallowed.** An unimplemented script member, a sound file that
is missing, a command that does not parse: each is counted and shown, never thrown and never
silently dropped.

**A gap and an answer are counted apart.** A command the game read and then declined, a selector that
matched nothing, a structure holding a chest this cannot fill: those are listed after the verdict
line rather than among the gaps, because counting them would make a pack asking an ordinary question
read as a pack this mod cannot serve. `/mcaddon scripts` still says everything was implemented.

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

tools/testpack/              The four add-ons the harness checks against, and the scripts building them
tools/verify/verify.py       End to end harness: drives a dev server and checks a conversion
tools/preview/render.py      Offline model renderer
tools/molang/MolangCheck.java  Molang conformance harness
tools/census/components.py   Coverage of Bedrock's published vocabulary, no pack and no game
tools/census/model.py        Coverage of the fields the geometry and animation readers act on
tools/census/commands.py     Coverage of Bedrock's published command dialect
tools/census/particles.py    Coverage of Bedrock's published particle format
tools/census/data.py         The same, for loot tables, recipes and trade tables
tools/checkall.sh            Every check, in the order that fails fastest
tools/census/script.py       How much of the published script API this mod offers
tools/scene/scene.py         Scenes run inside the game, with nobody at the keyboard
tools/scene/zoom.py          One piece of a scene's picture, scaled up to look at

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
  structure/                 Bedrock `.mcstructure` reading, whose NBT is little endian
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
  minecraft_common.js        The @minecraft/common error types and shapes
  minecraft_math.js          The @minecraft/math vectors, arithmetic only
  minecraft_vanilla_data.js  The @minecraft/vanilla-data tables, read from the registries
  minecraft_server_gametest.js  The @minecraft/server-gametest surface: registrations, the Test
                                object, the sequence and the runner the server tick drives
```

`ScriptHost.MODULES` maps an import specifier to one of these files. A module is linked before any
of it runs, so a specifier a pack imports that is not in that map, or a named export missing from
one that is, ends that pack's whole script rather than the line that asked for it. Add the name
even when the mod cannot act on what the module offers.
