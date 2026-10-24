# Packs, manifests and settings

## Loading

- `.mcaddon`, `.mcpack` and unpacked pack folders in `addons/` all load.
- Bedbug orders packs by their dependencies and reports problems.
- Converted output is cached in `.mcaddon/` next to `addons/`. Deleting it costs one slower start.
- `/mcaddon packs` lists what loaded, with the authors, licence and URL from the manifest's
  `metadata`, and the subpack that loaded.

## Example add-ons

Each release attaches two add-ons built from this repository. Both are written here from nothing and
carry no content from any other pack. The archive is the same for every Minecraft version.

- **`bedbug-showcase.mcaddon`** is the Bedbug Showcase. It has two mobs with their own models,
  animations and render controllers, two blocks, four items, two particles, a biome with its own
  fog and colour grade, a camera preset, a script that opens a form, and settings in both
  manifests. Use it to see what an add-on looks like in game.
- **`bedbug-test-addon.mcaddon`** is the add-on the mod's tests run against. It carries one of
  nearly everything Bedbug has a reader for, such as recipes, loot, trades, entities with many
  behaviours, particles, fogs and scripts. Use it to check that an install works.

To build them, run `python3 tools/showcase/build.py` and `python3 tools/testpack/build.py`. The
archives land in `build/showcase/` and `build/testpack/`.

## Add-ons per world

Each world keeps its own list of add-ons in `data/mcaddon-addons.json`:

- A new world plays every installed add-on.
- An add-on installed after the world was made is off in that world.
- A world made before this list existed turns on every installed add-on the first time it opens.
- A dedicated server uses the list of the world `level-name` names. It sends an add-on that is off
  to joining players as inactive.

The Add-Ons screen in the pause menu shows which add-ons the world plays. It does not change them.

To choose the add-ons of a world:

- When you create a world, open the **More** tab and press **Add-Ons**. Every installed add-on is on.
  Press **On** or **Off** on a row to change it, then **Done**. The game loads the world's data
  again without the add-ons that are off.
- For a saved world, press **Edit** in the world list, then **Add-Ons**. Each change is saved in the
  world at once and applies the next time the world opens.
- **Re-Create** starts from the add-ons of the world it copies.

An add-on that adds a dimension shows a warning when it is off in a saved world: the world may not
open without that dimension.

## The Add-Ons screen

Bedrock lists behaviour packs and resource packs apart, each with its own manifest description. The
Add-Ons screen shows one entry per add-on, so it shows the behaviour pack's description first and
then the resource pack's, if it says something else.

## Pack settings

A pack's settings open from **Settings** on the Add-Ons screen.

Add-on packs are not listed in Java's Resource Packs and Data Packs screens. They are managed in the Add-Ons screen.

- Labels, toggles, sliders and dropdowns appear in manifest order, translated through the pack's
  `texts/`.
- A slider stops on its steps. A dropdown moves to its next option on each press.
- A setting nobody changed holds its `default`, brought inside the slider's range and step or the
  dropdown's options.

Behaviour pack settings belong to the world:

- They are saved in the world's `data/mcaddon-state.json`.
- Only an operator, or the owner of a single player world, can change them. Others see them greyed
  out.
- A change applies at once. `World.getPackSettings` and the server's `query.*_pack_setting*` read
  the new value, and the script receives `packSettingChange`.

Resource pack settings belong to each player:

- They are saved in `config/mcaddon-pack-settings.json`.
- The client's `query.get_pack_setting`, `query.is_pack_setting_enabled` and
  `query.is_pack_setting_selected` read them.

## Manifest fields that are reported

These are named by `/mcaddon entities` and otherwise ignored:

- `has_education_metadata` and the `chemistry` capability (Education Edition)
- `platform_locked`, `pack_scope`, `product_type`, `pack_optimization_version`
- a module's `uuid` and `version`. The header uuid and version identify an add-on.
- the `editorExtension` and `experimental_custom_ui` capabilities

A world template (`world_template` module, with `base_game_version`, `allow_random_seed` and
`lock_template_options`) is refused before it is read.

A script API dependency that Bedbug does not provide is reported by `/mcaddon packs`, unless it is
`optional`. If the script imports that module, the script still fails to load.

The `pbr`, `raytraced` and `script_eval` capabilities work.
