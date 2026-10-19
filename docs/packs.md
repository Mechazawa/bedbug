# Packs, manifests and settings

## Loading

- `.mcaddon`, `.mcpack` and unpacked pack folders in `addons/` all load.
- Bedbug orders packs by their dependencies and reports problems.
- Converted output is cached in `.mcaddon/` next to `addons/`. Deleting it costs one slower start.
- `/mcaddon packs` lists what loaded, with the authors, licence and URL from the manifest's
  `metadata`, and the subpack that loaded.

## The Add-Ons screen

Bedrock lists behaviour packs and resource packs apart, each with its own manifest description. The
Add-Ons screen shows one entry per add-on, so it shows the behaviour pack's description first and
then the resource pack's, if it says something else.

## Pack settings

A pack's settings open from **Settings** on the Add-Ons screen.

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
