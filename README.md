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
  looks the same in multiplayer as it does alone. A component group is where Bedrock states what an
  entity becomes rather than what it is, so a group arriving changes how it gets about: the
  navigator and the move control are built again, and the step it takes, the height it climbs, what
  pushes it, what burns it, what effects land on it and what it has for sale all move with it.
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
  off the screen where the world has none. Which table a trader offers is whichever its live
  component groups name, because Bedrock states the table inside the group that gives a trader its
  stock, and every table any of an entity's declarations names is read while its pack is still open.
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
  regional difficulty, a kill by a player's pet, a Bedrock biome tag and what a mob has just
  swallowed all still decide a drop. That last one is `entity_killed`, which belongs to the table
  `minecraft:behavior.eat_mob` names rather than to a death drop: the goal rolls it as the meal goes
  down, so the mob doing the killing is the entity the loot is about. A function Java has none of is
  answered the same way: a book drops carrying one of the enchantments the pack listed, at a level
  out of that enchantment's own range.
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
  Java's overworld has no such list to add to. What the resource pack paints a biome with goes into
  it either way: its colours, the fog its `fogs` file paints the air and the water with, the
  particle it drops through its air, the sounds it loops and the track it plays, and from 26 the
  distances that fog fades over as well. A feature becomes a Java configured feature, and a
  feature rule becomes the placed feature and the generation step beside it, hung at world load on
  every biome whose tags the rule's own filter accepts. That last part is the direction Bedrock
  attaches from and the one a Java biome cannot, because a Java biome lists the features it carries
  and nothing a data pack writes reaches one from the other side.
- The worlds a behaviour pack declares beside the one it is installed in. A `dimensions/` file states
  an identifier, a band of height and a generator, and Bedrock builds such a world as empty space and
  leaves the pack to build in it, so it becomes a Java dimension over a flat generator carrying one
  layer of air and whatever biome the pack named. The band becomes the dimension type's own `min_y`
  and `height`. Bedrock writes it two ways, `minecraft:dimension_bounds` giving the floor and the
  ceiling and `minecraft:dimension_height` giving the floor and the height, and both are read.
  `WorldDimensions.bake` takes the union of the data pack's own entries and the ones the world was
  saved with, so a dimension an add-on adds to a world that already exists is there the next time
  that world opens, and a pack removed again costs the world nothing.
- Attachables: what any entity wears or holds, players and vanilla mobs included, animating on its
  own clock. A pack picks between several models for one item on a condition of its own, and what an
  attachable writes into whoever carries it reaches that entity's variables, so the layer it stands
  in for switches off. A pack's own entity wears what it has equipped where its client definition
  says it may, and a definition hiding armour hides what is in an armour slot and keeps what is in a
  hand. A model whose root bone declares a `binding` hangs off the bone of the wearer that binding
  names, so a shield stays in the hand as the arm swings; the name is worked out from the
  attachable's own scope, where `context.item_slot` is the slot it is worn in, which is what every
  binding Mojang publishes hands to `query.item_slot_to_bone_name`.
- The item an entity holds that no attachable draws for it. A Bedrock model says where its hands are
  by carrying a `rightItem` or a `leftItem` bone, which forty of Mojang's own entity geometries do,
  and the item is drawn there at the size `held_item_scale` works out, at full brightness where
  `held_item_ignores_lighting` asks for it, and not at all while `hide_held_items` holds. A model
  carrying neither bone has nowhere to put an item and draws none, which is what Bedrock does too.
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
  is shown as the Java one instead. A quad turns the way the effect asked: to the camera on every
  axis or about the vertical one, or along the particle's own direction, which the pack either
  states outright or takes off the velocity once it is moving faster than the threshold it named.
- The conversations an NPC opens. A scene under `dialogue/` states a name, a body of text and a row
  of buttons, which is what one of Bedrock's own forms states, so it is drawn by the screen this mod
  already turns a form into. Each button runs the commands the scene gave it with the NPC's own
  authority and with `@initiator` standing for whoever is reading the screen, and the scene's opening
  and closing commands run either side of that. `/dialogue change` says which scene an NPC shows,
  `/dialogue open` shows one now, and interacting with an NPC that has been given a scene opens it.
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
| Command names | 78% |
| Command keywords | 63% |
| Game rules | 78% |
| Particle components | 100% |
| Particle component fields | 100% |
| Particle curves and Molang variables | 100% |
| Loot table functions | 83% |
| Loot table conditions | 100% |
| Recipe types | 100% |
| Recipe fields | 100% |
| Trade table fields | 100% |
| Trade item functions | 80% |
| Geometry, animation and controller fields | 97% |
| Pack file kinds | 88% |
| `@minecraft/server` and friends | 100% |

Every name in the remaining share is counted and shown by `/mcaddon entities` and `/mcaddon
scripts`, so a pack relying on one is told rather than left wondering. Run the censuses yourself to
get the current figures; the ones above are a snapshot.

## What does not work

**What a pack writes on the player rather than on an entity of its own.** `minecraft:player.hunger`,
`player.saturation`, `player.exhaustion`, `player.level` and `player.experience` each state a `max`
and a `value`, and `minecraft:exhaustion_values` prices each of ten actions against the food bar.
Bedrock ignores all six anywhere but on the player, so the only place a pack can write them is its
own `entities/player.json`. Java holds every one of the numbers: `FoodData.setFoodLevel`,
`setSaturation` and `addExhaustion` are public, and `Player.experienceLevel` and `experienceProgress`
are fields, so what stands in the way is where the numbers are applied rather than whether Java has
them. The five values are a player's starting state, which belongs to a first join and a respawn
rather than to every login; the five `max` fields are caps `FoodData` holds as literals; and the ten
exhaustion prices are constants at the call sites of `Player.causeFoodExhaustion`, one per action,
which is a mixin each and a different method name on each of the four versions. Mojang's own
`player.json` writes the numbers Java already uses, 0.1 for a sprint, 0.005 for mining and 6.0 for
healing among them, so a pack that changes none of them is served correctly today. This mod keeps
the game's definition of such an entity and names every component written on the pack's, these six
included, so a pack overriding a vanilla entity is told which of its components went unread.

**An entity in a bucket.** `minecraft:bucketable` would make a mob one a bucket picks up, which Java
has an interface for: a mob implementing `Bucketable` answers `getBucketItemStack()` with the item
the player is left holding. What the component does not say is which item that is. Its whole
published schema is `{"title": "minecraft:bucketable", "type": "object"}` with no `properties` and
the one-line description "Defines interactions with this entity", no entity in the vanilla behaviour
pack declares it, and no documentation names a field, so there is nothing in a pack to build the
bucket out of. It is counted and named.

**Aim assist, pack settings and the device memory tiers** describe a Bedrock client rather than a
world, and are counted and named the same way.

**An item's data value.** `set_data`, `random_aux_value`, `set_data_from_color_index` and
`random_block_state` all pick one of the things a Bedrock id stands for by number: `minecraft:dye`
at 0 is an ink sac, `minecraft:wool` at a sheep's colour index is that colour of wool, and
`minecraft:bed` and `minecraft:banner` at 0 to 15 are the sixteen of each. Java has every one of
those under an id of its own, because that is what flattening did, so the number is a lookup into a
published family rather than a value with nowhere to go. What is missing here is the table: sixteen
entries per family for the families Bedrock still writes, and, for the colour index, the colour of
the entity the loot is about. Until it exists the entry naming `minecraft:bed` or `minecraft:wool`
names an item Java has none of, and the function is counted. The same is why a recipe's `data`
beside an item, and a result naming one, are read and reported. `minecraft:stacked_by_data` is a
different thing: it says whether two stacks of one id but different numbers may merge, and in Java
two stacks of one id are the same item, so both answers to it describe what already happens.

**What a bag may hold.** `minecraft:storage_weight_limit` caps what a bag carries at up to 64 and
`minecraft:storage_weight_modifier` prices one item inside one at 4 by default. Java counts the same
two numbers and keeps both inside `BundleContents`: `BundleContents.Mutable.getMaxAmountToAdd`
returns `(Fraction.ONE - weight) / perItem`, and an item's weight is the private static
`BundleContents.getWeight`, one over its stack size. `Mutable` holds the items, their total and the
selected index, and no reference to the stack carrying them, so a cap of a pack's own cannot be
handed to the arithmetic; it needs the whole insertion path replaced, which is a mixin on both of
`BundleItem`'s `override*` methods and on `getWeight`. 1.20.1 has no `BundleContents` at all. A bag
from a pack is a Java bundle of the ordinary size, and both components are counted.

**A camera and a touch button.** `minecraft:camera` times the black bars, the shutter and the
photograph sliding away, which is a full-screen animation the Bedrock client plays while a player
takes a picture in a world. Java has neither the picture nor the screen. `minecraft:interact_button`
writes the word on the button a touchscreen shows for using what is held, and Java has no touch
controls to put one on.

**The sounds a swing makes.** `minecraft:swing_sounds` states `attack_miss`, `attack_hit` and
`attack_critical_hit`, and Java plays exactly those three: `SoundEvents.PLAYER_ATTACK_NODAMAGE`,
`PLAYER_ATTACK_STRONG` and `PLAYER_ATTACK_CRIT`, from the method `Player.attack` hands its visual
effects to. What each field takes is a member of Bedrock's own level sound event list, 570 names the
engine resolves through a catalogue of its own, and only some of those are spelled the same as a
Java sound event; the rest would need the name mapping this mod already keeps for the 70 ambient and
music names a biome may write, widened to a list four times longer. The three sounds have a Java
home, the naming does not, and the component is counted.

**What prices a trade, read out of a loot table.** `enchant_book_for_trading` states four costs
beside the enchantments it picks between: a base, a base spread, and both again per level. In a trade
those decide the emeralds the offer charges, and this mod charges them. In a loot table there is no
other side to put a price on, so the book is enchanted and the four costs are counted.

**A Bedrock biome tag with no Java biome carrying the same idea**, `sulfur_caves` among them, is
counted by `/mcaddon entities` rather than dropped without a word, and a `biome_has_tag` asking for
one then reads as false.

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
points it at a structure. `random_aux_value` is the one left, for the same reason as in a loot
table: the shepherd offers `minecraft:bed` and `minecraft:banner` at a number between 0 and 15, and
picking the right one of Java's sixteen needs the flattening table nothing here holds yet. It is
counted instead.

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

**A bone built out of a texture.** `texture_meshes` extrudes geometry from a texture's opaque pixels,
placing the sheet by a `local_pivot`, a `position` and a `rotation`. That is the same construction
Java's own `ItemModelGenerator` performs for a flat item model, so nothing about Java stands in the
way; what is missing is the reader, because the geometry pipeline here bakes boxes out of JSON and
never opens an image. Eleven of Mojang's declarations use it, every one of them an item attachable,
the bow and the crossbow among them. A bone written that way is drawn as the boxes it also declares,
which for those eleven is nothing.

**A bone bound to an entity Java draws with a model of its own.** A `binding` names a bone of
whoever carries the model, which works for a pack's own entity and has nowhere to land on a player
or a vanilla mob, whose Java model has parts rather than bones with no two models naming theirs
alike. Such a binding is reported by the bone name it asked for, the same way `parent_setup` is.

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

**Three of a particle's facing modes.** `emitter_transform_xy`, `emitter_transform_xz` and
`emitter_transform_yz` lay the quad in a plane of the emitter's own transform, and a particle here
carries the position it was born at and nothing of the emitter it came from, so there is no
transform to match. They fall back to facing the camera and are counted. The other eight all work
from 1.21 on: `SingleQuadParticle.FacingCameraMode` became an interface with two values rather than
the whole vocabulary, so the four that aim a quad along the particle's direction are supplied here
instead of being turned into one of Java's. 1.20.1 has no such interface and turns every quad to the
camera, so those four are counted and named there.

**A fifth of Bedrock's commands.** The twenty left divide three ways. `aimassist`, `controlscheme`
and `inputpermission` describe a controller, a touchscreen and what a Bedrock client lets a player
do, and `packstack` and `project` describe that client's pack list and the Bedrock Editor.
`connect`, `wsserver`, `sendshowstoreoffer`, `setmaxplayers`, `ops`, `permission`,
`editor-allowlist`, `reloadconfig` and `reloadpacketlimitconfig` speak to a websocket server, a
marketplace or a Bedrock server's own configuration. `camera`, `camerashake`, `fog` and `hud` name a
client the server drives through a packet Java has no counterpart of; the last two are the nearest of
the twenty to being possible here, because Java does draw fog, this mod already reads a pack's own
`fogs` files into biomes, and Java does draw a heads-up display whose pieces a client mixin can skip.
What each would take is a payload of this mod's own and a client that acts on it. A function using
any of them keeps its other lines and the dropped command is reported, and so is a line whose
selector asks for something Java cannot select on, such as `hasitem`.

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
Bedrock calls the nether. `schedule` is rewritten too, because the two editions agree about more of
it than the word suggests: Bedrock's `schedule delay add <function> <time> <replace|append>` is
Java's `schedule function <function> <time> [append|replace]` with one keyword moved and the same two
words at the end, and `schedule clear` is spelled alike in both. `schedule on_area_loaded`, which
waits for chunks, is the form Java keeps no queue for and the one that is reported.
`playanimation`, `event`, `scriptevent`, `music`, `toggledownfall`, `structure`, `mobevent`,
`particle`, `dialogue`, `gametest` and `transfer` are registered by this mod, and a line using one is
written out in the form the command built here reads. `transfer` hands a player to another server,
which Java carries as `ClientboundTransferPacket` from 1.20.5 rather than as a command of its own; a
line naming no port means the port a Java server listens on, because a Java client sent to Bedrock's
own 19132 reaches nothing. 1.20.1 has no such packet and the command says so rather than reporting a
move that never happened. Of Bedrock's thirty-seven game rules, the
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

**Bedrock's own screen language.** `ui` is the Bedrock client's whole screen tree written out as
JSON. Mojang's resource pack ships 189 of those files, declaring more than four thousand controls in
twenty-three element types and reading 1441 distinct binding names back out of the running client.
What a pack writes there reaches a player by replacing a control inside one of the engine's own
screens under the name the engine gave it: nothing in a pack opens a screen of its own, because
`_ui_defs.json` adds a file to the list and that file is drawn only where one of the engine's screens
already points at it. A Java screen is a class rather than a named tree, so there is no
`hud_screen.json` to patch and no control name for an override to match, and the bindings name values
the Bedrock client holds with no published list saying what any of them is. What a pack can put in
front of a Java player is what `@minecraft/server-ui` describes and what an NPC dialogue scene
states, and both of those are drawn.

**A camera that is somewhere the player is not.** `cameras` holds the presets `/camera` picks
between, and a preset a pack writes inherits from `minecraft:free` or from another of its own, so
every one of them is a camera standing at a position and a rotation while the player stays where they
are. The server has no way to say that: `ServerPlayer.setCamera` calls `teleportTo` with the camera
entity's own position before it sends `ClientboundSetCameraPacket`, which is why
`Camera.attachToEntity` works here and a preset does not. A client can say it, because
`net.minecraft.client.Camera` keeps its `position`, `xRot`, `yRot` and `detached` as fields a mixin
on `setup` may overwrite, which is what a free camera mod does; that is a payload of this mod's own,
a client that acts on it, and the easing `/camera` states, and none of the three is built. Of the
fourteen things a preset states beside its identifier and what it inherits from, `pos_x`, `pos_y`,
`pos_z`, `rot_x` and `rot_y` are the only ones naming anything Java holds at all. `player_effects`,
`listener`, `extend_player_rendering`, `rotation_speed`, `snap_to_target`, `continue_targeting`,
`tracking_radius` and the two rotation limits each describe the Bedrock client's own renderer, its
audio or the input it takes away while a camera is up, and Java has none of the three.

**The index beside a pack's textures.** `textures/texture_list.json` is a flat list of texture paths
written without their extensions, which the Bedrock client keeps so it can resolve a texture by name
without searching the directory. It states nothing the pack's own files do not: Mojang's resource
pack lists 20 of the 8039 images it ships, every one of them a file that is there and already named
by `terrain_texture.json` or `item_texture.json`. The conversion walks `textures/` itself, so what it
writes covers whatever the index names and reading the index would change nothing. It is counted and
named all the same, because a file this mod never opens is a fact the pack's author is owed.

**A dimension with a generator nobody wrote down.** Bedrock builds a dimension a pack declares as
empty space and accepts `void` and no other generator, so a file naming another one is counted and
named rather than turned into a chunk generator this mod guessed at. A `minecraft:default_biome`
naming one of the pack's own biomes is named too and the world is made of the void instead: measured
on 26.2, a `dimension` entry referring to a biome out of the same pack loads on a dedicated server
and stops a client on the screen asking the player to fix their packs.

**Four of the renderer directories, field by field.** Bedrock reads all four only under Vibrant
Visuals, its deferred pipeline, and a client biome names each through
`minecraft:atmosphere_identifier`, `minecraft:lighting_identifier`,
`minecraft:color_grading_identifier` and `minecraft:water_identifier`. Of their 47 published fields,
six have a Java home and all six of those arrived in 26.

That 26 answer is `EnvironmentAttributes` with the `timeline` registry beside it.
`RegistryDataLoader` loads `Registries.TIMELINE` from a data pack, a `Timeline` is an
`AttributeTrack` per attribute made of a `KeyframeTrack` and a modifier, and a `DimensionType` names
a `HolderSet<Timeline>` while a `Biome` carries a flat `EnvironmentAttributeMap` of its own. So 26
does hold a keyframe table over the day that a pack may write, which is the shape both `atmospherics`
and `lighting` are written in. Before 26 there is no such table and no such vocabulary.

`atmospherics` states `sky_zenith_color` and `sky_horizon_color`, each keyframed. The zenith colour
is `EnvironmentAttributes.SKY_COLOR` on 26 and `BiomeSpecialEffects.skyColor` before it, as one value
rather than a curve; the horizon colour is nearest to `SUNRISE_SUNSET_COLOR`, which Java paints at
dawn and dusk only. Its other eight fields are the scattering model itself, `rayleigh_strength`,
`sun_mie_strength`, `moon_mie_strength`, `sun_glare_shape` and the four `horizon_blend_stops`, and
Java simulates no scattering and draws its sun as one textured quad.

`lighting` states four fields Java holds: `ambient.color` is `AMBIENT_LIGHT_COLOR`, `sky.intensity`
is `SKY_LIGHT_FACTOR`, `orbital_offset_degrees` is `SUN_ANGLE` and `MOON_ANGLE`, and the sun and moon
`color` and `illuminance` curves fold into the one `SKY_LIGHT_COLOR` and `SKY_LIGHT_LEVEL` Java
carries, which is four curves crossing into two. `ambient.illuminance` is nearest to
`DimensionType.ambientLight`, one float for a whole dimension. What has no home is the lightning
`flash` colour and illuminance, which Java computes in its renderer with no data behind it, and
`emissive.desaturation`, because Java has no emissive pass to desaturate.

`color_grading` has nothing. Its `contrast`, `gain`, `gamma`, `offset` and `saturation` per tonal
band, its Kelvin `temperature` and its tonemapping `operator` all describe a grade, and a Java post
effect is `PostChainConfig`, which is internal targets and passes and no grading term at all, with
nothing binding a chain to a biome or to a dimension.

`water` has nothing either. Its `particle_concentrations` in milligrams per litre, its five
`caustics` fields and its ten `waves` fields are a deferred water model, where Java water is a
flipbook texture tinted by one colour. The nearest is `biome_water_color_contribution`, which weights
how much of a client biome's own `surface_color` reaches that model, and that surface colour is
already read here in full; a weight of anything but the whole of it has no Java field. The water a
pack paints comes out of `minecraft:water_appearance` on the client biome rather than out of this
directory.

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
read as that biome's water fog. `minecraft:precipitation` becomes the biome's ambient particle,
`minecraft:ambient_sounds` its loop, mood and addition, and `minecraft:biome_music` the track named
by the entry it points at in `sounds/music_definitions.json`: 26 states each as an attribute, and
1.21.1 as a field of the `effects` block. The three underwater ambient moments have no Java field on
any version, and the music's volume multiplier and its underwater track reach 26 alone. Each of
those is counted and named, and so are the water surface transparency and the identifiers naming a
deferred renderer's atmosphere, lighting, water and colour grading. On 1.20.1 no biome from a pack is
converted at all, so every biome the resource pack paints is counted and named with the version that
would draw it.

**Two scales for one falling particle.** Bedrock states a precipitation density and Java the chance
it spawns a particle on a tick, and the ratio between the two is not the same for all four visuals.
Mojang writes both numbers for the four biomes that carry one: a white ash density of 2 against a
white ash chance of 0.118, an ash density of 0.05 against 0.00625, and a spore density of 0.25
against 0.025 for the red and 0.014 for the blue. The conversion is anchored on each of those four
pairs, so a pack writing Bedrock's own density gets Java's own chance and a pack writing twice that
gets twice the chance, up to the certainty Java's field allows.

**A biome naming one of the engine's own sounds.** Bedrock's built-in sounds live in the engine
rather than in any add-on, so a biome or a music definition naming one has nothing in the pack to
convert. Half of the engine's 70 ambient and music names are spelled the same in both editions and
go straight across; the other 35 are not, and `ambient.soulsand_valley.loop`, `music.game.endboss`
and `music.overworld.mesa` are named against Java's `ambient.soul_sand_valley.loop`, `music.dragon`
and `music.overworld.badlands` instead. A pack's own `sound_definitions.json` is searched first, so a
pack shipping a definition under one of those names means its own file. What decides the rest is the
sound event registry rather than the list, because Java gained two of these names after Bedrock had
them: all 70 reach an event on 26.2, 69 on 26.1.2, which has no sulfur caves music, and 68 on the
older two, which have no end flash either. A biome is one entry and an id the registry has not got
costs the whole biome, so a name nothing answers is reported and the biome keeps whatever the game
already plays there.

**Worldgen on 1.20.1.** An entry a data pack puts in a worldgen registry leaves a world stable only
while the pack it came from carries a `KnownPack`, and a converted pack carries one from 1.21 on.
1.20.1 has no such class at all: `RegistryDataLoader` keeps the decoded lifecycle for anything a pack
that is not built in supplies, a worldgen codec decodes as experimental, `PrimaryLevelData` carries
that through, and `WorldOpenFlows` then asks the player whether to load the world at all. Nothing
records the answer, so it is asked on every open, forever. A pack shipping worldgen is a pack too new
for that version, which is the answer Bedrock itself gives to a pack written for a newer engine. On
1.20.1 nothing under `biomes`, `features`, `feature_rules` or `dimensions` is written and every one
of them is
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
