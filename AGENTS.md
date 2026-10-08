# Working on this repository

A Fabric mod that loads Bedrock `.mcaddon` files at runtime, built for four Minecraft versions from
one source tree. Read `README.md` first for what the mod does; this file is about how to change it.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # macOS; Gradle has to run on Java 25 or newer
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk   # the Linux equivalent; the tools read it too

./gradlew build                 # all four versions, plus checkstyle and the tool parse gate
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

These are the checks it runs, and what each one catches:

- **`./gradlew build`** compiles all four versions and runs every test on each of them: the unit
  tests for the checked-out form, and the game tests for all four. The game test task builds
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
  `src/main/java/at/ioexception/mcaddon/gametest/`, headless, against a real world. `check` depends
  on it, so a failing one fails `./gradlew build`, and it is gated to the checked-out version like
  everything else. What it covers is the half a unit test cannot reach: that `/playanimation` and
  `/event entity` still parse and run, that an entity event no pack declares is answered instead of
  thrown, that delivering a tick's data-driven triggers ends, that a script failing inside the
  tick costs the tick rather than the server, and that a converted dimension and its dimension type
  decode through this version's own codecs, whose field lists differ on each of the four.

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
    colour, texture point, light), and each item with the pose it was hung at, its light and its
    tints. The same collector takes an item's own render state, which is how an icon is asserted.
  - `client/render/ScriptClock` builds the animator the renderer builds for an entity and moves it
    on a tick at a time, so a bone's pose can be asked for at an exact moment instead of at whatever
    moment a frame happened to land on.
  - `clientgametest/Born` and `clientgametest/Heard` write down every pack particle the client adds
    and every sound it is asked to play, through the test mod's own mixins in
    `mcaddon-gametest.mixins.json`. A particle is kept with its velocity and lifetime as it was
    before its first tick, and can be drawn into a recording quad state to read its size, its turn
    and its colour. A sound is recorded as the request rather than the playback, because a test
    client may have no audio device and the engine drops a sound before any listener hears it then.

  `RenderControllerClientGameTest`, `AnimationClientGameTest`, `AttachableClientGameTest`,
  `CullingClientGameTest`, `ParticleClientGameTest`, `FogClientGameTest`, `CameraClientGameTest` and
  `ItemAndBlockClientGameTest` are the drawing side's tests; each class's docblock names the pack
  content it reads and what it asserts. Two things about the machine the tests run on: a window
  hidden by the compositor can stall the render thread inside `glfwSwapBuffers` until the server's
  watchdog ends the run, which reads as a hang in whatever test was running, and a dedicated server
  test fails at once when another game on the same machine holds port 25565.

  Only the 26.x targets ship that API, so the tests are a source set of their own (`gametest`, with
  its own `fabric.mod.json` listing them) that the two older targets never compile, and `check`
  runs them for the checked-out version only. The two 26.x test APIs differ in a few spellings,
  which `Worlds` holds. A world built with the builder's consistent settings opens in survival on
  peaceful, so a test that needs the creative inventory, or a mob reacting to the player, sets the
  game mode and the difficulty itself: Java's avoid and target goals ignore a creative player and
  every player on peaceful.

  `-Paddons=<directory>` installs every `.mcaddon` in that directory beside the synthetic one, for
  either game test task, so the same tests can be run against a real pack that may never be
  committed here. Both tasks delete their world before running, because each run of the server
  tests places them somewhere new and a kept world only grows.

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
  - **Fabric registers a biome its modification API changed again, without its pack's name.** A
    biome a feature rule or a spawn rule reached carries no `KnownPack` afterwards, though it stays
    stable, and baking a world's dimensions registers every level stem again the same way. Ask a
    placed feature or a dimension type which pack it came from, not a biome or a level stem.
  - **The client game test task deletes its run directory**, so every run converts the pack afresh.
    A defect that only shows on a cached conversion has to be asserted by `runGameTest`, whose
    `.mcaddon/` survives between runs.

  **Multiplayer is asserted against a dedicated server.** The three `Server*ClientGameTest`s call
  `worldBuilder().createServer()` and `connect()`, so the client reaches the server over a real TCP
  connection on which every message is encoded, which a single player world's in-memory connection
  never does. `ServerRegistryClientGameTest` compares every add-on entity type, item, block, sound
  event and biome by number on both sides and sends an item stack and a block state across;
  `ServerEntityClientGameTest` follows a wisp's type, a `client_sync` property, a component group's
  box and drawn size, a wisp settled before the client could see it, and `/playanimation` on a wisp
  and a villager; `ServerPlayerClientGameTest` adds a second player, `Bystander`, and asserts a form,
  `/camera`, `/camerashake` and `/fog` reach the player they name and nobody else, that a form's
  answer counts only from its own player, and that the test pack's eight hundred button catalogue
  arrives whole.

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
  off its timeline, three `particles/*.json` in the 1.10.0 format, a `fogs/` file whose air and
  water blocks are written differently from each other, a texture set whose emissive
  channel has pixels in it, a glyph page beside the locale list and the locale names a pack declares,
  and a function using the commands and selector arguments that need translating.

  The hands-on client tests use a few things of their own. `tp:berry` is food eaten in 0.8 seconds,
  half Java's default, and `tp:pebble` throws `tp:spark`. `tp:totem` is a block with a
  `minecraft:placement_direction` trait turned by a `y_rotation_offset`, a use counter driven by
  `minecraft:on_interact`, a script component (`tp:tapped`, registered by the pack's script) that
  tags whoever uses it, half-high boxes a permutation makes whole on the third use, and a loot
  table of its own, against `tp:lamp`, which names none and so drops itself.

  A third entity, `tp:wisp`, states the numbers whose unit differs between the two editions: a health
  `value` below its `max`, a goal priority that is neither the first nor the last of its goals, an
  anger duration in seconds with an event at the end of it, a teleport cube in blocks beside a
  teleport interval in seconds, and an experience reward written as Molang rather than as a number.

  The sprite's own texture carries one thing a count cannot see. Its top four rows are written at an
  alpha of three, which is how Bedrock marks a texel its alpha test draws and Java's cutout shader
  discards, and the game test `aMarkedTexelIsConvertedOpaque` reads the converted file back to say
  the conversion raised it. A regression there costs an entity its markings and leaves it drawn in
  whatever is left of its texture, which no count here would report.

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
  a damage sensor refusing the blow and raising an event about it. The box is the one that proves a
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
  twice the size and lit by nothing around it. Beside it is the pack's one attachable, whose root
  bone binds with `q.item_slot_to_bone_name(c.item_slot)`, which is the spelling every binding Mojang
  publishes uses and the one that needs `context.item_slot` answered. The warden shares the sprite's
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
  air with a gap under it.

  `tp:prism` exists to be drawn. Its one render controller picks everything about its one pass by
  Molang over the entity: the geometry from `q.is_baby` and an enum property, the texture from an
  array indexed by `q.variant`, the material from `q.had_component_group` between
  `entity_alphatest` and `entity_alphablend`, the mark bone's visibility from `q.mark_variant`, a
  texture slide and a colour from the variant, an overlay from the mark variant and an
  `on_fire_color`, and one event per group or property switches one of those. Its three geometries
  differ in how far the core reaches from the middle, so which one was drawn reads off the vertices
  whichever way it faces. Its skin carries a texture set whose emissive channel glows over the mark,
  so a second, lit pass is drawn for the plain skin and none for the dyed one. Its script runs
  cubeless bones through each timing rule a clip has: a length past its last keyframe, its own
  `anim_time_update`, `hold_on_last_frame`, a blend value in the animate list and another in a
  controller state, a one second crossing out of a state declaring `blend_via_shortest_path` from
  350 degrees to 10, and a state that plays a clip once and moves on once its clips have all
  finished. It enables attachables, hides armour and hides what it holds while its mark variant is
  one, so the visor is drawn in its hand and never on its head.

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

  The worldgen side is three biomes, five features and two rules. The overworld biome, `tp:glade`,
  carries the components a Java biome has a field for and four it has none of, so both halves of the
  reporting path run; Java's overworld has no list a biome can be added to, so it is registered and
  named rather than generated. The two nether biomes, `tp:cinder_wastes` and `tp:soot_fields`, are
  added to the list the nether searches and are the ones a player can stand in. The features cover
  the three kinds that convert and one of the six with nothing behind them, and one of them wraps
  another in a scatter, which is what makes the placement chain more than one link. One rule hangs
  an ore on every overworld biome and the other hangs flowers on the forests, so a fresh chunk holds
  blocks that can be counted. **The ore places `tp:lamp`, which is the pack's own block**: a run
  where the block registry and the worldgen registry disagree leaves nothing in the ground. The
  flowers are mostly torchflowers, which no feature of the game's places anywhere, and the scatter
  wrapping them states its height as `query.heightmap(variable.worldx, variable.worldz)`, the
  surface query nearly every pack scattering something over the ground writes.
  `PackFeatureClientGameTest` counts the lamps in the chunks round a normal world's spawn, every one
  between the rule's heights, and the torchflowers in a forest, every one standing in a forest.

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
  `/scriptevent "tp:hut" <x> <y> <z>` has the script place it through `world.structureManager`, and
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
  `World.getPackSettings` are asserted against. Both manifests also state the fields nothing here
  acts on, a capability among them, so a run says the reporting half of the manifest reader still
  works.

  The book table is the one a game test rolls: it holds a single entry carrying
  `enchant_book_for_trading` and no condition, so the loot function type this mod registers is
  exercised from the converted table through the codec to the enchanted book that comes back.

  The behaviour pack's one script is a two-page handbook, opened by `/scriptevent "tp:handbook"`
  run as a player. It is a single 2.x `CustomForm` holding both pages, each page's components shown
  through an `ObservableBoolean` and the title an `ObservableString`, with buttons whose handlers
  turn the page by setting those observables, one handler calling `form.close()` and a
  `closeButton`. A tag on the player names how `show` resolved. The client game test
  `FormClientGameTest`, run by `./gradlew :26.2:runClientGameTest` and part of `check`, opens it,
  turns the page and asserts the same screen now draws the second page and not the first, then
  closes it both ways and reads the tag back: a form whose button press closed the screen, or whose
  observables reached the screen only once, fails there. The same script answers
  `/scriptevent "tp:catalogue"` with an `ActionFormData` of eight hundred buttons, whose description
  is longer than the 32767 characters a plain network string carries.

  `animation.tp.wisp.lift` raises the wisp's one bone by four pixels for two seconds, which is what
  `/playanimation` on an add-on entity is measured by.

  The same script watches the game's own villagers the way a pack replacing them does, in Bedrock's
  ids throughout: it compares `typeId` against `minecraft:villager_v2` and `minecraft:xp_orb` as
  each entity spawns, queries the overworld for `minecraft:villager_v2` every tick and spawns a
  sprite it then marks beside each one found, hears `entityHurt` through listeners narrowed to a
  villager and to a zombie, and answers `/scriptevent "tp:villager"` with
  `spawnEntity("minecraft:villager_v2")`. Every answer is a tag, which `VanillaNamesClientGameTest`
  reads back after summoning a Java villager and an experience orb: a surface reporting or accepting
  only Java's ids, a listener whose options are ignored, or an `entitySpawn` raised before
  `spawnEntity` has returned each fails there.

  Every command line in the pack that runs while the game is going is written so Java's parser
  refuses it as it stands. On `/scriptevent "tp:commands"` the script runs, as whatever sent it, a
  selector asking with `hasitem` about the main hand and about everything carried, a selector naming
  `villager_v2`, `function tp/relay` by its path alone (which calls `tp/relayed` the same way inside
  the file) and `hud`, which nothing translates. The ember's `tp:ember_command` event tags it through
  a Bedrock radius and asks for `hud` as well, and its controller's command tags it through a Bedrock
  radius. `CommandGameTests` reads each of those back as a tag, and finds `hud` among what
  `/mcaddon scripts` and `/mcaddon entities` say cannot be translated rather than among what did not
  parse. The dialogue's first button runs `xp 1L`, and `/scriptevent "tp:stock_up"` gives the player
  `tp:dust` unless `hasitem` finds one on them; `PackCommandClientGameTest` presses the button and
  asks for the dust twice, and reads back one level gained and one dust carried.

  Four more things name the game's own items and blocks by Bedrock's ids, each of which Java's
  parser refuses as written and refuses whole: `loot_tables/names.json` drops `golden_rail`,
  `muttonRaw` and `wool` with a data value of fourteen, `recipes/lantern_from_rail.json` crafts a
  `lit_pumpkin` out of a `golden_rail`, `functions/names.mcfunction` gives `record_13` and places a
  `golden_rail` and a `wool` written with `["color"="red"]`, and `/scriptevent "tp:names" <x> <y> <z>`
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
  tamed, tags whatever a player tamed, and tags a player crouching and standing up again.
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
  python3 tools/verify/verify.py all                     # conversion, server, client and join per version
  python3 tools/verify/verify.py run --version 26.2      # a server, one version
  python3 tools/verify/verify.py client --version 1.21.1 # a client, one version
  python3 tools/verify/verify.py join --version 26.2     # a client connected to a server
  python3 tools/verify/verify.py convert --source <extracted pack directory>   # no game
  python3 tools/verify/verify.py companions --version 26.2  # a server and a client beside other mods
  ```

  A green `run` proves the mod started, every mixin applied, the packs converted and registered, the
  server reached `Done (`, and no pack script hit a missing `@minecraft/server` member while the
  pack's own entities were summoned and killed. It also types `gametest runall`, which is why the
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
    own: `KnownPack("mcaddon", <the manifest header uuid>, <the pack version> + "+" +
    McAddon.VERSION)`. The uuid is what two installations of one add-on share; the version separates
    two releases, and carries the mod's version because the bytes in a converted pack are a function
    of the converter as much as of the pack. Built with that `KnownPack` taken out again, one
    converted `jukebox_song` is enough to make the world experimental.
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
  start there and their properties are empty. 26.1.2 and 26.2 need no remapping and carry the
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

- **`texture_meshes`** on a bone extrudes geometry from a texture's opaque pixels, placed by a
  `local_pivot`, a `position` and a `rotation`. Java performs the same construction in
  `ItemModelGenerator` for a flat item model, so what is absent is a reader rather than a Java form:
  the geometry pipeline here bakes boxes out of JSON and never opens an image. All eleven published
  declarations are item attachables, the bow and the crossbow among them.
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
- **`parent_setup` and `binding`** are reported only for the wearer they cannot reach. An attachable
  writes the first into whoever is carrying it and hangs itself off the bone the second names, which
  both work for a pack's own entity and have nowhere to land on one Java draws with a model of its
  own, since such a model has parts rather than bones with no two naming theirs alike.

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

**A clip played on a mob Java draws moves Java's model of it.** `/playanimation` on a vanilla
villager names the bones of Bedrock's own villager geometry, so `JavaModelPoses` runs the clip on an
animator of its own and `JavaModelParts` finds each bone among the model's parts: the same name
compared without case or underscores first, then the short `ALIASES` table for what Bedrock numbers
and Java names (`leg0` to `leg7`, a cat's `backLegL`, the villagers' `helmet` and `brim`), then
`root` for the model's root. The pose goes on after the model's own `setupAnim` and comes off once
that draw is done, because one model draws every entity of its kind. From 1.21.9 the body is posed
again where it is finally drawn, so `ModelFeatureRendererMixin` lays the clip on there and
`LivingEntityRendererMixin` lays it on for the layers; before that `render` does both in one call.
A bone with no part is reported once per kind of entity. Nothing is stored on the server, so a
player who starts watching later sees whatever the pack plays next, as on an add-on entity, and
`next_state` is not acted on for either. `JavaModelPoseClientGameTest` reads the posed parts back.
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
- **`tools/census/packfiles.py`** measures the file kinds themselves. Every other census here is
  about the vocabulary inside a file kind the mod already reads, and none of them can see a whole
  directory an add-on ships that nothing opens, because a directory nobody reads asks no questions.

  ```bash
  python3 tools/census/packfiles.py            # the table, then what is reported and what is silent
  python3 tools/census/packfiles.py --strict   # exits non-zero while a pack kind goes unnamed
  ```

  The published side is Mojang's own pack pair, widened by the material in the same checkout naming a
  kind that pair happens not to ship. The mod's side is the `READ` and `DEFINED` declarations in
  `pack/PackFiles.java`, and the difference between them is what `PackFiles.report` names against the
  pack that shipped it.

  **The silent column is the one that has to stay at zero.** A kind in `DEFINED` and not in `READ` is
  reported to the author; a kind in neither is a directory the mod passes over without a word. The
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

**A command a pack runs goes through `server.PackCommand`.** A script, an entity event, a timeline,
a dialogue scene and a block all hand over Bedrock's dialect, which `Functions.line` translates
exactly as it does a function file's line, as the add-on that wrote it so a bare function name
reaches that add-on's function. `FunctionNames` is what a bare name is looked up in: every add-on's
functions, the caller's first. The line then runs in an execution context of its own, because from
1.20.3 the dispatcher alone refuses a `function` and the server's context runs a line queued during
another command only once that command is done. A new path that runs a pack's commands takes the
issuing add-on's namespace and a `CommandReport`, never the dispatcher directly.

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
  registry/                  Sounds, entities, items, blocks, spawns, worldgen and the creative tab
  bedrock/camera/            What `/camera` and `/camerashake` set, and the easing curves they name
  bedrock/fog/               A pack's `fogs/` settings, as far as Java's one fog can carry them
  bedrock/molang/            Molang parser, evaluator, maths. Trig is in degrees.
  bedrock/model/             Geometry parsing, baking, render controllers
  bedrock/animation/         Animations, channels, player, controllers
  bedrock/entity/            Entity definitions, the animator that runs them, the query answers
  bedrock/behaviour/         Entities, items, blocks, recipes, loot, trading, spawn rules, filters,
                             and BedrockUnits, where a Bedrock quantity crosses into a Java one
  script/                    GraalJS host, the bridge, pack-declared commands, coverage
  structure/                 Bedrock `.mcstructure` reading, whose NBT is little endian
  entity/                    The one mob class every add-on entity uses
  block/                     The block class that carries a pack's own states
  server/                    Server lifecycle, commands, sensors, entity events, property sync,
                             and the NPC dialogue an add-on's own screen is opened from
  gametest/                  The Mojang game tests, reached through the fabric-gametest entrypoint
  client/render/             Renderer, attachable layer, pass resolution, what only a client answers
  client/screen/             The screen a Bedrock form becomes
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
```

`ScriptHost.MODULES` maps an import specifier to one of these files. A module is linked before any
of it runs, so a specifier a pack imports that is not in that map, or a named export missing from
one that is, ends that pack's whole script rather than the line that asked for it. Add the name
even when the mod cannot act on what the module offers.

**An entity type crosses the bridge under its Bedrock id.** `AddonEntities.identifierOf` is what
the surface reports (`typeId`, `EntityTypes`, event payloads, families) and `AddonEntities.typeOf`
is what it accepts (spawns, queries, filters), with `BedrockEntityNames` holding the vanilla ids
the two editions spell differently in both directions. Compare two names through `identifierOf`,
never the raw registry key, or `minecraft:villager` and `minecraft:villager_v2` read as two types.

**So does an item and a block, everywhere a pack names one.** `AddonItems.named` and
`AddonBlocks.named` (or `AddonBlocks.stateOf`, where Bedrock's states pick the block) are what a
name from a pack becomes, and `AddonItems.identifierOf` and `AddonBlocks.identifierOf` are what the
game's own item or block state is reported and compared as. `BedrockItemNames` and
`BedrockBlockNames` hold the spellings in both directions, with `DataValues` for the families a data
value or a named state picks a member of. A Bedrock id may be a Java block in a state of its own, so
the way back starts from a `BlockState`: a lit furnace reports itself as `minecraft:lit_furnace`. A
command line is translated through `BedrockBlockNames.Resolved.command`, which writes only the
properties the pack named, because a test for a block asks about those and no others.
