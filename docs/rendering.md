# Rendering, sound and text

## Textures

- PNG and Targa.
- A `pbr` pack's texture sets become labPBR sidecars, which Iris and OptiFine shader packs read.
  Without a shader pack they cost nothing.
- The emissive channel glows with or without a shader pack. Normal, roughness, metalness and
  subsurface need Iris or OptiFine with a labPBR shader pack.
- Bedrock reads a texture's alpha by the material that draws it:
  - A colour mask drops only fully clear texels. Bedbug makes every texel with any alpha opaque for
    it.
  - A plain alpha test drops alpha under one half. Bedbug writes a second copy with those texels
    cleared and uses it for such passes.
  - Alpha that Java draws as partly clear is a real fade and stays as the pack wrote it.

## Sound and text

- Each sound definition becomes a sound event. Ogg files with a bad length on the final page are
  repaired.
- `.lang` files are read by Bedrock's rules.
- The pack's locale list becomes entries in Java's language screen, with the pack's names. A player
  can pick a language the game does not have.
- Each `font/glyph_<page>.png` joins the default font, so the pack's icons show in text.

## Models and animation

- Geometry, animations, animation controllers and render controllers, with a full Molang evaluator.
- Each pass is drawn as its `.material` asks: alpha tested or blended, culled or not, plain or
  glowing, with the light its controller asks for (`light_color_multiplier`).
- A model larger than its hitbox is culled against its own `visible_bounds`, so wings and tails stay
  drawn.
- `blend_transition` fades one state into the next. `blend_via_shortest_path` turns the short way.
- A blend value in an animate list or a state scales the clip. A value of 0 or less turns it off.
- A clip that does not loop plays once. `q.any_animation_finished` and
  `q.all_animations_finished` read the current state's clips.
- A controller enters its first state on its first frame (runs `on_entry` and plays the clips) and
  checks transitions from the next frame.
- `should_update_effects_offscreen` and `should_update_bones_and_effects_offscreen` keep the
  animation running while nobody looks.
- With several client definitions for one entity, the newest that the pack's engine version reaches
  is used.
- A bone turned `relative_to` the entity is turned in the entity's frame, on pack models and on
  vanilla mobs.
- Sounds and particles from animations, controller states and the entity definition play at the
  bone the pack names.
- `q.walk_distance` and `q.modified_distance_moved` come from where the entity stands each tick, so a
  model a pack teleports along with a mob walks.

## Render controller colours

- `color` tints a pass only under a colour mask material, such as `entity_alphatest_change_color` or
  a material defining `USE_COLOR_MASK`. Bedrock's shaders read it nowhere else.
- Over a texture with partly clear texels, the pass is drawn untinted and the tint is added in a
  second, blended pass weighted by each texel's alpha. A blended colour mask over such a texture is
  tinted whole and reported.
- `overlay_color`, `is_hurt_color` and `on_fire_color` apply to every material that keeps
  `USE_OVERLAY`.
- A hurt or dying entity flashes red unless its controller sets `is_hurt_color` or its material
  removes `USE_OVERLAY`.
- `MASKED_MULTITEXTURE` (the villager outfits) draws the second texture over the first. The mix is
  then alpha tested, so a nearly clear marker texel is dropped as in Bedrock.
- `materials` gives each bone the material of the last entry that matches it. `*` matches any
  letters, so a villager's hat can be masked and its body not.

## Attachables and held items

- Attachables draw what any entity wears or holds, players and vanilla mobs too, on their own clock.
- A pack can pick between models for one item by its own condition.
- An attachable can write variables into its wearer, and the layer it replaces switches off.
- A pack's entity wears its equipment where its client definition allows (`enable_attachables`).
  `hide_armor` hides armour slots and keeps hand items.
- A bone with `binding` hangs on the named bone of the wearer, so a shield stays in the hand. Its
  children go with it. `context.item_slot` is the slot, as Mojang's `query.item_slot_to_bone_name`
  bindings expect. On a player or a vanilla mob the bone is the Java model part of the same name,
  and `rightItem` and `leftItem` are on the arms. A hat follows the head.
- A bound root is drawn 24 pixels lower than it is modelled, as in Bedrock. A bound bone below the
  root keeps its offset from its parent's pivot and is not lowered.
- Java does not draw an item that an attachable draws: no item on the head, no armour piece, no
  horse or wolf armour, llama carpet or happy ghast harness, and no item in the hand.
- An item with no attachable is drawn at a `rightItem` or `leftItem` bone, scaled by
  `held_item_scale`, full bright under `held_item_ignores_lighting`, and hidden under
  `hide_held_items`. A model with neither bone draws no item.

## Texture meshes

`texture_meshes` turns each drawn texel into a one pixel deep voxel, like Mojang's bow and crossbow.
Each texel has both broad faces and a side where its neighbour is clear. The mesh uses the entity's
`textures` key and is placed by `local_pivot`, `position`, `rotation` and `scale`.
`use_pixel_depth` sets the layer depth.

## Clips on vanilla mobs

`playanimation` on a Java mob, such as a villager, moves the Java model.

- Each Bedrock bone is matched to a Java model part.
- Where Bedrock hangs the head, arms and legs under `body`, those parts follow the body. A pack that
  hides a villager by scaling its body to 0 hides all of it, with clothes, hat and held item.

## Particles

- The emitter runs at the pack's rate and lifetime. Its shape sets where particles start and which
  way they go.
- Molang drives motion, size, spin, colour gradient and flipbook.
- Collision, the kill plane and block filters work.
- Curves, all four kinds, are evaluated each update into their Molang variable.
- Events fire at creation, expiration, on the timeline, every so many blocks travelled and on
  collision. Each can play a sound, start an effect, evaluate an expression, run a sequence or pick
  one by weight.
- A Bedrock id for a vanilla effect shows the Java effect.
- Facing modes: to the camera on all axes, about the vertical axis, or along the particle's
  direction, stated or taken from velocity above a threshold.

## Subpacks

You get the highest tier the pack declares, which is what Bedrock picks on a PC. Files in the subpack
replace the pack's files at the same path, and new files join the pack. `/mcaddon packs` names the
subpack that loaded.

## Interface files

A resource pack's `ui/server_form.json` and `ui/hud_screen.json` work as far as packs use them.

- An action or message form that a pack routes to its own layout with a `#visible` binding on the
  title is drawn in that layout.
- Controls: panels, stack panels, grids over the buttons, images, labels and buttons.
- Placement by `size`, `offset` and anchors.
- `@` inheritance, `$variables` and `modifications`.
- Bindings on `#title_text`, `#form_text`, `#form_button_text` and `#form_button_texture`.
- Controls inserted into the HUD's `root_panel` are drawn over the game. They read the last title,
  subtitle and `$actionbar_text`.
- A pack that hides Bedrock's title or action bar for some texts hides Java's for the same texts.
- A texture the pack ships is drawn. Textures of the Bedrock client are not available.

## Paper doll

The paper doll is drawn as in Bedrock, with or without an add-on: the player, small and turned, at
the top left while they sneak, swim, fly, glide or ride, and for 15 ticks after. The coordinates
and day count move below it. Turn it off with **Paper Doll** (see the README).

## Colour grading

From 26, a resource pack's colour grading is drawn over the world, before the HUD.

- A biome uses the grading its client biome names. A vanilla biome uses the one Bedrock's client
  biome names for it, so `minecraft:default_color_grading` grades the plains, forests and others.
- Other biomes use `color_grading/color_grading.json`.
- The grade blends between biomes as the camera moves.
- `contrast`, `saturation`, `gain`, `offset` and `gamma` per tonal band, and the Kelvin
  `temperature`, use Bedrock's maths.
- It is off by default, as Bedrock grades only under Vibrant Visuals. Turn on **Vibrant Visuals
  Colour Grading**.

## What does not work

- A material pass that only writes a stencil mask is left out.
- Particle facing modes `emitter_transform_xy`, `_xz` and `_yz` face the camera and are reported.
  On 1.20.1 the four direction modes also face the camera.
- `effect_emitter_count` and `effect_particle_count` during entity drawing are reported. The
  `total_` counts work.
- `bone_orientation_matrix` returns a matrix, which is reported. Mojang does not say what its
  members are called.
  A query with several numbers works one member at a time (`q.bone_aabb('leg1a').max.y`). Storing
  the whole value in a variable is reported.
- A `parent_setup` on a player or a vanilla mob is reported. A `binding` to a bone the Java model
  has no part for is reported.
- `filter_lighting` in a render controller is ignored. Mojang does not document what it does.
- `MULTI_COLOR_TINT` and `COLOR_SECOND_TEXTURE` materials are reported.
- `ui/` files that change Bedrock's own screens (inventory, settings, chat, pause menu) are reported.
  Java draws those screens itself. Inside drawn layouts, `scroll_view`, toggles, sliders, text
  boxes, `anims`, `font_size` and a modal form's `custom_form` layout are reported.
- `textures/texture_list.json` adds nothing. Paths it lists that the pack does not ship are reported.
- A colour grading's tonemapping `operator` is reported. Before 26, lighting, atmosphere and colour
  grading files are reported.
