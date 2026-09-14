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
  shader pack and costs nothing when there is none. Where a texture's alpha channel is one of
  Bedrock's cut-out masks it is converted to the mask Java reads: Bedrock's alpha test draws every
  texel carrying any alpha at all, which is why Mojang's own entity textures mark a sheep's face and
  a cat's markings at an alpha of three, and Java's cutout shader keeps only what is above a tenth.
  A channel also holding alpha Java draws partially is a real fade and is left as the pack wrote it.
- Sounds, with every definition registered as a sound event, and files whose final page claims a
  length no clip has repaired on the way through.
- Text, with Bedrock's `.lang` rules rather than a properties reader's. The locale list a pack ships
  beside those files becomes the language list Java's own language screen reads, under the names the
  pack gave them, so a language the game does not carry itself is one a player can still pick.
- A pack's own glyphs. Each `font/glyph_<page>.png` is a sixteen by sixteen grid of characters, which
  is the same grid a Java bitmap font provider is written against, so the page joins the font
  ordinary text is drawn with and the pack's own icons appear wherever it wrote them.
- Models, animations, animation controllers and render controllers, drawn through a full Molang
  evaluator, with each pass drawn the way its `.material` asks: alpha tested or blended, culled or
  not, plain or glowing, tinted the colour its controller worked out for it and lit by the light its
  controller asked for. A model reaching past the box the server gave the entity is culled against
  the extent its own geometry declares, so wings and a tail stay drawn while the hitbox is off the
  side of the screen. A controller state a pack gave a blend transition is crossed out of rather
  than left, so a walk releases into an idle instead of snapping. An entity whose pack asked for it
  keeps running while nobody is looking at it, so a trail or an aura is still going when it comes
  back into view, and a pack describing one entity several times over for different engines is drawn
  from the newest of them its own engine version reaches.
- Entities a behaviour pack declares, with their properties, events, component groups, filters,
  breeding, riding, leashing, home ranges, navigation and item pickup, and the sensors that fire
  their events: environment, entity, target-nearby, block, timer, scheduler, drying-out, damage,
  interact, spawn-entity and hurt-on-condition. Their properties reach every client, so a model
  looks the same in multiplayer as it does alone.
- The clips and controllers a behaviour pack declares, which animate what an entity is rather than
  where its bones are. A moment on a clip's timeline, and a controller state's entry and its exit,
  each raise one of the entity's own events, run a command as the entity, or assign a Molang
  variable the controllers read back, and the whole state machine runs on the server off the
  entity's own tick. What its transitions ask is answered from the entity itself, so a controller
  waiting on `query.is_on_ground`, on the entity's health or on a property the pack declared moves
  when the world does. A command a state names runs with the server's own authority and with `@s`
  standing for the entity, because a mob carries no authority of its own and a pack writing a
  command means the one the console would run.
- What a pack's entity can be besides a mob that walks about. One a pack calls a projectile flies
  the arc its own component describes, spread by the inaccuracy the pack asked for, and on landing
  it damages, burns, poisons, sticks in the ground, drops experience, throws its particles and fires
  the pack's own event; what shoots it is an ordinary `minecraft:shooter`. One that belongs on rails
  stays on the track at the speed the pack allows and answers a live rail with its own event,
  putting its riders off where it was told to. One that remembers writes down what came near it and
  where it heard something happen, and its memory behaviours walk to that, flee it, or hold the
  place it chose. One given the ender dragon's death takes the dragon's two hundred ticks over it
  rather than a mob's twenty. And one declaring hit boxes wider than the box it collides in is hit
  where it looks hit, by a swing and by an arrow alike, while still fitting through the same gap.
- Items, including edible and wearable ones, spawn eggs, and a creative tab per add-on. A dyeable
  item takes the colour the pack painted it and the one a player dyes it. A bow draws and fires
  whatever the pack loads it with. A bag holds other items. A drop the pack asks to keep lies there.
  An item the pack lets into the off hand goes there and one it does not is refused, an item that
  clips liquids aims at water and lava, and one carrying a durability sensor shows the particle and
  plays the sound its threshold names as it wears down.
- Records. A record a pack ships goes into a jukebox and plays, for the length the pack stated, with
  the comparator reading it asked for. Bedrock lets a pack give a record music of its own by writing
  one of the engine's own record sound names in its `sound_definitions.json`, which is the only way
  it can name one, and that definition is the sound the jukebox plays. A record naming a sound the
  pack never defined has nothing to play and is reported rather than registered silent.
- The custom components a pack registers from its own script, on its own blocks and items: placing,
  breaking, clicking, standing on and ticking each reach the pack's own callback.
- Blocks, with the states and permutations a pack declares: each combination gets its own model, the
  permutation conditions are evaluated through the Molang evaluator, and a block drawn with its own
  geometry keeps it. A block naming one of the pack's own `shapes/` under `culling_shape` hides the
  faces that shape covers, which is what a Bedrock voxel shape is for and what a Java occlusion
  shape does.
- The events a block declares, fired the way Bedrock fires them: on its own clock, on a random tick,
  and on the block being placed, broken, clicked, stepped on, stepped off or landed on. Each one runs
  the commands the event queues and writes back the states it sets, with its Molang condition checked
  against the block standing in the world. A block that says it holds water is waterlogged, one with
  a placement filter only survives where the filter allows, one that produces redstone powers what it
  touches, and one a pack marks pottable gets the second block a flower pot needs.
- What a block does to the things around it. A chest opens under a block that says it may whatever
  the block does to redstone, a block that accepts no connections is one no fence, wall or pane
  reaches across to, a block supporting like a fence carries what rests on it where a fence post
  would, and a block that obstructs rain stops it, gathering snow on top where it asked to.
- Trading. A tier opens once its trader has earned the experience it asks for, a group offers the
  few trades it was told to pick weighted the way it weighted them, a payment listing several items
  becomes one offer per item, and `max_uses`, `trader_exp`, `reward_exp` and `price_multiplier` all
  reach the merchant screen. A trade that enchants what it hands over hands over an enchanted item,
  and one that dyes it hands over a dyed one. A trade selling an enchanted book picks the
  enchantment, rolls its level and charges the emeralds that level is worth, and one selling an
  explorer map hands over a map pointing at the nearest structure of the kind it names, or is left
  off the screen where the world has none.
- Brewing. A pack's own brewing stand recipes are added to the list the game builds in code, so a
  mix turning one potion into another over a reagent, and a container recipe turning one bottle into
  another, both work at an ordinary brewing stand. A bottle of the pack's own is accepted as a
  container to brew in.
- Recipes: shaped, shapeless, furnace, smithing transform and smithing trim. `tags` names the block
  a recipe is made at, so one Bedrock furnace recipe listing a furnace, a blast furnace, a smoker
  and a campfire becomes four Java recipes, a shapeless recipe tagged for the stonecutter becomes a
  stonecutting one, an ingredient naming a tag stays a tag, and a `group` keeps its group. What a
  recipe says a player must hold before it is theirs becomes the advancement Java unlocks a recipe
  from, so a converted recipe reaches the recipe book the way a vanilla one does.
- Loot tables, with the functions that set a count, a damage, a potion, a named enchantment, a
  random enchantment at a level, an armour trim, an illager captain's banner, a suspicious stew's
  effects, an explorer map, an ominous bottle's amplifier and a drop that grows with looting, and
  the gear a mob spawns in enchanted by the rule Java's own spawning enchants it with. A condition
  Java has a predicate for becomes that predicate; one it has none for is asked by this mod as the
  loot is rolled, which is how an entity's variant, a property a pack declared, the world and the
  regional difficulty, a kill by a player's pet and a Bedrock biome tag all still decide a drop. A
  function Java has none of is answered the same way: a book drops carrying one of the enchantments
  the pack listed, at a level out of that enchantment's own range.
- `.mcfunction` files translated into Java's command dialect with a `tick` function tag.
- Natural spawning from a pack's `spawn_rules`, with each alternative the rule lists becoming a
  biome spawn entry of its own. The weight, the group size and the biome tags decide which biomes
  it is offered in; the brightness, height, difficulty, world age, distance, density, delay, the
  surface and underwater and lava markers and the blocks it may stand on are asked again of the
  spot the game is trying, so a mob that a pack said belongs on sand at night stays there.
- The world a behaviour pack describes: the biomes it declares, the features it builds and the rules
  that place them. A Bedrock biome states the climate a player feels and a list of tags, and both
  become the band of Java's own climate space the biome is searched for in. A biome the pack's tags
  put in the nether or the end is added to the list that world already searches, so it generates in
  an ordinary world; one in the overworld is converted and registered and then reported, because
  Java's overworld has no such list to add to. The colours the resource pack paints a biome with go
  into it either way, the fog its `fogs` file paints the air and the water with among them, and from
  26 the distances that fog fades over as well. A feature becomes a Java configured feature, and a
  feature rule becomes the placed feature and the generation step beside it, hung at world load on
  every biome whose tags the rule's own filter accepts. That last part is the direction Bedrock
  attaches from and the one a Java biome cannot, because a Java biome lists the features it carries
  and nothing a data pack writes reaches one from the other side.
- Attachables: what any entity wears or holds, players and vanilla mobs included, animating on its
  own clock. A pack picks between several models for one item on a condition of its own, and what an
  attachable writes into whoever carries it reaches that entity's variables, so the layer it stands
  in for switches off. A pack's own entity wears what it has equipped where its client definition
  says it may, and a definition hiding armour hides what is in an armour slot and keeps what is in a
  hand.
- The sounds and particles an animation, an animation controller state or an entity's own definition
  fires, played at the bone the pack anchored them to.
- Particle effects a pack describes itself: the emitter runs on its own clock at the rate and
  lifetime the pack chose, its shape decides where each particle is born and which way it leaves, and
  the particle's Molang drives its motion, size, spin, colour gradient and flipbook. Collision, the
  kill plane and the block filters are honoured. A curve the pack declares is evaluated every update
  and becomes the Molang variable it is named after, in all four shapes Bedrock draws. An effect's
  events run as well: at its creation, at its expiration, along its timeline, every so many blocks
  travelled and on a collision, each one able to play a sound, start another effect, evaluate an
  expression, run a sequence or pick one of several by weight. A Bedrock id naming a vanilla effect
  is shown as the Java one instead.
- Behaviour pack scripts against `@minecraft/server`, sandboxed: a script sees its own pack's files
  and nothing else on disk. Commands a script registers on startup become real commands,
  `@minecraft/server-ui` forms become real screens, and the world, system, entity, dimension, block,
  scoreboard, game rule and structure surfaces are answered by the game.
- The world events a script subscribes to, raised out of the game rather than out of a pack's own
  data: what a player breaks, places, uses, picks up and swings at, where they go and what they
  hold, the weather turning and a game rule being set, an explosion and each block it takes, a
  piston going out and coming back, a pressure plate, a tripwire, a target block, a button and a
  lever, and a container opening and closing on a block or on an entity. An explosion is offered
  first as a before event, so a listener can call one off, and the blocks it names are the ones
  still standing.
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
filter documentation and the Molang query list out of Mojang's `bedrock-samples`;
`tools/census/commands.py` reads the published command list; `tools/census/particles.py` reads the
particle schemas and documentation; `tools/census/data.py` reads the vanilla loot tables, recipes and
trade tables; `tools/census/model.py` reads every shape the vanilla resource pack's geometries and
animations are written in; `tools/census/packfiles.py` reads the file kinds Mojang's own pack pair
ships; and `tools/census/script.py` reads the published `@minecraft/*` type definitions. All of them
compare what they read against the names this mod acts on:

| Surface | Answered |
| --- | --- |
| Entity components | 97% |
| Entity AI goals | 100% |
| Entity filters | 100% |
| Molang queries | 100% |
| Molang maths | 100% |
| Block components | 100% |
| Item components | 88% |
| Command names | 75% |
| Command keywords | 60% |
| Game rules | 78% |
| Particle components | 100% |
| Particle component fields | 97% |
| Particle curves and Molang variables | 100% |
| Loot table functions | 83% |
| Loot table conditions | 94% |
| Recipe types | 100% |
| Recipe fields | 100% |
| Trade table fields | 100% |
| Trade item functions | 80% |
| Geometry, animation and controller fields | 93% |
| Pack file kinds | 83% |
| `@minecraft/server` and friends | 100% |

Every name in the remaining share is counted and shown by `/mcaddon entities` and `/mcaddon
scripts`, so a pack relying on one is told rather than left wondering. Run the censuses yourself to
get the current figures; the ones above are a snapshot.

## What does not work

**What a pack writes on the player rather than on an entity of its own.** `minecraft:player.hunger`,
`player.saturation`, `player.exhaustion`, `player.level` and `player.experience` set the five numbers
a player carries, and `minecraft:exhaustion_values` prices each action against the food bar. Bedrock
ignores all six anywhere but on the player, so the only place a pack can write them is its own
`entities/player.json`, a definition of an entity the game already has rather than one it is adding.
This mod keeps the game's definition of such an entity and names every component written on the
pack's, these six included, so a pack overriding a vanilla entity is told which of its components
went unread rather than finding out by watching.

**An entity in a bucket.** `minecraft:bucketable` would make a mob one a bucket picks up, which Java
has an interface for: a mob implementing `Bucketable` answers `getBucketItemStack()` with the item
the player is left holding. What the component does not say is which item that is. Mojang publishes
the name with an empty body, no field of any kind and no documentation, and no entity in the vanilla
behaviour pack declares it, so there is nothing in a pack to build the bucket out of. It is counted
and named.

**Aim assist, pack settings and the device memory tiers** describe a Bedrock client rather than a
world, and are counted and named the same way.

**An item's data value.** `set_data`, `random_aux_value`, `set_data_from_color_index` and
`random_block_state` all pick one of the things a Bedrock id stands for by number. Java gave every
one of those its own id when it flattened blocks and items, so there is no number left to set, and
the entry naming `minecraft:bed` or `minecraft:wool` beside one is already naming an item Java has
none of. The same is why a recipe's `data` beside an item, and a result naming one, are read and
reported. `minecraft:stacked_by_data` is the same absence on the item itself: it says whether two
stacks of one id but different numbers may merge, and in Java two stacks of one id are the same
item, so both answers to it describe what already happens.

**What a bag may hold.** `minecraft:storage_weight_limit` caps what a bag carries and
`minecraft:storage_weight_modifier` prices one item inside one. Java counts the same two numbers and
keeps both inside `BundleContents`: the cap is the literal `Fraction.ONE` that
`BundleContents.Mutable.getMaxAmountToAdd` subtracts from, and an item's weight is
`BundleContents.getWeight`, one over its stack size. Neither can be asked which bundle it is
counting for, because `Mutable` holds the contents and their total and no reference to the item
carrying them, so there is nowhere to read a pack's own cap from. 1.20.1 has no `BundleContents` at
all. A bag from a pack is a Java bundle of the ordinary size, and both components are counted.

**A camera and a touch button.** `minecraft:camera` times the black bars, the shutter and the
photograph sliding away, which is a full-screen animation the Bedrock client plays while a player
takes a picture in a world. Java has neither the picture nor the screen. `minecraft:interact_button`
writes the word on the button a touchscreen shows for using what is held, and Java has no touch
controls to put one on.

**The sounds a swing makes.** `minecraft:swing_sounds` names three members of Bedrock's own level
sound event list, which the Bedrock client resolves through a catalogue of its own. That list and
the sound definitions a pack ships are two vocabularies: of its 570 names only 219 are also
definition names, and the three the component is written with, `attack`, `attack.strong` and
`attack.critical`, are none of them. A pack's sound definitions are what this mod registers, so
there is nothing to name a Java sound with.

**What prices a trade, read out of a loot table.** `enchant_book_for_trading` states four costs
beside the enchantments it picks between: a base, a base spread, and both again per level. In a trade
those decide the emeralds the offer charges, and this mod charges them. In a loot table there is no
other side to put a price on, so the book is enchanted and the four costs are counted.

**One loot condition with nothing to ask.** `entity_killed` asks what the dropping mob itself
killed, and a Java loot context holds the victim and whoever killed it and no record of what the
victim did. It is counted by `/mcaddon entities` rather than dropped without a word. A Bedrock biome
tag with no Java biome carrying the same idea, `sulfur_caves` among them, is named the same way, and
a `biome_has_tag` asking for one then reads as false.

**The material reducer.** `recipe_material_reduction` describes the Education Edition material
reducer, which the Java game does not have as a block.

**A recipe's priority and its symmetry.** Java offers every recipe that matches rather than the one
with the highest priority, and mirrors a shaped pattern whatever `assume_symmetry` says. A Bedrock
recipe writing either keeps everything else and the field is reported. An unlock naming a game event
instead of an item is reported the same way: Java unlocks a recipe from an advancement, and no
advancement trigger waits on a player standing in water or filling their inventory.

**A smithing trim a pack invents.** A Java trim pattern names an asset, and the armour overlays for
that asset are textures a resource pack ships under `textures/trims/`. A Bedrock pack has nothing to
build one out of. Its own eighteen patterns are drawn out of a directory the engine reads by name,
no file in a pack points at that directory, there is no schema for a pattern or a material, and
which pattern a worn piece shows is decided by a Molang variable the engine sets rather than by
anything a pack can write. `minecraft:recipe_smithing_trim` names no pattern either: it names the
template, the base and the material, and Bedrock reads the pattern off whichever template item was
placed. From 26 the Java side wants the pattern named on the recipe, so it is read back out of a
template called `<pattern>_armor_trim_smithing_template`, which makes the patterns a converted
recipe can apply the ones the game already ships. A recipe naming any other template is reported
instead.

**What a trade runs over the item it hands over.** `enchant_with_levels` and `enchant_randomly`
enchant it, `set_count` counts it, `random_dye` paints it one of the sixteen dyes,
`enchant_book_for_trading` makes it an enchanted book and prices the trade, and `exploration_map`
points it at a structure. `random_aux_value` is the one left: it picks one of the things a Bedrock
id stands for by number, which Java has not had since it flattened items, so the item the trade
names is already the wrong one and there is no number to pick. It is counted instead.

**A filter on a trade item.** Bedrock checks it as the trade is offered, out of the filter
vocabulary. A Java offer is built once and sent to the client, which leaves nothing to ask again.
The `format_version` a trade table may carry is named the same way: every published one writes the
same tiers, groups and trades this reads, so the stamp changes nothing about how the table is read.

**Three parts of what a block does to its surroundings.** `minecraft:support` shapes what rests on a
block like a fence or like a stair, and the stair shape is read off the facing and the half that
`minecraft:placement_direction` carries, which is a trait and becomes no Java property.
`minecraft:connection_rule` saying `only_fences` separates a fence from a wall and a pane, and its
`enabled_directions` narrows the rule to some sides only. Java asks a pane and a bar their question
without saying which side is asking, so neither can be answered for them.
`minecraft:precipitation_interactions` asking for `snowlogging` puts snow inside the block's own
space, and Java keeps a snow layer as a block of its own standing on top, with no second occupant
for the space below it. Each is reported and the rest of its component still decides.

**The cause a block event names for its damage.** Bedrock picks from its own list of damage causes
and Java builds a damage source out of what dealt the blow, so a block that hurts whoever set it off
hurts them for the right amount by a source that names no cause. Every other response a block event
can give runs: a command, a block state, dying, a sound, a particle, an effect added or taken away,
a teleport, a swing, a loot table, a spent or swapped held item, and a block replaced here or one
step away.

**Two ways of building a bone.** `texture_meshes` builds geometry out of a texture's opaque pixels,
and `binding` names a bone of whatever entity is carrying the model and hangs this one off it. Every
one Mojang ships is `q.item_slot_to_bone_name(...)` on an attachable, and an entity Java draws with a
model of its own has parts rather than bones, with no two models naming theirs alike. A bone written
either way is drawn as the boxes it also declares.

**The item an engine puts in a hand.** `held_item_scale`, `held_item_ignores_lighting` and
`hide_held_items` size, light and hide what Bedrock draws in an entity's hand by itself. The only
thing drawn in an add-on entity's hand here is the attachable a pack describes for that item, which
carries its own scale and its own material, so all three are counted and named.

**What a Molang value can be.** A value is a number or a string, which is what every expression in a
pack computes with. Two queries answer with neither: `bone_orientation_matrix` gives a matrix and
`combine_entities` gives an array of entities, so both are counted and named. A query answering with
several numbers at once does work, one member at a time, so `q.bone_aabb('leg1a').max.y` and
`q.spellcolor.r` each answer. A pack that stores the whole of one in a variable and reads the members
off that variable later is told rather than handed a zero.

**Two particle counts with no effect to count.** `effect_emitter_count` and `effect_particle_count`
count what belongs to the particle effect whose own Molang is being evaluated, and a query asked
while an entity is drawn belongs to no effect, so both are named by `/mcaddon entities`.
`total_emitter_count` and `total_particle_count` count the whole world and are answered.

**Stencil-masked material passes.** A material's alpha testing, blending, culling and emissiveness
all become the render type the pass is drawn with. What cannot follow is the stencil buffer itself:
a pass that exists only to write a mask is left out rather than drawn as a shape that does not
belong there.

**Eight of a particle's ten facing modes.** Java turns a particle to the camera on every axis or
about the vertical one only, which is what `rotate_xyz`, `rotate_y`, `lookat_xyz` and `lookat_y`
ask for. The three that aim the quad along the particle's own direction and the three that match the
emitter's transform have no Java form, so they fall back to facing the camera, and the direction
settings those modes read are not used.

**A quarter of Bedrock's commands.** `fog`, `camera`, `camerashake`, `hud`, `dialogue`,
`inputpermission`, `aimassist`, `controlscheme`, `project` and the rest of the twenty-three name a
renderer feature Java has none of, a marketplace or a server Java does not speak to, or a word both
editions use for different things: Bedrock's `schedule` waits for an area to load and Java's delays
a function. A function using one keeps its other lines and the dropped command is reported, and so
is a line whose selector asks for something Java cannot select on, such as `hasitem`.

**One form of a command the rest of which translates.** A `ride` that summons what it mounts or
empties a vehicle, a `music` that loops, a `structure` mirrored on both axes or placed a block at a
time, a `summon` that turns or aims what it summons, a `gametest` that lays out a test area, the
ender dragon's mob event, `replaceitem` into the slot a Bedrock equippable component fills,
`tickingarea` picked out by name or as a circle, `save query`, `place featurerule` and the setting
that says whether cheats are allowed: each is a form Java has no way to say. The line goes and the
form is named, rather than being handed to a parser that would refuse it and take the file with it.

Everything else is translated or registered. `effect`, `xp`, `playsound`, `stopsound`,
`spreadplayers`, `replaceitem`, `ride`, `tickingarea`, `save`, `gamerule`, `gamemode`, `difficulty`,
`changesetting`, `damage`, `locate`, `loot`, `place`, `summon`, `title`, `tellraw`, `titleraw`,
`daylock`, `allowlist`, `testfor`, `testforblock`, `testforblocks` and both forms of `execute` are
rewritten into what Java reads, including the command behind `execute ... run` and the dimension
Bedrock calls the nether. `playanimation`, `event`, `scriptevent`, `music`, `toggledownfall`,
`structure`, `mobevent`, `particle` and `gametest` are registered by this mod, and a line using one
is written out in the form the command built here reads. Of Bedrock's thirty-seven game rules, the
twenty-five every supported version of Java has are set under the name that version knows them by,
which 26 changed, and four more reach a rule only the newer versions hold. The remaining eight name
a Bedrock heads-up display or a limit Java keeps no rule for, and are reported.

**The player a test drives itself.** `Test.spawnSimulatedPlayer` and every member of the
`SimulatedPlayer` it answers with describe a player the game walks, attacks and builds with. Java's
own stand-in player lives inside its test framework and carries none of that vocabulary, so each of
those members is counted by `/mcaddon scripts` instead of doing nothing in silence. A test that
spawns mobs, places blocks and asserts against the world runs. Running one test at several rotations
or several times over is counted the same way: a run places one structure and calls the test once.

**The parts of the script API that draw a Bedrock client.** Aim assist is how a controller and a
touchscreen pick what a player is pointing at, and the primitive shapes are text and boxes drawn over
the world by the Bedrock renderer. Java has neither, so every member of both is answered by `/mcaddon
scripts` rather than quietly doing nothing. The same holds for `world.getPackSettings` and for the
`get_pack_setting`, `is_pack_setting_enabled` and `is_pack_setting_selected` queries, which all read
the sliders, toggles and dropdowns on a screen only Bedrock shows beside a resource pack.

**The locator bar's waypoints.** Java has a locator bar from 26, and its waypoints belong to entities:
one is transmitted by whoever carries it, and it is shown to whoever is near enough. Bedrock's are a
pack's own markers, placed at a location that need hold no entity, coloured and given a texture per
waypoint and shown to a chosen list of players. Nothing in Java carries any of that, and the two
older supported versions have no bar at all, so `Player.locatorBar` and every member of `Waypoint`,
`EntityWaypoint`, `LocationWaypoint` and `PlayerWaypoint` are counted and named.

**What an error would carry.** `BookError.reason`, `InvalidEntityError.id`,
`InvalidItemStackError.itemType` and the `reason` of the container, command and namespace errors are
data on failures Bedrock raises where this mod answers instead: a book is written as far as the pack
asks, and an entity or an item that has gone reads as invalid rather than throwing. The errors are
exported, so a pack catching one by name still compiles, and each of their members is counted.

**Four world events nothing in Java raises.** `entityUpgrade` fires when Bedrock migrates an entity
to a newer version of the definition a pack wrote, which is a step in reading a pack rather than
anything that happens in a world: this mod reads one definition and keeps it. `playerEmote` names a
persona piece, and a persona is a Bedrock skin. `playerInputModeChange` says whether somebody is on a
keyboard, a controller, a touchscreen or a motion controller, and `playerInputPermissionCategoryChange`
follows Bedrock's `inputpermission` command, which takes movement and the camera away from a player.
A Java client tells the server nothing about the device it is on, and Java has no such command. Each
of the four hands a pack a signal of the class Bedrock names, so subscribing works, and `/mcaddon
scripts` says the event is never raised.

**Four spawn rule conditions with nothing to ask in Java.** `minecraft:permute_type` swaps the
entity that spawns for another one after the biome list has already chosen a type.
`minecraft:mob_event_filter` waits on one of Bedrock's own world events, `minecraft:is_experimental`
on a toggle Java has no form of, and `minecraft:player_in_village_filter` measures a distance to a
village border that Java's poi map does not hold. Each is counted and named by `/mcaddon entities`.
A Bedrock biome tag with no Java biome carrying the same idea, `sulfur_caves` and the `spawn_*`
nether tags among them, is named the same way and then left out of the filter, so the rest of the
rule still decides.

**Whole directories that describe a Bedrock client.** `ui` is Bedrock's own screen language, which
draws every menu the engine shows and has no counterpart in a Java resource pack. `cameras` holds
the presets `/camera` picks between and `dialogue` holds the screen an NPC opens, and both commands
are already reported as ones Java has no form of. `textures/texture_list.json` is the index Bedrock
keeps of every texture in the pack, and the conversion walks the directory itself. Each is named
against the pack that shipped it, so `/mcaddon entities` says which directory went unread.

**Four of the renderer directories, field by field.** `atmospherics` states a Rayleigh and Mie
scattering model: `rayleigh_strength`, `sun_mie_strength`, `moon_mie_strength`, `sun_glare_shape`
and the four `horizon_blend_stops`, each keyframed over the day. Java simulates no scattering and
draws its sun as one textured quad, and its sky colour is a single value with Java's own time curve
over it, so there is no keyframe table to put anywhere. `lighting` states a sun and a moon colour
and illuminance as curves over the day, a lightning `flash` tint, an `orbital_offset_degrees`, an
ambient colour and illuminance, an `emissive.desaturation` and a `sky.intensity`; Java holds one sky
light colour and one ambient light colour per dimension from 26 and neither before, has no emissive
pass and no lightning tint. `color_grading` states `contrast`, `gain`, `gamma`, `offset` and
`saturation` per tonal band, a Kelvin `temperature` and a tonemapping operator, and a Java post
effect file holds targets, passes and samplers with no grading term in it and nothing binding a
chain to a biome. `water` states particle concentrations in milligrams per litre, a caustic
projection and an eleven field wave model, where Java water is a flipbook texture tinted by one
colour. Each of the four is read by Bedrock only under Vibrant Visuals, its deferred pipeline.

**The half of a fog a biome has no field for.** `fogs` states a `distance` block per camera
location. The `air` and `water` colours become a biome's own fog colour and water fog colour, and
from 26 the start and end distances go with them; 1.21.1 carries the two colours and has no field
for a distance, so a distance is counted and named there. A distance written as a fraction of the
render distance is named on every version, because Java states its own in blocks. The `volumetric`
block, the `transition_fog` fade and the lava, weather and powder snow locations have nothing behind
them. So does a fog identifier no installed pack defines, which is what Bedrock's own
`minecraft:fog_*` settings are: they live in the engine rather than in any add-on, so a biome naming
one keeps Java's fog and the author is told.

**What a client biome states beside its colours.** The water, sky, grass and foliage colours out of
`biomes` and `biomes_client.json` reach a converted biome, and the dry foliage colour reaches 26
alone and is named before it. The whole-pack file's own water fog colour and water fog distance are
read as that biome's water fog. Its water surface transparency, the precipitation particles, the
ambient sounds, the music definition and the identifiers naming a deferred renderer's atmosphere,
lighting, water and colour grading are counted and named. On 1.20.1 no biome from a pack is
converted at all, so every biome the resource pack paints is counted and named with the version that
would draw it.

**Worldgen on 1.20.1.** An entry a data pack puts in a worldgen registry leaves a world stable only
while the pack it came from carries a `KnownPack`, and a converted pack carries one from 1.21 on.
1.20.1 has no such class at all: `RegistryDataLoader` keeps the decoded lifecycle for anything a pack
that is not built in supplies, a worldgen codec decodes as experimental, `PrimaryLevelData` carries
that through, and `WorldOpenFlows` then asks the player whether to load the world at all. Nothing
records the answer, so it is asked on every open, forever. A pack shipping worldgen is a pack too new
for that version, which is the answer Bedrock itself gives to a pack written for a newer engine. On
1.20.1 nothing under `biomes`, `features` or `feature_rules` is written and every one of them is
counted and named by `/mcaddon entities`, with the version that would run it. The other three
versions convert all of it.

**What a Bedrock biome states that a Java biome has no field for.** Of Mojang's own 88, 87 state a
surface builder, 75 an overworld height and 31 the overworld generation rules, and Java has a field
for none of the three: it states its surfaces in `worldgen/noise_settings` and its heights in density
functions, one set for a whole dimension rather than one per biome. `minecraft:mountain_parameters`,
`minecraft:village_type`, `minecraft:humidity`, `minecraft:replace_biomes` and a grass tint stated as
noise are the same absence. Each is counted and named, and the rest of the biome still converts.

**What a converted biome starts out holding.** Java requires carvers, features, spawners and spawn
costs on every biome, and a Bedrock one states none of them, so each takes an empty value and the
biome comes out valid. What fills it is the pack's own rest: a `feature_rules/` file puts features in
it, a `spawn_rules/` file puts mobs in it, and a biome from a pack that ships neither generates as
bare terrain. Java's own carvers belong to the dimension rather than to anything a data pack adds, so
a converted biome has no caves of its own.

**A biome in the overworld.** The limit here is Java's overworld biome source rather than anything
about the pack. The nether and the end each hold a list of the biomes they search and a mod may add
one to it, which is what a pack's nether or end biome does: the game's own entries stay exactly as
they were and the pack's is one more beside them. The overworld has no such list to add to. Its biome
source names a preset the game holds in code, and `MultiNoiseBiomeSource`'s codec takes either that
name or an explicit list of every climate band in the world, so the only way to add one biome is to
write all of them out again as a `dimension` entry. Doing that states the overworld's whole
generator, the noise settings included, so a world created as amplified or as large biomes would read
the default ones instead for as long as the pack was installed, and terrain made before the pack
arrived would disagree with terrain made after. Rewriting the game's own biome list to add one entry
to it is not a thing a pack should be able to do to somebody's world, so it is not done. An overworld
biome is read, converted and registered, and `/mcaddon entities` says it will not generate.

**Half of Bedrock's feature types.** Of the twenty-eight, three become a Java configured feature that
takes the same fields and builds the same thing: `ore_feature` becomes `minecraft:ore`,
`single_block_feature` becomes `minecraft:simple_block` through a state provider, and
`weighted_random_feature` becomes `minecraft:random_selector` with each weight turned into the chance
it is worth after the entries before it. Three more state a position rather than a thing and become
placement modifiers on whatever they wrap: `scatter_feature`, `snap_to_surface_feature` and
`surface_relative_threshold_feature`. The rest are counted and named. Six have nothing behind them on
any of the three: `aggregate_feature`, `conditional_list`, `beards_and_shavers`, `rect_layout`,
`multi_block_feature` and `horizontal_tree_decoration_feature`. Three are carving, which in Java is a
registry of its own outside the eleven generation steps and fixed when a dimension is made. The
remaining thirteen name a Java feature whose configuration is a different model of the same idea, a
tree and a geode among them, and a converted entry would be a guess at what the pack meant rather
than what it wrote.

**`dimensions`.** A Bedrock dimension states an identifier, a generator type name and a height range.
A Java one needs a whole chunk generator with a biome source and a noise settings reference behind
it, and a `dimension_type` beside it whose fields 26 rewrote: `ultrawarm`, `natural`, `bed_works`,
`respawn_anchor_works`, `piglin_safe`, `has_raids` and `effects` all went, and what replaced them is a
map of environment attributes. The directory is counted and named against the pack that shipped it.

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
