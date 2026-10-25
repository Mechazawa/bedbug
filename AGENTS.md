# Working on this repository

A Fabric mod that loads Bedrock `.mcaddon` files at runtime, built for five Minecraft versions from
one source tree. Read `README.md` first for what the mod does; this file is about how to change it.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # macOS; Gradle has to run on Java 25 or newer
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk   # the Linux equivalent; the tools read it too

./gradlew build                 # all five versions, plus checkstyle and the tool parse gate
./gradlew check                 # verification only
./gradlew :26.2:compileJava     # one version, for fast iteration
./gradlew :26.2:runGameTest     # the in-game tests, headless, part of check
./gradlew :26.2:runClientGameTest   # the client game tests, in a window, part of check
./gradlew :26.2:runServer       # loads the mod headless
./gradlew :26.2:runClient       # opens a window
```

Jars land in `versions/<minecraft>/build/libs/`. Gradle downloads the JDKs each version needs.

**A dev run carries other mods.** `companionMods()` in the two buildscripts installs Sodium, Iris,
Lithium, Ferrite Core and the rest into every `runClient`, `runServer` and `runGameTest`, so what
starts is a modded game rather than this mod alone. None of them is compiled against and none is in
the jar. `verify.py companions` is the mode that reads their half of the log back.

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

**A branch holding nothing but `//` comment lines loses the slashes in the other form.** Stonecutter
reads such lines as commented code and uncomments them, which leaves bare prose for javac. Keep at
least one line of code in every branch, or put the comment outside the branch.

**Testing a version other than 26.2 means switching to it.** The unit, game and client game tests
run for the active version only, so `./gradlew "Set active project to 26.3"`, run them, and
`./gradlew "Reset active project"` before committing. While switched, the 26.3 branches are the live
code and the 26.2 ones are commented, so an edit made then is written in that form.

**What 26.3 moved**, each handled where it is used:

- The GPU API left `com.mojang.blaze3d` for `com.mojang.renderpearl.api`, a pipeline is compiled
  when first used rather than registered, and `PoseStack.mulPose(Quaternionf)` is `rotate`.
- The window and input moved from GLFW to SDL, so key and mouse button numbers are SDL's: the left
  button is 1, not 0. A test presses a button or a key by its `InputConstants` name, never by number.
- A player's swing starts on the server, so the swing event listens in the packet listener's
  punch, use and interact handlers.
- Brewing is a recipe type, and fuel and composting are item components naming entries the
  converter writes.
- A living entity's hurt cooldown is `damageCooldownTime`; `invulnerableTime` is a separate guard.
- `blocksMotion` is the `minecraft:blocks_motion` block tag, the piston reactions carry Bedrock's
  names, colour environment attributes are vectors and a biome's spawns are an environment attribute.
- Cooking recipes must state their time, and `recipe_unlocked` names its recipes under `recipes`.
- Java ships the cushions, the poplar wood, the shrubs, the shelf mushroom, the straw bed and the
  wool and concrete slabs and stairs, so none of them is Bedrock's own on 26.3.

## Verifying a change

`tools/checkall.sh` runs all of them in the order that fails fastest, which is what to reach for
before saying a change is done. Every census runs there with `--strict`, so a gap Mojang publishes
fails the run until it is answered or named with the reason Java cannot answer it.

**Do not build while a game is running.** A dev client and a dev server read their classes out of
`versions/<version>/build/`, and a build that replaces those underneath a running game gives it a
`NoClassDefFoundError` the moment it next needs one of them. That reads as a failure of whatever was
being checked rather than as what it is. `--quick` stops after the build and the censuses; `--scenes` adds the
in-game scenes, which open a window.

**A worktree that has been built in costs about a gigabyte and a half.** Four versions of Minecraft,
their libraries and their run directories are all per checkout, so a handful of worktrees left
around fills a disk, and everything then fails at once in ways that look like anything but a full
disk. Remove a worktree as soon as its branch is merged: `git worktree remove --force <path>`, or
`rm -rf <worktree>/versions/*/build` to keep the checkout and drop only what a build can make again.

**Run `tools/seed-worktree.sh` once in a new worktree, before its first build.** `wt add` runs it
by itself through `.wt-post-add`; a worktree made any other way runs it by hand. It copies the
Loom downloads and remapped companion jars (`.gradle/loom-cache/{minecraftMaven,remapped_mods,natives}`)
from the main checkout with reflinks where the filesystem has them, which takes a first
`./gradlew assemble` from about 65 seconds to about 16. It is safe on a worktree that already holds a
full copy of the main checkout, and it deletes what such a copy gets wrong: `.gradle/loom-cache/projects`
and `versions/*/build/loom-cache` hold absolute paths to the checkout that made them, and
`versions/*/build/{run,reports}` and `versions/*/run/{saves,world,.mcaddon,logs,crash-reports}` are
worlds and caches of that checkout. Loom writes the first two again on the next build. The worlds and
caches are removed only on the first run in a worktree, which `.gradle/seeded-worktree` records by the
worktree's path, so running it again keeps the worlds the worktree made itself. Build outputs
are not copied, since the Gradle build cache restores them and execution history is keyed by absolute
path. Run it while the main checkout is not building.

These are the checks it runs, and what each one catches:

- **`./gradlew build`** compiles all five versions and runs every test on each of them: the unit
  tests for the checked-out form, and the game tests for all five. The game test task builds
  `tools/testpack` into its own run directory first, so the tests that assert on a loaded add-on have
  one; with no pack each of those succeeds without asserting., which is what catches a call that exists on only
  one of them.
- **`./gradlew :26.2:test`** runs the unit tests in `src/test/java/`, for the checked-out version
  only, for the same reason linting is. They cover what is a function of its input rather than of a
  running game: the Molang evaluator, the Targa and Ogg readers, the command translation, the labPBR
  channel arithmetic, the biome and fog colours, the band of height a dimension states, manifest
  reading and pack order. Anything needing a world belongs in the game tests instead. A test needing
  the registry calls
  `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()` in a `@BeforeAll`, which is
  enough without launching the game.
- **`./gradlew :26.2:runGameTest`** runs the Mojang game tests in
  `src/main/java/at/ioexception/mcaddon/gametest/`, headless, against a real world. The run takes
  one class or one test at a time through Fabric's selector property, which the forked game reads
  from the environment: `JAVA_TOOL_OPTIONS='-Dfabric-api.gametest.filter=mcaddon:bedrock_command_game_tests_*'`
  in front of `./gradlew :26.2:runGameTest`. A test's id is `mcaddon:` and its class and method
  names in snake case, joined by an underscore. `check` depends
  on it, so a failing one fails `./gradlew build`, and it is gated to the checked-out version like
  everything else. What it covers is the half a unit test cannot reach: that `/bedrock playanimation`
  and `/bedrock event entity` still parse and run, that Java's own tree reads none of Bedrock's
  commands or selector arguments and lets nobody below an operator run `/bedrock`, that an entity
  event no pack declares is answered instead of thrown, that delivering a tick's data-driven triggers ends, that a script failing inside the
  tick costs the tick rather than the server, and that a converted dimension and its dimension type
  decode through this version's own codecs, whose field lists differ on each of the four.

  **Hunting a flaky game test.** The whole suite takes about forty seconds, so repeat it. A single
  test runs with `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter='mcaddon:*<snake_case_name>*'` (the
  id is the class and method in snake case, and the wildcard needs the `mcaddon:` prefix).
  Adding `-Dfabric-api.gametest.verify=true` runs 400 copies of each match, 100 in each of four
  rotations, at once. A test that reads absolute offsets fails in the three turned copies by
  construction, so read only the unturned ones. Fabric's `@GameTest` allows 20 ticks unless `maxTicks`
  says more.

  The run directory is `versions/<minecraft>/build/run/gameTest/`. No pack content is committed, so
  the two tests that need one succeed when none is loaded; dropping a `.mcaddon` into that
  directory's `addons/` gives them something to assert against.

  Fabric's game tests carry Mojang's annotation before 26 and Fabric's own from 26 on. The two
  spellings are one pair of string replacements in `build.remap.gradle.kts`, which is why `@GameTest`
  is written with empty parentheses.

  On 26 an annotated method is already a test instance. Fabric registers each one as a
  `Consumer<GameTestHelper>` in `BuiltInRegistries.TEST_FUNCTION` and puts a
  `FunctionGameTestInstance` straight into the `test_instance` registry as that registry loads,
  behind the `fabric-api.gametest` property. 1.20.1 and 1.21.1 have neither registry and neither
  class, so those two targets run the annotation framework Mojang shipped before 1.21.5.
- **`./gradlew :26.2:runClientGameTest`** runs the client game tests in
  `src/gametest/java/at/ioexception/mcaddon/clientgametest/`, which drive a real client through a
  real world with Fabric's `fabric-client-gametest-api-v1`: they create worlds, run server
  commands, wait on the client's copy of an entity, click a screen's buttons by label and measure
  what the renderer would draw. This is where anything only a player reaches is asserted: a form
  that stays open across a button press, a model standing on its feet at its pack's scale, a goal
  that runs only while its group is on. A picture is not a test; assert on the state that produced
  it.

  **The drawing side is read back through what it hands the frame.** Four helpers in the same
  source set do that, and a test about drawing goes through one of them rather than through a
  screenshot:

  - `client/render/Submitted` extracts an entity's render state the way the level renderer does,
    submits it to a collector that answers every call and keeps two kinds: each piece of custom
    geometry with the render type it asked for and every vertex it wrote (position from the feet,
    colour, texture point, light, overlay), and each item with the pose it was hung at, its light
    and its tints. The same collector takes an item's own render state, which is how an icon is
    asserted, and the overlay is where a hurt entity's red is read.
  - `client/render/ScriptClock` builds the animator the renderer builds for an entity and moves it
    on a tick at a time, so a bone's pose, a script variable or the passes its render controllers
    pick can be asked for at an exact moment instead of at whatever moment a frame happened to land
    on. Its clips' sounds and particles go nowhere.

  `Submitted` extracts at a partial tick of nothing, which is earlier than the frame before it
  drew. An animator therefore never winds its clock back to a moment it has already passed: a clock
  wound back replays a stretch of every clip on the next frame, so each probe would make the clips
  run faster than the world and a test of anything timed would see it happen early.
  - `clientgametest/Born` and `clientgametest/Heard` write down every pack particle the client adds
    and every sound it is asked to play, through the test mod's own mixins in
    `mcaddon-gametest.mixins.json`. A particle is kept with its velocity and lifetime as it was
    before its first tick, and can be drawn into a recording quad state to read its size, its turn
    and its colour. A sound is recorded as the request rather than the playback, because a test
    client may have no audio device and the engine drops a sound before any listener hears it then.

  `RenderControllerClientGameTest`, `AnimationClientGameTest`, `AttachableClientGameTest`,
  `TextureMeshClientGameTest`, `RelativeRotationClientGameTest`, `CullingClientGameTest`, `ParticleClientGameTest`, `FogClientGameTest`,
  `CameraClientGameTest`, the five camera tests named with the test pack below and `ItemAndBlockClientGameTest` are the drawing side's tests; each class's docblock names the pack
  content it reads and what it asserts. Two things about the machine the tests run on: a window
  hidden by the compositor can stall the render thread inside `glfwSwapBuffers` until the server's
  watchdog ends the run, which reads as a hang in whatever test was running, and a dedicated server
  test fails at once when another game on the same machine holds port 25565. Past about four
  clients at once the desktop starves every one of them, so where `~/.claude/bin/cpu-lock` is
  installed `runClientGameTest` first waits for one of four slots of its `minecraft-client` pool
  across every checkout on the machine (`-PclientSlots=<n>` changes the count, and
  `cpu-lock --status` shows who holds them and who waits).

  **A run that stops ticking ends itself.** `HangWatchdog`, the test source set's `client`
  entrypoint, counts every tick of the client and of any server in the process. Fabric lets the
  client tick only from a test's `waitTick`, so every wait a test makes is progress, and the longest
  wait in these tests is a minute. Once nothing has ticked for three minutes (`-PclientHangMinutes=<n>`
  changes that, fractions allowed), it writes a full thread dump, locked monitors and synchronizers
  included, to `versions/<version>/build/run/clientGameTest/hang-<timestamp>.txt`, logs
  `CLIENT GAME TEST HANG: <file> during <test class>` at `ERROR` and halts the process with exit code
  124, so the Gradle task fails and gives back its `minecraft-client` slot. The dump's first line is
  that same line, which `tools/checkall.sh` prints after the build, and `verify.py` reports the log
  line as a failure of its own. The clock starts at the first tick, so a game that never finishes
  starting is not caught. The test it names is a `*ClientGameTest` class found on a thread's
  stack, or the last one seen when no thread carries one.

  A client that stops logging with a window the desktop calls not responding and almost no CPU is
  worth a `jstack <pid>` before it is killed. One such stop on 26.1.2 had the render thread parked
  in `IntegratedServer.halt` under Fabric's `deferDisconnect`, the server thread and the test thread
  both parked in Fabric's `ThreadingImpl.enterPhase`, and the test thread inside
  `TestSingleplayerContextImpl.close`: the three wait on each other while a test world closes, and
  no code of this mod is on any of the three stacks.
  The render thread has left Fabric's phaser to run the deferred disconnect, and `IntegratedServer.halt`
  waits for the server thread to run a task, while the server thread, already halted by its owner's
  logout, waits at the phaser for the render thread to arrive: a race inside the API (version 6.0.2 on
  26.2), seen with a server still busy generating chunks. Nothing in this repository's tests or mod
  touches those phases.
  The `gametest` source set works around it with `IntegratedServerHaltMixin`, which redirects the
  `executeBlocking` call in `halt` to run the guest removal in place on the server thread, queue it
  with `execute` otherwise, and drop it once the server has stopped. It is in
  `mcaddon-gametest.mixins.json` only, so the mod jar never carries it. To reproduce the deadlock
  on demand, add a temporary mixin sleeping 300 ms on the render thread at the head of
  `IntegratedServer.halt` (the server thread calls `halt` too, and must not sleep) and close a world:
  without the redirect the first close hangs.

  Only the 26.x targets ship that API, so the tests are a source set of their own (`gametest`, with
  its own `fabric.mod.json` listing them) that the two older targets never compile, and `check`
  runs them for the checked-out version only. The two 26.x test APIs differ in a few spellings,
  which `Worlds` holds. A world built with the builder's consistent settings opens in survival on
  peaceful, so a test that needs the creative inventory, or a mob reacting to the player, sets the
  game mode and the difficulty itself: Java's target goals ignore a creative player and every player
  on peaceful. A pack's avoid goal does not, because what it flees is its filters' call alone.

  A test that measures an entity standing still freezes the world with `tick freeze` and moves it on
  with `tick step`, because a pack's scripts and goals move entities that `NoAI` leaves alone.

  `-Paddons=<directory>` installs every `.mcaddon` in that directory beside the synthetic one, for
  either game test task, so the same tests can be run against a real pack that may never be
  committed here. Both tasks delete their world before running, because each run of the server
  tests places them somewhere new and a kept world only grows.

  **Test worlds load 5 chunks in every direction.** Fabric forces the client's render distance to 5
  and, whenever a test world opens, puts every option back to what it saw at startup, so a value a
  test sets in code lasts for that world only. The simulation distance, which the integrated server
  reads from the client's options, therefore comes from the `options.txt` that `runClientGameTest`
  writes into its run directory (`simulationDistance:5`); 5 is the lowest the game accepts and a
  lower value is silently replaced by the default of 12. Dedicated servers started through
  `Worlds.server` get `view-distance` and `simulation-distance` 5 in their `server.properties`
  unless a test passes its own. A test that needs more chunks raises the client's options after its
  world has opened.

  What a player does with a pack's items and blocks is driven through the client's own input: the
  use, attack and inventory keys, the view turned onto a block or an entity, and container clicks
  sent the way a screen sends them. `ItemUseClientGameTest` eats, wears, throws and spawns from an
  egg; `BlockClientGameTest` places a block by its placement trait, uses it, stands on it, aims over
  it and mines it, timing the break against the state's hardness and counting what it drops;
  `CreativeTabClientGameTest`, `CraftingClientGameTest`, `TradeClientGameTest`,
  `DialogueClientGameTest` and `RecordClientGameTest` cover the creative tabs, the crafting table,
  furnace and brewing stand, the merchant screen, an NPC's scene and a jukebox. Each starts from
  `Stage`, a stone floor high over the world with the player on it in the game mode the test asks
  for. The client runs on its own clock, so time is counted on the server's (`getTickCount`) or
  from the first tick the client itself reports progress (`getDestroyStage`), never from the key
  press; and a teleport turns the view, so `Stage` waits for the teleport to land before a test
  turns it. A killed mob stays in the crosshair while it dies, so `Stage` discards what it clears
  away, and a widget with no label is pressed with `onPress`, the way `clickScreenButton` presses
  one, because a click placed with the pointer did not always land.

  `VillageClientGameTest` makes a normal world with structures, teleports to the nearest village and
  waits for villagers to be standing in it, which is what a player calls a village. Two traps met
  while writing it:

  - **A survival player teleported into the air dies of the fall, and a dead player holds no
    chunk.** The overworld then has nothing loaded, `areEntitiesLoaded` answers false everywhere and
    no entity is found, which reads exactly like entity loading being broken. A world made with
    `setUseConsistentSettings(false)` starts in survival, so put the player in creative or somewhere
    they can stand.
  - **`/locate poi #minecraft:village` finds job sites, not villages.** The tag holds every job
    site, bed and bell, and a barrel is the fisherman's, so it answers with the nearest trial
    chamber or Terralith refinery as readily as a village. `/locate structure #minecraft:village` is
    the question about villages.

  Three more make a world and go somewhere in it. `PackBiomeClientGameTest` and
  `PackFeatureClientGameTest` are described with the test pack below; `PackDimensionClientGameTest`
  asks for the three pack dimensions as levels, flies the player into one and asks every entry the
  test pack wrote into a dynamic registry whether it is stable. What they met:

  - **A biome is three dimensional, so `findClosestBiome3d` finds one at a height rather than on
    the ground.** Asked for a forest, it answered with a point two hundred blocks up over an ocean.
    `PackFeatureClientGameTest` searches for ground whose surface is forest for as far as it counts,
    working the surface out from the generator rather than the chunk.
  - **Java puts a new player anywhere within the spawn radius**, so a search started from the player
    looks at different chunks on every run of one seed. `PackFeatureClientGameTest` searches from the
    middle of the world, and passes over dark forests: their canopy leaves a scatter onto the
    heightmap almost no ground, and runs there counted between two and five torchflowers in 49
    chunks, which is one unlucky run from none.
  - **Fabric registers a biome its modification API changed again, without its pack's name.** A
    biome a feature rule or a spawn rule reached carries no `KnownPack` afterwards, though it stays
    stable, and baking a world's dimensions registers every level stem again the same way. Ask a
    placed feature or a dimension type which pack it came from, not a biome or a level stem.
  - **The client game test task deletes its run directory**, so every run converts the pack afresh.
    A defect that only shows on a cached conversion has to be asserted by `runGameTest`, whose
    `.mcaddon/` survives between runs.

  **Multiplayer is asserted against a dedicated server.** The `Server*ClientGameTest`s call
  `worldBuilder().createServer()` and `connect()`, so the client reaches the server over a real TCP
  connection on which every message is encoded, which a single player world's in-memory connection
  never does. `ServerRegistryClientGameTest` compares every add-on entity type, item, block, sound
  event and biome by number on both sides and sends an item stack and a block state across;
  `ServerEntityClientGameTest` follows a wisp's type, a `client_sync` property, a component group's
  box and drawn size, a wisp settled before the client could see it, and `/bedrock playanimation` on a wisp
  and a villager; `ServerPlayerClientGameTest` adds a second player, `Bystander`, and asserts a form,
  `/camera`, `/camerashake`, `/fog` and a property a script overrides for one player reach the
  player they name and nobody else, that a form's answer counts only from its own player, and that
  the test pack's eight hundred button catalogue arrives whole.

  What the dedicated server runs on: Fabric starts it with `net.minecraft.server.Main` inside the
  client's own process and in the same run directory, so it has the same mods, reads the same
  `addons/` that `installClientTestPack` fills, and writes its world to `world/`, which Fabric
  deletes before each one. Three things follow:

  - **The built-in registries and every static field are one copy shared by both sides.** A
    registry id cannot disagree here and a client reading server state by mistake would pass, so
    assert on what crossed the connection (an entity, a stack, a payload) rather than on statics.
  - **C2ME ends the whole process once a dedicated server shuts down**, which ended the run after
    the first of these tests and still reported success. `runClientGameTest` writes
    `config/c2me.toml` with `fixes.disableLoggingShutdownHook = false` first, which turns that off.
  - **Fabric's client `DISCONNECT` is raised on the network thread** when a remote connection drops.
    Anything a handler clears that the render thread also reads has to be handed to the client
    thread, which is what the first of these tests to disconnect found as a
    `ConcurrentModificationException`; the server's own `DISCONNECT` is handed over the same way.
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
  states and permutations drawn with its own geometry and culling against a named voxel shape, a
  recipe of each kind that converts, two
  entities whose spawn eggs take each of the two forms, an animation firing a sound and a particle
  off its timeline, four `particles/*.json` in the 1.10.0 format, a `fogs/` file whose air and
  water blocks are written differently from each other, a texture set whose emissive
  channel has pixels in it, a glyph page beside the locale list and the locale names a pack declares,
  and a function using the commands and selector arguments that need translating.

  The hands-on client tests use a few things of their own. `tp:berry` is food eaten in 0.8 seconds,
  half Java's default, and `tp:pebble` throws `tp:spark`. `tp:berry` also composts at a chance of
  40, which `SurfaceGameTests` reads back, and `tp:wand` burns for five seconds, which
  `PackGameTests` lights a furnace with. `tp:totem` is a block with a
  `minecraft:placement_direction` trait turned by a `y_rotation_offset`, a use counter driven by
  `minecraft:on_interact`, a script component (`tp:tapped`, registered by the pack's script) that
  tags whoever uses it, half-high boxes a permutation makes whole on the third use, and a loot
  table of its own, against `tp:lamp`, which names none and so drops itself.

  `tp:pouch` holds 48 of Bedrock's 64 by its `minecraft:storage_weight_limit`, `tp:dust` weighs 16
  and `tp:berry` nothing by their `minecraft:storage_weight_modifier`. Java's bundle contents know
  nothing of the bag carrying them, so `BundleItemMixin` hands the bag's limit to the
  `BundleContents.Mutable` it opens through `BagCapacity`, `BundleCapacityMixin` measures against
  it, and `BundleWeightMixin` answers a stated weight; a weight of nothing is answered as 65, more
  than any bag holds, which keeps the item out without a division by nothing. `StorageGameTests`
  clicks a cursor stack onto a fresh pouch and counts what stayed on the cursor. `tp:wand` names a
  level sound event for each moment of `minecraft:swing_sounds`, and the resource pack's
  `individual_event_sounds` gives each a definition of its own. The server plays a hit and a
  critical hit in place of Java's own (`PlayerSwingSoundMixin`); a swing at nothing is known only
  to the client, so `AttackMissMixin` sends the `swing_missed` payload from `startAttack`'s miss
  branch and the server plays the miss for everyone near. `SwingSoundClientGameTest` hears all
  three. An `individual_event_sounds` entry no item names is reported, since in Bedrock it would
  change that moment for every player.

  A third entity, `tp:wisp`, states the numbers whose unit differs between the two editions: a health
  `value` below its `max`, a goal priority that is neither the first nor the last of its goals, an
  anger duration in seconds with an event at the end of it, a teleport cube in blocks beside a
  teleport interval in seconds, and an experience reward written as Molang rather than as a number.
  Its render controller picks its texture the way a pack's style setting does, with
  `v.restless ? {return Texture.restless;}; return Texture.default;` over `tp:settled`. A `return`
  inside a block has to end the whole expression, or every wisp is drawn with the default texture.
  `ServerPlayerClientGameTest` reads the texture back while the script overrides that property.

  The sprite's own texture carries one thing a count cannot see. Its top four rows are written at an
  alpha of three. **What Bedrock does with such a texel depends on the material, not the texture**:
  its entity shader (the legacy `entity.fragment`, and `AlphaTest` against `AlphaTestColorMask` in
  the RenderDragon materials) drops alpha under half under a plain alpha test and keeps anything not
  fully clear under a colour mask, where the alpha is how much of the texel is tinted. Java's cutout
  keeps everything from a tenth. So the conversion writes the texture with those texels raised,
  which the game test `aMarkedTexelIsConvertedOpaque` reads back, and a `_alpha_tested` form beside
  it with them cleared, and `MaterialDefinition.dropsFaintTexels` picks the form per pass. The
  sprite draws with `entity_alphatest` and `tp:shade` draws the same texture with
  `entity_alphatest_change_color`, and `RenderControllerClientGameTest` asserts each pass binds the
  form its material sees. A pack marking a texture with one texel at an alpha of one, which a plain
  alpha test never draws, otherwise shows that texel as a speck floating beside the model.

  The shade's first controller also states a `color` halving red and a blue `overlay_color`. A colour
  mask draws a texel as `mix(rgb, rgb * color, alpha)`, so the marked band takes three parts in 255
  of that colour where the body takes all of it. The conversion writes the texture as painted
  beside it, `_tint_weights`, for any texture holding a partly clear texel, and `AddonModels` draws
  such a pass untinted and then again, blended, from that form in the controller's colour.
  `RenderControllerClientGameTest.colourMask` reads both passes back. The shade's second controller
  draws `tp_unlaid`, the pack's own `entity_alphatest` with `USE_OVERLAY` removed, under the same
  overlay, and the same test asserts that pass takes neither the blue nor the red of a hit. The
  sprite's cap is a colour mask over the same texture, so the sprite draws three passes and the
  cap's colour is read off the third.

  `tp:shade` also collides in a box of nothing, the way a pack's stand-in for a vanilla mob often
  does. Java scales how far it draws a mob by its box, so `AddonEntityRenderer` measures that
  distance by the model's visible box as well, and `CullingClientGameTest` asserts the shade is drawn.

  `tp:vane` is the one entity whose model is built from a texture rather than from boxes. Its only
  bone carries a texture mesh over an eight by eight texture drawing an L, each texel painted a
  colour of its own, placed by a local pivot, a position and a quarter turn and then turned half a
  circle and lifted by its own animation. `TextureMeshClientGameTest` reads every face the renderer
  submits back into the texel it stands for, so a face over a clear texel, a side between two drawn
  ones, a layer of the wrong depth, a mesh left where its bone was not, or a face sampling a texel
  other than its own each fails it by name.

  The behaviour side carries a clip and a controller of its own, which is the same pair of readers
  driving the world rather than a model. The ember's `scripts.animate` names the controller; its
  first state waits on a Molang query, and the state it moves to assigns a variable, raises one of
  the ember's own events, runs a command as the ember and plays a clip whose timeline raises a
  second event. Each of the three kinds of statement leaves a mark a game test asks the world about,
  so a reader that stopped running shows up as an assertion rather than as a count.

  The ember also carries a component group holding one marker, the two events that switch it on and
  off, and a third event whose `has_component` filter asks for that marker. Nearly everything a
  Bedrock pack switches on partway through lives in a group rather than at the top, so three game
  tests ask what a group is worth: that the marker is published while the group is on and cleared
  when it goes, that a filter asking `has_component` sees it, and that the live set is built again
  from what was stored rather than lost with the chunk.

  Five more groups carry the components a group brings that Java holds on the entity: a size, an
  experience reward written the way Mojang writes one, an attack and a knockback resistance beside
  a `minecraft:persistent`, an anger that calms itself down again, and a timer beside an
  environment sensor. Each is switched on by an event of its own and read back through what Java
  does with it, so a reader that went back to asking the definition shows up as a box that never
  shrank, an attribute that never moved or a reaction that never ran. The timer's group is switched
  off partway through its wait and on again after the deadline has gone by, which is the one
  assertion a live read alone does not satisfy.

  Three more carry what an entity takes on rather than what it is. The laden group holds a collision
  box wider and shorter than the one on the entity, an inventory beside the shareables list saying
  what may go in it, an idle sound interval, a dye, a dweller's radius, a leashable refusing to be
  stolen, a ground control, a sittable and a hopper drawing from whatever stands over the entity;
  the primed group holds an explosion, and the warded group
  a damage sensor refusing the blow and raising an event about it. The mortal group's sensor
  refuses only a blow `has_damage` calls `fatal`, which the sensor answers from the damage on its
  way in against the health left, and `aDamageSensorKnowsAFatalBlow` deals a scratch and then a
  killing blow. The box is the one that proves a
  group moves the width and the height apart from each other, which no scale can do, and the laden
  group's components are otherwise unrelated so that each assertion names one of them alone.

  Five more carry what the entity was built as. The airborne group holds a navigation and a movement
  that put it in the air, the clinging group a `minecraft:can_climb`, the ghostly group an
  `is_collidable` and a `pushable` that both say no, the shod group a step height beside a preferred
  path and an effect immunity, and the stocked group a trade table of its own naming a second file.
  Java builds the navigator and the move control inside the mob constructor and keeps a field for
  each, reads the step height off an attribute, asks the entity itself whether an effect may land,
  and can only open a trade table's file while the pack is still in hand, so each of the five is
  read back through the thing Java holds rather than through the definition. The sprite carries one
  more, a `minecraft:fire_immune`, because the ember declares that at the top level and an entity
  already immune shows nothing about a group.

  Four more carry what the world does to an entity and what it sounds like. The wintry group holds
  a `minecraft:freezing_immune`, a `minecraft:can_stand_on_powder_snow` and a
  `minecraft:block_movement_slowdown_immunity` naming the cobweb the way Bedrock spells it,
  `minecraft:web`; the thawing group holds a `minecraft:freezing_vulnerable`; the chatty group a
  `minecraft:uses_legacy_ambient_sounds`, whose four idle moments the resource pack's `sounds.json`
  names for the ember, each a sound definition of its own; and the scoopable group a
  `minecraft:bucketable`. Each is read back through what Java does with it: a plain ember beside the
  wintry one is what says the powder snow froze anything at all, a cobweb's slowdown is applied and
  the move after it measured, the shape powder snow hands an entity standing on it is asked for, one
  freezing blow lands on a plain ember and on a thawing one, the idle sound is asked for on the
  ground, in the air, young and under water, and the scoopable ember is scooped into its bucket and
  let out again in the groups it went in with. The laden group also carries a
  `minecraft:leashable_to` beside its `minecraft:leashable`, both stating `unleash_on_removal`, so
  unloading it lets go of both ends of a lead.

  The rest of the ember's groups each carry one field Java has a place for, and `ComponentGameTests`
  reads each back through that place. The gilled group breathes water alone for two seconds and the
  wisp states seven seconds of air at the top level; the wading group prices water, damaging blocks
  and lava, and the finned group is a navigation that swims and never walks; the fertile group breeds
  into pregnancy with a two second rest; the loyal group follows an owner; the hunting and the
  vengeful groups name the wisp's family in their `entity_types`; the watchful group looks for half
  a second, the pottering group strolls one block, the gathering group picks items up at the top
  priority, the peckish group is tempted three blocks across, the springy group jumps at eight
  tenths and the hopping group rests a second between hops; the furious group spreads its anger;
  the turning group transforms after a second; the hoarding group's inventory is private; the
  anchored group is pushed by a preset; the lit group's fuse burns half a second; the remembering
  group's home restricts nothing and the nesting group's does; the lava buoyant group floats on lava
  alone; and the nameable group raises one event for a listed name and another for any other. The
  laden group's lead carries two presets and refuses to be stolen. The plain ember is the other half
  of most of these assertions, which is why it carries none of them: no `minecraft:breathable`, no
  `minecraft:leashable` and no `minecraft:nameable`, so it never drowns, takes no lead and takes no
  name tag. The shod group's preferred path also drops seven blocks, which Java asks the mob for as
  `getMaxFallDistance`.

  `tp:brute` is a plain mob for the fields inside behaviours, and `GoalGameTests` reads each back
  off the world. It hits for one point, is not immune to fire and has no goal at the top level, so
  whatever moves it is the group a test switched on, and a test holding its feet sets its speed to
  nothing so that a pig standing apart from it is reached by the goal and not by a walk. Its groups
  cover the melee fields (a cooldown, a box reach, a reach multiple, the attack and kill events, fire
  passed on), a ranged attack's interval stretched across its range, its potion arrows, its charged burst and
  swing, and its minimum range and sight time (`poisoner`, `burster`, `wary`), the panic causes, force and
  water, an escape from a block, a target and a projectile teleport, three breeding groups (what a
  child inherits, what a certain mutation and a denied variant give it), taming by item and breaking
  in by riding, listed riders, a poisoning attack, the rideable fields, an explosion heeding or
  ignoring the griefing rule and a resistance cap, a potion drunk by its conditions, the shelter
  walks, water looked for, a goal radius, a leap, an area attack's cooldown, a step taken while
  steered, and four flocking groups. Three more flocking groups hold a brute off a wall, between two
  heights and under the surface of a pool, the last two floating with gravity off; a water drag group
  is launched down a channel beside a plain brute; two swimming groups and four flying ones are read
  back through the spot the navigation heads for, the flying ones with a fly navigation of their own;
  a follow group walks towards a pig ten blocks off, a raider group towards a brute whose group makes
  it the captain, and three walk groups to a gold block set into the floor, raising `tp:reached` and
  `tp:stayed`. A walk's `goal_radius` is one block there, because Java's path ends a block short of
  its target and Bedrock's half block is never reached from there. Each event that adds a group is
  named after it. A few traps met
  writing them: the empty structure's floor is not a block Java's random walks may end on, so a test
  needing one lays stone first; light under a new roof takes a few ticks to settle, so a test asking
  `canSeeSky` builds first and waits; the older versions place test structures close enough that a
  flock wider than four blocks takes in the brutes of the next test; and a blast's fire is lit only
  where it broke a block. `GoalSoundClientGameTest` hears the panic and retreat sounds the brute's
  `sounds.json` entry names, which a sound moment no Java question reaches has to be kept for.
  `tp:spark` reflects when struck after a tenth of a second, and its brittle and dull groups are
  removed and stopped when struck instead. `tp:dart` is a slow projectile with no gravity that GoalGameTests
  fires through pigs: plain it stops at the first, `tp:dart_piercing` hits every one and
  `tp:dart_bouncing` bounces off an invulnerable one. `tp:brute_stalking` carries an ocelot attack and
  `tp:brute_pouncing` a pounce whose stuck block is gravel, because a snow layer does not survive on
  the empty structure's barrier floor. `tp:brute_hopping` jumps around its target the way a breeze does. `vaulting` leaps by `target_dist` and from the air, `soothing` hands out an ambient
  effect on a cooldown, `ramming` runs up to a far target, `lunging` rests between aquatic charges and
  `hauling` holds a strength over its `max`. `trampling` stomps and `winding` lands a delayed blow. `touchy` turns on what pushes it, `prickly` hurts without naming itself and `gunning` fires from
  and at its anchors within its view. `choosy` picks its arrow from its shooter's `projectiles`. `mirrored` turns a reflected snowball by its Molang angle. `flitting` teleports every tick in a box eight blocks high, and `aRandomTeleportLandsOnTheGround` asks that every jump ends on the ground and outside any block, the check `LivingEntity.randomTeleport` makes. Four more brute groups each blow up one
  way (`submerged`, `gentle`, `switching`, `launching`), read back in `GoalGameTests.aBlast*`. The brute's `tp:brute_roaring` group roars,
  pushing pigs tagged `tp_pushable` and hurting those tagged `tp_hurtable`.
  `tp:brute_milky` drinks milk only while it burns, which `anEntityDrinksThePotionItsConditionsName`
  reads off a weakened brute. `tp:brute_homely` walks in under a roof only at night or in rain, and
  `anEntityUnderTheSkyWalksInUnderARoofOnlyAtNightOrInRain` runs day, night and rain in turn in one
  test because the clock and the weather are shared by tests running at the same time.

  `tp:idler` is a plain mob for the resting and idling goals, and `IdleGoalGameTests` reads each
  group back through a pose or a property: timer flags, sitting, lying down, looking around and
  sitting, napping with its detection box and exceptions, croaking, sniffing, emerging, digging,
  holding ground, looking at the target, playing dead and sneezing. Java ticks a running goal only
  every second tick, so a goal that counts its own `tick` calls runs its waits at double length;
  these goals count on the game clock, and a test that reads a duration off them catches the other
  kind. `tp:cart`, `tp:cart_keeping` and `tp:cart_checking` carry `minecraft:rail_sensor` and
  `SensorGameTests` runs them over a rail powered by a block of redstone beside it, because a powered
  rail set powered by hand is recomputed unpowered. `tp:gazed` carries `minecraft:looked_at` in one
  group per field and `LookedAtGameTests` aims a player or a pig at it; a mock player made with
  `makeMockPlayer` is not in the level's entity list, and a creative one is never a valid target, so
  those tests use `makeMockServerPlayerInLevel` and a pig as the looker whose target is asserted.

  The ember's sensing group carries a `minecraft:damage_sensor` with one trigger per cause, each
  setting the enum property `tp:felt` to the cause it heard: `fall`, `entity_attack`, `projectile`,
  `drowning`, `suffocation` and `contact`, every one but the attack refusing the blow.
  `BehaviourGameTests.aDamageSensorHearsEachCauseByBedrocksName` hurts the ember with the Java
  source for each and with `magic`, which no trigger names and which lands.

  The brute carries five more groups for goals that move it somewhere of its own choosing:
  `tp:brute_stomping` looks for turtle eggs every tick, `tp:brute_exploring` walks five blocks past
  the edge of a village, `tp:brute_village_bound` walks to the nearest village, `tp:brute_hiding`
  runs to a bed when a bell rings and `tp:brute_playing` plays with a pig. `tp:brute_stomping_near`
  searches two blocks for eggs, `tp:brute_grazing` eats hay into dirt, `tp:brute_snacking` eats only
  sweet berries and then waits, `tp:brute_nesting` lays on sand with nothing above it and
  `tp:brute_flower_taking` takes a golem's flower once it is tagged. `tp:brute_admiring` admires
  for a second and neither admires nor barters for five after being hurt, `tp:brute_cheering` and
  `tp:brute_celebrating_survival` celebrate with no raid, and `GoalSoundClientGameTest` hears the
  admiring, cheering and nesting groups' own sound moments. `tp:brute_dressing` puts on a helmet where
  `tp:brute_unarmoured` cannot, and `tp:brute_hunting_alone` celebrates a kill only for the brute
  that made it. `tp:brute_sharing` shares with whoever its `entity_types` names, written the way
  older entity files write a filter: an `AND` group over subjects and operators given by number,
  beside a second entry no mob in the test answers, so a list read as all of its entries or a filter
  left wrapped in its entry hands the apple to the nearer cow. `tp:brute_caravanning` forms a
  caravan of at most two behind a leashed pig its `entity_types` admit, so of three brutes beside
  one, two fall in. `tp:brute_seeking_closely` walks to gold with the default `goal_radius` of half
  a block, which Java's own block walk never reaches because its path ends a block short and it
  measures from the middle of the block, so `MoveToMatchingBlockGoal` paths onto the spot and
  measures from the feet. Java's own egg search can be
  replaced by a speed mod with one over a fixed twenty-four blocks, so `StompEggGoal` searches in its
  own `canUse`. `GoalGameTests` builds the
  village each one needs out of a bell or a bed in the empty structure, which Java registers as a
  point of interest the moment the block is set. The brute's `tp:arrived` property is set by its
  `minecraft:entity_spawned` and `minecraft:entity_born` events, which is how
  `GoalGameTests.aBredChildIsBornRatherThanSpawned` tells a bred child from a placed one.
  The ember's `tp:ember_hatchling` grows up in a second and drops a turtle shell piece, and
  `tp:ember_picky` takes wheat worth its whole wait only from a player tagged `tp_feeder`.
  The script tags a cow `tp:cow_load_seen` from `entityLoad`, which `aBredAnimalIsBornToTheScript`
  asks a placed cow and a bred calf not to carry.

  Two more entities are there for what a mob walking about never reaches. One is a projectile with
  hit boxes wider than the box it collides in, which turns `isolated_physics` off the way Mojang's
  own arrows do, so it is moved the way any entity is and a row of cobwebs holds it back; the other
  shoots it, rides rails, remembers what comes
  near it and dies the ender dragon's long death. A third file describes `minecraft:player`, which is
  a definition of an entity the game already has: nothing on it is applied and every component on it
  is named, which is the only way `minecraft:player.hunger` and the five beside it reach
  `/mcaddon entities`. `verify.py` leaves a `minecraft:` definition out of both the identifiers it
  summons and the entity types it counts, for the same reason.

  The sprite's geometry also carries the two bones a Bedrock model says where its hands and its head
  are with, `rightItem` and `helmet`, and its client definition asks for an item in that hand at
  twice the size and lit by nothing around it. Beside it is the visor's attachable, whose root
  bone binds with `q.item_slot_to_bone_name(c.item_slot)`, which is the spelling every binding Mojang
  publishes uses and the one that needs `context.item_slot` answered. `tp:topper` (a hat pivoted at
  28 pixels and modelled on top of a humanoid head) and `tp:baton` (a cube at its pivot) bind the
  same way, and `AttachableClientGameTest` puts them on the player and a husk. `tp:scroll` binds
  `item`, the child of an empty root, with a second cube on `sheet` below it, all about a pivot of
  nothing, the way a pack holds a newspaper: a bone bound below the root keeps its pivot's offset
  from its parent's and is not lowered by the 24 pixels a bound root is, so its cube sits at the
  hand unlifted. The pack also dresses `minecraft:diamond_horse_armor`, so a horse wearing it is
  drawn with that attachable and without Java's armour model, which the same test counts through
  the Java models `Submitted` was handed. The warden shares the sprite's
  geometry and declares neither `enable_attachables` nor a held item scale, so a picture of the two
  side by side is the same model with and without each. The `held` scene is where they are looked at.

  Four of those carry the drawing fields that are only visible in a picture. The sprite's geometry
  declares the extent it is culled against. The ember is drawn through a controller that dims the
  light reaching it and animated by a controller crossing between two states on a blend transition,
  and its scripts block asks to keep running out of view. The sprite is described twice over, the
  second file naming an older engine and a geometry the pack has not got, so a run that picks the
  wrong one of the two draws nothing where the sprite should be. The sprite is also declared young,
  and one of its two controllers picks its body by `query.is_baby` while the other draws a cap
  written to sit on top of the young body, so a query answering the wrong way leaves the cap in the
  air with a gap under it. The sprite's idle clip writes its body's turn and lift as Molang over
  `q.life_time` rather than as keyframes. Its chime clip's timeline sets a variable at two and a half
  seconds, its `ring` controller leaves its first state once that variable holds and writes from
  both `on_exit` and `on_entry`, and the cap's controller halves its green once the second of those
  is set, so the whole chain from a timeline to a render controller reads off one colour. The cap is
  drawn with `entity_alphatest_change_color`, because a controller's `color` tints nothing under any
  material that is not a colour mask. Neither
  of the sprite's controllers states an `is_hurt_color`, so a hurt sprite is drawn with the red
  every Bedrock entity flashes.

  `tp:gaze` is the one entity whose animation turns a bone `relative_to` the entity. Its body leans
  45 degrees forward and its head, a child of the body, is turned 30 degrees about the vertical in
  the entity's frame. `RelativeRotationClientGameTest` reads the head's corners back: carried to
  where the leaning body puts its pivot, standing level and turned 30 degrees. A head turned in its
  parent's frame leans with the body and fails it. The gaze's `wake` controller starts in a state
  whose `on_entry` counts `v.woke`, whose one clip counts `v.stirred` off its timeline at nought
  seconds and which leaves on `1` for a state counting `v.awake`;
  `AnimationClientGameTest` runs it on a `ScriptClock` and reads a wake and a stir and no awakening
  off the first frame and the awakening off the second, which is the client controller entering its
  first state as that frame's one move, the way `ServerAnimator` does a tick's. The gaze's `stride`
  clip turns a cubeless bone 90 degrees under a blend of `q.modified_move_speed`, and its
  `pre_animation` keeps `q.ground_speed`. `AnimationClientGameTest` teleports it a tenth of a block
  each tick, which is how a pack walks a model it draws over a vanilla mob, and reads a walk and two
  blocks a second back: the client is sent no velocity for an entity the server only teleports, so
  the speed queries read how far the entity went in its last tick. Its `pre_animation` also keeps
  `q.modified_distance_moved`, which grows across the same walk: Java counts `moveDist` only inside
  `Entity.move`, which neither a teleported entity nor a client's copy of another entity runs, so
  `EntityTravelMixin` counts every entity's step at `setOldPosAndRot`, the moment the level has it
  remember where it stood before each tick, in `moveDist`'s own measure.

  `tp:pedlar` is a trader with a trade table, a look at the trading player and a stroll it starts at
  every chance, and no `minecraft:behavior.trade_with_player`.
  `BehaviourGameTests.aTraderWithNoTradingGoalStandsStillWhileTrading` gives it a customer and
  asks that it stands still, then takes the customer away and asks that it walks.

  `tp:mite` is a box of 0.8 by 0.5 scaled by 1.35, so 1.08 blocks wide.
  `BehaviourGameTests.aScaledMobPressedAgainstAWallTakesNoWallDamage` stands three at the middle of
  the blocks beside a wall, each reaching into it, and drives them at it for five seconds. Java's
  collision skips the block a box's leading face is already inside, so without
  `AddonEntity.stepOutOfWalls` they walk into the wall and are hurt `inWall`. `tp:hoverer` is
  Mojang's bee pattern (`minecraft:can_fly`, `navigation.hover`, `movement.hover`, gravity left on
  in `minecraft:physics`) with no goal, and `aHoveringMobLetGoInTheAirStaysThere` lets it go three
  blocks up and asks that it holds its height. Java's hovering move control turns gravity off only
  at the first step it is told to take, so `applyMoveControl` turns it off as hovering starts.

  **An add-on entity ignores Java's distance despawn.** `Mob.checkDespawn` discards a mob whose
  `removeWhenFarAway` answers true as soon as the nearest player is more than 128 blocks off, and a
  game test's mock player counts. With the test grid laid out one way, a mock player in a far test
  despawned `aPounceIntoGravelLeavesItStuck`'s brute part way through, which read as a pounce that
  never came. `AddonEntity.removeWhenFarAway` answers false; `minecraft:despawn` is the only way out.

  `tp:fade` is the particle painted by curves: one of each of the four kinds, read off
  `variable.particle_age`, sets its alpha, its red, its green and its size, and the bezier chain
  carries a `horizontal_range` Bedrock ignores on a chain.

  `tp:prism` exists to be drawn. Its one render controller picks everything about its one pass by
  Molang over the entity: the geometry from `q.is_baby` and an enum property, the texture from an
  array indexed by `q.variant`, the material from `q.had_component_group` between
  `entity_alphatest_change_color` and `entity_alphablend`, the mark bone's visibility from `q.mark_variant`, a
  texture slide and a colour from the variant, an overlay from the mark variant, an `is_hurt_color`
  that replaces the red of a hit and an `on_fire_color`, and one event per group or property
  switches one of those. The colour mask takes the variant's colour and the blended material drops
  it while keeping the mark's overlay, which is what Bedrock's entity shaders do. Its three geometries
  differ in how far the core reaches from the middle, so which one was drawn reads off the vertices
  whichever way it faces. Its skin carries a texture set whose emissive channel glows over the mark,
  so a second, lit pass is drawn for the plain skin and none for the dyed one. Its script runs
  cubeless bones through each timing rule a clip has: a length past its last keyframe, its own
  `anim_time_update`, `hold_on_last_frame`, a blend value in the animate list and another in a
  controller state, a one second crossing out of a state declaring `blend_via_shortest_path` from
  350 degrees to 10, and a state that plays a clip once and moves on once its clips have all
  finished. It enables attachables, hides armour and hides what it holds while its mark variant is
  one, so the visor is drawn in its hand and never on its head.

  `tp:tailor` draws the same two textures in three passes, the way a villager wears its profession
  over the clothes of its biome: through `tp_tailored`, the one material in the pack's own
  `materials/entity.material`, which defines `MASKED_MULTITEXTURE`, through the game's
  `villager_v2_masked`, and through `entity_alphatest`, which binds the first texture alone. Its base
  is blue all over and its outfit is four bands of an opaque red, a clear white, a clear black and a
  faint green, so `RenderControllerClientGameTest` reads red, blue, nothing and blue off the image
  each masked pass binds, and blue four times off the plain one. The third controller states a grey
  `color` that its `entity_alphatest` leaves unread, so that pass is drawn untinted. A fourth
  controller gives the body `entity_alphatest_change_color` and every bone matching `*ARK` the game's
  `villager_v2_masked`, under the same grey: the test reads two passes off it, the colour masked one
  drawing the body alone in grey and the masked one drawing the spark alone and untinted, because
  Bedrock's masked multitexture shader reads no controller colour. A pattern starting with its star
  and written in other capitals than the bone
  is what Mojang's own `*leg` and a pack's careless casing look like.

  `tp:apprentice` draws the tailor's two masked controllers over textures whose last texel is a red
  marker at an alpha of one, which every villager texture of Villager News carries. Bedrock mixes
  the two textures as painted and tests the alpha of the mix, so the marker is dropped; the mix is
  therefore made from each texture's `_tint_weights` form, which keeps the alpha `AlphaMask` raised
  in the texture itself, and `RenderControllerClientGameTest.maskedMarkers` reads the marker back
  clear.

  `tp:perch` is a seat for a villager, written the way a pack learns a Java villager's level. Its
  seat's `rotate_rider_by` writes the rider's `query.trade_tier` and where it sits into temporaries
  and answers a quarter turn for the villager family, with `lock_rider_rotation` at nought; its
  animate list copies the tier into a variable when the place written is its own; and the first state
  of its controller plays a clip tagging the perch `tp_perch_journeyman` for tier two, then leaves on
  `1` for a state tagging it `tp_perch_settled`.
  `BehaviourGameTests.aSeatAsksItsRiderWhatItTradesAndTurnsIt` seats a journeyman farmer on it and
  reads both tags and the villager's facing back. That one test fails when any of these goes: the seat
  asked as the rider every tick before the mount's scripts, the temporaries every server expression
  shares, a Java villager's tier and families, a family list letting the villager on, or the first
  state's clips playing on the tick the controller starts.
  `BehaviourGameTests.aComponentConditionReadsTheTemporariesTheSeatWrote` asks the perch's
  `holds`, the call a component's Molang condition goes through, for the tier its seat wrote into
  `t.perch_tier`: a call site keeping temporaries of its own reads nothing there.

  `tp:bench` lists three seats the way Mojang's boat does: one for a rider alone at its middle,
  turning that rider a quarter, and two for a pair half a block either side along its own X, one
  turning a villager half a circle and one turning its rider a quarter back, each with
  `lock_rider_rotation` at nought. `BehaviourGameTests.eachRiderSitsInItsOwnSeat` seats one villager
  and then a second on a bench facing west and reads where each sits and which way each faces;
  `SeatClientGameTest` rides it as the player and reads the player's facing off its own client,
  which the seat's turn reaches only through the `seat_turn` payload.

  `tp:steed` is a mount its rider steers, with one group holding `minecraft:can_power_jump` over a
  jump strength of 0.9 and another holding a `minecraft:dash_action` with a six second cooldown.
  `MountJumpClientGameTest` rides it on a dedicated server, finds no jump bar while neither group is
  on, holds the jump key for a full charge and reads the height back, then presses it to dash and
  reads the distance, a second press inside the cooldown doing nothing, and `can_dash` going false
  and true again. **A ridden mount moves on its rider's client, not on the server.** From 1.21.2 the
  server never sees a rider's jump key, and `LivingEntity.travelRidden` zeroes any motion the server
  writes while a player steers, so `MountJump` applies the impulse in `tickRidden` on the client that
  heard the key through `PlayerRideableJumping.onPlayerJump`, as Java's horse and camel do. The
  server hears of the jump through the riding jump command (`handleStartJump`), keeps its own count
  of the cooldown and publishes `can_dash` from it. A dash's `horizontal_momentum` is a multiple of
  the mount's movement speed, which is how Mojang's camel, stating 20 over a speed of 0.09, dashes
  about as far as Java's.

  The recipes cover the seven kinds and the fields that decide what each becomes: a furnace recipe
  naming four cookers and an unlock item, a shapeless one tagged for the stonecutter and unlocked by
  a context, a shaped one keyed on an item tag and carrying a `group`, a `priority` and an unlock
  naming both an item and a tag, a smithing transform, a smithing trim, a brewing mix turning one
  potion into another over one of the pack's own items, and a brewing container turning a bottle into
  a splash bottle. The ember's own loot table, its equipment table, a book table and its trade table
  are the other four: between them they use each
  loot function and condition that converts, both entry types, a pool condition and an entry
  condition, a payment with two items to choose between, two tiers with the second behind an
  experience threshold, a trade whose result is enchanted, one whose result is dyed, one selling an
  enchanted book priced by its own result and one selling an explorer map. Each also names something
  this mod reports rather than converts, so a run says both halves of the reporting path still work.
  A second trade table sits beside the first, holding one trade and named by the stocked group
  alone, which is what says both tables were read while the pack was open. A fifth table is the meal
  one, whose two pools each ask `entity_killed` about a different kind of mob: that condition is
  about the moment `minecraft:behavior.eat_mob` rolls a table rather than about a death, so the game
  test writes down what the entity swallowed and reads back which of the two pools fired. A sixth,
  `rubble`, hands out a full stack under `explosion_decay`, which a game test rolls inside a blast
  and outside one.

  The lamp names its sound group in its own `minecraft:sound`, glass, and the pack ships no
  `blocks.json`, so the block sounding like glass says the behaviour pack's component was read. The
  top of its shade names a `glow` material instance, which the lamp's `minecraft:material_instances`
  paints with a texture of its own, and its geometry states how its item is turned in the inventory,
  lifted on a shelf and shown embedded, the last of which Java has no context for.
  `aBlockModelKeepsItsMaterialInstancesAndItsItemDisplay` reads the converted model back. The item
  `tp:tally` names a custom component the way a pack does from 1.21.90, as a key of its own with a
  `step` in its body, which the script registers and answers by tagging whoever used the item with
  that step.

  **Bedrock's block geometry is centred on the block.** A full block's cube has the origin
  `[-8, 0, -8]`, where Java's block model runs from 0 to 16, so `BlockGeometry` moves every corner
  and pivot half a block on X and Z. The lamp's geometry is written in Bedrock's frame (its post at
  `[-2, 0, -2]`), and `ItemAndBlockClientGameTest` reads the drawn model's extent back. The same test
  draws `tp:ember_spawn_egg`, which states only two colours: from 1.21.5 Java has no grey egg to
  paint, so `Platform.spawnEggTemplate` names `mcaddon:item/template_spawn_egg`, whose two textures
  are drawn for this mod. It also reads the particle texture of the totem, which names `*` and
  `north` and breaks into its `down` face as Bedrock does, and of the lamp, whose
  `minecraft:destruction_particles` names `lamp_on`, and the name of the lamp's item, which from
  1.21.2 is the block's only because `Platform.blockItemProperties` asks for the block's key.

  The worldgen side is four biomes, five features and two rules. The overworld biome, `tp:glade`,
  carries the components a Java biome has a field for and four it has none of, so both halves of the
  reporting path run; Java's overworld has no list a biome can be added to, so it is registered and
  named rather than generated. The two nether biomes, `tp:cinder_wastes` and `tp:soot_fields`, are
  added to the list the nether searches and are the ones a player can stand in. The end biome,
  `tp:hush`, is added to the end's highlands. The features cover
  every kind that converts, and one of them wraps another in a scatter, which is what makes the
  placement chain more than one link. The `mark*` features and `tp:turned_pumpkin` are placed by
  `FeatureGameTests` with `place feature` and read back block by block: a grid walked in the order
  `coordinate_eval_order` names, a uniform spread whose height reads the x it chose, a scatter of
  that grid, three aggregates with each `early_out`, and a single block against each of its
  neighbour rules. One rule hangs
  an ore on every overworld biome and the other hangs flowers on the forests, so a fresh chunk holds
  blocks that can be counted. **The ore places `tp:lamp`, which is the pack's own block**: a run
  where the block registry and the worldgen registry disagree leaves nothing in the ground. The
  flowers are mostly torchflowers, which no feature of the game's places anywhere, and the scatter
  wrapping them states its height as `query.heightmap(variable.worldx, variable.worldz)`, the
  surface query nearly every pack scattering something over the ground writes.
  `PackFeatureClientGameTest` counts the lamps in the chunks round a normal world's spawn, every one
  between the rule's heights, and the torchflowers in a forest, every one in a chunk whose corner
  is forest at the surface.

  The resource pack paints the glade and the cinder wastes, once in each of the two shapes Bedrock
  allows. One has a `biomes/*.client_biome.json` naming a fog of its own; the other is an entry in
  `biomes_client.json` naming a second fog and stating its water half outright, which is what a pack
  written before the split says. Between them the two fog files carry a distance in blocks, a
  distance as a fraction of the render distance, and each of the locations and blocks no Java biome
  has a field for, so the read half and the reported half both run. Nothing about a colour shows in a
  count, so the `fog` scene is where these are looked at and `BiomeColourTest` is where the
  arithmetic is asserted.

  The per-biome file also states what that biome sounds like and what falls through its air, in the
  spellings that put each half of the sound mapping to work. Its ambient loop is the pack's own
  definition, its mood is one of the engine's own names the two editions spell differently, and its
  addition is one they spell the same; an underwater moment is there too, which no Java biome has a
  field for. Its music names an entry in the pack's `sounds/music_definitions.json`, asks for
  underwater music, which reaches the `water` entry beside it, and states a volume multiplier, which
  26 holds and the older versions report. Its precipitation is a white ash density, which is a
  number rather than a count, so `BiomeSoundTest` is what asserts it comes out as Java's own chance.

  The soot fields are the generated biome painted the per-biome way: tints for grass and foliage in
  the behaviour pack, and a water colour, a fog of their own with both halves in blocks, an ash
  density, an ambient loop, mood and addition and a music definition with a volume in the resource
  pack. They carry the tag `soot`, which no biome of the game's does, and `spawn_rules/sprite.json`
  selects on it alone, puts the sprite in the monster population and allows it on crying obsidian.
  `PackBiomeClientGameTest` finds both nether biomes in a normal world, reads every one of those back
  from the client's copy of the biome and its environment attributes, and drives Java's natural
  spawner at an arena cut into the soot fields; a filter answering a pack's own tag against Java's
  biome ids, a sprite built as a creature, or a cinder wastes entry whose water half went missing
  fails there. The same test reads the ambient light the pack's `minecraft:default_lighting` states
  in the overworld, which is the lighting file reaching the world through the overworld's timelines.

  `structures/tp/hut.mcstructure` is written by `build.py` with `mcstructure.py`, the little endian
  NBT writer `build_gametests.py` uses too: a stone floor, the pack's lamp and four jack o'lanterns
  written as `minecraft:lit_pumpkin` in the layer above with the rest of that layer left alone, and
  air on top. `functions/hut.mcfunction` places it with `structure load tp:hut ~ ~ ~` and
  `/bedrock scriptevent tp:hut <x> <y> <z>` has the script place it through `world.structureManager`, and
  `StructureGameTests.aPacksStructureLoadsByItsName` reads both copies back on all four versions.

  Three dimension files and one dialogue file are there for the two directories with no count of
  their own. Two of the dimensions state their band of height in each of the spellings Bedrock
  writes it in and the third names a generator nothing converts, so the read half and the reported
  half both run. A game test server cannot say whether a dimension became a level, so
  `AddonGameTests.aPackDimensionIsWrittenOrNamed` asks only whether the conversion wrote it or named
  it on the version that runs, and `PackDimensionClientGameTest` takes a player into `tp:grove` on 26
  and reads its band back from the client. The dialogue file holds one scene with a name, text and two
  buttons and one written in rawtext rather than in plain strings, which is the pair
  `DialogueSceneTest` asserts on and the `dialogue` scene photographs.

  The resource pack ships two subpacks of itself, each repainting the sprite in a colour of its own
  and the pack itself painting it in a third, with the higher tier written first so a reader taking
  the last entry rather than the highest tier picks the wrong one. The behaviour pack declares one
  setting of each kind, which is what the three `query.*_pack_setting*` answers and
  `World.getPackSettings` are asserted against; `/bedrock scriptevent tp:pack_settings` tags the
  player with what `getPackSettings` reads (`tp:setting_read_<name>_<value>`) and every
  `packSettingChange` tags them `tp:setting_heard_<name>_<value>`. The resource pack declares a
  label and a toggle written as translation keys of its own `texts/`, a slider and a dropdown, and
  `tp:gazed`'s `pre_animation` copies the three resource settings and the behaviour pack's slider
  into `v.lit`, `v.size`, `v.blue` and `v.heat`, which `PackSettingsClientGameTest` reads through a
  `ScriptClock`. Both manifests also state the fields nothing here
  acts on, a capability among them, so a run says the reporting half of the manifest reader still
  works.

  The book table is the one a game test rolls: it holds a single entry carrying
  `enchant_book_for_trading` and no condition, so the loot function type this mod registers is
  exercised from the converted table through the codec to the enchanted book that comes back.

  The behaviour pack's one script is a two-page handbook, opened by `/bedrock scriptevent tp:handbook`
  run as a player. It is a single 2.x `CustomForm` holding both pages, each page's components shown
  through an `ObservableBoolean` and the title an `ObservableString`, with buttons whose handlers
  turn the page by setting those observables, one handler calling `form.close()` and a
  `closeButton`. A tag on the player names how `show` resolved. The client game test
  `FormClientGameTest`, run by `./gradlew :26.2:runClientGameTest` and part of `check`, opens it,
  turns the page and asserts the same screen now draws the second page and not the first, then
  closes it both ways and reads the tag back: a form whose button press closed the screen, or whose
  observables reached the screen only once, fails there. The same script answers
  `/bedrock scriptevent tp:catalogue` with an `ActionFormData` of eight hundred buttons, whose description
  is longer than the 32767 characters a plain network string carries. `/bedrock scriptevent tp:ledger`
  opens a `CustomForm` of forty lines with a tally and a signing button under them, taller than the
  small window `FormClientGameTest` gives the client: the wheel has to bring the signing button into
  view where a click with the pointer reaches it, and the tally the pack redraws on a press has to
  leave the form scrolled where it was.
  `/bedrock scriptevent tp:hints modal|message|custom` opens a form of each generation whose
  controls carry a `tooltip` or a `description` (and a description on each dropdown item). The form
  sends them as the element's `tooltip` and a dropdown's `descriptions`, and `AddonFormScreen` sets
  them as the widget's Java tooltip; `FormClientGameTest` reads the tooltip off the widget.

  `animation.tp.wisp.lift` raises the wisp's one bone by four pixels for two seconds, which is what
  `/bedrock playanimation` on an add-on entity is measured by. `/bedrock scriptevent tp:animate_wisp`
  has the script play the lift and then `animation.tp.wisp.hover`, a looping two pixel raise, in one
  named controller, with a stop expression counting frames and the lift as its next state;
  `ServerEntityClientGameTest` reads the hover alone, then the lift, then neither. A clip played
  this way is a `PlayedAnimation`: one controller runs one such clip at a time, a controller of the
  entity's own by that name is held while it runs, and a next state naming no clip of the entity,
  such as `none`, ends it, as the Bedrock Wiki's playanimation page describes.

  The same script watches the game's own villagers the way a pack replacing them does, in Bedrock's
  ids throughout: it compares `typeId` against `minecraft:villager_v2` and `minecraft:xp_orb` as
  each entity spawns, queries the overworld for `minecraft:villager_v2` every tick and spawns a
  sprite it then marks beside each one found, hears `entityHurt` through listeners narrowed to a
  villager and to a zombie, and answers `/bedrock scriptevent tp:villager` with
  `spawnEntity("minecraft:villager_v2")`. Every answer is a tag, which `VanillaNamesClientGameTest`
  reads back after summoning a Java villager and an experience orb: a surface reporting or accepting
  only Java's ids, a listener whose options are ignored, or an `entitySpawn` raised before
  `spawnEntity` has returned each fails there. A villager a player uses is teleported onto its own
  position every tick for two seconds afterwards, which is how a pack holds one still while it
  speaks; from 26 on Java's villager ends its trade on any teleport and Bedrock's does not, so
  `TradeClientGameTest` keeps the screen open through those two seconds and then buys from it.
  A villager taking a job is tagged with the `minecraft:become_*` event that reached the script and
  with the `minecraft:variant`, `minecraft:mark_variant` and `minecraft:skin_id` the script read off
  it then, which is what a pack dressing its own model of a villager does with that event.
  `ScriptGameTests.aVillagerTakingAJobTellsTheScriptWhatItBecame` makes a villager a snowy farmer and
  reads back `become_farmer`, a variant of 1 and a mark variant of 4.
  A villager a spawn egg made of another, which Java makes through `getBreedOffspring` as it does
  a bred one, reaches `entitySpawn` with the cause `Born` and with `minecraft:is_baby` already on
  it, and the script tags it `tp:born_young`; `aVillagerFromASpawnEggIsBornYoung` reads that back.
  Java's egg used on the ground makes an adult, sneaking or not.
  `server.VanillaEvents` raises the other events Mojang's behaviour files raise on the game's mobs, from
  `MobEventsMixin` (`finalizeSpawn` by spawn reason: `spawn_from_village` for a villager made by a
  structure and a naturally spawned cat, `from_wandering_trader` for an event-spawned trader llama; and
  `convertTo`: `entity_transformed` on the new mob), `ShearEventMixin` (`on_sheared`), `RaiderCelebrateMixin`
  and `VillagerCelebrateMixin` (`start_celebrating`). A mob that breeding made is marked in
  `VanillaEvents.bred`, so its `entitySpawn` cause reads `Born`. An add-on entity's event reaches
  `getModifiers()` as the groups it added and removed. `ScriptGameTests` reads each back through tags the
  test pack's script writes.

  `tp:wooly` carries two `minecraft:interact` interactions: a shearing one (`swing`, `hurt_item`,
  `spawn_items`, `play_sounds`, `cooldown`, an event) and one stating `use_item`, and an `on_bred`
  reward in a group; `InteractGameTests` reads each back. `interact_text` and `vibration` are reported.
  A cooldown holds the whole entity, as Bedrock's does. `VanillaLoot` answers a table path no pack ships
  with Java's own table for that mob (`entities/<mob>`, or `shearing/<mob>` for a `_shear` name).
  A Vector3 dynamic property is stored as a `PropertyVector` and handed to a script as a triple.
  A young vanilla mob that grows up raises the event its `minecraft:ageable` names in Mojang's
  entity file, from `AgeableMobMixin`: `minecraft:ageable_grow_up` for all but the rabbit
  (`grow_up`) and the dolphin (`ageable_grow_up`). A pack that draws its own model over a villager
  makes that model young from `minecraft:is_baby` at spawn and grown only on this event.
  `ScriptGameTests.aYoungVanillaMobGrowingUpTellsTheScript` ages a villager and a rabbit through it.

  **A villager's job is also one of its families.** Bedrock's `villager_v2` states a family per
  profession in the component group that profession switches on: a jobless villager is `unskilled`
  and `peasant`, a nitwit `nitwit` and `peasant`, a mason `stone_mason` and `artisan`. A pack such
  as Villager News picks between greeting a trader and refusing a trade by those families, so
  without them every jobless villager answers a click as if it had wares. `BedrockVillager.families`
  holds them and `Families.of` adds them to a villager and a zombie villager (which states the
  profession's family alone). `/bedrock scriptevent tp:families` tags every villager within four
  blocks with each family the script reads, and `ScriptGameTests.aVillagersFamiliesNameItsJob`
  reads them back off a jobless villager, a nitwit, a farmer and a jobless zombie villager.

  **A pack's `sounds.json` entry for a mob of the game's own decides what it sounds like.** Villager
  News keys `villager`, `villager_v2` and `wandering_trader` with every moment at a volume of
  nothing, which silences them in Bedrock. `AddonSounds.readVoices` reads every active pack's
  entries, and `EntityVoiceMixin` asks `AddonSounds.voiceOf` at the head of
  `Entity.playSound(SoundEvent, float, float)`, which every mob voice goes through on both sides. A
  Java sound is matched to Bedrock's moment by the last part of its id (`entity.villager.trade` is
  `haggle`, `no` and `yes` are `haggle.no` and `haggle.yes`). A moment with an empty sound or a
  volume of nothing is silent, one naming a sound Java has plays that instead, and one the entry
  leaves out keeps Java's own. The test pack's `villager_v2` entry silences the idle and hurt sounds
  and plays `tp:villager.refuse` for `haggle.no`, which `VanillaVoiceClientGameTest` hears.

  Every command line in the pack that runs while the game is going is written so Java's parser
  refuses it as it stands. On `/bedrock scriptevent tp:commands` the script runs, as whatever sent it, a
  selector asking with `hasitem` about the main hand and about everything carried, a selector naming
  `villager_v2`, `function tp/relay` by its path alone (which calls `tp/relayed` the same way inside
  the file) and `sendshowstoreoffer`, which needs Mojang's marketplace and so is never translated. The
  ember's `tp:ember_command` event tags it through a Bedrock radius and asks for `sendshowstoreoffer`
  as well, and its controller's command tags it
  through a Bedrock radius. `CommandGameTests` reads each of those back as a tag, and finds
  `sendshowstoreoffer` among what `/mcaddon scripts` and `/mcaddon entities` say cannot be translated rather
  than among what did not parse. No function file of the pack carries `sendshowstoreoffer`, because a function
  line is reported as the pack loads, which would answer the event's assertion before the event ran. The dialogue's first button runs `xp 1L`, and `/bedrock scriptevent tp:stock_up` gives the
  player `tp:dust` unless `hasitem` finds one on them; `PackCommandClientGameTest` presses the button
  and asks for the dust twice, and reads back one level gained and one dust carried.

  `functions/tp/gap.mcfunction` tags whoever runs it on either side of a `project` line,
  which belongs to Bedrock's editor and is never translated, so `CommandGameTests` can say the file loaded and both tags landed.
  `functions/tp/unrun.mcfunction` is run by nothing, and its `hud @s hide touch_controls` line, a piece no
  Java client draws, has to be among what `/mcaddon entities` names all the same: that is the
  report `Functions.untranslatable` makes of every function line as the pack loads. The same class
  types `/bedrock` itself: an `execute` chain selecting embers by `hasitem` and running a Bedrock
  `event` behind its `run`, embers selected by `has_property` on a value, on its opposite and on a
  property never declared, players selected by `haspermission` once `/bedrock inputpermission` has
  taken one's camera and mount away (the minecart refuses that one and takes the other, and the
  script hears the change through `playerInputPermissionCategoryChange`), a function named by its
  Bedrock path from Java's own `execute`, and the line a player who is no operator may not run. The
  client game tests ship two functions of a Java data pack of their own under
  `src/gametest/resources/data/mcaddon_gametest/`, whose `bedrock camera` and `bedrock
  playanimation` lines `CameraClientGameTest` and `JavaModelPoseClientGameTest` measure, and
  `ParticleClientGameTest` has the player type `/bedrock particle` and has Java's own `/particle`
  make the one particle Java makes of any type.

  `BedrockCommandGameTests` runs the Bedrock forms of commands whose Java namesake has no word for
  them, each through `/bedrock` and read back off the world, with vanilla entities so that every one
  asserts with no pack installed. `/ride` seats a group only when the whole of it fits a boat, fills
  the boat to its two seats, empties it, brings it to its rider and summons a rider and a ride under
  each of the three ride rules; `/summon` turns what it makes to a rotation, a point and an entity,
  takes a name written before the position, and raises a spawn event the ember answers;
  `/replaceitem` saddles a horse and arms it through `slot.equippable`, and `keep` leaves its armour
  alone and fills an armour stand's empty head; `/structure` saves a region of six different blocks
  to memory and to disk, deletes the disk copy, and loads the region mirrored both ways, turned a
  quarter, without its blocks, at no integrity, into water with and without waterlogging, and a
  layer at a time, each checked block by block where Bedrock puts it; `/tickingarea` forces a
  rectangle's and a circle's chunks, marks and unmarks one to preload and releases a rectangle
  removed by a position inside it; and `/schedule on_area_loaded` holds the test pack's
  `tp/loaded`, which counts its runs on a scoreboard, until a ticking area loads the far chunk it
  waits for. `place featurerule` puts the test pack's ore rule into stone the test lays far from
  every other test, and `place jigsaw` places the mod's own one piece pool,
  `mcaddon:gametest/fence`, into water with its jigsaw kept and its fence dry, and again with both
  words the other way. That pool and its template ship in `src/main/resources/data/mcaddon/`, the
  template under both `structure/` and `structures/` because 1.20.1 reads the plural; the template
  is a 248 byte NBT file written by hand, a jigsaw named `mcaddon:start` beside an oak fence.
  `place structure` is tested against `mcaddon:gametest/post`, a jigsaw structure (start height 150)
  over a pool of one piece, a template like the fence plus an armor stand. One test per word
  (`ignoreStartHeight`, `keepJigsaws`, `includeEntities`, the liquid setting) reads the blocks and the
  armor stand back, and one asks that an igloo refuses the words. `Platform.placeStructure` runs the
  structure's own pool through `JigsawPlacement.addPieces` with the fields it reads through
  `JigsawStructureAccessor`, because `Structure.generate` takes no override. `includeEntities` false
  discards the entities that were not in the area before the pieces were placed, since a piece always
  spawns its template's entities. `BedrockTree.MERGED_FORMS` merges Java's short `place structure`
  lines into the node `Structures` registers. `save query`, `setmaxplayers`, `packstack` and `permission` are typed by the
  console after `/bedrock` and read back through what the console is told, because a line run the
  way a pack runs one has a function's authority and each of these asks for more.
  `PermissionGameTests` sets a mock server player to `visitor` and to `member` through `/bedrock
  permission set` and reads back whether a door opened, a chest's menu opened, a block was placed
  and one broken, and a pig lost health. `server.Visitors` holds the visitors in the add-on state
  and refuses their clicks through Fabric's interaction callbacks and `ALLOW_DAMAGE`, registered
  before `WorldEvents` so a script hears none of them; `VisitorMenuMixin` refuses a container
  entity's and a mount's inventory, which open with no click on a block. Everything refused is the
  wiki's list of the player permissions Bedrock's visitor preset has off. `/gametest
  create` lays out the test pack's `tp:hut_stands`, `pos` finds its area, `runthis` runs it there,
  and `runsetuntilfail` runs `tp:fails_the_second_time`, which the test pack's script registers to
  pass once and fail after, until its second round. `/mobevent` holds back the dragon the End's
  fight makes where it finds none, through the event and through `events_enabled`; the test asks
  the fight to look for its dragon itself, through reflection on its private `findOrCreateDragon`,
  because a fight only looks once a player has stood in the End long enough for its arena to load,
  and reads the dragon back from the fight's own record, because a dragon made that moment is not
  yet among the entities its level hands out.

  **A Bedrock line a pack runs has a function's authority, and a command asking for more refuses
  it as an unknown command.** `PackCommand.run` lowers its source to the level Java runs a function
  at, and the Bedrock tree asks each command for its Bedrock level plus one, so `place featurerule`,
  Bedrock's words after a jigsaw's position, `save`, `setmaxplayers` and `permission` parse only for
  an operator typing `/bedrock`. Java's own `place` forms keep the level Java asks for them. A test of one of
  those types it through Java's own tree instead of through `BedrockLines`.

  **`allow-cheats` refuses only what a player types.** `server.Cheats` holds the switch for the
  session and `CommandsMixin` asks it at the head of `Commands.performCommand`, where a typed command
  still carries the source that typed it; a command whose source is a player is refused unless
  Bedrock's command metadata marks it `requires_cheats` false (the line after `/bedrock` is judged by
  the Bedrock command it names, and a script's custom command by its `cheatsRequired`). Which origins
  keep running was measured on Bedrock Dedicated Server 1.26.52 with cheats off: the console, a
  repeating command block, `tick.json`, a scheduled function, a script's `runCommand`, an animation
  controller's command and an entity event's `queue_command` all ran a cheat command. Bedrock refuses
  some game rules with cheats off (`commands.gamerule.type.nocheatsenabled`) without publishing
  which, so `gamerule` is left to run. `changesettingAllowCheatsStopsWhatAPlayerTypes` in
  `BedrockCommandGameTests` asserts both halves.

  `MusicClientGameTest` plays the test pack's chime through `/bedrock music` and reads back the
  track the client plays: music heard everywhere, looping when asked, turned down by `music volume`,
  queued behind a track that plays once and not behind one that loops, and gone after `music
  stop`. A server tells a client what only it acts on through `Channels.sendInstruction`, one payload
  carrying a kind and a body, which `McAddonClient` hands to the class the kind names.

  **`/bedrock wsserver` (and `connect`) is held by the client.** Bedrock opens the WebSocket from
  the game the player runs, so it reaches what that machine reaches and outlives the world.
  `server.WebSockets` only sends the player's client the address, and only for a line the player
  typed: the `/bedrock` command's executor calls `WebSockets.connectTyped`, which accepts a line
  when `Platform.LineReader`'s `topLevel` is true (frame depth 0, so not a function line), the
  Java input begins `bedrock ` (so not `execute ... run bedrock`) and the entity is a player. The
  Bedrock tree's own `wsserver`/`connect` always refuse, which covers `PackCommand` (scripts,
  events, timelines, dialogue, functions, WebSocket-run lines), a Bedrock `execute` and the
  console. `client.WebSocketLink` holds a
  `java.net.http.WebSocket` and speaks the JSON protocol. A subscription set and each
  `commandRequest` go back to the server through `Channels.sendRequest`, the one client-to-server
  payload beside a form's answer; a line runs through `PackCommand.said` as the player with the
  player's own authority, and its answer carries Bedrock's status code: 0, `-2147483648` for a line
  that did not parse and `-2147352576` for one that failed. A failure is told from a success by
  the red `CommandSourceStack.sendFailure` wraps it in, since both reach a source through one method.
  `PlayerMessage` (from `ServerPlayerChatMixin`, one call per receiving player), `BlockBroken` and
  `BlockPlaced` (`BlockItemMixin`) are raised; their bodies follow what `socket-be` records Bedrock
  sending, and a field nothing records the values of (a player's `color`, `destructionMethod`, an
  enchantment's `name`) is left out. The other events, `PlayerTravelled`, `ItemUsed` and the rest,
  are not raised: their numbering (travel methods, use methods, Bedrock's numeric entity and biome
  ids) and when each fires are known only from a few observations. Encryption is the ECDH and
  AES-256-CFB8 exchange behind `ws:encrypt`; the old `enableencryption` command form and the
  `cfb` mode are refused. `WebSocketClientGameTest` runs `WebSocketPeer`, an RFC 6455 server on a
  plain socket bound to 127.0.0.1, and asserts the answers, the world change, the three events, an
  encrypted round trip and `closewebsocket`.

  `CameraFadeClientGameTest` reads a fade back as the last rectangle the heads-up display filled
  across the whole screen, which the test source set's `GuiGraphicsExtractorMixin` writes into
  `Drawn` beside every line of text drawn, and a field of view off the camera a frame was drawn with.

  `BedrockRulesClientGameTest` reads `showcoordinates` and `showdaysplayed` back as the lines the
  heads-up display drew, `showtags` as the lines a pickaxe's `can_break` writes into its tooltip and
  `showrecipemessages` as the recipe toast the client is or is not showing; `BedrockCommandGameTests`
  covers the server's two, `recipesunlock` through an advancement's reward and `respawnblocksexplode`
  through a bed used in the nether and a charged anchor used in the overworld.

  Bedrock's border block is `block.BorderBlock`, registered as `mcaddon:border_block` before any
  pack is read and reached from `minecraft:border_block` through `BedrockBlockNames.BUILT_HERE`.
  `BorderCollisionMixin` cuts an entity's movement at the head of `Entity.collide`, before Lithium's
  own redirect inside it, against the columns `BorderColumns` holds per loaded chunk; the
  `LevelChunk.setBlockState` mixin and the client's chunk load event forget a chunk's columns. A
  unit test's bootstrapped registry has no such block, so `IdCensus` answers it from the name alone.
  `BorderGameTests` stops a pig five blocks over the block from two sides; `BorderClientGameTest`
  walks the player into the column over a block under the floor and reads the dust `BorderDust` adds
  going up and down, and none once `showbordereffect` is off.

  `PacketLimitClientGameTest` starts a dedicated server with `enable-packet-rate-limiter=true`,
  writes `packetlimitconfig.json` into the run directory, sends bursts of commands that each add one
  to a score, and reads the score back: a bucket of three that never drains lets three through, a
  looser file nobody reloaded changes nothing, and `/bedrock reloadpacketlimitconfig` lets the next
  burst through whole. `server.PacketLimits` holds the file as read, `BedrockPacketIds` the Java
  packets each Bedrock id stands for, and `ConnectionPacketLimitMixin` drops a packet on the network
  thread before any listener sees it. The switch is read off the dedicated server's own
  `server.properties` through `SettingsAccessor`, so a single player world never limits anything.

  One client game test at a time: the entry point list in `src/gametest/resources/fabric.mod.json`
  is the whole selection, so cutting it down to one class runs that class alone. Put it back after.

  `HudClientGameTest` hides and resets pieces of the heads-up display on a dedicated server, one
  piece at a time, all of them, and through the script's `tp:hud_hide` and `tp:hud_reset` probes.
  A piece is watched through the sprite Java draws it with, which a mixin of the test source set,
  `GuiGraphicsExtractorMixin`, counts into `Drawn`: a hidden piece's sprite stops being drawn while
  another piece's goes on. The bystander's display is hidden first and has to leave the client's
  alone, and nothing the client is told may reach the bystander. The paper doll is this mod's own
  layer, `client.render.PaperDoll`, drawn through `Platform.Painter.entity` with the inventory's
  own helper for a mob that follows the pointer, so the mixin counts it at `GuiGraphicsExtractor.entity`:
  the test puts the player in a boat with `/ride`, hides and resets the doll, presses the doll's
  button in the video settings off and on again and reads `options.txt` after each press, and reads
  it gone once the player has left the boat for longer than the 15 ticks it stays. The option is
  `PaperDoll.OPTION`: `VideoSettingsScreenMixin` adds it to the Preferences group (to the one list
  the screen has before 26), and `OptionsMixin` saves and loads it through the operation that
  processes Java's first option, because `Options.FieldAccess` is package private. `InputPermissionClientGameTest`
  holds the forward key and moves the mouse the way a player would, and reads back that the player
  neither walked nor turned while `/bedrock inputpermission` had taken the input away, and did both
  once it was given back. `ControlSchemeClientGameTest` drives each control scheme the same way and
  reads where the player stands and faces: the right key turning the player in place under
  `player_relative`, the mouse turning a follow orbit camera and not the player, the test pack's
  `tp:steered` (a free camera looking west whose preset names `camera_relative`) walking the player
  west whichever way they faced, and the pointer of `camera_relative_strafe` under a camera looking
  straight down turning the player toward it. The script's `tp:control_scheme <scheme>` probe calls
  `Player.setControlScheme` and tags the player with `getControlScheme()` or with the refusal.

  Four more things name the game's own items and blocks by Bedrock's ids, each of which Java's
  parser refuses as written and refuses whole: `loot_tables/names.json` drops `golden_rail`,
  `muttonRaw` and `wool` with a data value of fourteen, `recipes/lantern_from_rail.json` crafts a
  `lit_pumpkin` out of a `golden_rail`, `functions/names.mcfunction` gives `record_13` and places a
  `golden_rail` and a `wool` written with `["color"="red"]`, and `/bedrock scriptevent tp:names <x> <y> <z>`
  has the script read a powered rail's `typeId` and place a `BlockPermutation` resolved from
  `minecraft:lit_pumpkin`. `NamesGameTests` reads each back as the Java item or block, and every
  one of the four fails when the mapping is taken out.

  Beside the handbook the script answers probes, each a script event `ScriptGameTests` runs as a
  mock player and each answered by tagging that player with what the script read back: one pushes
  and pops the pack's fog on the player's fog stack, one clones a row of blocks through a filter and
  then moves it, one asks a volume for its nearest and farthest positions, and one asks a block what
  a note block plays off it and stores a value in the block's own store. Two more shake the
  player's camera and stop it, which only a client can show, so `CameraShakeClientGameTest` reads
  the shake back off the client. The script also refuses to let anything tagged `tp:untameable` be
  tamed, tags whatever a player tamed, and tags a player crouching and standing up again. It hears an iron sword being picked up by any entity, through both pickup events and an `itemFilter`, and refuses a diamond one; `aMobPickingAnItemUpIsHeardThroughTheItemFilter` hands three husks a sword each. A player's pickup is raised around `Inventory.add` in `ItemEntity.playerTouch` and a mob's around `Mob.pickUpItem` in `Mob.aiStep`, so the after event names what was taken. It counts
  each first spawn in the player's `tp:visits` dynamic property, which `WorldClientGameTest` reads
  back as two after leaving the world and opening it again. Fabric raises the join before the level
  or the player list holds the player, so `ServerHooks.playerJoined` hands the player to
  `ScriptBridge.remember` first, or every read and write a pack makes in `playerSpawn` is lost.
  `tp:override` overrides the nearest wisp's `tp:settled` to false for the player who sent it, which
  a wisp settled a second after arriving is not, and tags that player when an override of a property
  the wisp never declared is refused; `tp:override_clear` clears the override again. `tp:config` tags the player with two variables and a
  secret `config/` hands the pack, which `ScriptGameTests` writes, reads again with
  `bedrock reloadconfig` from the console and asserts on, and `tp:operator` makes the player an
  operator and unmakes it twice each. `tp:board` asks an empty Java boat for its
  `minecraft:rideable` and seats a sprite through it, the way a pack dressing the game's villagers
  moves its own model into the boat a villager boarded; `BoatSeatClientGameTest` reads the sprite
  back in the boat's seat on the client. Bedrock's boat, minecart, ravager, spider, cave spider,
  skeleton horse and camel husk carry that component outside every group, so the script finds it on
  the Java mob with nobody aboard (`ScriptBridge.ALWAYS_RIDEABLE`); any other vanilla mob has it only
  while something rides it.

  `cameras/presets/` holds the presets. `tp:overlook` is a free camera at 0.5 120 0.5 turned to a
  pitch of 20 and a yaw of 45 that hears from the player, and also asks for `player_effects` and
  `extend_player_rendering`; `tp:overlook_east` inherits from it and states only its x and its yaw,
  so a chain walked wrongly leaves the camera at another height, pitch or listener.
  `CameraPresetClientGameTest` reads those back off the client's camera and its sound engine's
  listener, through `/camera` and through three script events (`tp:camera`, `tp:camera_default` and
  `tp:camera_clear`) calling `Camera.setCamera`, `Camera.setDefaultCamera` and `Camera.clear`, and
  `AddonGameTests.aCameraPresetsDroppedFieldsAreNamed` asks for the two dropped fields among what
  `/mcaddon entities` names and for none of the read ones. A function translates a preset or a
  spline in a pack's own namespace unread, because the functions are converted before the cameras
  are read; the command looks the name up as it runs.

  `aim_assist/` holds the preset `tp:aim` and the categories `tp:cow_first` and `tp:pig_first`. An
  empty hand takes `tp:cow_first`, which gives a cow ten times the priority of anything else, and
  the preset leaves sheep out. `cameras/presets/aimed.json` is `tp:aimed`, a third person camera whose
  `aim_assist` names `tp:aim`. `AimAssistClientGameTest` floats a pig 20 degrees to one side of where
  the player faces, a cow 40 degrees to the other and a sheep straight ahead out of reach, and reads
  `PlayerAimAssist.target()` and the client's own `crosshairPickEntity` back under the default
  preset, `tp:aim`, `clear`, first person and `tp:aimed`.

  The rest of the presets and `camera/splines/sweep.json` are there for the cameras that move on
  their own, and five client game tests read each one back off the client's camera, all of them on
  `Stage` with the player at 0.5 120 0.5 looking south:

  - `OrbitCameraClientGameTest`: `minecraft:follow_orbit`, `rot` turning the player, `view_offset`
    and `entity_offset`, `minecraft:control_scheme_camera`, and `tp:orbit` (a radius of six and both
    offsets), set by `/camera` and by `tp:camera_orbit`'s `Camera.setCamera` with a view offset of its
    own. An orbit circles the middle of the box, so the expected place is worked out from the
    client's own player.
  - `BoomCameraClientGameTest`: `tp:boom` (a radius of four and a starting turn of 45 and 30), a
    fixed boom that keeps the player's turn as it was set, `rot` over the preset's turn,
    `tp:boom_aligned` turning the player to the camera's yaw, and `tp:camera_boom`'s two offsets.
  - `AttachedCameraClientGameTest`: `attach_to_entity` on an armour stand, an orbit circling it,
    `detach_from_entity`, `tp:camera_attach`'s `Camera.attachToEntity` at the stand's feet, and a
    player refused.
  - `TargetCameraClientGameTest`: `target_entity` with and without `target_center_offset`,
    `remove_target`, a first person camera refused, `tp:watch` held to ten degrees round and ten
    above the horizon, `tp:lookout` following nothing past five blocks, `tp:slow` seen partway round
    at thirty degrees a second, `tp:snap` on the target at once, and `tp:camera_target`'s
    `targetEntity`.
  - `SplineCameraClientGameTest`: `play_spline tp:sweep` seen on its first leg and held at its end,
    `tp:camera_spline`'s Catmull-Rom curve through four points, and a missing spline and a third
    person camera refused.
**Fog media are drawn from the fog file on the client.** Java has no environment attribute for lava
or powder snow fog, so `FogRendererMixin` (26 and later) asks `PlayerFog.paint` after `setupFog`.
`paint` takes the pushed `/fog`, else the camera biome's fog (`AddonFogs.forBiome`, built from each
client biome's `fog_identifier`), and applies `lava` (`lava_resistance` while the camera entity has
fire resistance, `lava` when that block is absent), `powder_snow`, or, in open air under
precipitation with sky above the camera, `weather` mixed over the fog the game or a pushed air fog
drew by `ClientLevel.getRainLevel`. Mojang publishes no blend rule for weather, so the rain level is
the weight. `FogClientGameTest.media` reads each back off the frame's `FogData`. Biome `air` and
`water` still travel as environment attributes at conversion.

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

  Started from the server console, `bedrock gametest run probe:floor_is_stone` has to answer
  `passed` and `bedrock gametest run probe:diamond_is_missing` has to answer with the message the
  assertion raised. A
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
- **`tools/showcase/build.py`** builds the Bedbug Showcase, the add-on the store page's pictures are
  taken of. It is written to look good rather than to cover a reader: two mobs with their own
  geometry, animations and render controllers, a quilt block, a toadstool drawn from its own
  geometry, four items, two particles, a biome with a fog and a colour grade, a free camera preset,
  a script whose `/scriptevent bb:guide` opens an action form and whose `bb:swarm` reads the world's
  pack settings, and settings in both manifests. `icons.py` and `skins.py` paint every texture.
  Nothing checks it, so it is not a place for a test to assert against.

  ```bash
  python3 tools/showcase/build.py --version 26.2
  ```

  The lumen moth states `has_gravity` false, which this mod leaves alone, so a moth summoned in the
  air falls unless the summon adds `{NoGravity:1b}`.
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
  python3 tools/verify/verify.py all                     # conversion, server, client and join per version
  python3 tools/verify/verify.py run --version 26.2      # a server, one version
  python3 tools/verify/verify.py client --version 1.21.1 # a client, one version
  python3 tools/verify/verify.py join --version 26.2     # a client connected to a server
  python3 tools/verify/verify.py join --bare --version 26.2      # a second client with no add-ons takes the server's
  python3 tools/verify/verify.py join --other --reshaped --version 26.2   # other versions, and a refused one
  python3 tools/verify/verify.py join --slow --version 1.20.1    # a login longer than every timer on it
  python3 tools/verify/verify.py lan --version 26.2      # a world opened to LAN, a second client joins it
  python3 tools/verify/verify.py lan --dormant --version 26.2   # the host also holds a dormant add-on
  python3 tools/verify/verify.py convert --source <extracted pack directory>   # no game
  python3 tools/verify/verify.py companions --version 26.2  # a server and a client beside other mods
  python3 tools/verify/verify.py companions --bare --version 26.2  # the same, as a client taking the server's add-ons
  ```

  A green `run` proves the mod started, every mixin applied, the packs converted and registered, the
  server reached `Done (`, and no pack script hit a missing `@minecraft/server` member while the
  pack's own entities were summoned and killed. It also types `bedrock gametest runall`, which is why the
  probe pack ships one test written to pass and one written to fail: a run has to report both, so a
  run where every test silently never started fails the same way as one where a test broke. It
  proves nothing about rendering.

  **A dynamic registry entry is safe from any pack that names itself.** What decides it is the pack
  the entry was read from rather than the registry it went into.
  `ResourceManagerRegistryLoadTask.REGISTRATION_INFO_CACHE`, `RegistryDataLoader` before 26, reads
  the pack's `KnownPack`: `optional.map(KnownPack::isVanilla).map(ignored -> stable())
  .orElse(experimental())`, so any `KnownPack` at all gives `Lifecycle.stable()` and the namespace
  does not have to be `minecraft`. With none, the entry is experimental, the lifecycle
  `LevelStorageSource.getLevelDataAndDimensions` folds into the level data is experimental with it,
  and a client puts a screen in front of the world asking whether to load it at all. The game works
  that out again on every open and keeps no record of having been answered, so it is a question the
  player gets every time rather than one they can settle once.

  **Read it off the log line, not off the world failing to open.** `PrimaryLevelDataMixin` opens such
  a world anyway in a development environment and says so at `ERROR`, naming a pack with no
  `KnownPack`. So the check in both directions is that line: a run that should be stable has to not
  carry it, and a run that should be experimental has to carry it. A missing
  `Starting integrated minecraft server` line proves only that the world did not open, which a hang
  or a crash gives just as readily.

  Three kinds of pack, measured on 26.2 with a client and the mod's own line read back:

  - **The mod's own `src/main/resources/data/mcaddon/`** is safe. Fabric gives every mod pack a
    `KnownPack`, so a `jukebox_song`, a `test_instance` and a `test_environment` shipped there all
    leave the world stable.
  - **A converted add-on pack** is safe too, because `Platform.directoryPack` gives it a name of its
    own: `KnownPack("mcaddon", <the manifest header uuid>, <the pack version> + "+" + <the first
    eight hex digits of the archive's sha256> + "+" + McAddon.VERSION)`, the digest left out for a
    pack read from a directory. The uuid is what two installations of one add-on share; the version
    separates two releases, the digest two copies of one release with other bytes (the game refuses
    a join outright when two offered packs share one `KnownPack`), and the mod's version is there
    because the bytes in a converted pack are a function of the converter as much as of the pack.
    Built with that `KnownPack` taken out again, one converted `jukebox_song` is enough to make the
    world experimental.
  - **A data pack in the world's own `datapacks/`** is not. One `test_instance` plus one
    `test_environment` written there marks a world exactly as one `jukebox_song` from an unnamed pack
    does, which is what `tools/scene/scene.py` would hit if a scene ever wrote one.

  **A dimension a pack adds becomes a level, and only a real server shows it.**
  `WorldDimensions.bake` unions the keys of the `dimension` registry the data packs loaded with the
  ones the world was saved with, so a converted `dimension` entry is a level the next time that
  world opens, on a dedicated server and in a single player world alike.

  **The same method decides whether that world is experimental, and the test changed at 26.** From
  26 it is `keys.containsAll(BUILTIN_ORDER)`, so a world holding the three dimensions the game ships
  stays stable however many a pack adds. Before that it is `list.size() == VANILLA_DIMENSION_COUNT`,
  so a fourth dimension makes the world experimental whatever pack it came from and whatever
  `KnownPack` that pack carries. `Platform.convertsDimensions` therefore writes a pack's dimensions
  from 26 on and reports them below it, and `verify.py run` asks only the 26 versions for a level.
  A dedicated server is unaffected either way, because it never asks the question, so a red 1.21.1
  client beside a green 1.21.1 server is what this looks like rather than a fault in the pack.

  A game test server proves
  nothing about this: `GameTestServer` bakes its own dimensions against
  `new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze()`, an empty registry, so
  every add-on's dimension is dropped there whatever the pack holds. `verify.py run` types
  `execute in <dimension>`, which the parser refuses outright when there is no such level, and reads
  the line it would have printed back.

  **A dedicated server never asks.** The screen is `WorldOpenFlows`, which is client code. A server
  loaded the same experimental pack, started, ran the console script and stopped with nothing said
  about it, so this is a thing a player meets and a server operator does not.

  **A `dimension` entry naming the same pack's own biome loads on a server and not on a client.**
  Measured on 26.2 with one converted `dimension/overworld.json` whose biome list held the pack's own
  `worldgen/biome` entry beside the game's. The dedicated server read it, the biome generated and its
  ore placed; a client opening a single player world logged `Failed to parse minecraft:overworld from
  pack mcaddon/tp` and stopped on the screen saying the selected data packs prevented the world from
  loading. The same pack, the same file, the same version. `dimension` and `dimension_type` are
  loaded as a set of their own after the worldgen registries rather than with them, and the two
  world loads drive that second load from different places; which of the two answers a reference to a
  pack's own entry differently, and why, is not established here. What follows for a pack either way
  is that a `dimension` entry must not name one.

  **A world that was once opened experimental stays experimental after the cause is gone.** Measured
  while chasing the above: with the offending entry removed from the converted output and the jar
  rebuilt, `verify.py client --version 1.21.1` still reported the world as experimental, and the same
  check passed the moment `versions/1.21.1/run/saves` and `versions/1.21.1/run/world` were deleted and
  the world made again. So a fix is not disproved by a run that still fails; delete both directories
  first and only then believe the verdict. The same holds for the join check, whose client takes its
  world from the server's.

  **An either codec reports the last branch's error and swallows the first.** That is what made the
  above read as the wrong thing entirely. `MultiNoiseBiomeSource`'s codec is
  `Codec.mapEither(<the explicit list>, <the preset name>)`, so a list that failed to decode is
  reported as `No key preset in MapLike[{"type":"minecraft:multi_noise","biomes":[...]}]`, with the
  whole list printed after it. The list in that message is the input the *first* branch was given,
  not something the second branch objected to, so the message reads as the explicit form being
  refused outright when the explicit form is accepted on all four versions. Decode the suspect JSON
  against the inner codec by hand before believing the message: `Climate.ParameterPoint.CODEC` and
  `BiomeSource.CODEC` under a `RegistryOps` over `VanillaRegistries.createLookup()` both parse in a
  unit test, and that is how the failure was narrowed to the one entry whose reference went
  unresolved.

  **What a name costs a server that has one.** `RegistrySynchronization.packRegistry` leaves an
  entry's content out of what it sends when the client acknowledged that entry's `KnownPack`, and
  sends the whole of it encoded otherwise; the entry's id goes over either way. A client acknowledges
  only what its own repository holds, and `PackRepositoryMixin` puts this mod's converted packs in
  that repository, so a client with the same add-on installed reads the entry out of its own copy and
  a client without one is sent everything. Getting the version string wrong therefore costs a bigger
  packet rather than a client short of content.

  Loot tables, recipes, advancements, tags and functions are safe from any of these, because none of
  them is a registry the world's lifecycle is read from.

  The built-in registries are a different thing again, and this mod does write into them at startup,
  which is where its entity types, items, blocks and its own loot condition type go. Fabric keeps
  those unfrozen until mod initialisation has run, and nothing about them makes a world experimental.

  A green `client` proves what the server half cannot reach: every mixin applied on the drawing side,
  every model a pack's items and blocks name loaded, every texture those models name was stitched,
  and the mod reported a client definition for more than zero add-on entities. It takes a world from
  the dedicated server's own when the saves directory is empty, so a fresh checkout needs nobody to
  make one by hand, and then waits for that world's own server to start rather than for a fixed time.
  Waiting for the world rather than for the drawing is what tells a client that loaded from one
  stopped on a screen asking the player a question: everything about models and textures has already
  happened by the time such a screen is up. `1.21.1` also loads Mod Menu, Sodium and Iris, so it takes
  noticeably longer than the other two.

  **A checkout no client has ever opened a world in fails this check whatever the mod does.** The
  world is copied fresh from the dedicated server's own on every run, a client half of it has never
  been through, and the client then stops before starting a server for it. `run`, `convert` and
  `./gradlew build` are unaffected, and the same commit passes the client check in a checkout that
  has one. A worktree is always such a checkout on its first run, which has cost three separate
  passes time spent doubting a change that was fine. Before reading a red client as a fault of the
  change under test, run it in the main checkout.

  Failing in either: `ERROR`, `Caused by`, a crash report, a mixin apply failure, `Registry loading
  errors:` and `Failed to parse <id> from pack`, which are named apart from the rest because a
  registry entry a data pack could not decode stops a client on a screen asking the player to fix
  their packs and a run with nobody at the keyboard would otherwise wait that screen out, `Script error:`,
  `Failed to load function`, `Multi threaded access requested by thread`, `Could not initialize
  class`, `StackOverflowError`, `Unable to load model`, `Missing textures in model`, a Java compile
  error and a Gradle build failure. The last two model checks are logged at `WARN`, which is why they
  went unread for so long. Filtered as normal in a dev environment: `No data fixer registered for`,
  the Realms authorisation failure, `Failed to retrieve profile key pair`, and Sodium force-disabling
  the mixins Iris replaces.

  A green `join` proves what neither half alone can: a client entered a *dedicated server's* world.
  It starts the server, waits for `Done (`, launches a client with `--quickPlayMultiplayer
  127.0.0.1:<port>`, and then reads three things back, none of which a client that merely launched
  can produce:

  - The server wrote `logged in with entity id` and `<name> joined the game`, which it writes only
    after a client finished logging in and spawned into the level.
  - `list` typed into the server console answered with somebody on it, so the client was still
    standing there and not already gone.
  - A message typed into the server console came back out of the *client's* log. Only the server can
    send it and only a client in that server's world prints it, so this is the whole round trip. The
    text is unique per run, which is what stops a line left in an older log passing for this one.

  It also types `give @a <an item the packs declare>` and fails when the server refuses it, and it
  fails when the two halves report different numbers of entity types, items or blocks, because the
  registry sync insists those agree and a bare disconnect reason does not say which registry broke.

  `-PjoinServer=<host>:<port>` in `gradle/mod.gradle.kts` is what passes the address, next to the
  `-PquickPlay=<world>` the single player half uses. The server is given `online-mode=false` and
  `enforce-secure-profile=false`, because a dev client has a made-up name and no session and is
  refused before any of this mod's code is reached otherwise.

  **The second client and the sync modes.** `./gradlew :<version>:runJoinClient` is a second dev
  client with run directory `versions/<version>/run/join-client/`, its own `addons/` and
  `.mcaddon/`, and the JVM property `-Dmcaddon.role=guest`, which is what `running` and `kill` tell
  it from the first client by. `-PjoinServer` and `-PquickPlay` reach both clients. `-Pserved=<dir>`
  gives a dedicated server the `mcaddon.served` property, so it also hands out every archive in
  that directory. The harness only waits on, and ends, clients that belong to its own checkout, so
  other worktrees' game tests do not block it; servers are counted across all checkouts because
  they share the ports.

  - `join` is the original check, the first client sharing the server's add-ons.
  - `join --bare` empties the second client's directory and expects `Downloading N add-ons`,
    `Received N add-ons from the server.` and `Activated the server's add-on set of N add-ons.`,
    the item and chat round trip, N archives under `join-client/.mcaddon/received`, and, after the
    harness stops the server under the client, `Restored the installed add-on set.`.
  - `join --other` gives the second client the server's archives with the manifest version raised
    (a same-shape copy that gets replaced) plus the `sv` add-on the server lacks (which stays
    installed and goes dormant). `join --reshaped` gives it a copy whose `tp:lamp` has one more
    `tp:level` value, and expects `The server's copy of <add-on> cannot replace yours without a
    restart.` and no world.
  - `join --slow` is `--bare` with the `-PslowJoin=<seconds>` property, which the join client
    reads as `mcaddon.slowJoin` in a development run and waits for between holding every archive
    and activating them. It waits 75 seconds, past the 600-tick login timer and the 30 second read
    timeout before 1.21 and past the server's minute without a message, so the client reaches the
    world only while the login heartbeat, `ServerLoginTimerMixin` and `Outgoing`'s waiting on an
    activating client all work. It reads the login's length off the two logs' timestamps, from the
    client's `Connecting to` to the server's login line, and fails on `Took too long to log in`,
    `Timed out`, `ReadTimeoutException` and `stopped making progress`. Measured on 1.20.1 with each
    part taken out, it fails each time. With no heartbeat the server ends the login with `Timed out`
    30 seconds after the last piece. With no mixin it ends it with `Took too long to log in` 31
    seconds after the client connected. With the minute counted from the last acknowledgement
    whatever the client was doing, it ends it with `stopped making progress` 60 seconds after the
    download. With all three in place the login took 80 seconds.
  - `lan` runs the first client with a saved world as host. A development-only handler,
    `client/LanRequests`, answers a `lan.request` file in the host's run directory: each line is a
    console command for the integrated server, and `publish` opens the world to the LAN through
    `Platform.publishServer`, which also turns authentication off because a dev guest has no
    session, then writes the port to `lan.port`; `open <world>` opens a saved world while none is
    open. The host is made creative and the world peaceful before anybody joins, because a zombie
    kills a survival host at night and a dead player holds no chunks. The guest is the second
    client. The host's log must hold no `Received`, `Downloading`, `Activated` or `Restored` line
    while it hosts. `--dormant` has the host client join a dedicated server serving an add-on the
    host has not installed, leave, and only then open its world with `open`, all in one process,
    because a dormant add-on is one the process registered: a host started afresh holds none. The
    guest is then offered it as an inactive entry. `all` runs both. Before 26 `LanRequests` waits
    for the resource reload's overlay to go before it acts: the leave's reload is still showing it
    when the harness asks for `open`, and a world opened under it waits for it inside
    `Minecraft.doWorldLoad`, which it never leaves.
  - A fresh game directory has no `options.txt`, and the game then opens its accessibility
    onboarding screen instead of the quick play it was told to run, so a client in a new worktree
    sat on the title screen until the deadline. `play` writes `onboardAccessibility:false` first.

  A green `convert` proves the output under `versions/<version>/run/.mcaddon/converted/` holds one
  texture per texture the pack ships, one sound event per definition, and an `_n` and `_s` sidecar
  per texture set that asks for one. A stale cache shows up here as a count that moved.

  It reads what a game wrote rather than converting anything itself, which is why `all` runs it last.
  A pack is converted again as soon as anything in it changes, so `convert` on its own after an edit
  reports on the conversion before that edit until a game has opened the pack.

  With no `.mcaddon` in `versions/<version>/run/addons/`, every mode reports that there was nothing
  to test and still passes.

  A green `companions` proves this mod behaves beside the mods a player is likely to have. It runs
  the same server and the same client, and reads three more things out of each log:

  - Every mod the version's `gradle.properties` pins for this side really loaded. A mod whose own
    manifest says it draws is left out of a server run by Fabric with nothing written about it, so
    `COMPANION_IDS` in `verify.py` states which side each one belongs to and a run asks only for
    the mods that side was ever going to load.
  - No injector lost its target. Mixin logs that at `WARN`, so the error check never sees it, and an
    injector that found nothing is a feature switched off in silence. One naming this mod's own
    configuration fails the run. One naming a neighbour fails it too, unless the whole target class
    is absent, which is what a mod's optional integration with a mod nobody installed looks like.
  - Fabric resolved the set at all. `Mod resolution failed` means the run proved nothing about
    anybody.

  `companions --bare` runs `join --bare` instead of the server and the client, and reads the same
  three things out of the join server's and the second client's logs. That client registers the
  server's blocks, items and entity types after the freeze while every companion is loaded, so a
  companion holding an array sized at startup from the block state list fails it on the first frame
  after the join.

  The mods themselves are `companionMods()` in `build.remap.gradle.kts` and `build.named.gradle.kts`,
  each pinned by exact version in `versions/<version>/gradle.properties` and installed only for a dev
  run. They are chosen for overlap with what this mod touches: Sodium, Iris and Indium replace the
  renderers, Lithium rewrites the entity, explosion, piston and chest paths this mod injects into,
  Ferrite Core repacks the block state storage, Entity Texture Features and Entity Model Features
  both extend the living entity renderer this mod adds a layer to, C2ME moves chunk loading onto
  other threads, and Lithostitched and Terralith write dynamic registry entries from packs of their
  own. A version with no build of one leaves the property empty.

  **The two remapped targets carry fewer of them.** Loom remaps a mod for 1.20.1 and 1.21.1 and
  drops the libraries that mod ships inside itself, so C2ME, Lithostitched and Terralith do not
  start there and their properties are empty. 26.1.2, 26.2 and 26.3 need no remapping and carry the
  whole set, so run `companions` against 26.2 to exercise all of them.

  **What the companions cost.** A 26.2 dev client loads 95 mods with the whole set and a 1.21.1 one
  loads 63 with the ones that work there, against the handful Fabric API alone brings. The
  extra mods are downloaded once and cached. Terralith replaces overworld generation, so the first
  world a version makes takes longer than a vanilla one and every later run opens the world it left.
  Nothing about the jar changes: none of them is compiled against.

  **`PrimaryLevelDataMixin` masks this.** It reports an experimental world and opens it anyway in a
  development environment, so a companion writing a dynamic registry entry from a pack with no
  `KnownPack` shows up as its `ERROR` line rather than as a screen. Read the line; do not read a
  world that opened as proof the entry was stable.

  **A world the dedicated server made is one a client stops in front of.** `check_client` copies one
  out of `versions/<version>/run/world` when `saves/` is empty, and that `level.dat` carries
  `WasModded`, which is a second question `PrimaryLevelDataMixin` does not answer. Such a run reports
  `the world never opened` whatever else is installed: a 1.21.1 client with every companion property
  blanked, 57 mods in the game, stops at the same point as one with all of them. Read that line as
  the world rather than as the mods.

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
  because only one window has it and a paused game draws its menu instead of the world. A picture is
  asked for by writing `screenshot.request` into the version's run directory, which a development
  client watches for: it hides the heads-up display, saves a screenshot and deletes the file. No
  window has to be brought to the front and no key sent, so the versions are photographed at once.

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

  The `held` scene stands one copy of every entity the packs declare, puts the first item in each
  one's main hand and puts the first item any attachable is described for on each one's head. Only
  a model carrying a `rightItem` bone draws the first and only one whose definition enables
  attachables draws the second, so the row is what says which entities do. The items an attachable
  belongs to are read out of the archive's own `attachables/` rather than named here, for the same
  reason the entity and item lists are.

  The `fog` scene paints the stage with the first biome a pack declares and floods half of it, so one
  frame holds the fog colour a pack's `fogs` file puts over the distance and the water colour its
  biome tints the pool with. Every scene starts by putting the stage's biome back, because
  `fillbiome` is what the world keeps rather than what a scene builds. 1.20.1 registers no biome from
  a pack, so the scene fills the pool there and names no biome: a command naming one would not parse
  and would take the whole function with it, and the picture that comes back is what the game paints
  when a pack's colours have nowhere to go.

  The `dialogue` scene opens an NPC conversation on a pack's own entity, which is the one place a
  scene photographs a screen rather than a world. A single player game pauses behind a screen and a
  scheduled function in a paused game never runs, so the scene asks everything it has to ask first
  and opens the screen in its last line.

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
- **`tools/census/fields.py`** counts the fields inside each entity component rather than the
  components themselves, which is the gap every other measure here leaves. A component whose name is
  handled and whose body is never opened reads as implemented and does nothing; so does one whose
  field is read in the wrong unit.

  ```bash
  python3 tools/census/fields.py                        # the table and the gated gaps
  python3 tools/census/fields.py --strict               # non-zero while a gated field has no reader
  python3 tools/census/fields.py --min-declarations 1   # and the whole tail below the gate
  python3 tools/census/fields.py --component minecraft:teleport
  ```

  The published side is the field list in Mojang's own JSON schemas under
  `metadata/json_schemas/server/entity` in `scratchpad/bedrock/bedrock-samples`, together with how
  many of Mojang's own entities declare each field, which is what says whether a gap matters: a
  field a hundred entities declare is worth more than ten with one each. The mod's side is the field
  names that reach a `has`, a `get` or one of the JSON helpers inside the reader that names the
  component, following the helpers that reader calls and the type its body is handed to. That side
  is an upper bound, so a field the census calls unread is one no reader mentions anywhere.

  `--strict` fails on a field eight or more of Mojang's entities declare that has neither a reader
  nor a reason in the `REASONS` table at the top of the script. A reason states what Java holds
  instead, so "Java's look goal picks how long it looks" closes `look_time` and nothing else. The
  tail below the gate is counted in the same line and listed by `--min-declarations 1`, because a
  field one entity declares is a reading list rather than a regression.

  Three defects this found, none of which any count of component names could see: `minecraft:health`
  read `value` as the ceiling so a mob could never be healed past what it spawned with;
  `minecraft:angry` was read as a flag, so an entity was angry for life and its `calm_event` never
  ran; and the goals ran in the order their components happened to be written in, because nothing
  read the `priority` all sixty-eight `behavior.look_at_player` declarations carry.

  **A reason is a claim about Java, and it is checked against Java.** Read the class it names in the
  game jar before believing it: of the reasons this table once carried, most named something Java
  does let an entity set, a protected method, an overridable getter or a price on the mob. Checking
  them turned up defects a reason had been covering: `breed_cooldown` was read as how long the mood
  lasts; `avoid_water` was overwritten by the price `minecraft:preferred_path` sets and
  `can_pass_doors` was read as `can_open_doors`; `base_buoyancy` was read under a name no schema has;
  `can_be_stolen` was read as whether the entity takes a lead at all; an empty `minecraft:home` held
  the entity to sixteen blocks where Bedrock restricts nothing; `nearest_attackable_target` fell back
  to hunting players for any family no Java class stands for; and the anger a reason said already
  spread spread nowhere. A default a pack leaves out is the same kind of claim: Bedrock turns at
  thirty degrees a tick, breeds only what is tamed and avoids no damaging block unless told to.

  **The census sees a method only by its modifier.** A reader written as a package-private method
  with no `public`, `private`, `static` or `final` in front of it is not found, so the fields it reads
  are credited to nothing and a field read there can fail `--strict`. Give such a method a modifier.

  With no `scratchpad/bedrock/bedrock-samples` checkout it reports that there is nothing to measure
  and passes, the way the rest of the harness does with no add-on present.
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
  python3 tools/census/components.py --strict   # exits non-zero while a gap has no decision
  ```

  The published side comes from `metadata/` in that checkout: the entity component schemas and the
  entity docs, the engine's Molang query and function lists, the block component schema together
  with the block docs, and the item component schemas. The block docs are read beside the schema
  because Mojang leaves `minecraft:custom_components` out of the schema and names it only in the
  documentation. With `metadata/` absent it falls back to the component names the vanilla behaviour pack's
  own entities use, which is a lower bound rather than the vocabulary. `scratchpad/` is gitignored,
  so the checkout is never committed.

  **Three things Mojang's own material hides, each closed by how it is read.** Half of the vanilla
  entity and item files carry a `//` comment or a comma before a closing brace, which `json` refuses,
  so they are read through `lenient`, the same tolerant reader `data.py` uses. `Entity component
  definitions.json` is the master property list and leaves three of Mojang's own component schemas
  out of it, so a `minecraft_*.json` schema file no other schema points at with `$ref` counts as a
  component in its own right; one another schema does point at is a piece of some component's body
  and does not. And Mojang publishes every `query.` name and no list at all of the `context.` ones,
  so `AddonQueries.CONTEXT` declares which of that switch's own case labels belong to the second
  namespace and the query surface leaves them out.

  The mod's side is read out of the source: the `IMPLEMENTED` declarations in `AddonEntities`,
  `AddonItems` and `AddonBlocks`, and the `case "..."` labels in `AddonEntity.addGoals`,
  `EntityEvents.applyComponent`, `Filter.evaluate`, `EntityQueries.query` and `MolangMath.call`. A
  name added to one of those switches counts the moment it is added.

  The run ends with the lists worth reading. **Names in the source that the published vocabulary
  does not contain are stale or misspelt**, and that list means nothing else, so it is empty while
  the source is right. A spelling read on purpose because packs older than the current schemas ship
  it goes in a `LEGACY` declaration beside `IMPLEMENTED`, which the census subtracts and then lists
  under its own heading, so a tolerance stays visible without standing in the alarm. Such a
  declaration may only carry a name `IMPLEMENTED` also carries and no published schema has; one that
  is published today or read by nothing is named in a list of its own. Names an `IMPLEMENTED`
  declaration carries with no reader of their own are either a component Java does for every mob
  anyway, which is why the declaration names it, or a coverage claim nothing backs; each
  declaration's docblock says which it allows.

  **A gap with a decision is listed apart from one without.** `AddonItems.BEDROCK_ONLY` names the
  item components Java has nothing to answer with, each with its reason in the docblock. They still
  count against the percentage and are still reported as a pack declares them, and `--strict` fails
  only on a missing name no such declaration carries, or on a declared name that has since been
  implemented or stopped being published.
- **`tools/census/model.py`** does the same for the drawing side: geometry, animations, animation
  controllers, render controllers and client entity definitions. Mojang publishes a JSON schema per
  behaviour pack component and none for any of these, so this measures against the files instead.
  The resource pack in `bedrock-samples` is Mojang's own and carries every shape the engine accepts,
  for every vanilla entity.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/model.py            # the table, then what is dropped and what is stale
  python3 tools/census/model.py --strict   # exits non-zero while a dropped field goes unreported
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
  read and what is not. A dropped field a reader names in one of its `UNREAD` constants is reported as
  a pack declares it and is marked `(reported)`; `--strict` fails on the rest. A field read that the published files never declare is a reader being more
  tolerant than vanilla, which is fine, or a misspelling, which is not.

### What the drawing readers do not read

Every standing drop is reported through `AddonEntities.unsupported` at the reader that drops it, so
what the census calls missing is what `/mcaddon entities` names. The field names sit in a constant
beside the reader rather than inside it, because the census reads a reader's own string literals to
decide what it acts on and a reported field is still a dropped one.

- **`rebuild_animation_matrices`** is declared by nine render controllers and Mojang documents none
  of them. Every one is a second pass over a *different* geometry than the first, the cape over the
  player, the clothes over a stray, the robes over a villager, which says it asks for the bone
  matrices to be worked out again for that geometry rather than taken from the previous pass's
  cache. `BedrockModelRenderer` poses every pass from `animator.poseOf` as it walks it, so the pass
  it names is already built that way and the flag asks for what happens anyway.
- **`filter_lighting`** is declared by ten render controllers and Mojang documents it nowhere, in
  the schemas, the doc modules or `Entities.html`. Every one of the ten is a flat or thrown thing:
  an arrow, an egg, a fireball, both potions, a leash knot, an item sprite, an experience bottle and
  a fireworks rocket. What it does to a pass is not established from the published material, so
  nothing is done with it rather than something guessed at.
- **Part of `item_display_transforms`.** A block geometry's transforms become its Java model's
  `display`, Bedrock's `shelf` becoming Java's `on_shelf` from 26. The `embedded` context, the two
  pivots and `fit_to_frame` are reported in `BlockGeometry.display`, because Java has no context for
  an embedded visual, turns and scales a model about its middle, and never fits an item to its frame.
- **`parent_setup`** is reported for a wearer Java draws with a model of its own, which has no
  variables to write into. **`binding`** on such a wearer finds the part `JavaModelParts` answers
  to the bone, with `rightItem` and `leftItem` seven pixels down each arm as Mojang's humanoid
  geometry puts them, and is reported only for a bone with no part.

**A bound model is drawn in the bone's own frame, its pivots left where the pack wrote them.**
Bedrock makes the bound bone's pivot the model's origin less 24 pixels (Mojang's spyglass is
moved to 27 pixels up to scope at the eye, and wiki.bedrock.dev's attachable guide counters the
same 24), and Java's model origin is already 24 pixels up. `AttachableLayer` is added as
`LivingEntityRenderer`'s constructor returns, before any subclass adds its layers, so it runs
first and clears the head item, armour and hand items of each slot it draws from the render state
the later layers read.

Seven fields are read outside the renderer itself, which is where to look for them:

- **`visible_bounds_width`, `visible_bounds_height` and `visible_bounds_offset`** are the box Bedrock
  culls a model against. `EntityRenderer.shouldRender` carries the same spelling on all four
  versions, so `AddonEntityRenderer` asks the frustum about the geometry's own extent as well as
  about the box the server gave the entity, and a model wider or taller than its hitbox stays drawn
  while part of it is on the screen.
- **`min_engine_version`** on a client entity says which of several definitions of one identifier is
  parsed, which `BedrockAssets.readDescribed` decides against the engine version the pack's own
  manifest names. A pack every one of whose definitions is too new still gets the first, because an
  entity nothing draws is worse than one drawn from a definition written later.
- **`enable_attachables` and `hide_armor`** gate `Attachables.on`. An entity a pack described wears
  what it has equipped only where its own definition says it may; one Java draws with a model of its
  own carries no such definition and always wears it.
- **`held_item_scale`, `held_item_ignores_lighting` and `hide_held_items`** size, light and hide the
  item an entity holds that no attachable draws for it, in `HeldItems.on`. The bone it hangs off is
  `rightitem` or `leftitem`, which is what `query.item_slot_to_bone_name` answers and what forty of
  Mojang's own entity geometries carry; `BedrockModelRenderer.anchorOf` finds it in the posed model,
  matching the name without regard to case because Bedrock does. A model carrying neither bone draws
  no item, which is Bedrock's answer too.
- **`light_color_multiplier`** multiplies the light reaching a pass, in `AddonModels.Pass.light`.
  Java carries the two light channels as levels packed in an integer, so the multiplier moves both
  and stops at the brightest level there is.
- **`should_update_effects_offscreen` and `should_update_bones_and_effects_offscreen`** keep an
  entity's animation script running while nobody can see it, which `Animators.tickOffscreen` does off
  the client tick. One pass advances the clips, fires their effects and poses the bones, so an entity
  asking only for its effects has its bones posed as well.

`texture_meshes` is baked beside a bone's cubes, in `GeometryBaker.bakeTextureMesh`: a voxel per
texel the texture draws, both broad faces of each and a side wherever the texel beside it is clear,
every face sampling its own texel, which is `ItemModelGenerator`'s shape cut per texel so a material
without an alpha test still draws only the texels. Mojang documents the fields and not the frame, so
the frame is Blockbench's, the one tool that imports, previews and exports a texture mesh. The
texture lies flat with its columns along X, its rows along Z and its layer running down, against
Bedrock's Y; `scale` stretches it and `local_pivot` is then taken off in those units; `rotation`
turns it the way a cube with the same rotation turns; and `position` puts the pivot at X and Z
measured in the model and at Y measured down from the bone's pivot. Mojang's own declarations agree
with the axes as far as they can be checked without a Bedrock client: the crossbow's turn lays its
diagonal sprite along its holder's arm, pointing the way the arm reaches, and the bow's lays its
arrow along an axis of the hand bone, though that axis is the hand's Z, which faces down rather than
forward once the arm is raised to draw. A texel is the geometry's `texture_width` over the image's
width across, so a geometry declaring its texture's own size draws a texel a model unit wide, and
`use_pixel_depth` set false makes the layer as deep as a texel is wide rather than one unit. The mesh
names its texture by the client definition's `textures` key, not by a path, so `AddonModels` bakes a
geometry once per definition and reads the mask out of the converted image through
`ConvertedPack.textureFile`. A block geometry carrying one is reported, since a block model is built
from elements.

**A controller's `color` is read only under a colour mask.** Bedrock's entity shaders (the
RenderDragon `Actor`, `ActorMultiTexture` and `ActorTint` materials, reconstructed in
veka0/mcbe-shader-codebase) multiply a texture by `ChangeColor` only where the material compiles
with `CHANGE_COLOR` on, which is what `USE_COLOR_MASK` in a `.material` selects: the texel is mixed
toward itself times the colour by its own alpha, and its alpha is multiplied by the colour's.
`OverlayColor`, which `overlay_color`, `is_hurt_color` and `on_fire_color` feed, is laid over in
every one of them; the legacy `entity.fragment` laid it only under `USE_OVERLAY`, and Microsoft's
material documentation still lists that define, so `MaterialDefinition.laysOverlay` leaves the
overlay off a material removing it, as `entity_beam_additive`, `entity_dissolve_layer1` and
`item_in_hand_multicolor_tint` do. Every vanilla render controller stating a `color` names a material that reads
it: `player_spectator`, `warden_bioluminescent_layer` and `horse_leather_armor`.
`MaterialDefinition.readsControllerColour` names the game's own materials defining
`USE_COLOR_MASK`, read off the game's `entity.material`; `MULTI_COLOR_TINT` (the red channel alone)
and `COLOR_SECOND_TEXTURE` (a third texture layered over the first) are reported rather than drawn.
`scratchpad/bedrock-legacy-materials/` and `scratchpad/mcbe-shader-codebase-veka0/` hold the copies
this was read from.

**A `color_grading/` file grades the frame through a pass of this mod's own, from 26, and only when
the player turns on `ColourGrading.OPTION`.** The option is off by default because Bedrock grades only
under Vibrant Visuals. `OptionsMixin` saves it as `mcaddon.colourGrading` and `VideoSettingsScreenMixin`
lists it beside Paper Doll. With it off `ColourGrading.at` returns no grade, so `GradePass` skips the
copy and the draw.
`GameRendererGradeMixin` runs `client/render/GradePass` after `renderLevel`, which copies the main
target's colour aside and draws one triangle back over it through
`assets/mcaddon/shaders/post/colour_grade.fsh`, a pipeline registered with `RenderPipelines.register`
as the client starts so the shader manager compiles it with the game's own. `Platform.screenPipeline`,
`screenPass` and `drawScreenTriangle` hold what 26.1.2 and 26.2 spell differently. Before 26 the pass
draws nothing and the conversion names every grading file. The arithmetic is Bedrock's
`ColorPostProcessing` fragment shader (veka0/mcbe-shader-codebase) with its tone mapper off and the
average luminance at its fixed 0.18, and `bedrock.grading.GradeUniforms.grade` is the same arithmetic
in Java, which `ColourGradeTest` checks against numbers worked out by hand. Three things the
published material gets wrong or leaves out: the documentation says `color_temperature` warms the
image as the number rises, while the vanilla pack grades its hot biomes at 4000 K and its cold ones at
10000 K under that type, so the files win; no default is published for `shadowsMax` or
`highlightsMin`, so both are the average itself; and the contrast pivot is a shader constant no file
states, taken as the average. `client/render/ColourGrading` decides which collection a biome takes,
with `VANILLA` carrying Bedrock's own client biomes' naming onto Java's biome ids, and blends the
grade around the camera with Java's fog colour weights. `ColourGradingClientGameTest` reads a
wall's pixel out of the main target in plains and again in `tp:glade` and asks that the second is
the first through `tp:glade_grade`. It reads through `Screenshot.takeScreenshot` rather than the
test API's `takeScreenshot`, which draws a frame of its own that Sodium refuses on 26.1.2 (`Global
terrain uniforms have not been updated`). A blend of one grade alone hands back that grade, because
the weighted sum divided back out is not the same floats and the pass would never be skipped.

`blend_transition` is acted on as well: a state declaring one is crossed out of rather than left, its
clips staying at a falling weight while the state it moved to comes up. `blend_via_shortest_path`
rides on that crossing: a bone's rotation is the weighted sum of every clip reaching it, so 350 and
10 degrees at equal weight otherwise leave the bone at 180, and a state declaring the flag has each
later angle turned to within half a circle of the first one before it is weighed. The state being
left decides it for both sides of a crossing, the way its `blend_transition` decides how long the
crossing takes, so the answer does not depend on which clip is summed first.

The expression beside a name in an animate list or a controller state is a blend value, not a
condition: Mojang writes `{"walk": "query.modified_move_speed"}` so a slow mob only half walks. A
value at or below zero leaves the clip off. A clip that does not loop, asked for by either list,
plays once and stays finished while it is asked for, and `q.any_animation_finished` and
`q.all_animations_finished` answer for the clips of the controller's current state, a looping clip
counting once it has come round the first time. An effect a clip fires at a locator is born where
the drawn model carries that locator, through its bone's pose.

**A clip played on a mob Java draws moves Java's model of it.** `/bedrock playanimation` on a vanilla
villager names the bones of Bedrock's own villager geometry, so `JavaModelPoses` runs the clip on an
animator of its own and `JavaModelParts` finds each bone among the model's parts: the same name
compared without case or underscores first, then the short `ALIASES` table for what Bedrock numbers
and Java names (`leg0` to `leg7`, a cat's `backLegL`, the villagers' `helmet` and `brim`), then
`root` for the model's root. The pose goes on after the model's own `setupAnim` and comes off once
that draw is done, because one model draws every entity of its kind. From 1.21.9 the body is posed
again where it is finally drawn, so `ModelFeatureRendererMixin` lays the clip on there and
`LivingEntityRendererMixin` lays it on for the layers; before that `render` does both in one call.
A bone with no part is reported once per kind of entity. Nothing is stored on the server, so a
player who starts watching later sees whatever the pack plays next, as on an add-on entity.
`JavaModelPoseClientGameTest` reads the posed parts back.
Every model a layer submits goes through the same `ModelFeatureRenderer.prepareModel`, a villager's
clothes model without the hat included, so the clip reaches all of them there.
`JavaModelPoseClientGameTest` also reads what the real frame draws: the test source set's
`ModelDrawMixin` hands each model `prepareModel` is about to draw for an entity `Submitted.watch`
names to `Submitted`, and a dressed villager scaled to nothing has to leave nothing but a point
from any of them, and its held emerald a scale of nothing. With Villager News and every companion
mod installed, a copy of a played world showed every vanilla villager in view drawn that way; the
dressed figure Villager News draws in a villager's place is its own entity wearing the villager's
profession textures.
A bone a clip turns `relative_to` the entity has its part's turn kept in the entity's frame once
every other part, and the body carrying it, has moved: `JavaModelParts.keepEntityFrame` takes the
turn of the parts above it back off, the way `BedrockModelRenderer.poseBone` does for a pack's own
model.

**Bedrock's hierarchy is not Java's.** Bedrock's model of most humanoid mobs hangs the head, the
arms and the legs off `body`, where Java's model draws them beside it, so a pack hiding a vanilla
villager by scaling `body` to nothing left its arms and legs standing in Java. For the mobs
`JavaModelPoses.BODY_CARRIES_EVERYTHING` names, read from Mojang's own geometry, `JavaModelParts.carryWith`
moves every part outside the body by what the clips did to the body, about the body's Java pivot.
Mojang's geometry is not committed, so that list is the one place this knowledge lives.
- **`tools/census/commands.py`** does the same for the command dialect. A `.mcfunction` is the one
  part of a pack Java refuses by halves, one bad line costing the whole file, so knowing which
  commands survive translation matters more here than anywhere else.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/commands.py            # prints the table and the gaps
  python3 tools/census/commands.py --strict   # exits non-zero while a gap has no decision
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
  in `BEDROCK_ONLY`, a keyword in neither that nor `BEDROCK_ONLY_KEYWORDS`, or a game rule outside
  `RULES_BEDROCK_ONLY` would be a gap nobody decided on, and that is what `--strict` fails on.
  `RULES_JAVA_FOLDS` names a Bedrock rule whose limit Java keeps under another Bedrock rule's name.

  **What the keyword number counts.** Every published keyword goes into one of three groups, and
  the first two are what the percentage is of:

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
  instead, as rewritten or passed through. `hasitem`, `haspermission` and `has_property` count as
  rewritten: each is answered by a tag `server.SelectorTags` puts on the entities that answer it.
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
- **`tools/census/packfiles.py`** measures the file kinds themselves. Every other census here is
  about the vocabulary inside a file kind the mod already reads, and none of them can see a whole
  directory an add-on ships that nothing opens, because a directory nobody reads asks no questions.

  ```bash
  python3 tools/census/packfiles.py            # the table, then what is reported and what is silent
  python3 tools/census/packfiles.py --strict   # exits non-zero while a pack kind goes unnamed
  ```

  The published side is Mojang's own pack pair, widened by the material in the same checkout naming a
  kind that pair happens not to ship. The mod's side is the `READ` set and the `UNREAD` map in
  `pack/PackFiles.java`. `UNREAD` gives each kind no reader opens the reason Java has no place for
  it, and `PackFiles.report` names the kind and that reason against the pack that shipped it, so a
  kind cannot be declared unread without a reason. `textures/texture_list.json` is read by
  `pack/TextureList.java`, which names every listed path the pack ships no `.png` or `.tga` for; the
  test pack's list names `tp`'s `wand` and a `vanished_wand` it lacks, and
  `AddonGameTests.aTextureListNamesWhatItListsAndThePackLacks` asserts only the second is named.

  **The silent column is the one that has to stay at zero.** A kind in `UNREAD` is reported to the
  author; a kind in neither is a directory the mod passes over without a word. The
  run also names any path declared as read whose own literal appears in no other source file, which
  is a coverage claim with no reader behind it.
- **`tools/census/manifest.py`** measures the one file every pack has and no other census looks
  inside. `packfiles.py` counts `manifest.json` as a kind that is opened and says nothing about what
  is in it.

  ```bash
  python3 tools/census/manifest.py            # the table, then what is reported and what is silent
  python3 tools/census/manifest.py --strict   # exits non-zero while a manifest field goes unnamed
  ```

  The published side is Mojang's own schema set for the manifest, `Manifest.json` and the documents
  it points at with `$ref`, under `metadata/json_schemas/client_server/packaging/`. A field is named
  by the level carrying it, so `header.name` and `subpack.name` are two names rather than one. Both
  dependency shapes are one level and the four setting shapes are another, because a pack writes
  each pair in one array and the reader tells them apart by the fields themselves.

  The mod's side is `pack/PackManifest.java`. What it **reads** is found by the object each call is
  given, `Json.text(header, "name", ...)` and `header.get("name")` being the two shapes every read
  there is written in, so the census cannot credit `header.description` for a module's. What it
  **reports** is the `<LEVEL>_DROPPED` declarations, which is what `PackManifest.report` names
  against the pack that stated one, and `SUBPACK_LEGACY` is the older spelling read on purpose.

  **The silent column has to stay at zero here as well**, for the same reason it does in
  `packfiles.py`. Two more checks end the run. A field named here that the schemas do not carry is
  stale, unless a `LEGACY` declaration claims it. And a record component nothing outside the reader
  ever asks for is a field parsed and then ignored, which is as silent as one never parsed; string
  literals are blanked before that search so a field name spelt in the reader cannot stand in for
  its value being used.

  A slider's `min`, `max` and `step` and a dropdown's `options` are read by `PackManifest.Setting.tryFrom`,
  which brings the shipped default inside them; `PackOrder.sort` names a non-`optional` script API
  dependency `ScriptHost.provides` lacks. The test pack's `tp:ember_overdriven` and `tp:ember_drift`
  settings ship defaults outside their controls so `aPacksOwnSettingsReadBackAtTheirDeclaredValues`
  can read the correction back.

  The count behind each dropped field has two halves. **Vanilla** is Mojang's own pack pair, which
  writes a thin manifest and says little. **Installed** is every manifest in the add-ons under
  `versions/*/run/addons/`, one copy of each, which is where a real pack's habits show: a worktree
  no game has run in has none of those directories, counts nothing and still passes, and `--addons`
  points the count at a checkout that has them.
- **`tools/census/declaration.py`** measures the one thing a count of names cannot: *where* a pack
  declares a component, against where the mod reads it from. Every other census here is satisfied by
  the name being present on both sides, so a component read off the top level of a definition while
  every published pack writes it inside a component group counts as answered and answers nothing.

  ```bash
  python3 tools/census/declaration.py            # the table, then what no read can see a group of
  python3 tools/census/declaration.py --strict   # exits non-zero while one stands
  ```

  Bedrock puts a component in a group whenever it is something the entity becomes rather than
  something it is: `minecraft:is_saddled` arrives with the saddle and `minecraft:ageable` with being
  young. Of the thirty-five vanilla entities that age, one writes `minecraft:ageable` at the top
  level, so a reader asking the definition answers for almost no published pack.

  The published side is how often each component appears at the top level of a vanilla entity and how
  often only inside one of its groups. The mod's side is the call each name is read through:
  `declaresNow` and `liveComponent` see the live set, `componentBody` sees every group whether it is
  on or not, a `case` in `EntityEvents.applyComponent` sees only the moment a group arrives, and
  `declares` and `components().get` see the top level alone. A goal is left out, because
  `collectBehaviours` takes the union of both sides before the entity exists, and so is a marker,
  because the markers are published together off one list.

  **The `read` column ranks the work rather than deciding it.** `elsewhere` means only that no
  recognised call names that literal.

  **Two indirections are worked out rather than listed.** A name walked over in a `new String[]` or
  a `List.of` is read through the loop's own variable, so the line carrying the name carries no call
  and the line carrying the call carries no name; the loop body decides for every name in the list.
  And a helper taking the name as a parameter and handing it to one of the recognised calls reads it
  exactly as widely, so `raise("minecraft:on_hurt", ...)` is a live read that looks like a bare
  string. Each such helper is found by its own parameter reaching a recognised call inside its body,
  which is why a helper written in the source counts the moment it is written. Only the `IMPLEMENTED`
  list itself is cut out of `AddonEntities.java`; the rest of that file reads components like any
  other.

  **A value the entity type was built from is still on the list.** The type being frozen bounds
  where a group's value may be applied rather than whether it can move at all: Java holds a size,
  an attribute and a navigator on the entity as well as on its type, so `minecraft:scale` reaches
  the entity through the same scale `minecraft:scale_by_age` moves, and `minecraft:attack` through
  the attribute instance rather than the supplier. What is left off is a name with no read at all,
  taken from the same set `components.py` prints as implemented with no reader of its own: those
  are the components Java does for every mob anyway, so where a pack declared one decides nothing.
- **`tools/census/ids.py`** counts the nouns rather than the vocabulary: which of the item and block
  ids Bedrock's own material writes resolve to a Java one. A pack names vanilla things by Bedrock's
  ids, a few hundred of which are not Java's, and one that resolves to nothing costs a whole loot
  table, recipe or function.

  ```bash
  bash tools/census/fetch.sh
  python3 tools/census/ids.py            # the table, then every unanswered id and where it is written
  python3 tools/census/ids.py --own      # and the ids declared Bedrock's own
  python3 tools/census/ids.py --strict   # exits non-zero while an id is unanswered
  ```

  The published side is the vanilla data modules under `metadata/vanilladata_modules` in
  `bedrock-samples`, every id its behaviour pack writes in a loot table, recipe, trade table, entity,
  spawn rule, biome, block, item, feature or feature rule (by the keys that name an item or a block,
  less anything the same material uses as an item tag), and the `@minecraft/vanilla-data` 1.20.0
  tables `fetch.sh` puts in `scratchpad/mcapi`, which still carry the family ids (`wool`, `log`,
  `stone_block_slab`) packs of that time write. The mod's side is the mapping itself rather than a
  reading of its source: `./gradlew :<version>:idCensus` runs every id through `BedrockItemNames`
  and `BedrockBlockNames` on the unit tests' classpath, because which Java spelling answers depends
  on the registry of the version that runs it.

  An id is answered, Bedrock's own, or unanswered. **Bedrock's own** is what
  `BedrockBlockNames.bedrockOnly` and `BedrockItemNames.bedrockOnly` declare Java has nothing for:
  the education edition's chemistry and hardened glass, the building permission blocks, Bedrock's
  technical blocks, the experiments Java has not shipped, an item frame (an entity in Java), and
  an item Java keeps none of. **The unanswered column has to stay at zero.**

  Measured on 26.2 against bedrock-samples, over 1979 item ids and 1711 block ids written there:
  1632 items and 1166 blocks resolved as written, and with the mapping 1869 items and 1408 blocks
  resolve, the 110 and 303 left being Bedrock's own and none unanswered.

**A trade table path naming one of Mojang's own tables, which no pack ships, borrows Java's offers.**
`TradeTable.read` maps `trading/economy_trades/wandering_trader_trades.json` to the wandering trader
and every profession table, under `trading/economy_trades/` and the older `trading/`, to Java's
villager of that profession (`TradeTable.PROFESSIONS`: `stone_mason` is Java's `mason`,
`leather_worker` its `leatherworker`, `tool_smith` and `weapon_smith` its `toolsmith` and
`weaponsmith`). `TradeTable.Borrowed.offers` makes a Java mob at one level and copies what it offers,
so no Mojang data is committed. Such a table has Java's five levels as its tiers, at 0, 10, 70, 150
and 250 experience, which is what Bedrock's economy tables state. The older tables count their tiers 0 to 4 or state
nothing, and Mojang documents no experience for them, so they take the same five. `AddonEntity.lendTiers` adds a level's
offers as the trader reaches it and keeps those already on the counter. A trader's
offers with their uses, trade experience, spent `single_use` keys and inventory are filed in
`AddonState.trading` under its uuid by `AddonEntity.saveTrading` and read back by `restoreTrading`
from `restoreGroups`. `minecraft:spawn_entity` `should_leash` leashes the spawned mob to the producer.
A trade item's `filters` are asked of the trader, as subject `self`, when `TradeItem.offeredBy` builds
the offer, which is when Bedrock's fisherman picks the boat of its biome and its cartographer drops a
map the dimension has no use for. A choice entry states its own filters or takes its side's. A failed
payment leaves its alternative off the counter and a failed result leaves the whole trade off; the
filters are not asked again later. `ember_trades.json` carries one of each, and
`BehaviourGameTests.aTradeItemsFiltersDecideWhetherItIsOnOffer` reads both back by tag.
`tp:hawker` in the test pack names the wandering trader's path, and its `tp:hawker_mason` group the
mason's; `BehaviourGameTests` covers them.

## Projectile and hit box details

- `spawn_aoe_cloud` (`entity/CloudOnHit`) spawns Java's `AreaEffectCloud`. Mojang's schema gives `duration` in seconds and `reapplication_delay` in ticks, a `potion` of -1 meaning the projectile's own `potion_effect`, and defaults of 0 for radius and `reapplication_delay`. Its `radius_on_use` default is documented as -1 meaning "unchanged", which contradicts vanilla's -0.5 as a shrink, so the value is the radius change per application and an absent one is 0. The cloud starts at once (wait time 0) and shrinks to nothing over its duration as Java's lingering cloud does. `AreaEffectCloudAccessor` sets the reapplication delay and, for `affect_owner` false, parks the owner in the cloud's `victims` map with a tick that never comes.
- `potion_effect` with `splash_range` (default 4) splashes the potion at the impact with Java's splash share: 1 less distance over range, whole for the entity hit directly.
- `custom_hit_test` boxes are centred on `pivot` (default `[0, 1, 0]`, X and Z from the entity's middle) with `width` and `height` defaulting to 1; Mojang publishes no schema for it, the defaults and the centre are read from the vanilla entities (a 1.75 tall box with pivot y 1). Java has one uniform `getPickRadius`, so the radius is the furthest any box reaches past the collision box in any direction.
- `minecraft:behavior.drink_milk` waits `cooldown_seconds` (default 5) after each drink.
- `rail_sensor` `tick_command_block_on_activate` and `_on_deactivate` stay reported. In Bedrock they only mean something on `minecraft:command_block_minecart`, whose command lives in the entity's own data and cannot be written by a pack; Java's `MinecartCommandBlock` runs its command on a powered rail by itself.

## Molang structs

A `MolangValue` is a number, a string or a struct of lowercase members, and it is never changed in
place, so `v.a = v.b` copies the whole struct by sharing it. `v.x.t.x = 1` rebuilds `v.x` with the
member set, making a struct of any step that held a number, which is how a pack declares one
(`v.pose = 0; v.pose.t = 0; v.pose.t.x = 0;`). A missing member reads as zero and a struct read as a
number is zero, as Mojang's Molang documentation says of errors. A query is asked by its own name and
answers a struct whole; the parser reads the member a pack names after the name or after the call
off it, so `v.c = q.spellcolor; return v.c.r;` works.

`query.bone_orientation_trs(bone)` answers `BoneOrientation.ofDrawn` of the bone's matrix in the
queryable geometry, posed by `BedrockModelRenderer.anchorOf` the way the frame draws it, read back
into the pack's own frame: `.t` is the posed pivot in pixels from the feet, `.r` the whole turn in
degrees as `rotationZYX` decomposes it, `.s` the scales multiplied down the chain. Mojang publishes
the member names and nothing on how the matrix is composed, so `.t` is the pivot rather than the
translation column of a pivot-about matrix; Villager News reads it as a place in the model, dividing
by sixteen and turning it by the body's yaw. A bone the geometry lacks, and every bone on a server,
answers the identity, as documented. `bone_orientation_matrix` stays reported: no member names for
the matrix are published. Searched for them: `metadata/doc_modules/molang.json`,
`metadata/molang_modules/mojang-molang-queries.json` (return type `matrix`, the type `bone_aabb` and
`bone_origin` also carry) and `documentation/Molang.html` in bedrock-samples; the query's page, its
form in `MicrosoftDocs/minecraft-creator` and `Mojang/bedrock-schemas`, the 1.20.60 changelog and
the Molang syntax guide on learn.microsoft.com; the Bedrock wiki's Molang pages; and a GitHub code
search for the name, whose only other implementation leaves it out too. Each gives the description
and nothing on the members. `AnimationClientGameTest.orientation` reads `tp:gaze`'s `v.head_pose` and
`BoneOrientationTest` the frame arithmetic.

**`queryable_geometry`** names the `geometry` key every bone query answers from
(`ClientEntityDefinition.queryableGeometry`), whatever the render controllers draw. Mojang publishes
the field in no schema and no document beyond the phrase "the queryable geometry of the mob", so a
definition naming none, or naming a key it lacks, answers from `default`, else from the first entry.
`tp:gaze` names `geometry.tp.gaze_tall`, a geometry it never draws whose head pivots four pixels
higher, so `AnimationClientGameTest.orientation` reads a pivot the drawn model has not got.

## Molang entity references

A `MolangValue` may also hold an entity (`MolangValue.Reference`) or an array of them, which a
variable, a temporary, a struct member or a context holds like any other value. `left->right`
evaluates `right` against `left`'s `scope()`, and answers zero without evaluating it when `left`
refers to no entity or to one that is gone; an assignment on the right writes into that scope, which
is what Mojang's `v.pigpig->v.test.a.b.c = 1.23` examples need. A reference to an entity that is gone
is false and `??` falls through on it. `for_each(t.x, array, {...})` binds each entity that is still
there in turn. `break` and `continue` end the pass of the innermost `loop` or `for_each`; outside
one they leave the expression malformed, which answers zero, as Bedrock's content error does. A
loop answers zero itself. `q.combine_entities` keeps each entity once, in order of first mention, and
`q.count` counts an array as its elements and anything else as one.

`bedrock.entity.EntityReference` is the reference to a Java entity, and the side reading it decides
the scope. A client reads `Animators.rootOf`'s animator through `published()`, so only the variables
its client definition names `public` are seen, as Mojang's syntax guide says. A server reads the
entity's `ServerAnimator`, or `AddonEntity.scope()`'s scope of queries alone when it runs no script.
A behaviour pack has no `public` setting, so every variable of a server script is readable. Anything
with no script of its own on that side gets `EntityReference.questions`, a scope that answers its
queries and holds no variables. An attachable's `c.owning_entity` is a reference to the entity
wearing it, so a wearer Java draws with a model of its own answers its queries through it as well.

Which queries answer an entity, decided from the published material: Mojang's query list
(`mojang-molang-queries.json`) types one query as an array of entities, `combine_entities`, and none
as a single entity. `query.get_nearby_entities(distance, identifier)` is absent from that list and
from the experimental list in `documentation/Molang.html`, which is empty, yet Mojang's own Molang
documentation uses it in its `->` and `for_each` examples, so it is answered: the entities whose feet
stand within the distance, itself included, of the type named where one is. `get_nearby_entities_except_self`
is the same without the entity asking, a name found in no Mojang material today. Both are listed in
`EntityQueries.EXPERIMENTAL`, which `components.py` reads as answered on purpose. `q.get_ride`,
`q.target` and `context.player` appear in no Mojang material and are not answered. `context.other`
is the repair target of an item's `repair_amount`, which nothing here evaluates.
`BehaviourGameTests.anEntityReadsThePropertiesOfTheEntitiesNearItThroughAnArrow` has `tp:herald` add
up `tp:settled` over two wisps through `->` inside `for_each` and count them through
`combine_entities`; `MolangTest` runs Mojang's struct examples against stub entities.

## Bedrock features

A pack's `features/` become configured features and its `feature_rules/` placed features, in
`convert/Worldgen`. Where Java has no type with the same fields, the feature is one of this mod's
own, in `worldgen/`: `mcaddon:scatter`, `mcaddon:aggregate` and `mcaddon:single_block`, and the
placement modifier `mcaddon:scatter` that a feature rule's `distribution` becomes. From 26.3 each
record is a Java `Feature`; before, it is the configuration of `BedrockFeatures.Placing`. Every
feature gets a placed feature under its own name, so another can name it, and one naming a feature
that does not convert, or itself through a loop, is left out: an unresolved reference would stop
the whole data pack loading.

`bedrock.behaviour.Scatter` is both scatters. Mojang publishes the fields and not the arithmetic,
so these are decided here, from the Bedrock wiki's feature page where it says anything:

- A coordinate is an offset from the position the feature is given, and a feature rule is given
  its chunk's corner at height 0, so a rule's heights are world heights. Java gives a placed
  feature the bottom of the world, which `ScatterPlacement` moves to 0.
- A rule's biome filter is asked once per chunk, of the biome at the surface over the chunk's
  corner, before the scatter. Asked at each position the scatter reaches, a rule placed from height
  0, as the test pack's bloom rule is, met Java's cave biomes under a forest and placed nothing.
- `uniform`, `gaussian`, `inverse_gaussian` and `triangle` leave the upper bound out; the grids
  include it (the wiki says both). `gaussian` is a normal spread a sixth of the range wide about
  the middle, kept inside the range; `inverse_gaussian` folds that onto the two ends; `triangle`
  is the mean of two uniform draws.
- A grid steps one cell per iteration. The first grid axis in `coordinate_eval_order` steps every
  iteration and each later one when the earlier have come round (the wiki's example).
- `scatter_chance` written as a number or an expression is a percentage.
- `query.heightmap` reads Java's `MOTION_BLOCKING_NO_LEAVES`, as the entity query does, and
  `query.above_top_solid` reads `OCEAN_FLOOR`.

`SingleBlockFeature` decides these where nothing is published: no `may_replace` replaces anything
(Mojang's documentation says the block can be placed anywhere); `min_sides_must_attach` defaults to
1 and counts only the four sides, capped at how many are listed, while `top` and `bottom` must
always match; `auto_rotate` defaults to true and then tries the side lists in all four turns; and
both `enforce_*` flags ask `canSurvive`, because Java folds a block's placement and survival rules
into that one call. An aggregate places its list in order, as the wiki says, though Mojang's page
promises no order.

## JSON UI

`bedrock/ui/` reads a resource pack's `ui/` files and lays out what packs do with them to a server's
forms and to the heads-up display's title and action bar; `client/screen/PackUi` holds the active
set's definitions. `UiDefinitions` merges definitions key by key (a pack's same-named control lays
its keys over the lower one, `controls` and `bindings` are replaced whole, `modifications` edit them
in place) and settles `$variables`; `UiExpression` evaluates binding expressions; `UiLayout` places
controls by `size`, `offset` and anchors, with stack panels, grids and collection factories.
`ServerFormUi` and `HudUi` are what a form and the display hand that layout as bindings, collections
and factories, both pure, so they read the same on every version.

- **Mojang's UI files are not shipped**, so a definition resting on one of Bedrock's controls has no
  base. Three stand-ins answer for it in `UiDefinitions.standIn`: `server_form.long_form_panel` is
  this mod's own form screen (`UiControl.VANILLA_FORM`), `common_dialogs.main_panel_no_buttons` a
  225 by 200 panel showing its `$child_control`, and any `common.*button` or `common_buttons.*` a Java
  button labelled with its `$button_text`. Anything else is drawn from what the pack wrote and named.
- **`long_form` is Bedrock's until a pack states its `controls` or `type`.** A pack only adding
  bindings to it keeps this mod's form, hidden where those bindings say so; `ServerFormUi.made`
  builds that stand-in.
- **The form kind comes from the script.** `minecraft_server_ui.js` gives each form class a static
  `serverForm` (`long_form` or `custom_form`) and the description carries it as `kind`; `body()` is
  its own element type so `#form_text` can be told from a label. A data driven `CustomForm` has none
  and is never laid out from a pack.
- **The title and action bar are taken as they arrive.** `HudTextMixin` hands each `setTitle`,
  `setSubtitle` and `setOverlayMessage` to `client/render/PackHudText`, which drops Java's own when a
  pack hides Bedrock's for that text. It is a mixin of its own beside `HudMixin`, and the pack's
  controls are drawn by a layer added through `Platform.drawOverHud`.
- **What is not drawn is reported by file or control** through `UiDefinitions.report`, called from
  `PackFiles.report`: a file changing a screen outside `server_form` and `hud` (followed through every
  name they mention), an undrawn control type, `anims`, a font size and a `custom_form` layout.

`JsonUiClientGameTest` opens the test pack's `tp:tiles` form under a routed title and reads the
panel, the grid cells, the icon and the heading back off the screen, presses a tile with the pointer
and reads the selection back as a tag, then opens the same form under a plain title and finds Java's
buttons. It sends a `tp_banner:` title and a `tp_tally:` action bar and reads the pack's labels, their
places and Java's own hidden title and action bar back, then a plain one of each. The pack side is
`tools/testpack/pack/resources/ui/`.

## Bedrock item tags and vanilla textures

`convert/BedrockItemTags` answers a pack's `minecraft:sign`, `is_axe`, `is_food` and the other vanilla Bedrock item tags through the Java tag or item property that is the same set. Script `hasTag` and `getTags` (which report Bedrock's names for covered sets), `has_equipment_tag`, Molang's item tag queries and recipe `tag` keys all go through it. A tag with no entry is asked of Java as written, so a pack's own tag works. Mojang publishes no list of the vanilla item tags, so the table is built from the tags Bedrock's own behaviour pack and script bindings use; a missing tag is a one line addition. `ItemTagGameTests` and the `tp:item_tags` probe assert it.

`client/render/VanillaTextures` maps a Bedrock vanilla texture path a pack names without shipping (the signs) to the game's own. Bedrock's sign texture is 64 by 32 in the layout Java's `textures/entity/signs/<wood>.png` had up to 26.1.2. From 26.2 Java ships only `textures/block/<wood>_sign.png`, 32 by 32, which the block models `template_sign_rot_*` and `template_wall_sign` sample at two texels per model unit. `VanillaTextures.SIGN_FACES` copies each face out of it into a 64 by 32 `DynamicTexture`, `mcaddon:textures/vanilla/sign/<wood>`, made once per `AddonModels`; checked against 26.1.2's entity textures, every texel matches except the post's two ends, which the block model draws only at the bottom. A hanging sign still maps to the block texture. `tp:placard` and `RenderControllerClientGameTest.vanillaSignTexture` assert the board front and the post.

## Hidden from Java's pack screens

`PackSelectionModelMixin` filters packs whose id starts with `mcaddon/` out of the streams `getSelected` and `getUnselected` return, so the Resource Packs and Data Packs screens list none of them. The model's own lists keep them, and they are required packs, so Done leaves them selected. `PackSelectionClientGameTest` asserts both screens.

## Client options

`PaperDoll.OPTION` and `ColourGrading.OPTION` are reachable three ways on 26.1.2 and 26.2, and only the first on 1.20.1 and 1.21.1. `VideoSettingsScreenMixin` lists them in Java's Video Settings. `client/integration/ModMenuIntegration` opens `client/screen/AddonOptionsScreen` from Mod Menu. `client/integration/SodiumIntegration` adds a "Bedrock Add-ons" page to Sodium's video settings, which replace Java's screen. Sodium reads that class from a `sodium:config_api_user` key under `entrypoints` (not under `custom`) in `fabric.mod.json`. Both entrypoints are written by the `settings_entrypoints` token in `gradle/mod.gradle.kts` for 26 and later only, and both classes are wrapped in a `//? if >=26` block. Sodium and Mod Menu are `compileOnly`, so the jar does not need either. `SettingsClientGameTest` presses the Mod Menu screen's buttons and asks Sodium's `ConfigManager.CONFIG` for both options; it fails when either `fabric.mod.json` key is missing.

## Add-ons handed over at join

A client joining a server that runs this mod plays with exactly the server's add-ons for that
visit. `docs/servers.md` says what a player sees; this is how it is built.

**The built-in registries only grow.** Nothing an add-on registered is ever removed and no raw id
moves. `registry.RegistryEdits.adding` opens the five registries an add-on writes into (sound event,
particle type, entity type, block, item) after the freeze and freezes them again as
`MappedRegistry.freeze` would: the intrusive holder map comes back and goes, and from 26 the
component lookup is rebuilt and every holder's tags are bound. A registry that did not grow is
closed without either, because the client game tests' server reads those from other threads at the
same moment. `registry.AddonRegistry.ensureRegistered` runs the writers inside it, and startup goes
through it too.

**What answers is the active set.** `registry.ActiveAddons` holds the installed set and the active
one, each a `resource.AddonSet` (the packs, their conversions and their function names). The active
set is what the pack repository is offered, what the creative tabs list, and what music, fogs,
camera presets, dialogue scenes and, from 1.21, brewing are read from. From 26.3 brewing, an item's
burn time and its composting chance are files in the converted data pack (`minecraft:brewing`
recipes and `context_int_provider` entries the item's components name), so they follow the active
set by the pack being offered and change on the reload an activation runs. An add-on registered and not
active is dormant: its entries stay registered with their last definitions and nothing offers it.
Spawn rules, feature rules and the nether and end biome lists are registered once for the installed
set and asked as each world loads whether their add-on is active, as the next paragraphs say.
Before 26 the model bakery asks for a model for every registered item and block, so
`ModelBakeryMixin` and `BlockStateModelLoaderMixin` leave out what `ActiveAddons.isDormant` names;
the miss would otherwise be logged as `Unable to load model` on every reload after a visit.

**What a world runs follows the active set.** `ActiveAddons.isActive(namespace)` gates the spawn
rule and feature rule selectors, which Fabric asks again as each server is constructed
(`MinecraftServerMixin` calls `BiomeModificationImpl.finalizeWorldGen`). The feature rule gate is
required, because `addFeature` throws on a placed feature the world's registries lack. The server's
scripts (`ServerHooks.start`), the pack structures and the script gametest structure search read
`ActiveAddons.active()`. `/mcaddon packs` and the resource pack listing of `ServerAdmin` stay on the
installed set, since they describe what a server holds and can hand out. `ActiveAddons.isActive(set)`
compares converted roots, so a resolved subset equals the active set whichever object carries it.
Fabric holds every nether and end biome `AddonWorldgen` hands it for the life of the process and
resolves each one as a world builds its nether and end, which fails with `Unbound values in
registry ... worldgen/biome` when the add-on's data pack is not offered. `NetherBiomeDataMixin`
leaves such a biome out of the nether's parameter list, and `TheEndBiomeOverridesMixin` leaves it
out of the end's biome set and resolves it as the biome it replaces, because Fabric's weighted
pickers cannot drop an entry. Both ask `AddonWorldgen.isDormant`, which is false for a biome no
add-on placed. An add-on item's component naming an entry of the add-on's data pack is built so
it is left off when the entry is missing: from 26 every component is built again on each data
load, and a lookup that throws ends the load. `Platform.jukeboxPlayable` is the one such component
today. `InactiveAddonClientGameTest` opens a world with the test pack inactive and finds none of
its biomes, no script and no ore, then the same world settings with it active and finds all three.

**A world plays the add-ons its list turns on.** `state.WorldAddonList` is
`<world>/data/mcaddon-addons.json`; an installed add-on it does not name is off.
`ServerPacksSourceMixin` runs at the head of `ServerPacksSource.createPackRepository(LevelStorageAccess)`,
which a dedicated server (the world `level-name` names), a single player world and a world being
created all call before their data packs load. It takes `WorldAddonList.open`: the world's own list,
else `client.WorldAddons.takePending()` (the list Create World chose), else every add-on of a
fallback on, which is the installed set for a world that has `level.dat` and the active set for one
being created. The list is written back and `ActiveAddons.open` activates `list.resolve(installed)`
when it differs from the active set, and keeps the list as `ActiveAddons.world()`.

A client has to reload its resources before a saved world loads, so `WorldOpenFlowsMixin` cancels
`WorldOpenFlows.openWorld` while the world's set is not the active one, or while `AddonSession`'s
`settled` chain is still busy, and `AddonSession.holdOpen` activates the set on that chain, reloads
and calls `openWorld` again. A join's activation and a world's never overlap that way, and the
hook then finds the set active and changes nothing. `holdOpen` sets `visiting`, so the play
connection's disconnect, which Fabric raises for a single player world too, puts the installed set
back through the same `leave`. `ServedArchives` hands an installed add-on the active set leaves out
as not active, which is the dormant path, so a LAN host serves its world's set and a dedicated
server its world's; a development archive from `mcaddon.served` is active unless the world's list
names one of its pack uuids off. The pause menu's Add-Ons screen reads On or Off off the active set,
so in a world it shows the world's choice.

**A world removes the content of an add-on its list turns off.** `registry.RemovedAddons` is built
per server at `SERVER_STARTING` from `ActiveAddons.world()` against the installed set: the blocks,
entity types and items (block items and spawn eggs among them) the off add-ons declare and no on
add-on declares, leaving out `minecraft:` ids. Nothing is built when the list turns nothing off, so
a server playing every add-on pays nothing. The chunk and entity load events only queue (a chunk
only when a section's palette may hold a removed block or it has a container); `END_SERVER_TICK`
sets the blocks to air with `UPDATE_CLIENTS`, discards the entities and removed dropped stacks, and
empties the items out of containers, player inventories, ender chests, equipment, item frames and
`DataComponents.CONTAINER` contents. A load event runs while the chunk or entity is still being
added, which is why the work waits for the tick. A client's visit to another server and a server
handing an add-on out as inactive never build one, because the set comes from the list the server
opened its own world with. Bedrock turns a block whose definition it lacks into its `unknown` block
in some cases; Java has none, so the block becomes air.

`RemovedAddonsClientGameTest` places `tp:lamp`, an idler and `tp:dust` in the player's inventory,
ender chest, a chest and on the ground, each beside something of the game's own, writes `tp` off,
reopens and finds none of the first and all of the second.

**Create World and Edit World switch the list.** `CreateWorldMoreTabMixin` puts an Add-Ons button
under Data Packs, which opens `AddonsScreen.forNewWorld`: every installed add-on on, or the choice
`client.WorldAddons.chosen()` already holds. Done hands a changed list to `WorldAddons.choose`, which
keeps it pending for `ServerPacksSourceMixin`, plays its set through `AddonSession.play` (activate,
`visiting`, reload) and then has the screen load its data packs again through
`CreateWorldScreenMixin`'s invokers (`getDataPackSelectionSettings`, then `reload` on that
repository, then `tryApplyNewDataPacks`). Create World builds the new world from the data packs it
loaded as it opened, so without that second load the world gets the whole set's recipes, biomes and
dimensions. `popScreen` (Cancel, or a failed creation) calls `WorldAddons.cancel`, which forgets the
choice and runs `AddonSession.leave`; a world that was created puts the installed set back through
the same leave when its connection ends. Re-Create goes through `WorldListEntryMixin`, which holds
`recreateWorld` behind `AddonSession.holdOpen` the way a saved world's open is held, so the source
world's set is active and the resources reloaded before `recreateWorldData` loads its data packs;
`createFromExisting` then takes `ActiveAddons.world()`, the list that load just opened, as the
pending choice. `EditWorldScreenMixin` adds an Add-Ons button above Save and Cancel, which opens
`AddonsScreen.forWorld`: the world's list with every installed add-on it does not name added as off
(`WorldAddonList.naming`), each switch written into the world's file at once, nothing activated. A
row whose add-on is off there and whose converted pack holds a dimension
(`ConvertedPack.addsDimension`) carries a warning that the world may not open without it.

`CreateWorldAddonsClientGameTest` switches `tp` off in Create World, finds the screen's data packs
reloaded without its recipes, creates the world and finds the server without them and the world's
list with `tp` off; then Re-Creates that world and finds `tp` inactive, the client's resources
without it and the source list chosen; then cancels and finds the installed set back.
`EditWorldAddonsClientGameTest` empties a saved world's list, finds `tp` Off with the dimension
warning, switches it on and off again, reads each switch back from the file and finds the active set
unchanged.

`WorldAddonsClientGameTest` creates a world, reads back a list with every installed add-on on,
writes `tp` off, opens it again and finds `tp` inactive, `tp:dust` dormant, `mcaddon/tp` selected
by neither the server's data packs nor the client's resource packs, the script's first spawn count
standing still and the Add-Ons row reading Off, then writes it on again and finds all of it back.
`AddonSyncClientGameTest` sets the pending list with `sw` off before a dedicated server starts and
finds `sw` served as not active, registered on the client and not active there. Fabric's dedicated
test server writes `level.dat` itself and never calls the hook on the client, so the pending list is
taken by the server.

**A same-id copy is checked before anything is registered.** `AddonRegistry.conflict` compares the
shape every registered object was built from (an entity type's population and size, a block's whole
definition, an item's whole component map) against another set's, and names the add-on whose copy
would have to be built again. A block is never rebound, which is why its whole definition is its
shape.

**The transfer.** `sync.AddonManifest` names each archive by the sha256 of its bytes;
`sync.ServedArchives` copies them into `.mcaddon/served/` the first time a client needs one, forgets
the manifest as a server starts, and lists every archive registered in the process that the server
did not install as not active, which is what a LAN host visited servers for. `sync.Outgoing` and
`sync.Incoming` are the two ends of one transfer whatever carries it: from 1.21
`server.AddonSyncTask`, a configuration task added before Fabric's registry sync, with
`network.SyncChannels`; on 1.20.1 `server.AddonSyncLogin`, login queries with a heartbeat every five
seconds and `ServerLoginTimerMixin` holding the login timer, with `network.LoginChannels`. The
client side is `client.AddonSession`. Archives arrive in `.mcaddon/received/` (`pack.ReceivedArchives`),
unpack into `.mcaddon/extracted/<sha256>/` and convert into a directory ending in the first eight
digits of the digest.

**The log lines `verify.py` reads stay as they are:** `Received N add-ons from the server.`,
`Activated the server's add-on set of N add-ons.`, `Restored the installed add-on set.` and the
refusal `The server's copy of <add-on> cannot replace yours without a restart.`

Traps met building it:

- **A task queued for the client thread from a disconnect can be thrown away.** The client drops
  its pending tasks as a play connection ends (`Minecraft.disconnect` calls `dropAllTasks` before
  anything else), and Fabric raises the disconnect event before that, so a leave handed over with
  `execute` or `schedule` never ran. The leave is
  a flag the client's own tick takes instead, and an enter takes any leave still owed first.
- **Every client game test joining a dedicated server is handed the served add-on.** The server and
  the client share one process, so the add-on is registered from the first such test on, and a test
  cannot ask whether it was registered before the join; it asks whether the server installed it.
  The client game test task builds it from `tools/testpack/served` into
  `run/clientGameTest/addons-served/` and names that directory in the `mcaddon.served` property,
  which `ServedArchives` reads in a development run only.
- **The client game test harness does not tick the client between tests**, so a leave owed by one
  test runs as the next one connects, while that test's server configures it.
- **`Outgoing` tells its link how a transfer ended only once its lock is let go.** Ending a
  connection from the network thread waits on the server thread (`executeBlocking`), and the server
  thread takes that lock every tick in `expire`, so a refusal told under it froze the server until
  the watchdog ended it. `AddonSyncRefusalClientGameTest` met it on 26.1.2; `OutgoingTest` holds it.
- **A configuration that changes nothing must not reload.** A join whose set equals the active one
  answers at once.
- **The server's minute without a message counts only while archives are on their way.** A client
  holding every archive it asked for converts, activates and reloads, which sends nothing for as
  long as a large set takes. `Outgoing` waits on it then, the ten minute limit still holds, and a
  client that stops answering is dropped by the connection's read timeout, which the login's
  heartbeat and the configuration phase's keep-alive hold off for one that answers.

Tests: `RegistryGameTests` (adding after the freeze, on all four versions), `ActiveAddonsGameTests`
(an empty set and back), `ShapeGameTests` (copies that do and do not conflict),
`ServedArchivesGameTests` (a registered archive handed out dormant), `AddonSyncClientGameTest` (a
join, a leave and a second join against a dedicated server), and the unit tests `AddonManifestTest`,
`ReceivedArchivesTest`, `ArchivesTest` and `FunctionNamesTest`. Three more client game tests point
the server at another set through `clientgametest/Served`, which sets `mcaddon.served` to one of
the directories `installServedPack` writes beside `addons-served` and has the server prepare its
manifest again: `addons-reshaped` holds a later release of `sv` whose crate has a fourth state, and
`addons-other` the same content as the add-on `sw`.

- `AddonSyncRefusalClientGameTest` joins once to register `sv`, then joins a server handing out the
  reshaped copy and expects the disconnect screen naming the add-on, no world, and the installed set
  active. It fails without `AddonRegistry.conflict` in `AddonSession.activate`.
- `AddonSyncReloadClientGameTest` closes the connection from the server while the join's reload is
  on the screen, then again with the client in the world, and expects the installed set back both
  times. The second close fails when the leave is queued with `Platform.schedule` from the
  disconnect event: the client drops its queued tasks as a play connection ends
  (`Minecraft.disconnect` calls `dropAllTasks` first), so the leave never runs.
- `AddonSyncTransferClientGameTest` has the server hand out `sw` instead of `sv` and `/transfer`
  the client back to itself, which to the client is a second server with another set. It records
  every activation through `ActiveAddons.onActivate` and expects the installed set once and then the
  second set. It fails when the play phase's disconnect does not end the visit. A server needs
  `accepts-transfers=true` (that spelling) in its properties to take a transfer.

## The add-on screen and pack settings

`client/screen/AddonsScreen` lists the installed and the active add-ons (`AddonEntry`, one per
`ConvertedPack`, active when the active set holds its converted directory). `new
AddonsScreen(parent)` is the one way in for a world, which only shows what is on, and `forNewWorld` and `forWorld` put an On/Off switch over each row for Create World and Edit World; `AddonOptionsScreen`, Mod Menu's config screen,
opens from a button of its own; `addToPauseMenu` puts the button on the pause menu through Fabric's `ScreenEvents`, in the
row of the lowest button, which it moves down a row. The three screens extend `ColumnScreen`, a
heading, a `ScrollingColumn` and a Done button that goes back to the parent.

Each add-on is one `AddonRow` widget: the pack's `pack.png` (a `DynamicTexture` per converted root,
loaded when first needed and released in `removed()`, with the game's `unknown_pack.png` as the
fallback), name and version over one truncated description line, an On/Off label, and two
`IconButton`s laid over the row's right edge (gear for `PackSettingsScreen`, disabled with a tooltip
when no pack has settings; info for `AddonReportScreen`). A click on the row body opens it to the full
descriptions, authors, both packs and each pack's modules with their version and description;
the screen keeps which roots are open and rebuilds, so the column learns the new height. The row
draws through `Platform.paint`, and the gear and info pictures are this mod's own
`textures/gui/*.png`, since Java ships no gear. The version-specific widget API is written for 26
and later only. `AddonReportScreen`
prints `PackSet.report(pack)` for each pack and `ServerHooks.entitiesReport()` and
`scriptsReport()`, which are what `/mcaddon` prints; those two count every add-on together, because
nothing records which add-on asked for a name.

`pack/PackSettings` holds the declared settings of the active set and three layers of values over
them: `WORLD` on a server, `TOLD` (what the server sent) and `CHOSEN` (the player's resource pack
choices) on a client. The statics are shared by both sides in one process, so each side needs
layers of its own. `PackManifest.Setting.with` brings any value inside its control, so
a value read from a file or a request is clamped the way a default is. `EntityQueries` asks
`PackSettings.onClient` on a client entity and `WORLD` on a server one; `World.getPackSettings`
reads `WORLD`. `server/WorldPackSettings` keeps `WORLD` in the `packSettings` section of
`AddonState`, takes a change through the `pack_settings` request only from an operator or the
single player host, raises `packSettingChange` and sends every player the values and whether they
may change them. `client/PlayerPackSettings` keeps `CHOSEN` in
`config/mcaddon-pack-settings.json` and redraws an open `PackSettingsScreen` when the server
answers; the screen asks again as it opens, so an operator made or unmade since the join is seen.

`PackSettingsClientGameTest` reaches the screen from the pause menu, moves every control with the
pointer (the wheel first brings it into the column's window), reads the world's values back through
the test pack's script and the player's through the client's Molang, and on a dedicated server
finds the world's controls greyed out for a player who is no operator and a request sent by hand
refused. While three other client game tests ran on the same desktop, its dedicated server half
stopped in four of its first runs with the render thread in `glfwSwapBuffers` until the server's
watchdog ended the run, and every later run of the same code passed: that is the compositor stall
described under the client game tests, which only a dedicated server's watchdog turns into a
failure.

## Script developer tools

`/bedrock script` is `server.ScriptCommand`, over three classes in `script/`:

- **`ScriptDebugger`** speaks the protocol of Mojang's VS Code extension (github.com/Mojang/minecraft-debugger,
  `src/protocol-events.ts` and `src/session.ts`). `DebugConnection` frames it: eight hex digits of
  length, a newline, one line of JSON counted with its newline. Minecraft sends a `ProtocolEvent`
  (version 11, one plugin per script module uuid), the extension answers `protocol`, sets its
  breakpoints and sends `resume`; DAP requests arrive as `{"type":"request","request":{...}}` and
  are answered as `{"type":"response","request_seq":...,"body":...}`. A script is named by its path
  inside its pack's `scripts/` folder, which is what the extension's `generatedSourceRoot` produces.
  The answers come from Truffle's `com.oracle.truffle.api.debug`, which ships in `truffle-api`.
- **`ScriptProfiler`** is Truffle's `CPUSampler` from `org.graalvm.tools:profiler-tool`, written out
  as a Chrome `.cpuprofile` with the `$vscode.locations` the extension's capture handler reads.
- **`ScriptDiagnostics`** builds one `StatEvent2` per tick, sent live to an attached extension and
  written one per line under a `{"encoding":"utf8"}` header by a capture: the `.mcstats` replay
  format of the extension's `src/stats/replay-stats-provider.ts`.

Traps:

- **A suspended Truffle frame may only be read on the thread it stopped on.** The server thread
  stays inside the suspend callback and answers the requests the socket thread queues; the socket
  thread only answers what needs no frame. A closed socket lets the thread go.
- **An attach holds the scripts until the extension's `resume`**, as Bedrock does, through
  `ScriptDebugger.awaitConfiguration` in `ScriptHost.enter`. So does a breakpoint, so a client of
  the debugger in a test runs on a thread of its own.
- **The entry script is evaluated as a file-named source** (`Source.newBuilder("js", File)` with its
  content), so its URI is the same `file:///<uuid>/scripts/...` its imports get from the guest file
  system. Giving a literal source a `uri` instead broke the relative imports of the entry.

`ScriptToolGameTests` asserts all three: a profile naming `weighTheProbe` in `weigh.js` with as
many samples as its hit count and time deltas inside its span (the sampler's hit times are wall
clock milliseconds), a capture of one `StatEvent2` a tick under its header, and a socket client
that stops on the marked line of the test pack's `scripts/weigh.js` and reads the stack. Bedrock's command metadata lists no `watchdog` form, so
there is no `exportstats` to answer.

## Connection rule sides

`enabled_directions` names the side of the block a fence, wall or pane reaches it on (Mojang does
not say which frame; this reading is decided here). `FenceConnectionMixin` answers it in
`connectsTo`, which gets the direction from the fence to the neighbour. `BarsConnectionMixin`
redirects the neighbour's `isFaceSturdy(side)` call inside a pane's `getStateForPlacement` and
`updateShape`, the only place a pane knows the side. `tp:pillar` carries the rule and
`aConnectionRuleNamesTheSidesNeighboursReach` reads fence, bars and wall back.

## Recipe unlock context and priority

`AlwaysUnlocked` adds a `minecraft:tick` criterion to the recipe's unlock advancement, which grants
the recipe on a player's first tick. Mojang publishes nothing on `priority`: the rule used is that a
lower number wins and a missing one is 0. The converter writes the non-zero ones to
`mcaddon_recipe_priorities.json` in the converted root, `RecipePriorities` reads those of the active
set, and `RecipePriorityMixin` stable-sorts `RecipeMap.getRecipesFor` (the one call every version's
recipe lookup goes through) by them, so only recipes that already match are reordered.

## Releasing

A pushed `v*` tag runs `.github/workflows/release.yml`, which builds the jars, makes the GitHub release and then runs `tools/release/modrinth.py` to publish each jar to Modrinth.
The repository secret `MODRINTH_TOKEN` must hold a Modrinth token with the read-project, read-version, create-version and write-version scopes. The read scopes are needed while the project is a draft. `--dry-run` prints the requests without a token.

## Script and entity details worth knowing

- `minecraft:entity_spawned` runs once per entity: `AddonState.firstAnnouncement` records it, and `ServerHooks.announce` raises it from the load event.
- A sensor cooldown starts when the sensor fires (`AddonEntity.startSensorCooldown`), not when it looks and finds nothing.
- `weather_at_position` reads `Level.precipitationAt`, which needs sky above the entity. A game test places its entity above the test box, whose ceiling shelters everything inside it.
- A `sounds.json` moment states its own `volume` and `pitch` (`EntitySounds.momentVolume`, `momentPitch`). An empty `sound` silences the moment for add-on entities too.
- Bedrock effect ids differ from Java's in `BedrockEffectNames`; `Platform.addEffect`, `hasEffect` and `removeEffect` translate, and a script sees Bedrock's id.
- A script's `playSound` resolves a name through `BedrockSoundNames.played`: the id as written, then the pack's own definitions, then Bedrock's name (`mob.x.y` falls back to `entity.x.y`). While several add-ons ship a script the bridge has no namespace, so the pack's own definitions are skipped.
- The census scripts need `scratchpad/bedrock` in the worktree and run without `python -I`, because they import each other.

## Sleep, sit, mingle and avoid block goals

`tp:napper` (`NapperGameTests`) carries one group per field.

- `minecraft:behavior.sleep` is `SleepGoal`, a selector goal, so its `priority` orders it against others. It holds MOVE and LOOK but not JUMP, because Java disables the JUMP flag of a mob riding a boat and a rider that may sleep (`can_sleep_while_riding`) must still start it. `speed_multiplier` and `timeout_cooldown` have no reader: the entity has no bed to walk to or fail to reach (reasons in `fields.py`). `drink_milk` runs on the sensor pass, so its `priority` has a reason too.
- `minecraft:behavior.mingle` is `MingleGoal`. Mojang publishes no search distance, so the partner is the nearest living entity of `mingle_partner_type` within 16 blocks. It walks to `mingle_distance`, faces the partner for `duration` seconds, then waits `cooldown_time`.
- `continue_sitting_on_reload` is stored as the value `is_looking_and_sitting`, written when the goal starts and cleared when it stops. A goal that finds it set on its first check sits again when the flag is true and clears it otherwise.
- `avoid_block` `target_selection_method` is `nearest` (default) or `random`, as Mojang's entity docs say.
- The world clock is shared by every game test, so the sleep test holds the time on each tick of its own window.

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

**A unit is named where a number crosses.** Bedrock counts in seconds, blocks a second and whole
blocks; Java counts in ticks, blocks a tick and, inside a model, sixteenths. Both sides of every one
of those is a plain decimal, so a conversion that is missing or applied once too often costs no error
and no warning: it reads as a number the pack could have written. `bedrock.behaviour.BedrockUnits`
holds the crossings that more than one reader needs, and its unit tests are the measurement. What to
check when reading a component for the first time:

- **A field whose name says a unit may not carry it.** `seconds_to_destroy` is a hardness, and
  `damage_per_tick` is the damage of one hit rather than a rate. Mojang's own numbers settle it: every
  vanilla entity writes four against lava, and four is what lava takes off each time it lands.
- **An acceleration crosses twice**, once for the distance and once for the time, so a particle's
  gravity of 9.8 blocks a second each second is 0.0245 blocks a tick each tick.
- **A chance stated per tick, asked once per interval, fires a fraction as often.** The sensors run
  every ten ticks, so what carries over is the chance of at least one success across the ten.
- **A default a pack leaves out is where a wrong value hides**, because nothing in any pack ever
  disagrees with it. The doc modules under `metadata/doc_modules/` carry Mojang's own default and
  type per field, which is what to read it against.

**Java's command tree stays Java's, and every Bedrock line goes through `/bedrock`.** The only
commands this mod adds to Java's tree are `/bedrock` and `/mcaddon`; it adds no option to Java's
selector and changes no command Java has, so a Java data pack's line means what it means without
this mod. A Bedrock command Java has not got is registered in the tree `server.BedrockTree` builds,
which holds Java's own commands beside them and an `execute` whose `run` looks there too, and
`/bedrock <line>` is the one way in, for an operator and for a Java data pack's function alike.

`server.PackCommand` is the interpreter behind it. `Functions.line` translates the line as the
add-on that wrote it, so a bare function name reaches that add-on's function through
`FunctionNames`, the caller's first; `server.SelectorTags` answers each `hasitem`, `haspermission`
and `has_property` with a tag that names the run and comes off when it ends; and the line is read
against the Bedrock tree. A converted function file holds `bedrock <the line>` for every line, with
a bare call another add-on also ships written as the id its own add-on reaches, and from 1.20.3
`/bedrock` queues the line onto the execution that is running it, so the command limit, a
function's depth and `execute store` hold across it.

**A function line is translated twice, once to report it.** `Functions.untranslatable` translates
every line of every add-on's functions as the mod starts, writes nothing and hands what each line
cannot do to `AddonEntities.unsupported`, which is the register `/mcaddon entities` prints. It runs
in `McAddon.onInitialize` after the entities, items and blocks are registered rather than inside the
conversion, for two reasons: a conversion that is cached never runs again, and a line naming a pack's
own item would read as naming one Java has not got before that item is in the registry. A line that
runs is reported again each time it runs, so the count beside a name is the lines plus the runs.

**What a player sees and may do is the client's own.** `/hud` and `/inputpermission` keep their
state per player on the server, in `server.PlayerHuds` and `server.PlayerInputs`, and send it to that
player's client alone as a bitmask of `bedrock.hud.HudElement` or `bedrock.input.InputPermission`,
whose constants carry the numbers a script names them by. The client acts on it: `HudMixin` leaves a
hidden piece undrawn, `KeyboardInputMixin` lets go of a key whose input was taken away and
`MouseHandlerMixin` keeps the player facing the same way. Mounting and dismounting are decided by the
server, so `MountPermissionMixin` and `DismountPermissionMixin` refuse those there. `HudMixin` carries
three bodies: `Gui` up to 26.1.2 and `Hud` from 26.2 hold the same `extract*` methods from 26, and
before 26 the armour, the hunger and the air bubbles are drawn inline in `renderPlayerHealth` (the
air bubbles alone on 1.21.1), so the drawing calls inside Mojang's own profiler section of that name
are what is skipped there. A script's `ScreenDisplay` and `Player.inputPermissions` reach the same two
classes, so a command and a script never disagree about one player.

`/controlscheme`, a camera preset's `control_scheme` and `Player.setControlScheme` go through
`server.PlayerControlSchemes`, which keeps the chosen `bedrock.input.ControlScheme` per player and
works out the one in force against the camera `server.PlayerCameras` holds: the chosen scheme where
the camera allows it, else the preset's own, else the locked one, which is Java's. A camera change
works it out again, and the client is sent it as an instruction whenever it changes.
`client.PlayerControl` acts on it from `KeyboardInputMixin`, which hands it each tick's movement, and
`MouseHandlerMixin`, which hands it each mouse turn; under the schemes that turn the camera the
camera keeps a turn of its own, which `PlayerCamera.apply` puts in place of the player's. Mojang's
table of the schemes each camera allows leaves out `minecraft:control_scheme_camera`, which allows
all five here, and publishes no rate for the side keys' turn under `player_relative`, which is half
a circle a second here. A script, an entity event, a timeline, a dialogue scene and a block hand their lines to
`PackCommand` directly, which runs each to its end in an execution context of its own with the
authority of a function, because the server's context runs a line queued during another command
only once that command is done. A new path that runs a pack's commands takes the issuing add-on's
namespace and a `CommandReport`, never a dispatcher directly, and a new Bedrock command goes into
`BedrockCommands`, never into Java's tree.

**Aim assist is picked on the client.** `server.PlayerAimAssists` keeps what `/aimassist` and a
script set, and sends the player an `AimAssistTargeting` (the settings, the preset and every category
it names, as `AddonAimAssist.targeting` resolves them) under `Channels.sendInstruction`, again whenever
the player's camera preset changes, because a camera preset's `aim_assist` overrides the command's
while it is set. `client.PlayerAimAssist` picks the target at the start of each client tick, and
`AimAssistPickMixin` lays it over every pick after it (`Minecraft.pick` from 26, `GameRenderer.pick`
before), where it becomes the hit result only within the player's reach. Ids are kept as
`identifierOf` reports them on both sides, so a preset naming either edition's spelling matches.
What Mojang does not publish, decided here: the cone reaches its X and Y angle either side of the way
the player faces, not across its whole width; a candidate is scored at the middle of its box; a
priority of 0 or below leaves a target out; a hand with no `hand_settings`, and an item with no
setting and no `default_item_settings`, use `minecraft:default`, which like `minecraft:aim_assist_default`
gives everything a priority of 1 and leaves nothing out; a target out of reach is marked but never
acted on. Before 26 the reticle is placed with the player's own field of view, which leaves out what
sprinting does to it.

**A server's identity is a key the client remembers.** `server.ServerIdentity` takes
`keys/server_identity_key.pem` in the game directory as the session's P-384 key when the server
starts, or generates one and keeps it in memory, and sends the public half to every joining client as
the `server_identity` instruction. Bedrock's own server documentation says this much and no more: the
key identifies the server to clients, a generated key makes players re-accept server trust, and the
server validates the key at start. It does not say what a server does with a key that fails that
check, so a bad file is logged at `ERROR` and a generated key used. It publishes no wording for the
client's question either, so `client.TrustedServers` asks in plain words of its own (the
`mcaddon.server_trust.*` keys in `en_us.json`), keeps the trusted keys by the address the player
typed in `mcaddon_trusted_servers.json` in the game directory, and asks once the world is up with no
other screen open. The key is one per process, so a dedicated server and a client in one process
(the client game tests) read the same `running()`. `ServerTrustClientGameTest` starts two servers on
one port with `Worlds.server(context, port)`, because every other server test takes a free port and
so a new address, which a client trusts without asking.

**A goal's waits are not counted in `tick()` calls.** The selector runs `canUse`, `canContinueToUse` and a running goal's `tick` only every second tick, unless the goal's `requiresUpdateEveryTick` is true. A wait a pack states in seconds is counted against `tickCount` or the game clock, or the goal asks for every tick when it also steers each one. `aGrazerEatsWhenItsTimeUntilEatIsUp` reads the first.

**A Java loot table's functions and conditions are written through `JavaLoot`.** 26.3 names both
types under `type`, holds functions under `modifier` and takes one `condition`, where older versions
use `function`, `condition`, `functions` and `conditions`. A table holding the other version's keys
loads with no error and with its functions and conditions dropped, so nothing else spells them.

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
build.named.gradle.kts       The 26.x targets, which Mojang ships unobfuscated
gradle/mod.gradle.kts        Shared build logic: toolchain, GraalJS bundling, checkstyle, checkTools
config/checkstyle/           Lint rules and the Stonecutter suppressions

tools/testpack/              The four add-ons the harness checks against, and the scripts building them
tools/verify/verify.py       End to end harness: drives a dev server and checks a conversion
tools/preview/render.py      Offline model renderer
tools/molang/MolangCheck.java  Molang conformance harness
tools/census/components.py   Coverage of Bedrock's published vocabulary, no pack and no game
tools/census/fields.py       The fields inside each entity component, against the ones read
tools/census/model.py        Coverage of the fields the geometry and animation readers act on
tools/census/commands.py     Coverage of Bedrock's published command dialect
tools/census/particles.py    Coverage of Bedrock's published particle format
tools/census/data.py         The same, for loot tables, recipes and trade tables
tools/census/packfiles.py    Coverage of the file kinds a pack may ship, against the ones read
tools/census/manifest.py     Coverage of the fields a manifest may carry, against the ones read
tools/census/declaration.py  Where a pack declares a component, against where the mod reads it
tools/census/ids.py          Which item and block ids Bedrock writes resolve to a Java one
tools/checkall.sh            Every check, in the order that fails fastest
tools/census/script.py       How much of the published script API this mod offers
tools/scene/scene.py         Scenes run inside the game, with nobody at the keyboard
tools/scene/zoom.py          One piece of a scene's picture, scaled up to look at

src/main/java/at/ioexception/mcaddon/
  pack/                      Discovery, manifests, dependency order
  convert/                   Runtime conversion: Targa, Ogg repair, sounds, text, models, functions
  resource/                  The converted packs and how the game is told about them
  registry/                  Sounds, entities, items, blocks, spawns, worldgen and the creative tab,
                             what is registered and what is active
  sync/                      The add-on hand-over at join: the manifest, what a server serves and
                             both ends of a transfer
  bedrock/camera/            What `/camera` and `/camerashake` set, the presets and splines a pack adds, and the
                             easing curves they name
  bedrock/fog/               A pack's `fogs/` settings, as far as Java's one fog can carry them
  bedrock/hud/               The pieces of the heads-up display `/hud` names
  bedrock/input/             The kinds of player input `/inputpermission` names
  bedrock/molang/            Molang parser, evaluator, maths. Trig is in degrees.
  bedrock/model/             Geometry parsing, baking, render controllers
  bedrock/animation/         Animations, channels, player, controllers
  bedrock/entity/            Entity definitions, the animator that runs them, the query answers
  bedrock/behaviour/         Entities, items, blocks, recipes, loot, trading, spawn rules, filters,
                             and BedrockUnits, where a Bedrock quantity crosses into a Java one
  script/                    GraalJS host, the bridge, pack-declared commands, coverage
  structure/                 Bedrock `.mcstructure` reading, whose NBT is little endian
  worldgen/                  The feature types and the placement modifier Java has none of
  entity/                    The one mob class every add-on entity uses
  block/                     The block class that carries a pack's own states
  server/                    Server lifecycle, `/bedrock` and the tree it reads a line against,
                             sensors, entity events, property sync, and the NPC dialogue an
                             add-on's own screen is opened from
  gametest/                  The Mojang game tests, reached through the fabric-gametest entrypoint
  client/render/             Renderer, attachable layer, pass resolution, what only a client answers
  client/screen/             The screen a Bedrock form becomes, the add-on list and pack settings
  mixin/                     Pack repository source, the attachable layer, the camera and the fog
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
  minecraft_server_admin.js  The @minecraft/server-admin surface: `config/` variables and secrets,
                             `dedicatedServer`, operators
```

**Every pack's script shares one copy of each module**, so `minecraft_server_admin.js` tells which
pack called by the first frame of the stack whose file sits under a pack's header uuid, which is
the folder `ScriptHost.load` puts a pack's scripts in. `script.ScriptConfig` maps that header uuid
to the script module uuid naming the pack's folder under `config/`. A game test server answers
`isDedicatedServer()` with true, so `dedicatedServer` is decided by the server's class instead.

`ScriptHost.MODULES` maps an import specifier to one of these files. A module is linked before any
of it runs, so a specifier a pack imports that is not in that map, or a named export missing from
one that is, ends that pack's whole script rather than the line that asked for it. Add the name
even when the mod cannot act on what the module offers.

**An entity type crosses the bridge under its Bedrock id.** `AddonEntities.identifierOf` is what
the surface reports (`typeId`, `EntityTypes`, event payloads, families) and `AddonEntities.typeOf`
is what it accepts (spawns, queries, filters), with `BedrockEntityNames` holding the vanilla ids
the two editions spell differently in both directions. Compare two names through `identifierOf`,
never the raw registry key, or `minecraft:villager` and `minecraft:villager_v2` read as two types.

**So does a damage cause.** `bedrock.behaviour.DamageCause` holds Bedrock's causes against the
Java damage types that are the same harm, in both directions, for the types the running version's
`DamageTypes` declares. A cause Java has no type for is dealt as its stand-in and hears what the
stand-in hears. Every place a pack names a cause asks it, and nothing compares a cause against
`DamageSource.getMsgId()`, which is a death message key several types share: `campfire` and
`in_fire` are both `inFire`. The field census reads `name(plain, "text"` as a field read, so a
constant's Java types are listed before its flags.

**So does an item and a block, everywhere a pack names one.** `AddonItems.named` and
`AddonBlocks.named` (or `AddonBlocks.stateOf`, where Bedrock's states pick the block) are what a
name from a pack becomes, and `AddonItems.identifierOf` and `AddonBlocks.identifierOf` are what the
game's own item or block state is reported and compared as. `BedrockItemNames` and
`BedrockBlockNames` hold the spellings in both directions, with `DataValues` for the families a data
value or a named state picks a member of. A Bedrock id may be a Java block in a state of its own, so
the way back starts from a `BlockState`: a lit furnace reports itself as `minecraft:lit_furnace`. A
command line is translated through `BedrockBlockNames.Resolved.command`, which writes only the
properties the pack named, because a test for a block asks about those and no others.
