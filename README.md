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
- Textures, including Targa, with the physically based sidecars a `pbr` pack ships left out.
- Sounds, with every definition registered as a sound event, and files whose final page claims a
  length no clip has repaired on the way through.
- Text, with Bedrock's `.lang` rules rather than a properties reader's.
- Models, animations, animation controllers and render controllers, drawn through a full Molang
  evaluator.
- Entities a behaviour pack declares, with their properties, events, component groups, filters and
  the sensors that fire them: environment, entity, timer, scheduler, damage, interact and
  hurt-on-condition. Their properties reach every client, so a model looks the same in multiplayer
  as it does alone.
- Items, including edible and wearable ones, spawn eggs, and a creative tab per add-on.
- Blocks, with the states and permutations a pack declares: each combination gets its own model and
  the permutation conditions are evaluated through the Molang evaluator.
- Trading, with Bedrock's tiers and groups flattened into Java's merchant screen.
- Recipes, loot tables, natural spawning, and `.mcfunction` files translated into Java's command
  dialect with a `tick` function tag.
- Attachables: what an add-on entity wears or holds, animating on its own clock.
- Behaviour pack scripts against `@minecraft/server`, sandboxed: a script sees its own pack's files
  and nothing else on disk. Commands a script registers on startup become real commands, and
  `@minecraft/server-ui` forms become real screens.

`/mcaddon packs` lists what loaded. `/mcaddon entities` and `/mcaddon scripts` list what a pack asked
for that this mod does not implement yet, which is the honest answer to "will my pack work".

## What does not work yet

Bedrock block geometry (a block gets a cube with the pack's textures, not its custom model), the
scoreboard and structure parts of `@minecraft/server`, game rules, and attachables on players rather
than on add-on entities. Bedrock's physically based rendering and its stencil material passes have
no Java equivalent at all.

## Supported versions

| Minecraft | Java |
| --- | --- |
| 1.20.1 | 21 |
| 1.21.1 | 21 |
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
