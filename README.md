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
- Text, with Bedrock's `.lang` rules rather than a properties reader's. The locale list a pack ships
  beside those files becomes the language list Java's own language screen reads, under the names the
  pack gave them, so a language the game does not carry itself is one a player can still pick.
- A pack's own glyphs. Each `font/glyph_<page>.png` is a sixteen by sixteen grid of characters, which
  is the same grid a Java bitmap font provider is written against, so the page joins the font
  ordinary text is drawn with and the pack's own icons appear wherever it wrote them.
- Models, animations, animation controllers and render controllers, drawn through a full Molang
  evaluator, with each pass drawn the way its `.material` asks: alpha tested or blended, culled or
  not, plain or glowing, and tinted the colour its controller worked out for it.
- Entities a behaviour pack declares, with their properties, events, component groups, filters,
  breeding, riding, leashing, home ranges, navigation and item pickup, and the sensors that fire
  their events: environment, entity, target-nearby, block, timer, scheduler, drying-out, damage,
  interact, spawn-entity and hurt-on-condition. Their properties reach every client, so a model
  looks the same in multiplayer as it does alone.
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
- Attachables: what any entity wears or holds, players and vanilla mobs included, animating on its
  own clock. A pack picks between several models for one item on a condition of its own, and what an
  attachable writes into whoever carries it reaches that entity's variables, so the layer it stands
  in for switches off.
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
  piston going out, a pressure plate, a tripwire, a target block, a button and a lever, and a
  container opening and closing on a block or on an entity. An explosion is offered first as a
  before event, so a listener can call one off, and the blocks it names are the ones still standing.
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
| Entity components | 93% |
| Entity AI goals | 99% |
| Entity filters | 100% |
| Molang queries | 100% |
| Molang maths | 100% |
| Block components | 100% |
| Item components | 86% |
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
| Geometry, animation and controller fields | 82% |
| Pack file kinds | 70% |
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

**An item's data value.** `set_data`, `random_aux_value`, `set_data_from_color_index` and
`random_block_state` all pick one of the things a Bedrock id stands for by number. Java gave every
one of those its own id when it flattened blocks and items, so there is no number left to set, and
the entry naming `minecraft:bed` or `minecraft:wool` beside one is already naming an item Java has
none of. The same is why a recipe's `data` beside an item, and a result naming one, are read and
reported. `minecraft:stacked_by_data` is the same absence on the item itself: it says whether two
stacks of one id but different numbers may merge, and in Java two stacks of one id are the same
item, so both answers to it describe what already happens.

**What a bag may hold.** `minecraft:storage_weight_limit` caps what a bag carries and
`minecraft:storage_weight_modifier` prices one item inside one. Java counts the same two numbers,
sixty-four for a full bundle and a sixteenth of it for a bundle inside a bundle, but it counts them
from a constant inside the bundle's own contents rather than from anything the item carries, and
there is no data component holding either. A bag from a pack is a Java bundle of the ordinary size.

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

**One ender dragon phase.** `behavior.dragondeath` is the animation a dragon plays while it dies.
Java stops running a mob's goals the moment its health reaches zero, so a goal has no tick left to
play anything in. The dragon's other seven phases are flown, and `/mcaddon entities` names this one.

**Two ways of building a bone.** `texture_meshes` builds geometry out of a texture's opaque pixels,
and `binding` hangs a bone off the skeleton of whatever entity is carrying the model. Java's
renderer has no form of either, so a bone written that way is drawn as the boxes it also declares.

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

**What four errors would carry.** `BookError.reason`, `BookPageContentError.reason`,
`InvalidEntityError.id` and `InvalidItemStackError.itemType` are data on errors Bedrock raises where
this mod answers instead: a book is written as far as the pack asks and an entity or an item that has
gone reads as invalid rather than throwing. The errors are exported, so a pack catching one by name
still compiles, and the four members are counted.

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
draws every menu the engine shows and has no counterpart in a Java resource pack. `fogs`,
`atmospherics`, `lighting`, `color_grading` and `water` are the settings its deferred renderer reads,
and the resource pack's `biomes` and `biomes_client.json` name one of each per biome; Java decides
all of it from the dimension and the biome in its own code. `cameras` holds the presets `/camera`
picks between and `dialogue` holds the screen an NPC opens, and both commands are already reported as
ones Java has no form of. `textures/texture_list.json` is the index Bedrock keeps of every texture in
the pack, and the conversion walks the directory itself. Each is named against the pack that shipped
it, so `/mcaddon entities` says which directory went unread.

**Worldgen a data pack would write.** `biomes`, `features`, `feature_rules` and `dimensions` on the
behaviour side belong in registries a Java data pack writes into, and a converted pack is a data pack
the server loads, so the route exists. Three things stand in it.

The first is the lifecycle. An entry read from a pack carrying a `KnownPack` leaves a world stable,
and a converted pack carries one from 1.21 on. 1.20.1 has no such thing: the game marks every entry
from a pack that is not vanilla experimental, the world data carries that lifecycle, and a client
then asks the player whether to load the world at all, every time they open it. One `worldgen/biome`
written for a pack would do that to every 1.20.1 world the pack is installed in.

The second is placement. A biome in the registry appears in no overworld by itself: the parameter
list a data pack may write names one of the game's own presets and cannot add a point to it, so a
pack's biome would reach a world only through a mixin into that preset, and only a world made
afterwards. A dimension is worse: it is read when a world is created and never again.

The third is that the two formats describe different things. A Bedrock biome states its terrain, the
surface builder, the height noise type, the mountain parameters and the hills and mutation rules, and
a Java biome has a field for none of them; a Java biome instead insists on carvers, features,
spawners and spawn costs, which a Bedrock one never states. A Bedrock dimension states an identifier,
a generator type name and a height range, where a Java one needs a whole chunk generator with a biome
source and a noise settings reference behind it. About a dozen of the feature types have a Java
configured feature behind them and the rest are placement, which Java states on a placed feature, or
carving, which is a registry of its own.

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
