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

## Playing on a server

Install the mod on the server and on every client, and put the packs in the server's `addons/`
only. The server decides what happens; the client draws it.

A client joining a server running this mod is handed the server's add-ons as it joins, with no
restart. It downloads the ones it lacks into `.mcaddon/received/`, registers what they add, and
plays with exactly the server's add-ons for that visit: an add-on the client installed and the
server has not got stays loaded and does nothing until the client leaves, and one the server has
in another release is replaced by the server's. Leaving puts the client's own add-ons back. A
downloaded add-on is kept for the next visit, so joining again downloads nothing, and one no server
asked for in a month is dropped. The same holds for a guest joining a world opened to LAN: the host
hands out its add-ons, and the host itself never downloads or changes anything.

What a client refuses, each with a message naming why:

- a server running another release of this mod;
- an add-on larger than 256 MB, more than 1 GB in all, more than 64 add-ons, or more than the disk
  has room for;
- an archive that arrives with other bytes than the server promised, or that unpacks into more
  than 50000 files or 1 GB;
- a release of an add-on the client already loaded whose items, blocks or entities are built
  differently, such as a block with another set of states or an item with other components. The
  message names the add-on: remove or replace it in `addons/` and start the game again.

A client without this mod cannot join a server that has add-ons, and is told which mod to install.
Against a server without this mod a client plays with its own add-ons.

## What works

- Finding, unpacking and ordering packs, with their dependencies respected and their problems
  reported rather than swallowed.
- Textures, including Targa. A `pbr` pack's texture sets are rewritten as labPBR sidecars, which is
  what Iris and OptiFine shader packs read, so a physically based add-on lights correctly under any
  shader pack and costs nothing when there is none. Bedrock reads a texture's alpha by the material
  drawing it. A colour mask reads it as how much of a texel to tint and drops only a fully clear
  texel, which is why Mojang's own textures mark a sheep's face at an alpha of three; Java's cutout
  shader keeps only what is above a tenth, so such a mask is converted with every texel that carries
  any alpha made opaque. A plain alpha test drops everything under half, so beside a texture holding
  faint texels the conversion writes a second form with those cleared, and a pass whose material only
  tests alpha is drawn with that one. A channel also holding alpha Java draws partially is a real
  fade and is left as the pack wrote it.
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
  not, plain or glowing, and lit by the light its controller asked for. A controller's `color`
  tints a pass only where its material is a colour mask, such as `entity_alphatest_change_color` or
  one defining `USE_COLOR_MASK`, because Bedrock's entity shaders read it nowhere else. Such a mask
  tints each texel by its own alpha, so over a texture holding partly clear texels the pass is drawn
  untinted and the tint is laid over it in a second, blended pass that weighs each texel by the alpha
  the pack painted; a blended colour mask over such a texture is tinted whole and reported. Its
  `overlay_color`, `is_hurt_color` and `on_fire_color` are laid over every material that keeps
  `USE_OVERLAY`, which the game's beams and a pack's own material can remove. A hurt or dying
  entity flashes red the way every Bedrock entity does, unless its controller states an
  `is_hurt_color` of its own, which is drawn instead, or its material removes `USE_OVERLAY`. A pass whose material
  defines `MASKED_MULTITEXTURE`, as the game's villager outfits do, draws its second texture over its
  first with the second's clear but coloured texels showing the first through, which is how a
  villager's profession is worn over the clothes of its biome. A controller's `materials` list gives each bone
  the material of the last entry naming it, with a star standing for any run of letters, so a
  villager's hat can be drawn masked while the rest of it is not. A model reaching past the box the server gave the entity is culled against
  the extent its own geometry declares, so wings and a tail stay drawn while the hitbox is off the
  side of the screen. A controller state a pack gave a blend transition is crossed out of rather
  than left, so a walk releases into an idle instead of snapping. An entity whose pack asked for it
  keeps running while nobody is looking at it, so a trail or an aura is still going when it comes
  back into view, and a pack describing one entity several times over for different engines is drawn
  from the newest of them its own engine version reaches. A bone an animation turns
  `relative_to` the entity is turned in the entity's frame while the bones above it still carry it,
  so a head keeps looking at its target while the body under it leans, on a pack's own model and on
  a vanilla mob a clip is played on alike. A controller enters its first state on the first frame it
  runs, running that state's `on_entry` and playing its clips, and asks its transitions from the next
  frame on.
- Entities a behaviour pack declares, with their properties, events, component groups, filters,
  breeding, riding, leashing, home ranges, navigation and item pickup, and the sensors that fire
  their events: environment, entity, target-nearby, block, timer, scheduler, drying-out, damage,
  interact, spawn-entity and hurt-on-condition. Their properties reach every client, so a model
  looks the same in multiplayer as it does alone, and a property a script overrides for one player
  with `Player.setPropertyOverrideForEntity` reaches that player's client alone. A component group is where Bedrock states what an
  entity becomes rather than what it is, so a group arriving changes how it gets about: the
  navigator and the move control are built again, and the step it takes, the height it climbs, what
  pushes it, what burns it, what effects land on it and what it has for sale all move with it.
  Each rider sits in a seat of its own: a seat is in use while the number of riders lies between its
  `min_rider_count` and `max_rider_count`, and the riders take the seats in use in the order the
  pack lists them, at each seat's own position turned with the mount. A seat's `rotate_rider_by` is
  asked every tick as its rider and turns the rider that far from the way the mount faces, held
  there by `lock_rider_rotation`, on every client watching as well, so a player in the seat is
  turned on the client that steers it. Bedrock keeps no Molang temporary to one expression, so
  neither does the server: every expression it runs, a seat's, a script's, a component's condition,
  an experience reward and a block event alike, reads and writes one store of `temp.` values, which
  is how a pack learns a villager's level by seating it.
- The clips and controllers a behaviour pack declares, which animate what an entity is rather than
  where its bones are. A moment on a clip's timeline, and a controller state's entry and its exit,
  each raise one of the entity's own events, run a command as the entity, or assign a Molang
  variable the controllers read back, and the whole state machine runs on the server off the
  entity's own tick. A controller moves at most once a tick and starting counts as a move, so its
  first state's clips play once even when a transition out of it holds from the start. What its
  transitions ask is answered from the entity itself, so a controller
  waiting on `query.is_on_ground`, on the entity's health or on a property the pack declared moves
  when the world does. A command a state names runs with the server's own authority and with `@s`
  standing for the entity, because a mob carries no authority of its own and a pack writing a
  command means the one the console would run.
- What a pack's entity can be besides a mob that walks about. One a pack calls a projectile flies
  the arc its own component describes, spread by the inaccuracy the pack asked for, and on landing
  it damages, burns, poisons, sticks in the ground, drops experience, throws its particles and fires
  the pack's own event; what shoots it is an ordinary `minecraft:shooter`, or a player's bow or
  throwable item, which sends it off at its own power and leaves the thrower safe from it for the
  ticks the pack says and named in its damage. One that belongs on rails
  stays on the track at the speed the pack allows and answers a live rail with its own event,
  putting its riders off where it was told to. One that remembers writes down what came near it and
  where it heard something happen, and its memory behaviours walk to that, flee it, or hold the
  place it chose. One given the ender dragon's death takes the dragon's two hundred ticks over it
  rather than a mob's twenty. And one declaring hit boxes wider than the box it collides in is hit
  where it looks hit, by a swing and by an arrow alike, while still fitting through the same gap.
  A projectile turning `isolated_physics` off, as Mojang's own arrows do, is moved the way any
  entity is, so it stops against what it hits and a cobweb holds it back.
- What the world does to a pack's entity. Powder snow freezes it unless `minecraft:freezing_immune`
  says it never freezes, the cold hurts it five times over where `minecraft:freezing_vulnerable`
  says so, powder snow bears its weight under `minecraft:can_stand_on_powder_snow`, and the blocks
  `minecraft:block_movement_slowdown_immunity` lists let it through at full speed, Bedrock's `web`
  being Java's cobweb. Each is asked of the entity rather than of its type, so a group bringing one
  in changes the entity while the group is on. `minecraft:uses_legacy_ambient_sounds` picks its idle
  sound by whether its head is under water, it is off the ground or it is young, from the moments
  its `sounds.json` names. A group taking `minecraft:leashable` or `minecraft:leashable_to` away
  lets go of the lead, which is what `unleash_on_removal` asks for and defaults to.
- The numbers inside an entity's components, in Bedrock's units and with Bedrock's defaults where a
  pack leaves one out. An entity holds its breath for `total_supply` seconds in whatever it cannot
  breathe, and one declaring nothing to breathe never drowns. Its navigation prices water, lava and
  blocks that hurt as its allowances say, and a generic navigation that swims and never walks plans
  like a fish. It turns no faster than `max_turn`, thirty degrees a tick unless the pack says
  otherwise, strolls and swims as far as `xz_dist` and `y_dist`, looks at a player for its
  `look_time`, is tempted as far across as `within_radius`, walks to an item to pick it up at the
  behaviour's own priority and jumps at its `jump_power`. It hunts and answers what the filters of
  its `entity_types` name, the game's own entities answering with the families Mojang's behaviour
  pack gives them, and an angry one hands its quarry to its own kind. It follows its owner, breeds
  only once tamed unless `require_tame` says otherwise and rests for `breed_cooldown` afterwards, and
  raises the event `breeds_with` names for its mate. A transformation waits out its delay, a lit
  fuse goes off when it has burned, a home holds the entity only when its `restriction_type` says
  to, and a bump or a piston pushes it as its presets say. A lead goes only on an entity carrying
  `minecraft:leashable`, stretches and breaks where the preset for its holder says from 26, and
  `can_be_stolen` says whether a player may take it off whatever holds it. A name tag names only what
  `minecraft:nameable` lets one rename, and the name raises the event its `name_actions` lists.
- How a pack's entity fights, flees and settles, by its behaviours' own fields. A melee blow waits
  out `cooldown_time`, reaches as far as `reach_multiplier` or `horizontal_reach` say, lands only
  inside `melee_fov`, raises `on_attack` and `on_kill`, and passes a burning attacker's fire on
  under `can_spread_on_fire`; a blow also carries the ailment `minecraft:attack` names. A ranged
  attack reloads for its `attack_interval`, stretched across its `attack_range`. A panic starts at
  the causes `damage_sources` lists, can leave every mob's blow out, heads for water under
  `prefer_water` and needs no hurt under `force`; fleeing a block raises `on_escape` once clear; and
  the panic and the flight make the sound the pack names for them. A teleporter closes on a far
  target and blinks away from a projectile, a pack's own projectile is batted back, stopped or
  removed when struck, and a blast breaks blocks whatever the griefing rule says unless the pack asks
  for the rule, under a cap on what a block withstands. A child bred under `minecraft:offspring`
  takes a tamed parent's owner, a parent's variant, mark variant, colour and listed properties
  unless a mutation keeps its own, the dye two colours make, and attributes blended the way Java
  breeds a horse, and a bred child arrives with `minecraft:entity_born` rather than
  `minecraft:entity_spawned`. A mount under `minecraft:tamemount` is broken in by riding, its temper rising with
  its food and each buck, and any rideable entity is mounted by using it, pulls in what touches it
  under `pull_in_entities` and raises its rider events. A school flocks by its separation, cohesion,
  variant matching, flock limit and loners; an entity walks out under the sky or in under a roof,
  looks as far for water as it says, drinks the potion its conditions pick by Bedrock's number,
  holds no more of an item than its shareables allow, leaps at its `yd`, and steps higher while a
  player steers it. A stomper looks for turtle eggs as often as its `interval` says. A villager
  explores past the edge of its village by `explore_dist`, a fox strolls to the nearest village, a
  child plays with the friend its `friend_types` name, and `minecraft:behavior.hide` runs to the bed
  or other place `poi_type` names when a bell rings or a raid is on. Java's village is the beds, job
  sites and bells within reach, since Java keeps no village of its own.
- Damage causes in Bedrock's own words. A damage sensor, the `has_damage` filter, a panic, playing
  dead, damage absorption, `minecraft:hurt_on_condition`, a block event's damage, the `damage`
  command and a script's `applyDamage` and `entityHurt` all name a cause the way Bedrock does
  (`entity_attack`, `fire_tick`, `suffocation`), and each is matched against the Java damage types
  that are the same harm on the version that runs. A cause Java has no type for, such as
  `temperature`, is dealt as the nearest one Java has and hears that one too.
- An entity in a bucket. `minecraft:bucketable` lets a player scoop the entity up with a water
  bucket and let it out again in the same component groups it was scooped up in. The component
  names no item, and Java holds a fish in an item per kind of fish, so each such entity is carried
  in a bucket under its own id with `_bucket` after it, drawn as a bucket of water and named after
  what is inside.
- What a pack writes on the player rather than on an entity of its own. Bedrock reads
  `minecraft:player.hunger`, `player.saturation`, `player.exhaustion`, `player.level` and
  `player.experience` on nothing but the player, so a pack states them in its own
  `entities/player.json`, and each states a `value` and a `max`. The values are a starting state, the
  same one Bedrock builds into a player it makes, so they are written at a first join and at every
  respawn and a later login reads back what the last one left. The ceilings are held wherever the
  value is next looked at, because Java keeps its own inside `FoodData` as literals and keeps none at
  all for the level. `minecraft:exhaustion_values` prices ten moments against the food bar, and each
  is charged where Java charges its own: attacking, being hurt, healing off a full bar, jumping,
  sprint-jumping, mining, sprinting, swimming, walking or crouching, and lunging on the versions
  that have the enchantment. A price is met by scaling what Java was about to spend, so the distance
  in a movement charge and the weight of the damage type in a damage charge both survive it. A field
  the pack leaves out stays Java's own.
- Items, including edible and wearable ones, spawn eggs, and a creative tab per add-on. An item or a
  block naming its category in `menu_category` also joins the Java tab that category stands for:
  construction is building blocks, nature is natural blocks, equipment is combat and items is
  ingredients. Food takes as long to eat as the pack says, which before 1.21.2 is one of Java's two
  eating times, the quick one for a pack asking for 0.8 seconds or less. A dyeable
  item takes the colour the pack painted it and the one a player dyes it. A bow draws and fires
  whatever the pack loads it with. A bag holds other items. A drop the pack asks to keep lies there.
  An item the pack lets into the off hand goes there and one it does not is refused, an item that
  clips liquids aims at water and lava, and one carrying a durability sensor shows the particle and
  plays the sound its threshold names as it wears down.
- `/bedrock camera` and `/bedrock camerashake`, which set one player's own view: all seven of
  Bedrock's presets, a position and a rotation of the pack's choosing or a point or an entity to face,
  all thirty-one of Bedrock's easing curves, and a shake that moves the camera or turns it. An ease to
  one of the three gameplay cameras is refused the way Bedrock refuses it. `minecraft:follow_orbit`
  circles the player at a radius and turns as the player turns, with `rot` turning the player;
  `minecraft:fixed_boom` follows the player at one angle and does not turn with them; and both take
  `view_offset` and `entity_offset`. `minecraft:control_scheme_camera` is drawn as the orbit, because
  Mojang publishes nothing about how it differs. `attach_to_entity` and `detach_from_entity` hang the
  camera off another entity, which an orbit camera then circles and any other camera looks out of.
  `target_entity` with its `target_center_offset` turns a free camera after an entity until
  `remove_target`, and `play_spline` carries a free camera along a curve a behaviour pack describes in
  `camera/splines/`. A behaviour pack's own `cameras/presets/` are presets too: each inherits from one
  of Bedrock's or from another of the pack's, takes what it states over what it inherits, and is set by
  `/camera set` and by a script's `Camera.setCamera` and `Camera.setDefaultCamera`, with whatever the
  command or the script states over the preset's own. A preset with `"listener": "player"` hears from
  the player's head while the camera is away from it. A script also has `Camera.attachToEntity` and
  `Camera.playAnimation`.
  `/bedrock camera ... fade` covers the screen in black or the colour it names, coming in, holding
  and going out over Bedrock's own one second, half a second and one second unless it names others,
  and a fade asked for while one runs is combined with it the way Bedrock combines them. `fov_set`
  and `fov_clear` move the field of view to a value or back to the player's own, over the seconds and
  the ease they name; what sprinting or being under water does to the view still happens on top.
- `/bedrock fog`, which layers a pack's own `fogs/` settings over the one the game would draw, with a stack
  per player that `pop` and `remove` take apart the way Bedrock's do. Like the two camera commands it
  is decided on the server and sent to each player alone, so setting one player's view in a
  multiplayer world leaves everybody else's as it was.
- `/bedrock hud`, which hides pieces of one player's heads-up display and draws them again: the
  hotbar, the crosshair, the health, the hunger, the armour, the air bubbles, the bar above the
  hotbar, the status effects, the held item's name and a ridden mob's health, on every supported
  version. `all` and a line naming no piece hide every one of them, and a script's
  `ScreenDisplay.setHudVisibility`, `hideAllExcept` and `resetHudElementsVisibility` change the same
  set that `getHiddenHudElements` reads back. Like the camera it reaches the targets' clients alone.
- `/bedrock inputpermission`, which takes one kind of input away from a player and gives it back. The
  camera, walking in each of the four directions, jumping and sneaking are let go of on that
  player's own client before the player moves by them, and getting onto a vehicle or off it is
  refused by the server; `movement` takes all of those but the camera at once. `query` reads a
  category back, `haspermission` selects by it, a script's `Player.inputPermissions` changes the
  same set, and `playerInputPermissionCategoryChange` is raised on every change. A player's set lasts
  as long as they are connected.
- Records. A record a pack ships goes into a jukebox and plays, for the length the pack stated, with
  the comparator reading it asked for. Bedrock lets a pack give a record music of its own by writing
  one of the engine's own record sound names in its `sound_definitions.json`, which is the only way
  it can name one, and that definition is the sound the jukebox plays. A record naming a sound the
  pack never defined has nothing to play and is reported rather than registered silent.
- The custom components a pack registers from its own script, on its own blocks and items: placing,
  breaking, clicking, standing on and ticking each reach the pack's own callback. Both of Bedrock's
  spellings are read: the list under `minecraft:custom_components` an older pack writes, and from
  1.21.90 each component as a key of its own, whose body reaches the callback as its `params`.
- Blocks, with the states and permutations a pack declares: each combination gets its own model, the
  permutation conditions are evaluated through the Molang evaluator, and a block drawn with its own
  geometry keeps it. What a permutation changes about a state follows the block into that state: its
  textures and its turn, its collision and selection boxes, how long it takes to mine and the light
  it gives. The placement traits add their states too, set as the player places the block: the way
  they faced, turned by `y_rotation_offset`, the face they clicked and the half of it. A block
  naming no `minecraft:loot` breaks into one of itself, as it does in Bedrock. A block naming one of
  the pack's own `shapes/` under `culling_shape` hides the faces that shape covers, which is what a
  Bedrock voxel shape is for and what a Java occlusion shape does. A face of a block's geometry
  naming a material instance wears the texture the block's `minecraft:material_instances` gives that
  instance, and the geometry's `item_display_transforms` turn, move and scale the block's item in
  either hand, on the head, on the ground, in a frame, in the inventory and, from 26, on a shelf. A
  block's own `minecraft:sound` names the sound group it makes its noises from, and outranks the one
  `blocks.json` names.
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
  A trade stays open while a script moves the trader within its own level, as a pack holding a
  talking villager in place every tick does; only a move to another dimension ends it.
  A trader whose pack states no `minecraft:behavior.trade_with_player` stands still while a player
  has its shop open, as one stating it does. Bedrock documents nothing about such a trader, and a
  shop that walks away and closes under the player is the more surprising answer.
  `query.trade_tier` answers the tier a trader has reached, counted from nought the way Bedrock
  counts it, for a pack's own trader and for Java's villager alike, whose novice is 0 and whose
  master is 4.
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
  effects, an explorer map, an ominous bottle's amplifier, a drop that grows with looting and one a
  blast thins out, and the gear a mob spawns in enchanted by the rule Java's own spawning enchants
  it with. A condition Java has a predicate for becomes that predicate; one it has none for is asked
  by this mod as the loot is rolled, which is how an entity's variant, a property a pack declared,
  the world and the regional difficulty, a kill by a player's pet, a Bedrock biome tag and what a
  mob has just swallowed all still decide a drop. That last one is `entity_killed`, which belongs to
  the table `minecraft:behavior.eat_mob` names rather than to a death drop: the goal rolls it as the
  meal goes down, so the mob doing the killing is the entity the loot is about. A function Java has
  none of is answered the same way: a book drops carrying one of the enchantments the pack listed,
  at a level out of that enchantment's own range.
- `/bedrock <line>`, which is how every Bedrock command reaches the game. It takes a whole line in
  Bedrock's dialect, translates it into Java's and runs it, so an operator types
  `/bedrock camera @s set minecraft:free` and a Java data pack's function may hold
  `bedrock playanimation @e[type=minecraft:villager] animation.wave`. Java's own command tree stays
  as Java builds it: `/bedrock` and `/mcaddon` are the only commands this mod adds there, `/particle`
  and `/transfer` take Java's arguments alone, and a Java data pack's `@e[...]` knows only Java's
  selector options. The Bedrock commands Java has not got, `camera`, `camerashake`,
  `clearspawnpoint`, `dialogue`, `event`, `fog`, `gametest`, `hud`, `inputpermission`, `mobevent`,
  `music`, `playanimation`, `scriptevent`, `structure`, `toggledownfall`, Bedrock's `particle` and
  `transfer` and the commands a pack's script declares, are reached through it and nowhere else. It asks for the level Java runs
  a function at, and every command the line names asks for its own on top of that.
- `/bedrock ride` and `/bedrock summon` in Bedrock's own forms. `ride` seats a group only when the
  whole of it fits (`if_group_fits`) or one rider at a time until the vehicle is full, brings the
  vehicle to its one rider (`teleport_ride`), empties a vehicle (`evict_riders`), and summons a rider
  for every vehicle with room or a vehicle for every rider under the three ride rules. Whether a
  vehicle has room is the vehicle's own answer, which for a pack's entity is its
  `minecraft:rideable`, and a player is never a vehicle, as in Java's `/ride`. `summon` makes the
  entity the way Java's own summon does and then turns it to a rotation, a point or an entity,
  names it and raises the spawn event the line picks, and it refuses a hostile mob on peaceful.
- `replaceitem` and `loot replace` into `slot.equippable`, whose first slot is the saddle and whose
  second is the horse's armour or the llama's carpet, as on every vanilla entity carrying a
  `minecraft:equippable`. `replaceitem ... keep` fills a slot only while it is empty, through Java's
  own `execute unless items`, which 1.20.1 does not have, so there the line is reported instead.
- `/bedrock structure` in Bedrock's own form. `save` keeps a region in memory, which is Bedrock's
  default and lasts as long as the server, or writes it beside the world with `disk`, with or
  without its entities and its blocks; `delete` forgets it and deletes its file. `load` puts the
  lowest corner of what the structure fills at the position the line names whichever way it is
  turned or mirrored, mirroring along both axes as a half turn, and takes the entities, the blocks,
  waterlogging, integrity and seed, and Bedrock's animation, which places the structure a block or
  a layer at a time over the seconds the line gives.
- `/bedrock tickingarea`, which forces the chunks of a rectangle or of a circle under a name, as
  Java's `/forceload` forces a chunk, and keeps the name, the shape and the `preload` mark beside the
  world, so an area is listed, found by a position inside it or by its name, and let go of without
  releasing a chunk another area still covers. Bedrock's limits hold: ten areas, a hundred chunks to
  an area and a circle four chunks across its radius. Java loads every forced chunk before the
  world starts, which is what `preload` asks for, so the mark is kept and reported.
- `/bedrock schedule on_area_loaded`, which holds a function back until every chunk of a rectangle,
  a circle or a ticking area is loaded with its entities and then runs it once as the server.
  What is waiting is kept beside the world.
- `/bedrock place featurerule`, which places a pack's feature rule by its name the way the world
  would, with the rule's own distribution starting from the position the line names, and a
  script's `Dimension.placeFeatureRule`, which does the same. The rule's biome filter decides only
  where the world generates it.
- `/bedrock place jigsaw` with the three words Bedrock writes after the position: whether the
  jigsaw blocks stay, whether the entities come along, and whether a block placed into water is
  waterlogged. A word the line leaves out takes Bedrock's value (blocks gone, entities left out,
  waterlogged). 1.20.1 waterlogs every block a jigsaw places, so `ignore_waterlogging` is reported
  there.
- `/bedrock place structure` with the four words Bedrock writes after the position:
  `ignoreStartHeight`, `keepJigsaws`, `includeEntities` and the liquid setting. They apply to a jigsaw
  structure, which is the only kind Bedrock names there. The placement starts at the structure's own
  start height unless `ignoreStartHeight` puts it at the height of the position, and the other three
  words work as they do for `place jigsaw`. The column is the position's own, where Java's short form
  snaps to the chunk. Any other structure refuses the words with a message when the command runs,
  and Java's own `place structure <structure> [pos]` places it as before.
- The commands that run the server rather than the world. `save query` writes out what `save hold`
  held back and lists every file of the world with its length; `setmaxplayers` sets the limit a
  login is checked against for this session only; `packstack server` lists Java's data packs in the
  order they apply, every converted add-on among them, and `packstack client` the add-on resource
  packs; `permission` and `ops` list Java's operators, read `ops.json` again and make a player an
  operator or a member. Like Bedrock's, each asks for more than a function's authority, so an
  operator types it after `/bedrock` and a pack's function cannot run it.
- `/bedrock music`, which plays a track to everyone as music rather than as a sound somewhere in the
  world: in the music category, with the game's own background music waiting until it is over,
  faded in and out over the seconds the line gives, looped when asked, turned up or down while it
  plays, and queued behind a track that plays once. A queue behind a looping track is refused, as
  in Bedrock. A track is a pack's own sound or one of the game's under its full name, or a Bedrock
  name such as `record.cat` or `cat`.
- Six of Bedrock's game rules Java keeps no rule for, set through `/bedrock gamerule` and a
  script's `world.gameRules`, kept beside the world and acted on by this mod. `recipesunlock` off
  stops recipes unlocking as a player collects what they take, which Java does through an
  advancement per recipe, so `/recipe` is then the way a player gets one. `respawnblocksexplode` off
  leaves a bed or a charged respawn anchor standing where Java would blow it up.
  `showcoordinates` and `showdaysplayed` write the player's position and the world's day count at
  the top left of the screen, `showrecipemessages` off shows no toast for a recipe unlocked, and
  `showtags` off leaves out the lines naming what an item may break or be placed on. A world starts
  showing no coordinates and no day count, as one a player makes in Bedrock does.
- Clips on a mob Java draws with a model of its own, such as `playanimation` on a vanilla villager.
  Each Bedrock bone is found among the Java model's parts, and on a mob whose Bedrock geometry hangs
  the head, arms and legs under `body`, those parts move, turn and scale with the body, so a pack
  that hides a villager by scaling its body to nothing hides all of it.
- `.mcfunction` files, written as Java functions whose every line is `bedrock <the line>`, with a
  `tick` function tag. Java's parser reads `/bedrock` and a string, so a file always loads, and a
  line that cannot be translated fails alone. Such a line is reported as the pack loads, whether
  anything ever runs it or not, and again each time it runs. A function called by its
  Bedrock name, which is a path with no namespace, reaches the add-on that ships it: the calling
  add-on's own first, then whichever installed add-on has it.
- The commands a pack runs while the game is going, translated the same way and as the add-on that
  wrote them: a script's `runCommand`, an entity event's `queue_command`, a command on an animation
  or controller timeline, a dialogue scene's buttons and a block event's `run_command`. Each runs to
  its end, functions it calls included, with the authority Java gives a function, and what could
  not be translated, what Java would not parse and what the game declined are each reported.
- Bedrock's selector arguments inside a Bedrock line, the three Java has no form of among them:
  `@s[hasitem={item=apple,location=slot.weapon.mainhand,quantity=2..}]` asks what an entity carries,
  `@a[haspermission={camera=disabled}]` which input a player has been left, and
  `@e[has_property={tp:mood="calm",tp:level=2..4,property=!tp:owner}]` what a pack's entity
  properties hold, a value, a range or a presence, any of them turned round by `!`. Each is answered
  by tagging every entity that answers it while the line runs, so it selects anywhere in an `execute`
  chain and Java's own selector never learns the word.
- Natural spawning from a pack's `spawn_rules`, with each alternative the rule lists becoming a
  biome spawn entry of its own. The weight, the group size and the biome tags decide which biomes
  it is offered in, a pack's own biome answering on the tags its pack gave it; the brightness,
  height, difficulty, world age, distance, density, delay, the surface and underwater and lava
  markers and the blocks it may stand on are asked again of the spot the game is trying, so a mob
  that a pack said belongs on sand at night stays there. The population a rule names is the one
  the entity counts against, so a pack's monster fills the monster cap rather than the animals'.
- A pack's own `structures/*.mcstructure`, placed by `/bedrock structure load` and a script's
  `StructureManager` under the name its path gives it, the first folder being the namespace and a
  file with none taking Bedrock's `mystructure`.
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
  that world opens, and a pack removed again costs the world nothing. This needs 26: before that,
  the same method calls a world experimental as soon as it holds a fourth dimension, and a client
  then asks the player whether to load it at all on every open, so 1.20.1 and 1.21.1 name each
  dimension through `/mcaddon entities` and write none of them.
- How a resource pack lights the sky across the day, from 26 on. A `lighting/` file states the
  ambient colour, the sky intensity, how far the sun and moon orbits are turned and the colour and
  strength of each of them at every hour; an `atmospherics/` file states the colour of the zenith and
  of the horizon. All of it is a keyframe table, and 26 reads one: a `Timeline` is a keyframe track
  per environment attribute, which is what the ambient light colour, the sky light factor, the sun
  and moon angles, the sky light colour and level, the sky colour and the sunrise band all are. So
  the file becomes a timeline entry in the converted data pack and is named in the overworld's own
  timeline tag, which merges rather than replaces, and the game's day, moon and early game timelines
  stay where they are. The two orbital lights fold into Java's one sky light, each measured against
  the brightest the file itself makes it, so a sun of a hundred lux beside a moon of half a lux comes
  out as full daylight and a night at the level Java already runs one at. Bedrock hangs these
  settings off each biome and Java hangs a timeline off a dimension, so the collection Bedrock gives
  every biome that names no other is the one that lands and a collection one biome names is reported.
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
- A bone built out of a texture. `texture_meshes` turns every texel a texture draws into a voxel one
  pixel deep, which is how Mojang's own bow and crossbow attachables are modelled, and the shape
  Java's `ItemModelGenerator` gives a flat item: both broad faces of each texel and a side wherever
  the texel beside it is clear, each face drawn in its own texel's colour. The mesh names its texture
  the way the entity's `textures` map does, is read from the converted image whatever the pack wrote
  it as, and is placed by its `local_pivot`, `position`, `rotation` and `scale`, with
  `use_pixel_depth` deciding how deep the layer is.
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
  already turns a form into. Each button runs the commands the scene gave it as the NPC, with the
  authority Java gives a function and with `@initiator` standing for whoever is reading the screen,
  and the scene's opening and closing commands run either side of that. `/bedrock dialogue change`
  says which scene an NPC shows, `/bedrock dialogue open` shows one now, and interacting with an NPC
  that has been given a scene opens it.
- A resource pack's `ui/server_form.json` and `ui/hud_screen.json`, as far as packs use them. An
  action or message form whose title a pack's `#visible` binding routes to a layout of its own is
  drawn in that layout: panels, stack panels, grids over the form's buttons, images, labels and
  buttons, placed by their `size`, `offset` and anchors, with `@` inheritance, `$variables`,
  `modifications` and binding expressions over `#title_text`, `#form_text`, `#form_button_text` and
  `#form_button_texture`. A form the pack does not route is drawn as before. On the heads-up display,
  controls a pack inserts into `root_panel` are drawn over the game, reading the last title and
  subtitle and the `$actionbar_text` each action bar hands a factory, and a pack hiding Bedrock's own
  title or action bar for some texts hides Java's for the same texts. A texture the pack ships is
  drawn; one of the Bedrock client's own is not, since Mojang's textures are not shipped.
- Behaviour pack scripts against `@minecraft/server`, sandboxed: a script sees its own pack's files
  and nothing else on disk. Commands a script registers on startup become commands `/bedrock` runs,
  `@minecraft/server-ui` forms become real screens (a 2.x `CustomForm` stays open while its buttons
  run their handlers and redraws as its observables change, and a form taller than the window
  scrolls under its heading), and the world, system, entity, dimension, block,
  scoreboard, game rule and structure surfaces are answered by the game. The game's own mobs, items
  and blocks reach a script under Bedrock's ids, so a Java villager's `typeId` is
  `minecraft:villager_v2`, a powered rail's is `minecraft:golden_rail` and a lit furnace's is
  `minecraft:lit_furnace`, and every query, spawn, permutation, item stack and filter accepts that
  spelling as well as Java's. A Java villager also states itself the way Bedrock's does: its
  profession as `minecraft:variant`, the biome it comes from as `minecraft:mark_variant` and a face
  as `minecraft:skin_id`, each numbered as Bedrock numbers them, and a villager taking a job raises
  the `minecraft:become_*` event Bedrock's villager raises, so a pack that dresses its own model of
  a villager for its job hears of it. Bedrock rolls the face once and keeps it; Java has nowhere to
  keep it, so it is worked out from the villager's UUID. A player's fog stack is the one `/fog` keeps, a camera shake is the
  one `/camerashake` sends, and a dimension clones a region through Java's own `/clone`, with a
  filter picking the blocks one at a time.
- The options a listener subscribes with narrow what it hears, as Bedrock's do: the entities, entity
  types, entity filter, event ids and damage or heal causes an entity event names. `entitySpawn`
  and `entityLoad` arrive on the tick after the entity came into the world, so a script that spawns
  one and then marks it has done both before a listener asks.
- The world events a script subscribes to, raised out of the game rather than out of a pack's own
  data: what a player breaks, places, uses, picks up and swings at, where they go and what they
  hold, the weather turning and a game rule being set, an explosion and each block it takes, a
  piston going out and coming back, a pressure plate, a tripwire, a target block, a button and a
  lever, a container opening and closing on a block or on an entity, a player crouching and standing,
  and a wolf, a cat, a parrot, a horse or a pack's own entity being tamed. An explosion and a tame
  are offered first as before events, so a listener can call one off, and the blocks an explosion
  names are the ones still standing.
- The tests a behaviour pack ships through `@minecraft/server-gametest`. `/bedrock gametest run <name>`,
  `/bedrock gametest runset <tag>` and `/bedrock gametest runall` build the structure a test named out
  of the pack's own `.mcstructure`, place it beside you, hand the pack's function a `Test` bound to
  that area and report what it decided. `/bedrock gametest clearall` takes the areas back. `run`
  with a repeat count runs a test again until the count is reached, stopping at a failure when told
  to, and `runsetuntilfail` runs a set again in the same areas until one of its tests fails.
  `/bedrock gametest create <name>` lays out a test the way Bedrock's editor does: a structure block
  naming the test over the area, a floor of polished andesite on dirt, and a command block with a
  button that runs it. `runthis` runs the test the nearest structure block within fifteen blocks
  names, in the area that block covers, `runthese` every one within two hundred, and `pos` says where
  you stand against the nearest such area. A test is always built unturned, so a line asking for
  rotation steps is refused.
- The other modules a pack may import: `@minecraft/math`, `@minecraft/common`, and
  `@minecraft/vanilla-data`, whose tables of vanilla ids are read from this game's own registries so
  they hold what the version you play ships.
- The several forms of itself a pack ships. A resource pack usually offers its textures at more than
  one resolution, each under `subpacks/<folder>/`, and Bedrock picks one by how much memory the
  player's device has. You get the highest tier the pack declares. Mojang's own tier table names a
  PC at the top tier and at no other, and Java Edition runs nowhere else, so the form a Bedrock
  player would see on a PC is the form you see here. Files in that folder stand in for the pack's
  own of the same path and files only it has join the pack, which is what Bedrock does with them.
  `/mcaddon packs` says which form was loaded.
- The settings a pack offers. Bedrock puts a pack's sliders, toggles and dropdowns on a screen
  beside it, and the pack's own Molang and its script read back whatever a player left them at. Java
  has no such screen, so each one holds the `default` its author declared, kept inside the slider's
  range and step or the dropdown's options:
  `query.get_pack_setting`, `query.is_pack_setting_enabled` and `query.is_pack_setting_selected` are
  answered with it, and so is `World.getPackSettings`. `/mcaddon packs` also says who wrote a pack,
  under what licence and where it lives, which is what its manifest's `metadata` states.

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
ships; `tools/census/manifest.py` reads the manifest schemas, which describe the one file every pack
has; `tools/census/ids.py` reads every item and block id the vanilla data and the vanilla behaviour
pack write; and `tools/census/script.py` reads the published `@minecraft/*` type definitions. All
of them compare what they read against the names this mod acts on:

| Surface | Answered |
| --- | --- |
| Entity components | 100% |
| Entity AI goals | 100% |
| Entity filters | 100% |
| Molang queries | 100% |
| Molang maths | 100% |
| Block components | 100% |
| Item components | 90% |
| Command names | 84% |
| Command keywords | 75% |
| Game rules | 81% |
| Particle components | 100% |
| Particle component fields | 100% |
| Particle curves and Molang variables | 100% |
| Loot table functions | 100% |
| Loot table conditions | 100% |
| Recipe types | 100% |
| Recipe fields | 100% |
| Trade table fields | 100% |
| Trade item functions | 100% |
| Geometry, animation and controller fields | 97% |
| Pack file kinds | 92% |
| Manifest fields | 58% |
| Vanilla item ids Bedrock writes (less the 110 Java has nothing for) | 100% |
| Vanilla block ids Bedrock writes (less the 303 Java has nothing for) | 100% |
| `@minecraft/server` and friends | 100% |

Every name in the remaining share is counted and shown by `/mcaddon entities` and `/mcaddon
scripts`, so a pack relying on one is told rather than left wondering. Run the censuses yourself to
get the current figures; the ones above are a snapshot.

## What does not work

**One exhaustion price, on the two older versions.** `minecraft:exhaustion_values` prices
`lunge` for the enchantment of that name. 26 added `minecraft:lunge` to Java, which spends 4
exhaustion per level through `ApplyExhaustion`, so the price is charged there; 1.20.1 and 1.21.1
have neither the enchantment nor that effect, and on those two it is counted and named by `/mcaddon
entities` as `minecraft:exhaustion_values lunge`. The other nine prices are charged on all four.

**Aim assist and the device memory tier a client reports** describe a Bedrock client rather than a
world, and are counted and named the same way.

**The manifest fields that describe a store, a device or a screen.** Every pack has a
`manifest.json` and this mod reads most of it, so the rest is named through `/mcaddon entities`
rather than passed over. `has_education_metadata` and the `chemistry` capability mark Minecraft
Education material, and the Java game has no Education Edition. `base_game_version`,
`allow_random_seed` and `lock_template_options` belong to a world template, which declares a
`world_template` module and is refused as neither a resource nor a behaviour pack before any of it
is read. `platform_locked` keeps a pack from travelling between a player's worlds and servers, and
there is no entitlement here to lock against. `pack_scope` says whether a pack belongs to one world
or to the installation, and `addons/` is one directory per instance with no world to attach a pack
to. `pack_optimization_version` is in Mojang's schema and in Mojang's own field table with no
description in either, so nothing is stated for a reader to act on. A module's `uuid` and `version`
identify one half of a pack to Bedrock's content updater, and what names one installation of an
add-on to another here is the header uuid and the header version. A script API dependency this mod
does not provide is a problem `/mcaddon packs` names, unless the dependency is `optional`. The flag
does not rescue a script that imports the module, because a module is linked before any of the
script runs. `product_type` decides on Bedrock whether a world keeps its achievements and whether the
resource pack half is downloaded, and Java locks no advancement behind a pack and installs both
halves together. The `editorExtension` capability asks for the Bedrock Editor, which Java has not
got, and `experimental_custom_ui` asks for an HTML interface Bedrock itself dropped in 1.18.10.28.
A setting's `text` and an option's `text` are labels on a screen Java does not have. A slider's
`min`, `max` and `step` and a dropdown's `options` are read: they bring the default inside what the
control could have been set to, so a slider shipped above its maximum answers with the maximum and
a dropdown shipped at an option it does not list answers with its first. The `pbr`, `raytraced` and `script_eval` capabilities are answered rather
than named: the first two ask for physically based rendering, which the texture set conversion
writes as labPBR sidecars, and the third asks for `eval`, which the script host evaluates with no
permission of its own to grant.

**An item's data value.** `set_data`, `random_aux_value`, `set_data_from_color_index` and
`random_block_state` all pick one of the things a Bedrock id stands for by number: `minecraft:dye`
at 0 is an ink sac, `minecraft:wool` at a sheep's colour index is that colour of wool, and
`minecraft:bed` and `minecraft:banner` at 0 to 15 are the sixteen of each. Java has every one of
those under an id of its own, because that is what flattening did, so the number is a lookup into a
published family rather than a value with nowhere to go. `DataValues` is that table, forty families
and their members in the order Mojang's own material states: the block properties in the command
metadata, the aux-indexed texture arrays in `item_texture.json` and `terrain_texture.json`, and
Mojang's own recipes, which is what settles that a banner's numbering runs the other way round from
a dye's. A stated value settles the entry's item as the table is converted, since Java refuses a
table naming an id it has not got, and a range or a colour index carries the family into the roll,
where a sheep's own `minecraft:color` picks the fleece. A recipe's `data` beside an item, a trade's
`minecraft:wool:14` and a command's `give @p wool 1 14` are read the same way, and so is the named
state an older block carries instead of a number: `minecraft:wool` with `["color"="red"]` is red
wool and `minecraft:log` with `old_log_type` set to `spruce` is a spruce log.

A value with no family, and a family member this version of the game has no item for, are two
different things: the first leaves the id the pack wrote and is counted, and the second empties that
one entry rather than costing the whole table, which is what keeps a banner pattern table working on
the versions before Java made the item.

**Bedrock's own spelling of a vanilla id.** A few hundred of the game's own blocks and items are
called something else in Bedrock: `golden_rail` is Java's `powered_rail`, `lit_pumpkin` its
`jack_o_lantern`, `frame` its `item_frame`, and Bedrock's `snow` is the full block Java calls
`snow_block` while its `snow_layer` is Java's `snow`. Some name one Java block in a state of its own
(`lit_furnace`, `oak_double_slab`, `powered_repeater`, `light_block_7`), and packs still write the
spellings Bedrock used before it renamed its items (`record_13`, `muttonRaw`, `horsearmorgold`).
`BedrockItemNames` and `BedrockBlockNames` hold both directions, so every loot table, recipe, trade
table, function, entity component, filter, Molang query, feature and script names the Java item or
block and is answered under Bedrock's name, and `tools/census/ids.py` runs every id Mojang's own
material writes through them. What Java has nothing for (the education edition's chemistry, the
building permission blocks, Bedrock's experiments) is declared rather than guessed at, and costs the
one entry, line or recipe that names it.

`minecraft:stacked_by_data` is a
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

**The fields of an entity's components with no Java place.** `tools/census/fields.py` keeps a
written reason beside each one. The main ones: a player cannot open an entity's inventory, because
Java builds that screen for its own horses, minecarts and hoppers by class; `suffocate_time` is
documented as seconds with a default of -20, which no count of seconds can be, so Java's own
drowning rhythm applies; `has_gravity` is left alone, because Mojang's fish declare it off and still
fall when they leap out of water; a dweller holds to where it stood without joining a Java village;
a tempt sound's gap is documented in ticks and written by Mojang in two units at once; a turtle egg
stomper searches the twenty-four blocks Java's goal fixes, whatever its `search_range` says; and how
fast an entity resends itself over the network is Java's own business. Outside a damage sensor,
`has_damage` asking whether a blow is `fatal` is reported, because only the sensor is told how hard
the blow is; inside one it answers whether the damage, before armour, is at least the health left.

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

**The water and the colour grading a resource pack writes for the deferred renderer.** A `water/`
file states the concentration of three optical particles in milligrams per litre, the texture and
scale of the caustics, and the frequency, depth, speed and shape of the waves. None of the three is
a colour: the water surface colour is `minecraft:water_appearance` on the client biome, which is
read, and Java draws the surface itself from a texture and an animation with no term any of these
would feed. A `color_grading/` file states the contrast, gamma, gain, saturation and offset of the
shadows, midtones and highlights, a colour temperature in kelvin, and which of six tonemapping
operators maps the finished frame back into range, all of it a post-process Java has no data-driven
form of. Each setting is counted and named by `/mcaddon entities` rather than the directory alone,
so an author is told which of the ones they wrote is dropped.

**The scattering terms of an atmosphere, and a sky one biome states.** `rayleigh_strength`, the two
mie strengths, `sun_glare_shape` and `horizon_blend_stops` are the model Bedrock scatters light
through to draw its sky. Java draws no atmosphere: its sky is the colours `SKY_COLOR` and
`SUNRISE_SUNSET_COLOR` are given, which is where the zenith and horizon colours go, and the five
terms have nothing to feed. `ambient.illuminance` is a strength in lux beside a colour Java states
on its own, `emissive.desaturation` shapes the light an emissive pixel gives off and this mod draws
that through a labPBR sidecar instead, and `directional_lights.flash` colours the light a lightning
bolt throws, which Java draws from no value a pack states. A lighting or atmosphere collection one
biome names is reported as well, because `HolderSet<Timeline>` is a field of `DimensionType` and a
`Biome` carries an `EnvironmentAttributeMap` of constants rather than of curves. 1.20.1 and 1.21.1
have no timeline registry at all and name both directories instead.

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

**Three pieces of the heads-up display.** `/hud` names the paper doll, the touch controls and the
hints Bedrock calls tooltips, which say what a key would do to whatever is under the crosshair. No
Java client draws any of the three; the one such hint Java shows, how to get off a mount, is written
to the action bar a pack writes to as well. A line naming one of them is dropped and reported, and a
script naming one is reported while the rest of what it asked for is done.

**The fog a pack describes for water, lava and powder snow, and its volumetric block.** A Bedrock fog
file says what the world looks like through each medium the camera can be in, and adds a second
description with its own scattering coefficients. `/fog` carries the `air` block, which is the one a
pack pushes a fog for; Java draws its own fog for the other media and has no volumetric fog to give
the second block to.

**Eleven of Bedrock's commands.** `aimassist` and `controlscheme` change how a Bedrock client turns
a controller or a touchscreen into aim and movement, and `project` and `editor-allowlist` belong to
the Bedrock Editor. `connect` and `wsserver` speak to a WebSocket server, `sendshowstoreoffer` to the
marketplace, `script` to the debugger and profiler of Bedrock's own script engine, and
`reloadconfig`, `reloadpacketlimitconfig` and `serveridentity` to a Bedrock server's own
configuration and identity. A function using any of them keeps its other lines and the dropped
command is reported.

**One form of a command the rest of which translates.** The cameras Bedrock draws behind or round an
entity (`minecraft:follow_orbit`, `minecraft:fixed_boom` and `minecraft:control_scheme_camera`), and
the camera forms that follow a spline, hang the camera off an entity, turn it towards one or offset
it from one, which would each be a camera this mod draws itself; the paper doll, the input hints
and the touch controls `hud` names, which a Java client draws none of; a `permission` visitor, a
level below Java's member; `changesetting allow-cheats`, because a Java server always lets an
operator run commands; and the liquid setting `ignore_waterlogging` on 1.20.1, which waterlogs
every block a jigsaw or structure places. The line does not run and the form is named, rather than being handed to a
parser that would refuse it over the wrong word.

Everything else is translated or registered. `effect`, `xp`, `playsound`, `stopsound`,
`spreadplayers`, `replaceitem`, `ride`, `tickingarea`, `save`, `gamerule`, `gamemode`, `difficulty`,
`changesetting`, `damage`, `locate`, `loot`, `place`, `summon`, `title`, `tellraw`, `titleraw`,
`daylock`, `allowlist`, `testfor`, `testforblock`, `testforblocks` and both forms of `execute` are
rewritten into what Java reads, including the command behind `execute ... run` and the dimension
Bedrock calls the nether. `schedule` is rewritten too, because the two editions agree about more of
it than the word suggests: Bedrock's `schedule delay add <function> <time> <replace|append>` is
Java's `schedule function <function> <time> [append|replace]` with one keyword moved and the same two
words at the end, and `schedule clear` is spelled alike in both.
`playanimation`, `event`, `scriptevent`, `music`, `toggledownfall`, `structure`, `mobevent`,
`particle`, `dialogue`, `gametest`, `transfer`, `camera`, `camerashake`, `fog`, `clearspawnpoint`,
`hud`, `inputpermission`, `ride`, `summon`, `tickingarea`, `packstack`, `setmaxplayers`,
`permission` and `ops` are registered by this mod in the tree a Bedrock line is read against,
beside Java's own commands and never in Java's tree, as are Bedrock's own forms of `place`,
`schedule` and `gamerule`, and a line using one is written out in the form the command built there
reads. `clearspawnpoint` takes a
player's respawn point away, which Java's `/spawnpoint` can only give. `transfer` hands a player to another server,
which Java carries as `ClientboundTransferPacket` from 1.20.5 rather than as a command of its own; a
line naming no port means the port a Java server listens on, because a Java client sent to Bedrock's
own 19132 reaches nothing. 1.20.1 has no such packet and the command says so rather than reporting a
move that never happened. Of Bedrock's thirty-seven game rules, the
twenty-five every supported version of Java has are set under the name that version knows them by,
which 26 changed, and four more reach a rule only the newer versions hold. `functioncommandlimit`
caps the commands one function runs, which Java caps with the rule it caps a chain of command blocks
with, so it sets that rule. Six more Java keeps no rule for are held beside the world and acted on
by this mod, as described with the commands above. `showbordereffect` is reported: it switches the
particle wall of Bedrock's border block, which Java has none of.

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

**How long a sound plays.** `playSound` hands back a `SoundInstance` carrying its id, the sound
and who it was played for. Bedrock follows a sound's lifetime on the server only where a server sound
definition, a beta file of its own, gives the sound a duration, and that is what `durationInfo`,
`SoundDefinitionRegistry`, `SoundDurationInfo` and `world.afterEvents.soundCompleted` all read.
Nothing here reads such a file, so each of those is counted and named, and so is
`SoundInstance.stop`, which Java can only do for every copy of a sound a player hears at once.

**A block's store carried on its item.** `ItemBlockDynamicPropertiesComponent` is the store of a
block entity riding on the item that places the block. Java keeps a block entity's data on its item
as the data that block entity writes, and nothing here moves a script's store onto an item and back
off it, so each of its members is counted and named.

**Three parts of how a block's item is shown.** A geometry's `item_display_transforms` may name an
`embedded` context, which is how a block draws as the visual another block embeds, and Java has no
such context. It may give a rotation or a scale a pivot of its own, where Java turns and scales a
model about its middle, and it may ask for the item to be fitted to its frame in the inventory,
which Java never does. Each of the three is counted and named; the rest of the transform is applied.

**Four world events nothing here raises.** `entityUpgrade` fires when Bedrock migrates an entity
to a newer version of the definition a pack wrote, which is a step in reading a pack rather than
anything that happens in a world: this mod reads one definition and keeps it. `playerEmote` names a
persona piece, and a persona is a Bedrock skin. `playerInputModeChange` says whether somebody is on a
keyboard, a controller, a touchscreen or a motion controller, and a Java client tells the server
nothing about the device it is on. `soundCompleted` waits on the declared duration of a sound, which
nothing here reads, as set out above. Each of the four hands a pack a signal of the class Bedrock
names, so subscribing works, and `/mcaddon scripts` says the event is never raised.

**Four spawn rule conditions with nothing to ask in Java.** `minecraft:permute_type` swaps the
entity that spawns for another one after the biome list has already chosen a type.
`minecraft:mob_event_filter` waits on one of Bedrock's own world events, `minecraft:is_experimental`
on a toggle Java has no form of, and `minecraft:player_in_village_filter` measures a distance to a
village border that Java's poi map does not hold. Each is counted and named by `/mcaddon entities`.
A Bedrock biome tag with no Java biome carrying the same idea, `sulfur_caves` and the `spawn_*`
nether tags among them, is named the same way and then left out of the filter, so the rest of the
rule still decides.

**Bedrock's own screen language, outside forms and the title.** A pack's `ui/` files are read, and
what packs do with them to a server's forms and to the title and action bar is drawn (see "What
works"). The rest of that language changes Bedrock's own screens, the inventory, the settings, the
chat, the pause menu and the rest, by laying keys over Mojang's definition of each screen. Java draws
those screens with classes of its own, and Mojang's definitions are not shipped, so there is nothing
for such a change to apply to. `/mcaddon entities` names each `ui/` file that does it, with the
namespaces it changes. Inside the layouts that are drawn, a `scroll_view`, a toggle, a slider, a text
box, a control's `anims`, its `font_size` and a modal form's own `custom_form` layout are named too:
the modal form is drawn as this mod's own form.

**What a camera preset says about Bedrock's own client.** A pack's `cameras/presets/` are read, and
of what a preset states beside its identifier and its parent, the placement (`pos_x`, `pos_y`,
`pos_z`, `rot_x`, `rot_y`), the `listener`, an orbit camera's `radius`, `view_offset`,
`entity_offset`, `starting_rot_x`, `starting_rot_y` and `align_target_and_camera_forward`, and the
fields that shape how a free camera follows its target (`rotation_speed`, `snap_to_target`, the
two rotation limits, `continue_targeting` and `tracking_radius`) are drawn. The rest is named by
`/mcaddon entities` as the pack loads. `player_effects` and `extend_player_rendering` describe the
Bedrock client's renderer: Java draws the player's effects whatever the camera and draws every entity
the loaded chunks hold. `aim_assist` and `control_scheme` belong to two systems this mod has not got,
aim assist and `/controlscheme`. Mojang describes `align_target_and_camera_forward` nowhere, so it is
read by its name: a fixed boom stating it turns the player to face the way the camera looks when it
is set. Bedrock keeps a player's `pos` and `rot` for a preset until `default` or a rejoin clears them;
here a `/camera set` with neither places the camera where the preset says.

**What a camera does that Mojang does not write down.** An orbit camera circles the middle of the
entity's box, the point Mojang's documentation says an entity offset of nothing stands at, and moves
that point along the world's own axes; Java's camera has no roll, so a spline's rotation about the
camera's forward axis is named rather than drawn. A spline file is read in the field names of
Bedrock's own spline definition (`name`, `spline_type`, `total_time`, `control_points`,
`progress_key_frames`, `rotation_key_frames`), at the top of the file or inside the one object it
holds. A Catmull-Rom spline passes through every point it is given, the first and the last included,
which is what Mojang's flyover tutorial draws, and the curve is spread evenly over its points rather
than over its length. Neither orbit camera is pulled in when a wall stands between it and the entity
it follows.

**The index beside a pack's textures.** `textures/texture_list.json` is a flat list of texture paths
written without their extensions, which the Bedrock client keeps so it can resolve a texture by name
without searching the directory. It states nothing the pack's own files do not: Mojang's resource
pack lists 20 of the 8039 images it ships, every one of them a file that is there and already named
by `terrain_texture.json` or `item_texture.json`. The conversion walks `textures/` itself, so what it
writes covers whatever the index names and reading the index would change nothing. It is counted and
named all the same, because a file this mod never opens is a fact the pack's author is owed.
`/mcaddon entities` names each of the three kinds nothing reads, this index, `color_grading` and
`water`, together with the reason Java has no place for it.

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
versions convert the biomes and the features, and 1.21.1 stops short of the dimensions for a reason
of its own: `WorldDimensions.bake` asks there whether the world holds exactly the three dimensions
the game ships, and from 26 whether it holds at least those three, so a fourth one of any origin is
experimental before 26 and ordinary from it.

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
