# Scripts, forms and dialogue

## Scripts

Behaviour pack scripts run against a Java implementation of `@minecraft/server`.

- A script sees its own pack's files and nothing else on disk.
- The world, system, entity, dimension, block, scoreboard, game rule and structure APIs are answered
  by the game.
- Commands a script registers at startup run through `/bedrock`.
- Other modules: `@minecraft/server-ui`, `@minecraft/server-gametest`, `@minecraft/server-admin`
  (see [Servers](servers.md#script-configuration)), `@minecraft/math`, `@minecraft/common` and
  `@minecraft/vanilla-data`. The vanilla data tables come from the running game's registries.

### Bedrock ids

Vanilla mobs, items and blocks reach a script under Bedrock's ids. A Java villager's `typeId` is
`minecraft:villager_v2`, a powered rail is `minecraft:golden_rail`, a lit furnace is
`minecraft:lit_furnace`. Queries, spawns, permutations, item stacks and filters accept Bedrock's and
Java's spelling.

A Java villager also gives Bedrock's states:

- `minecraft:variant`: its profession
- `minecraft:mark_variant`: its biome
- `minecraft:skin_id`: a face, worked out from its UUID
- Taking a job fires the `minecraft:become_*` event.

### Events

- A listener's options filter what it receives, as in Bedrock: entities, entity types, entity
  filter, event ids, damage and heal causes.
- `entitySpawn` fires on the tick after an entity arrives and `entityLoad` when one is read back from
  storage, never both for one arrival. A script that spawns an
  entity and marks it has done both before a listener runs.
- World events come from the game:
  - a player breaking, placing, using, picking up and swinging, moving and changing held item
  - weather and game rule changes
  - explosions and each block they take
  - pistons, pressure plates, tripwires, target blocks, buttons and levers
  - containers opening and closing on blocks and on entities
  - a player crouching and standing up
  - taming of wolves, cats, parrots, horses and pack entities
- Explosions and taming have before events, so a listener can cancel them. The blocks an explosion
  lists are the ones still standing.

### Other surfaces

- A player's fog stack is the one `/fog` uses. A camera shake is the one `/camerashake` sends.
- A dimension clones a region with Java's `/clone`. A filter picks blocks one at a time.

## Forms

`@minecraft/server-ui` forms open as screens.

- A 2.x `CustomForm` stays open while its button handlers run, and redraws when its observables
  change.
- A form taller than the window scrolls under its title.
- A resource pack can lay out forms in `ui/server_form.json`. See
  [Rendering](rendering.md#interface-files).

## NPC dialogue

A scene in `dialogue/` has a name, text and buttons. It opens in the same screen as a form.

- Each button runs its commands as the NPC, with function authority. `@initiator` is the player
  reading the screen.
- The scene's open and close commands run before and after.
- `/bedrock dialogue change` and `dialogue open` set and show scenes. Using an NPC with a scene opens
  it.

## Developer tools

Through `/bedrock script`:

- `debugger listen <port>` and `debugger connect [host] [port]` attach Mojang's Minecraft Bedrock
  Edition Debugger for Visual Studio Code, in either mode, with the same `launch.json`. Breakpoints,
  stop on uncaught exceptions, stepping, call stack, scopes, variables, debug console, command box,
  profiler buttons and live diagnostics work. A breakpoint holds the server thread. The debugger
  listens on the loopback address only.
- `profiler start` and `profiler stop` write a `.cpuprofile` under `logs/profiles/`.
- `diagnostics startcapture` and `stopcapture` write per tick statistics to an `.mcstats` file under
  `logs/diagnostics/`.

## Game tests

A behaviour pack's `@minecraft/server-gametest` tests run through `/bedrock gametest`.

- `run <name>`, `runset <tag>` and `runall` build the test's structure next to you, call the test
  and report the result. `clearall` removes the areas.
- `run` with a repeat count runs the test again, and can stop at the first failure.
- `runsetuntilfail` repeats a set in the same areas until a test fails.
- `create <name>` lays out a test: a structure block over the area, a floor of polished andesite on
  dirt, and a command block with a button.
- `runthis` runs the test of the nearest structure block within 15 blocks. `runthese` runs every one
  within 200. `pos` gives your position in the nearest test area.
- A test is always built unrotated. Rotation steps are refused.

## What does not work

`/mcaddon scripts` reports each of these:

- `Test.spawnSimulatedPlayer` and all of `SimulatedPlayer`. Repeated or rotated runs of one test.
- `world.primitiveShapesManager` and its shapes.
- `Player.locatorBar` and all of `Waypoint`, `EntityWaypoint`, `LocationWaypoint` and
  `PlayerWaypoint`.
- The data members of errors: `BookError.reason`, `InvalidEntityError.id`,
  `InvalidItemStackError.itemType` and the `reason` of the container, command and namespace errors.
  The error classes exist, so a pack that catches one by name still loads.
- Sound durations: `durationInfo`, `SoundDefinitionRegistry`, `SoundDurationInfo`,
  `world.afterEvents.soundCompleted` and `SoundInstance.stop`.
- `ItemBlockDynamicPropertiesComponent`.
- The events `entityUpgrade`, `playerEmote`, `playerInputModeChange` and `soundCompleted` never fire.
  Subscribing to them works.
