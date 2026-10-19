# Bedbug

Play Bedrock add-ons in Minecraft Java Edition. Drop a `.mcaddon` into `addons/` and Bedbug loads its
entities, items, blocks, scripts and world generation at startup.

## Install

You need Fabric Loader and Fabric API.

1. Put the Bedbug jar in `mods/`.
2. Put your add-ons in `addons/`. `.mcaddon`, `.mcpack` and unpacked pack folders all work.

```
.minecraft/
  mods/mcaddon-<version>.jar
  addons/My Add-On.mcaddon
```

Bedbug caches converted packs in `.mcaddon/`. Deleting that folder costs one slower start.

| Minecraft | Java | Status |
| --- | --- | --- |
| 26.3 | 25 | Supported |
| 26.2 | 25 | Supported |
| 26.1.2 | 25 | Supported |
| 1.21.1 | 21 | Built from the same source, no new work for now |
| 1.20.1 | 21 | Built from the same source, no new work for now |

Each version has its own jar.

## Try it

No add-on of your own? Each [release](https://github.com/Mechazawa/bedbug/releases) carries
two to start with: `bedbug-showcase.mcaddon` and `bedbug-test-addon.mcaddon`. Put one in `addons/`.
See [Example add-ons](docs/packs.md#example-add-ons).

## Servers and LAN

Install Bedbug on the server and on every client. Put the add-ons in the server's `addons/` only.

A joining client gets the server's add-ons during the join, with no restart, and plays with exactly
those for the visit. Its own add-ons come back when it leaves. A world opened to LAN gives its
add-ons to guests the same way. See [Servers and LAN](docs/servers.md) for limits and server
administration.

## In game

- **Add-Ons**, on the pause menu or on Bedbug's Mod Menu screen, lists the loaded add-ons with
  name, version, authors, description, packs and whether each is active. An add-on is inactive while
  you visit a server that does not have it.
  - **Settings** opens the add-on's own settings. See [Pack settings](docs/packs.md#pack-settings).
  - **What does not work** shows what `/mcaddon packs`, `/mcaddon entities` and `/mcaddon scripts`
    report for it.
- Bedbug's own options are under **Options > Video Settings**. With Sodium, they are on the
  **Bedrock Add-ons** page of Sodium's video settings. From 26.1 they are also on the Mod Menu
  screen.
  - **Paper Doll** (on by default) draws the player at the top left while sneaking, swimming,
    flying, gliding or riding, as Bedrock does. `mcaddon.paperDoll` in `options.txt`.
  - **Vibrant Visuals Colour Grading** (off by default, 26 and later) draws a resource pack's colour
    grading.
- `/mcaddon packs` lists what loaded, with authors, licence and URL.
- `/mcaddon entities` and `/mcaddon scripts` list what a pack asked for that Bedbug does not do. On
  a server, run `/mcaddon scripts` there to get the server's script report.

Bedrock commands run through `/bedrock`, for example `/bedrock camera @s set minecraft:free`. See
[Commands](docs/commands.md).

## How much of Bedrock works

The census tools in `tools/census/` compare Mojang's published vocabulary with what Bedbug reads.
The numbers do not depend on which pack is installed.

| Surface | Answered |
| --- | --- |
| Entity components | 100% |
| Entity AI goals | 100% |
| Entity filters | 100% |
| Entity component fields | 77% |
| Molang queries | 100% |
| Molang maths | 100% |
| Block components | 100% |
| Item components | 96% |
| Command names | 97% |
| Command keywords | 97% |
| Game rules | 100% |
| Particle components | 100% |
| Particle component fields | 100% |
| Particle curves and Molang variables | 100% |
| Loot table functions | 100% |
| Loot table conditions | 100% |
| Recipe types | 100% |
| Recipe fields | 100% |
| Trade table fields | 100% |
| Trade item functions | 100% |
| Geometry, animation and controller fields | 98% |
| Pack file kinds | 98% |
| Manifest fields | 77% |
| Vanilla item ids Bedrock writes (less the 110 Java has nothing for) | 100% |
| Vanilla block ids Bedrock writes (less the 303 Java has nothing for) | 100% |
| `@minecraft/server` and the other script modules | 100% |

`/mcaddon entities` and `/mcaddon scripts` name everything in the missing share, so a pack that uses
it is told. Run the censuses for current figures.

## What does not work

- Overworld biomes from a pack do not generate. Nether and end biomes do.
- 22 of Bedrock's 28 feature types, among them trees, geodes and carvers.
- Worldgen on 1.20.1, and pack dimensions before 26.
- Bedrock's own screens changed through `ui/` files. Forms and the HUD title work.
- Simulated players in game tests, primitive shapes and locator bar waypoints in scripts.
- Smithing trim patterns that Java does not already have.
- A player cannot open an entity's inventory.
- Education Edition content: chemistry, the material reducer, the camera item.
- Touch controls, the Bedrock Editor and marketplace commands.
- Water, volumetric fog and scattering from Vibrant Visuals.
- Normal, roughness and metalness maps need Iris or OptiFine with a labPBR shader pack.

Each detail page lists its own limits.

## Details

- [Servers and LAN](docs/servers.md): the join hand-over, refusals, administration commands
- [Commands and functions](docs/commands.md): `/bedrock`, functions, selectors, camera, HUD, game
  rules
- [Entities](docs/entities.md): components, behaviours, riding, damage, the player definition
- [Items, blocks, recipes, loot and trades](docs/items-and-blocks.md)
- [Rendering, sound and text](docs/rendering.md): models, materials, attachables, particles, UI
- [World generation](docs/worldgen.md): biomes, features, spawning, dimensions, sky lighting
- [Scripts, forms and dialogue](docs/scripts.md): script API, events, debugger, game tests
- [Packs, manifests and settings](docs/packs.md)

## Contributing

Build with Java 25 or newer:

```
./gradlew build
```

Jars land in `versions/<minecraft>/build/libs/`. [AGENTS.md](AGENTS.md) covers the build, the tests
and the conventions.

No textures, sounds, models or text from any pack are in this repository. Bring your own `.mcaddon`.
