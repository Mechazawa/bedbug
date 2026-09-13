# Bedrock add-ons on Fabric

Drop a Bedrock `.mcaddon` into your instance's `addons/` folder and play it in Java Edition.

The mod finds the packs at startup, converts what Java can read, registers what has to be in a
registry, and runs the behaviour pack's JavaScript against a Java implementation of
`@minecraft/server`.

## Installing

Put the jar in `mods/`, then put your packs in `addons/`:

```
.minecraft/
  mods/mcaddon-<version>.jar
  addons/My Add-On.mcaddon
```

`.mcaddon`, `.mcpack` and already unpacked pack directories all work. Converted output is cached in
`.mcaddon/` next to `addons/`; deleting that directory only costs one slower start.

On a server, install the mod on the server and on every client, and give both the same packs. The
server decides what happens; the client draws it.

## What works

- Finding, unpacking and ordering packs, with their dependencies respected and their problems
  reported rather than swallowed.
- Textures, including Targa. A `pbr` pack's texture sets are rewritten as labPBR sidecars, which is
  what Iris and OptiFine shader packs read, so a physically based add-on lights correctly under any
  shader pack and costs nothing when there is none.
- Sounds, with every definition registered as a sound event, and files whose final page claims a
  length no clip has repaired on the way through.
- Text, with Bedrock's `.lang` rules rather than a properties reader's.
- Models, animations, animation controllers and render controllers, drawn through a full Molang
  evaluator, with each pass drawn the way its `.material` asks: alpha tested or blended, culled or
  not, plain or glowing.
- Entities a behaviour pack declares, with their properties, events, component groups, filters,
  breeding, riding, leashing, home ranges, navigation and item pickup, and the sensors that fire
  their events: environment, entity, target-nearby, block, timer, scheduler, drying-out, damage,
  interact, spawn-entity and hurt-on-condition. Their properties reach every client, so a model
  looks the same in multiplayer as it does alone.
- Items, including edible and wearable ones, spawn eggs, and a creative tab per add-on. A dyeable
  item takes the colour the pack painted it and the one a player dyes it. A bow draws and fires
  whatever the pack loads it with. A bag holds other items. A drop the pack asks to keep lies there.
- The custom components a pack registers from its own script, on its own blocks and items: placing,
  breaking, clicking, standing on and ticking each reach the pack's own callback.
- Blocks, with the states and permutations a pack declares: each combination gets its own model, the
  permutation conditions are evaluated through the Molang evaluator, and a block drawn with its own
  geometry keeps it.
- The events a block declares, fired the way Bedrock fires them: on its own clock, on a random tick,
  and on the block being placed, broken, clicked, stepped on, stepped off or landed on. Each one runs
  the commands the event queues and writes back the states it sets, with its Molang condition checked
  against the block standing in the world. A block that says it holds water is waterlogged, one with
  a placement filter only survives where the filter allows, one that produces redstone powers what it
  touches, and one a pack marks pottable gets the second block a flower pot needs.
- Trading, with Bedrock's tiers and groups flattened into Java's merchant screen.
- Recipes, loot tables, and `.mcfunction` files translated into Java's command dialect with a `tick`
  function tag.
- Natural spawning from a pack's `spawn_rules`, with each alternative the rule lists becoming a
  biome spawn entry of its own. The weight, the group size and the biome tags decide which biomes
  it is offered in; the brightness, height, difficulty, world age, distance, density, delay, the
  surface and underwater and lava markers and the blocks it may stand on are asked again of the
  spot the game is trying, so a mob that a pack said belongs on sand at night stays there.
- Attachables: what any entity wears or holds, players and vanilla mobs included, animating on its
  own clock.
- The sounds and particles an animation, an animation controller state or an entity's own definition
  fires, played at the bone the pack anchored them to.
- Particle effects a pack describes itself: the emitter runs on its own clock at the rate and
  lifetime the pack chose, its shape decides where each particle is born and which way it leaves, and
  the particle's Molang drives its motion, size, spin, colour gradient and flipbook. Collision, the
  kill plane and the block filters are honoured. A Bedrock id naming a vanilla effect is shown as the
  Java one instead.
- Behaviour pack scripts against `@minecraft/server`, sandboxed: a script sees its own pack's files
  and nothing else on disk. Commands a script registers on startup become real commands,
  `@minecraft/server-ui` forms become real screens, and the world, system, entity, dimension, block,
  scoreboard, game rule and structure surfaces are answered by the game.
- The tests a behaviour pack ships through `@minecraft/server-gametest`. `/gametest run <name>`,
  `/gametest runset <tag>` and `/gametest runall` build the structure a test named out of the pack's
  own `.mcstructure`, place it beside you, hand the pack's function a `Test` bound to that area and
  report what it decided. `/gametest clearall` takes the areas back.
- The other modules a pack may import: `@minecraft/math`, `@minecraft/common`, and
  `@minecraft/vanilla-data`, whose tables of vanilla ids are read from this game's own registries so
  they hold what the version you play ships.

`/mcaddon packs` lists what loaded. `/mcaddon entities` and `/mcaddon scripts` list what a pack asked
for that this mod does not implement yet, which answers "will my pack work" with evidence.

## How much of Bedrock this is

Measured against Mojang's own published vocabulary rather than against whatever pack happens to be
installed. `tools/census/components.py` reads the entity, block and item component schemas, the
filter documentation and the Molang query list out of Mojang's `bedrock-samples`, and
`tools/census/script.py` reads the published `@minecraft/*` type definitions. Both compare them
against the names this mod acts on:

| Surface | Answered |
| --- | --- |
| Entity components | 93% |
| Entity AI goals | 99% |
| Entity filters | 100% |
| Molang queries | 91% |
| Molang maths | 100% |
| Block components | 92% |
| Item components | 80% |
| `@minecraft/server` and friends | 95% |

Every name in the remaining share is counted and shown by `/mcaddon entities` and `/mcaddon
scripts`, so a pack relying on one is told rather than left wondering. Run the censuses yourself to
get the current figures; the ones above are a snapshot.

## What does not work

**What no add-on entity can be.** A pack's entity is a Java mob, which fixes what it can become.
`minecraft:projectile` describes a thing that flies and hits, `rail_movement` and `rail_sensor`
describe a minecart, and the `player.*` components describe a player; none of the three is a mob.
The memory components describe Java's brain, which its own mobs have and a data-driven one does not.
Aim assist, pack settings and the device memory tiers describe a Bedrock client rather than a world.
`minecraft:exhaustion_values` prices a player's food bar, which a mob has none of, and
`minecraft:bucketable` asks for the entity in a bucket, which Java keeps as an item made for that one
mob. `minecraft:custom_hit_test` gives an entity hit boxes apart from the one it collides in, and Java
has the one box for both. Each is counted and named rather than silently doing nothing.

**A record plays nothing.** `minecraft:record` needs a jukebox song, and a song only exists as an
entry in a registry a data pack writes into. A world holding such a pack is one the game calls
experimental, and it then opens behind a screen asking whether to load it at all, every time. One
item component does not pay for that, so a record is registered as a plain item and counted as a
gap.

**The cause a block event names for its damage.** Bedrock picks from its own list of damage causes
and Java builds a damage source out of what dealt the blow, so a block that hurts whoever set it off
hurts them for the right amount by a source that names no cause. Every other response a block event
can give runs: a command, a block state, dying, a sound, a particle, an effect added or taken away,
a teleport, a swing, a loot table, a spent or swapped held item, and a block replaced here or one
step away.

**One ender dragon phase.** `behavior.dragondeath` is the animation a dragon plays while it dies.
Java stops running a mob's goals the moment its health reaches zero, so a goal has no tick left to
play anything in. The dragon's other seven phases are flown, and `/mcaddon entities` names this one.

**Stencil-masked material passes.** A material's alpha testing, blending, culling and emissiveness
all become the render type the pass is drawn with. What cannot follow is the stencil buffer itself:
a pass that exists only to write a mask is left out rather than drawn as a shape that does not
belong there.

**A handful of Bedrock commands.** `fog`, `camera`, `camerashake`, `hud`, `dialogue`,
`inputpermission`, `immutableworld`, `wsserver` and `connect` name a renderer feature Java has none
of, or a server Java does not speak to. A function using one keeps its other lines and the dropped
command is reported. `testfor`, `testforblock`, `testforblocks` and `titleraw` are translated;
`playanimation`, `event`, `scriptevent`, `music`, `toggledownfall`, `structure`, `mobevent` and
`gametest` are registered by this mod.

**The player a test drives itself.** `Test.spawnSimulatedPlayer` and every member of the
`SimulatedPlayer` it answers with describe a player the game walks, attacks and builds with. Java's
own stand-in player lives inside its test framework and carries none of that vocabulary, so each of
those members is counted by `/mcaddon scripts` instead of doing nothing in silence. A test that
spawns mobs, places blocks and asserts against the world runs. Running one test at several rotations
or several times over is counted the same way: a run places one structure and calls the test once.

**The parts of the script API that draw a Bedrock client.** Aim assist is how a controller and a
touchscreen pick what a player is pointing at, the primitive shapes are text and boxes drawn over the
world by the Bedrock renderer, and the locator bar and its waypoints are a Bedrock heads-up display.
Java has none of the three, so every member of each is answered by `/mcaddon scripts` rather than
quietly doing nothing. The same holds for `world.getPackSettings`, which reads a screen only Bedrock
shows.

**Four spawn rule conditions with nothing to ask in Java.** `minecraft:permute_type` swaps the
entity that spawns for another one after the biome list has already chosen a type.
`minecraft:mob_event_filter` waits on one of Bedrock's own world events, `minecraft:is_experimental`
on a toggle Java has no form of, and `minecraft:player_in_village_filter` measures a distance to a
village border that Java's poi map does not hold. Each is counted and named by `/mcaddon entities`.
A Bedrock biome tag with no Java biome carrying the same idea, `sulfur_caves` and the `spawn_*`
nether tags among them, is named the same way and then left out of the filter, so the rest of the
rule still decides.

Surface lighting from a physically based texture set is the one thing that depends on what the
player has installed: the emissive channel is drawn again at full brightness with or without a
shader pack, while normal, roughness, metalness and subsurface need Iris or OptiFine with a labPBR
shader pack, because a forward renderer lit per vertex has nothing to feed them to.

Nothing else in a pack is deliberately left out, and what is left is a matter of how far the work
has got rather than of what was decided. What a pack asks for that this mod cannot do is counted and
shown by `/mcaddon entities` and `/mcaddon scripts`.

## Supported versions

| Minecraft | Java |
| --- | --- |
| 1.20.1 | 21 |
| 1.21.1 | 21 |
| 26.1.2 | 25 |
| 26.2 | 25 |

These are anchors, not a range. Minecraft rewrote entity rendering in 1.21.2 and again in 1.21.5, so
a jar built against 1.21.1 will not load on 1.21.4.

1.20.1 shipped against Java 17, but the script engine needs 21 and the game runs on 21 without
complaint.

## Building

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
./gradlew build
```

Jars land in `versions/<minecraft>/build/libs/`. `AGENTS.md` covers the rest.

## Add-on content is not in this repository

No textures, sounds, models or text from any pack are committed here. Bring your own `.mcaddon`.
