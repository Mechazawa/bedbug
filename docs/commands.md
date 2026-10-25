# Commands and functions

## `/bedrock <line>`

Every Bedrock command goes through `/bedrock`. It reads one line in Bedrock's dialect, translates it
to Java and runs it.

```
/bedrock camera @s set minecraft:free
```

A Java data pack function can hold the same form:

```
bedrock playanimation @e[type=minecraft:villager] animation.wave
```

- Bedbug adds only `/bedrock` and `/mcaddon` to Java's command tree. Java's own commands keep Java's
  arguments, and Java's `@e[...]` knows only Java's selector options.
- Bedrock commands that Java does not have, and commands a pack's script registers, are available
  only through `/bedrock`.
- `/bedrock` needs the level at which Java runs a function. Each command in the line needs its own
  level in addition.

## Functions

- Each `.mcfunction` becomes a Java function whose lines are all `bedrock <the line>`. A `tick`
  function tag is written too.
- A file always loads. A line that cannot be translated fails alone.
- Such a line is reported when the pack loads, and again each time it runs.
- A function called by its Bedrock name (a path with no namespace) goes to the add-on that ships
  it: first the calling add-on, then any installed add-on that has it.

## Commands a pack runs

These are translated in the same way, as the add-on that wrote them:

- a script's `runCommand`
- an entity event's `queue_command`
- a command on an animation or controller timeline
- a dialogue scene's buttons
- a block event's `run_command`

Each runs to its end, with the functions it calls, with the authority Java gives a function. Bedbug
reports each line that it cannot translate, that Java does not parse, or that the game declines.

## Selector arguments

Bedrock's selector arguments work inside a Bedrock line. Three of them have no Java form:

| Argument | Example |
| --- | --- |
| `hasitem` | `@s[hasitem={item=apple,location=slot.weapon.mainhand,quantity=2..}]` |
| `haspermission` | `@a[haspermission={camera=disabled}]` |
| `has_property` | `@e[has_property={tp:mood="calm",tp:level=2..4,property=!tp:owner}]` |

`has_property` takes a value, a range or a presence, and `!` inverts each. All three work anywhere
in an `execute` chain.

## Translated commands

These are rewritten into what Java reads, with the command behind `execute ... run` and Bedrock's
name for the nether:

`effect`, `xp`, `playsound`, `stopsound`, `spreadplayers`, `replaceitem`, `ride`, `tickingarea`,
`save`, `gamerule`, `gamemode`, `difficulty`, `changesetting`, `damage`, `locate`, `loot`, `place`,
`summon`, `title`, `tellraw`, `titleraw`, `daylock`, `allowlist`, `testfor`, `testforblock`,
`testforblocks`, `schedule` and both forms of `execute`.

These are Bedrock commands that Bedbug adds to the tree that `/bedrock` reads:

`playanimation`, `event`, `scriptevent`, `music`, `toggledownfall`, `structure`, `mobevent`,
`particle`, `dialogue`, `gametest`, `transfer`, `camera`, `camerashake`, `fog`, `clearspawnpoint`,
`hud`, `inputpermission`, `ride`, `summon`, `tickingarea`, `packstack`, `setmaxplayers`,
`permission`, `ops`, `aimassist`, `controlscheme`, `wsserver`, `connect`, `script`,
`changesetting`, `reloadconfig`, `reloadpacketlimitconfig`, `serveridentity`, and Bedrock's forms of
`place`, `schedule` and `gamerule`.

Notes:

- `schedule delay add <function> <time> <replace|append>` becomes Java's `schedule function`.
  `schedule clear` is the same in both.
- `clearspawnpoint` removes a player's respawn point.
- `transfer` sends a player to another server. With no port, it uses the Java server port, not
  19132. On 1.20.1 it moves nobody and says so.
- The server administration commands are on [Servers and LAN](servers.md).

## Player view

### `camera` and `camerashake`

- All seven Bedrock presets, with a position and rotation, or a point or an entity to face.
- All thirty-one Bedrock easing curves. An ease to one of the three gameplay cameras is refused, as
  in Bedrock.
- A shake that moves or turns the camera.
- `minecraft:follow_orbit` circles the player at a radius and turns with the player. `rot` turns
  the player.
- `minecraft:fixed_boom` follows the player at one angle and does not turn with the player.
- Both take `view_offset` and `entity_offset`.
- `minecraft:control_scheme_camera` is drawn as the orbit camera.
- `attach_to_entity` and `detach_from_entity` hang the camera on another entity.
- `target_entity` with `target_center_offset` turns a free camera to follow an entity, until
  `remove_target`.
- `play_spline` moves a free camera along a curve from a behaviour pack's `camera/splines/`. The
  file uses Bedrock's field names (`name`, `spline_type`, `total_time`, `control_points`,
  `progress_key_frames`, `rotation_key_frames`), at the top level or in its one object. A
  Catmull-Rom spline passes through every point, the first and last too, and its time is spread
  evenly over its points.
- `fade` covers the screen in black or a colour. The defaults are 1 s in, 0.5 s hold, 1 s out. A
  second fade during a fade is combined the way Bedrock combines them.
- `fov_set` and `fov_clear` change the field of view over the given time and ease. Sprinting and
  water still change it on top.

A behaviour pack's `cameras/presets/` are presets too:

- Each inherits from a Bedrock preset or another of the pack's presets. What it states wins.
- `/camera set`, `Camera.setCamera` and `Camera.setDefaultCamera` set it. What the command or script
  states wins over the preset.
- `"listener": "player"` puts the sound listener at the player's head.
- A fixed boom with `align_target_and_camera_forward` turns the player to the camera's direction
  when it is set.
- A script also has `Camera.attachToEntity` and `Camera.playAnimation`.

### `fog`

Puts a pack's `fogs/` settings over the game's fog. Each player has a stack, and `pop` and `remove`
work as in Bedrock.

### `hud`

Hides and shows parts of one player's display: hotbar, crosshair, health, hunger, armour, air
bubbles, the bar above the hotbar, status effects, held item name, mount health and paper doll.
`all`, or a line that names no part, hides all of them. A script's `ScreenDisplay.setHudVisibility`,
`hideAllExcept`, `resetHudElementsVisibility` and `getHiddenHudElements` use the same set.

### `inputpermission`

Takes one kind of input from a player and gives it back.

- Camera, each of the four walk directions, jump and sneak stop on the player's client.
- Mounting and dismounting are refused by the server.
- `movement` takes all of these except the camera.
- `query` reads a category. `haspermission` selects by it.
- A script's `Player.inputPermissions` changes the same set, and
  `playerInputPermissionCategoryChange` fires on each change.
- The set lasts while the player is connected.

### `controlscheme`

Sets one of Bedrock's five control schemes. A camera preset's `control_scheme` and a script's
`Player.setControlScheme` do the same.

- `player_relative`: the side keys turn the player, half a circle per second.
- `camera_relative`: the keys walk along the camera's direction and the player turns to face the
  walk.
- In both, the mouse turns a follow orbit camera around the player.
- `player_relative_strafe` and `camera_relative_strafe`: the mouse moves a pointer on the screen and
  the player faces the point under it.
- All schemes except the locked one keep the player looking level.
- A camera allows the schemes Mojang's table gives it. Other schemes are refused.
- Clearing the camera puts the player back on the locked scheme, which is Java's own.

### `aimassist`

Picks the target of the player's hand in third person.

- It looks inside a cone around the facing direction, out to the given distance.
- It takes the nearest target, or the one nearest the middle of the cone, weighted by the
  priorities of the held item's category.
- It skips what the preset excludes. It skips liquids unless the held item is on the liquid list.
- A reticle is drawn on the target. An add-on can ship
  `textures/ui/aimassist_entity_highlight` or `aimassist_block_highlight`.
- The player attacks, uses or mines the target while it is in reach.
- Presets and categories come from a behaviour pack's `aim_assist/` (or beside its camera presets)
  and from `world.getAimAssist()`.
- A camera preset's `aim_assist` wins while that camera is set. `Player.getAimAssist()` reads and
  sets the same settings.
- First person has no aim assist, as in Bedrock.

## World commands

### `ride` and `summon`

- `ride` seats a whole group only if all of it fits (`if_group_fits`), or one rider at a time until
  the vehicle is full.
- `teleport_ride` brings the vehicle to its one rider. `evict_riders` empties a vehicle.
- `ride` can summon a rider for each vehicle with room, or a vehicle for each rider, under the three
  ride rules.
- A pack's entity has room as its `minecraft:rideable` says. A player is never a vehicle.
- `summon` turns the entity to a rotation, a point or an entity, names it and fires the spawn event
  in the line. A name can come before the position. It refuses a hostile mob on peaceful.

### `replaceitem` and `loot replace`

Both reach `slot.equippable`: slot 0 is the saddle, slot 1 is a horse's armour or a llama's carpet.
`replaceitem ... keep` fills a slot only when it is empty. On 1.20.1 `keep` is reported.

### `structure`

- `save` keeps a region in memory until the server stops. `disk` writes it beside the world. Both
  can include or skip entities and blocks.
- `delete` removes it from memory and deletes its file.
- `load` puts the lowest corner of the structure at the position, for every rotation and mirror. A
  mirror on both axes is a half turn.
- `load` takes entities, blocks, waterlogging, integrity, seed and Bedrock's animation, which places
  the structure one block or one layer at a time.
- A pack's `structures/*.mcstructure` loads by its path name. The first folder is the namespace. A
  file with no folder uses `mystructure`.

### `tickingarea`

- Keeps the chunks of a rectangle or a circle loaded under a name.
- The name, the shape and `preload` are saved with the world.
- List areas, find one by a position or its name, and remove one. A chunk that another area covers
  stays loaded.
- Limits as in Bedrock: ten areas, a hundred chunks each, a circle radius of four chunks.
- Java loads every forced chunk before the world starts, so `preload` is kept and reported.

### `schedule on_area_loaded`

Holds a function until all chunks of a rectangle, a circle or a ticking area are loaded with their
entities. Then it runs the function once as the server. Waiting functions are saved with the world.

### `place`

- `place featurerule` places a pack's feature rule at the position, with the rule's own
  distribution. `Dimension.placeFeatureRule` does the same. The rule's biome filter is ignored here.
- `place jigsaw` takes three words after the position: keep jigsaw blocks, include entities, and
  waterlog in water. Missing words take Bedrock's defaults: jigsaws removed, entities left out,
  waterlogged. On 1.20.1 every block is waterlogged and `ignore_waterlogging` is reported.
- `place structure` takes four words after the position: `ignoreStartHeight`, `keepJigsaws`,
  `includeEntities` and the liquid setting. They apply to jigsaw structures. Placement starts at the
  structure's start height unless `ignoreStartHeight` is set. The column is the position's own. Other
  structures refuse the words. Java's `place structure <structure> [pos]` still works.
- `place featurerule` and the jigsaw words need an operator. A pack's function cannot run them.

### `music`

Plays a track to everyone in the music category.

- The game's music waits until the track ends.
- Fade in and out over the given seconds, loop, change the volume while it plays.
- `queue` waits behind a track that plays once. A queue behind a looping track is refused.
- A track is a pack's sound, a game sound by full name, or a Bedrock name such as `record.cat` or
  `cat`.

### `dialogue`

`dialogue change` sets the scene an NPC shows. `dialogue open` shows a scene now. Using an NPC that
has a scene opens it. See [Scripts and forms](scripts.md#npc-dialogue).

### `wsserver` and `connect`

`/bedrock wsserver <address>` and `/bedrock connect <address>` connect the player's client to a
WebSocket server, such as Code Builder or a tool built on `mcpews` or `socket-be`.

- Only an operator who types the command in chat is connected. All other sources are refused.
- The connection lasts until the server closes it, sends `closewebsocket`, `/bedrock wsserver off`
  is typed or the game ends.
- The server's commands run as the player and get Bedrock's status codes.
- Events: `PlayerMessage` (chat, `/say`, `/me`, `/tell`), `BlockBroken` and `BlockPlaced`.
- A server that asks for encryption gets it. Unencrypted connections are always allowed.

## Game rules

- All 37 Bedrock game rules are read. 25 map to a rule that every supported Java version has, under
  that version's name. Four more map to rules only the newer versions have.
- `functioncommandlimit` sets Java's command chain limit.
- Seven rules have no Java rule. Bedbug saves them with the world and applies them. Set them with
  `/bedrock gamerule` or `world.gameRules`:

| Rule | Effect |
| --- | --- |
| `recipesunlock` | Off: recipes do not unlock when a player collects items. Use `/recipe`. |
| `respawnblocksexplode` | Off: beds and charged respawn anchors do not explode. |
| `showcoordinates` | Shows the position at the top left. Off in a new world. |
| `showdaysplayed` | Shows the day count at the top left. Off in a new world. |
| `showrecipemessages` | Off: no toast for an unlocked recipe. |
| `showtags` | Off: tooltips do not list what an item can break or be placed on. |
| `showbordereffect` | Off: border blocks send up no red dust. |

## Border block

`minecraft:border_block` is built as `mcaddon:border_block`. Packs reach it by its Bedrock id.

- Nothing breaks or moves it.
- No player or mob walks into its column, at any height. An entity that moves without collision,
  such as the ender dragon, crosses it.
- It is in the operator utilities tab.

## What does not work

- `project`, `editor-allowlist` (Bedrock Editor) and `sendshowstoreoffer` (marketplace) are dropped
  and reported. The other lines of the function still run.
- `/hud` touch controls and tooltips (button prompts) are dropped and reported.
- `/fog` uses the `air`, `lava`, `lava_resistance`, `powder_snow` and `weather` parts of a fog. Java
  draws its own fog for water, and has no volumetric fog.
- Camera presets: `player_effects` and `extend_player_rendering` are reported. Java always draws
  player effects and every loaded entity.
- An orbit camera circles the middle of the entity's box and is not pulled in by a wall.
- Java's camera has no roll, so a spline's roll is reported.
- Bedrock keeps a player's `pos` and `rot` for a preset until `default` or a rejoin. Here, a
  `/camera set` without them uses the preset's values.
