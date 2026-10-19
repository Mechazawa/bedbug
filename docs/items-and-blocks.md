# Items, blocks, recipes, loot and trades

## Items

- Edible and wearable items, spawn eggs, and one creative tab per add-on.
- A spawn egg with a `base_color` and an `overlay_color` is drawn in those two colours. From 26
  Java has no egg to paint, so Bedbug draws its own.
- `menu_category` also puts an item or a block in a Java tab: construction is Building Blocks,
  nature is Natural Blocks, equipment is Combat, items is Ingredients.
- Food takes as long to eat as the pack says. Before 1.21.2 Java has two eating times, so a pack time
  of 0.8 seconds or less uses the fast one.
- A dyeable item has the pack's colour and takes a player's dye.
- A bow draws and fires what the pack loads it with.
- A bag holds items up to `minecraft:storage_weight_limit`. Each item weighs its
  `minecraft:storage_weight_modifier`. A weight of 0 keeps an item out of all bags.
- `minecraft:swing_sounds` plays the sound that the resource pack's `individual_event_sounds` gives
  each event, for a miss or no damage, a hit and a critical hit. Players nearby hear it.
- A drop that the pack keeps stays on the ground.
- The off hand takes only items the pack allows there.
- An item that clips liquids aims at water and lava.
- A durability sensor shows its particle and plays its sound at its threshold.

## Custom components

A pack's script can register custom components on its own blocks and items. Placing, breaking,
clicking, standing on and ticking reach the pack's callback. Both forms are read:

- the `minecraft:custom_components` list of older packs
- from 1.21.90, each component as its own key. Its body reaches the callback as `params`.

## Blocks

- States and permutations: each combination gets its own model. Permutation conditions are Molang.
- A block with its own geometry keeps it, in the place Bedrock draws it.
- A block's item has the block's name.
- A block breaks into the texture `minecraft:destruction_particles` names, or else the texture of its
  `down` face, as in Bedrock.
- A permutation can change the textures, rotation, collision and selection boxes, mining time and
  light of its state.
- Placement traits add states that are set at placement: facing (turned by `y_rotation_offset`), the
  clicked face and the half of it.
- A block with no `minecraft:loot` drops itself, as in Bedrock.
- `culling_shape` naming one of the pack's `shapes/` hides the faces that shape covers.
- A geometry face naming a material instance uses the texture from `minecraft:material_instances`.
- The geometry's `item_display_transforms` place the block's item in each hand, on the head, on the
  ground, in a frame, in the inventory and, from 26, on a shelf.
- The block's `minecraft:sound` sets its sound group. It wins over `blocks.json`.

### Block events

Events fire on the block's own clock, on a random tick, and when the block is placed, broken,
clicked, stepped on, stepped off or landed on. Each runs its commands and sets its states. Its Molang
condition is checked against the block in the world.

- A block that holds water can be waterlogged.
- A placement filter decides where the block survives.
- A block that makes redstone powers what it touches.
- A pottable block gets the extra block a flower pot needs.

### Neighbours

- A chest under the block opens when the block allows it.
- A block that accepts no connections gets no fence, wall or pane connection.
- A block with fence support carries what rests on it like a fence post.
- A block that obstructs rain stops it, and collects snow on top if it asks to.

## Records

A pack's record plays in a jukebox for the length the pack states, with its comparator output. The
pack gives it music by defining one of the engine's record sound names in `sound_definitions.json`.
A record that names a sound the pack does not define has nothing to play and is reported.

## Recipes

Shaped, shapeless, furnace, smithing transform, smithing trim and brewing.

- `tags` names the station. One furnace recipe tagged for a furnace, a blast furnace, a smoker and a
  campfire becomes four Java recipes.
- A shapeless recipe tagged for the stonecutter becomes a stonecutting recipe.
- A tag ingredient stays a tag. `group` is kept.
- The unlock condition becomes the advancement that unlocks the recipe, so it reaches the recipe
  book.
- Brewing: a mix (one potion to another over a reagent) and a container recipe (one bottle to
  another) work at an ordinary brewing stand. A pack's own bottle is a valid container.

## Loot tables

- Functions: count, damage, potion, named enchantment, random enchantment at a level, armour trim,
  illager captain banner, suspicious stew effects, explorer map, ominous bottle amplifier, looting
  bonus, explosion decay.
- Spawn gear is enchanted by Java's spawn rule.
- A condition with a Java predicate becomes that predicate. Bedbug checks the others at roll time:
  variant, a declared property, world and regional difficulty, a kill by a player's pet, a Bedrock
  biome tag, and `entity_killed`.
- `entity_killed` belongs to the table `minecraft:behavior.eat_mob` names. It is rolled as the meal
  goes down, so the eating mob is the subject.
- A book drops with one of the pack's listed enchantments, at a level within that enchantment's
  range.

## Trading

- A tier opens when the trader has the experience it asks for.
- A group offers the number of trades it picks, by their weights.
- A payment with several items becomes one offer per item.
- `max_uses`, `trader_exp`, `reward_exp` and `price_multiplier` reach the trade screen.
- Enchanted and dyed results work.
- An enchanted book trade picks the enchantment, rolls the level and charges the emeralds that level
  is worth.
- An explorer map trade gives a map to the nearest structure of its kind. It is hidden when the world
  has none.
- The trade table is the one the live component groups name. All tables any declaration names are
  read.
- A script can move the trader inside its dimension without closing the trade. A move to another
  dimension closes it.
- `query.trade_tier` counts from 0, for pack traders and Java villagers (novice 0, master 4).

## Bedrock ids and data values

Packs name vanilla things by Bedrock's ids, and Bedbug maps them to Java's:

- Renamed ids: `golden_rail` is `powered_rail`, `lit_pumpkin` is `jack_o_lantern`, `frame` is
  `item_frame`. Bedrock's `snow` is Java's `snow_block`, and `snow_layer` is Java's `snow`.
- A Bedrock id can be a Java block in one state: `lit_furnace`, `oak_double_slab`,
  `powered_repeater`, `light_block_7`.
- Old item names still work: `record_13`, `muttonRaw`, `horsearmorgold`.
- This applies in loot tables, recipes, trade tables, functions, components, filters, Molang,
  features and scripts.

Data values pick a member of a family by number: `minecraft:dye` 0 is an ink sac, `minecraft:wool`
at a colour index is that wool, `minecraft:bed` and `minecraft:banner` 0 to 15 are the sixteen
colours. Forty families are mapped. This works for `set_data`, `random_aux_value`,
`set_data_from_color_index`, `random_block_state`, a recipe's `data`, a trade's
`minecraft:wool:14`, `give @p wool 1 14`, and named states such as `minecraft:wool` with
`["color"="red"]` or `minecraft:log` with `old_log_type` `spruce`. In a loot table, a sheep's own
`minecraft:color` picks the fleece colour.

## What does not work

- About 110 item ids and 300 block ids have no Java counterpart: Education Edition chemistry and
  hardened glass, building permission blocks, Bedrock's technical blocks and experiments, and items
  Java does not keep. Each costs the one entry, line or recipe that names it.
- A data value with no family keeps the id and is reported. A family member this version has no item
  for empties that one loot entry.
- `minecraft:stacked_by_data` changes nothing. Java stacks by id already.
- A recipe's `priority` and `assume_symmetry` are reported. Java offers every matching recipe and
  always mirrors shaped patterns.
- An unlock that names a game event (standing in water, a full inventory) is reported.
- `recipe_material_reduction` (Education Edition material reducer).
- A smithing trim works only with a template named `<pattern>_armor_trim_smithing_template`, so only
  Java's existing patterns apply. Other templates are reported.
- A filter on a trade item is reported. A trade table's `format_version` is reported and changes
  nothing.
- `enchant_book_for_trading` in a loot table: the book is enchanted and the four cost fields are
  reported.
- `minecraft:support` stair shape, `minecraft:connection_rule` `only_fences` and
  `enabled_directions`, and `snowlogging` are reported. The rest of each component works.
- `item_display_transforms`: the `embedded` context, rotation and scale pivots and `fit_to_frame`
  are reported.
