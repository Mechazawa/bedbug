# World generation, spawning and environment

## Biomes

- A Bedrock biome's climate and tags place it in Java's climate space.
- A biome whose tags put it in the nether or the end is added to that dimension's biome list, so it
  generates in an ordinary world.
- An overworld biome is converted and registered, but it does not generate. `/mcaddon entities` says
  so. See [What does not work](#what-does-not-work).
- A converted biome starts with no carvers, features or spawns. The pack's `feature_rules/` and
  `spawn_rules/` fill it. A biome with neither generates as bare terrain, with no caves.

What the resource pack paints goes into the biome:

- water, sky, grass and foliage colours from `biomes/` and `biomes_client.json`. Dry foliage colour
  from 26.
- the fog colours for air and water from `fogs/`, and from 26 the fog start and end distances
- `minecraft:precipitation` as the biome's ambient particle. The density converts to Java's chance,
  anchored on Mojang's own four biomes.
- `minecraft:ambient_sounds` as the loop, mood and addition
- `minecraft:biome_music` as the track from `sounds/music_definitions.json`

A biome or music definition can name an engine sound. The pack's own `sound_definitions.json` is
checked first. 35 of the 70 engine names have a different Java name and are mapped, for example
`music.game.endboss` to `music.dragon`. All 70 work on 26.2, 69 on 26.1.2 and 68 on 1.20.1 and
1.21.1. A name that does not resolve is reported and the biome keeps the game's sound.

## Features and feature rules

- `ore_feature` becomes `minecraft:ore`.
- `single_block_feature` becomes `minecraft:simple_block`.
- `weighted_random_feature` becomes `minecraft:random_selector`.
- `scatter_feature`, `snap_to_surface_feature` and `surface_relative_threshold_feature` become
  placement modifiers on the feature they wrap.
- A feature rule becomes a placed feature at its generation step. At world load it is added to each
  biome its filter accepts.

## Spawn rules

- Each alternative in a rule becomes its own biome spawn entry. Weight, group size and biome tags
  pick the biomes. A pack's biome answers to its pack's tags.
- Brightness, height, difficulty, world age, distance, density, delay, surface, underwater and lava
  markers, and allowed blocks are checked at the spawn spot.
- The rule's population is the mob cap the entity counts against.

## Dimensions

From 26, a `dimensions/` file becomes a Java dimension.

- It is a flat generator with one layer of air and the biome the pack names, as Bedrock builds it
  empty.
- `minecraft:dimension_bounds` (floor and ceiling) and `minecraft:dimension_height` (floor and
  height) both set its height range.
- A dimension added to an existing world appears the next time the world opens. Removing the pack
  does no harm to the world.

## Sky lighting

From 26, a `lighting/` file (ambient colour, sky intensity, sun and moon orbit offset, colour and
strength per hour) and an `atmospherics/` file (zenith and horizon colour) become a timeline for the
overworld.

- The game's own day, moon and early game timelines stay.
- Sun and moon light fold into Java's one sky light, each measured against the brightest the file
  makes it.
- Only the collection that Bedrock gives every biome with no other applies. A collection that one
  biome names is reported.

## What does not work

- **Overworld biomes do not generate.** Java's overworld has no biome list to add to. To add one, a
  pack must replace the whole overworld generator. That changes amplified and large biome worlds and
  breaks terrain made before the pack.
- **Most feature types.** 22 of 28 are reported. Six have nothing behind them in Java
  (`aggregate_feature`, `conditional_list`, `beards_and_shavers`, `rect_layout`,
  `multi_block_feature`, `horizontal_tree_decoration_feature`). Three are carvers. Thirteen map to a
  Java feature with a different model, trees and geodes among them.
- **Biome fields with no Java place**: surface builders, overworld height, overworld generation
  rules, `minecraft:mountain_parameters`, `minecraft:village_type`, `minecraft:humidity`,
  `minecraft:replace_biomes`, a grass tint as noise, three underwater ambient sounds, water surface
  transparency and the renderer identifiers.
- **Spawn rule conditions**: `minecraft:permute_type`, `minecraft:mob_event_filter`,
  `minecraft:is_experimental` and `minecraft:player_in_village_filter`. A Bedrock biome tag with no
  Java equivalent, such as `sulfur_caves` or the `spawn_*` nether tags, is reported and left out of
  the filter. `biome_has_tag` for it is false.
- **Dimensions**: a generator other than `void` is reported. A `minecraft:default_biome` naming the
  pack's own biome is reported and the dimension is void, because a client cannot load it.
- **Fog**: a distance as a fraction of the render distance, `transition_fog`, and
  `volumetric`. The lava, lava resistance, powder snow and weather parts are drawn by the client
  while the camera is in that medium or it rains or snows at the camera, in biomes the pack paints.
  A fog id no pack defines (Bedrock's `minecraft:fog_*`) keeps
  Java's fog.
- **Sky**: the lightning `flash`, `emissive.desaturation`, and the scattering fields of
  `atmospherics` (`rayleigh_strength`, the `mie` strengths, `sun_glare_shape`,
  `horizon_blend_stops`). `water/` files are reported on every version.
- **Music**: the volume multiplier and the underwater track need 26.
- **Before 26**: lighting and atmosphere files are reported.
- **1.21.1**: dimensions are reported, since a fourth dimension makes a world experimental before 26.
  Fog distances are reported.
- **1.20.1**: nothing under `biomes`, `features`, `feature_rules` or `dimensions` is converted. Each
  is reported, because worldgen from a mod pack makes the world ask on every open whether to load
  it.
