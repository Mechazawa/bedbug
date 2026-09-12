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
  their events: environment, entity, timer, scheduler, damage, interact and
  hurt-on-condition. Their properties reach every client, so a model looks the same in multiplayer
  as it does alone.
- Items, including edible and wearable ones, spawn eggs, and a creative tab per add-on.
- Blocks, with the states and permutations a pack declares: each combination gets its own model, the
  permutation conditions are evaluated through the Molang evaluator, and a block drawn with its own
  geometry keeps it.
- Trading, with Bedrock's tiers and groups flattened into Java's merchant screen.
- Recipes, loot tables, natural spawning, and `.mcfunction` files translated into Java's command
  dialect with a `tick` function tag.
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

## What does not work

**Four entity components with no Java counterpart.** `balloonable` describes an item Java does not
have. `managed_wandering_trader` describes how Bedrock runs its own trader. `behavior.take_flower`
and `behavior.celebrate_survive` are two moments Java writes into its villager's own brain, with no
general form a pack's entity could be given. Each is counted and named by `/mcaddon entities`, so a
pack relying on one is told rather than left wondering.

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

Surface lighting from a physically based texture set is the one thing that depends on what the
player has installed: the emissive channel is drawn again at full brightness with or without a
shader pack, while normal, roughness, metalness and subsurface need Iris or OptiFine with a labPBR
shader pack, because a forward renderer lit per vertex has nothing to feed them to.

Nothing else in a pack is deliberately left out. What a pack asks for that this mod cannot do is
counted and shown by `/mcaddon entities` and `/mcaddon scripts`.

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
