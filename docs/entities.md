# Entities

## Definitions

A behaviour pack's entities work with their properties, events, component groups, filters, breeding,
riding, leashing, home ranges, navigation and item pickup.

Sensors fire their events: environment, entity, target nearby, block, timer, scheduler, drying out,
damage, interact, spawn entity and hurt on condition.

- Properties reach every client, so a model looks the same in multiplayer as in single player.
- `Player.setPropertyOverrideForEntity` sends an override to that one player's client.
- Numbers use Bedrock's units and Bedrock's defaults when the pack leaves a field out.

## Component groups

A group says what an entity becomes. When a group is added or removed, these change with it:

- the navigator and the move control
- step height, climbing, pushing, fire, which effects can land on it
- what it sells
- the collision box, which can change width and height apart
- leashes: removing `minecraft:leashable` or `minecraft:leashable_to` lets go of the lead, as
  `unleash_on_removal` asks (its default)

## Behaviour pack animations and controllers

Clips and controllers in a behaviour pack drive what the entity does. They run on the server, on
the entity's tick.

- A timeline moment, a state's entry and a state's exit can fire an entity event, run a command as
  the entity, or set a Molang variable.
- A controller moves at most once per tick. Starting counts as a move, so the first state's clips
  play once even when a transition holds from the start.
- Transitions read the entity: `query.is_on_ground`, health, a declared property.
- A state's command runs with the server's authority, with `@s` as the entity.

## Riding

- Each rider sits in its own seat. A seat is in use while the rider count is between its
  `min_rider_count` and `max_rider_count`. Riders take the seats in use in the order the pack lists
  them, at each seat's position, turned with the mount.
- A seat's `rotate_rider_by` is evaluated each tick as its rider. It turns the rider by that much
  from the mount's facing, held by `lock_rider_rotation`, on every client too.
- Molang `temp.` values are shared by all server expressions, as in Bedrock. A seat can write the
  rider's `query.trade_tier` and a component condition can read it.
- `minecraft:can_power_jump`: hold the jump key to charge a jump up to
  `minecraft:horse.jump_strength`.
- `minecraft:dash_action`: press jump to dash along the mount's facing or the rider's look
  (`direction`), on the ground, or under water if `can_dash_underwater` is set. During
  `cooldown_time`, `query.can_dash` is false and `query.has_dash_cooldown` is true.
- A mount with neither component shows no jump bar.
- `minecraft:tamemount`: tame by riding. Temper rises with food and with each buck.
- A rideable entity is mounted by using it. `pull_in_entities` pulls in what touches it. Rider
  events fire.

## Kinds of entity

- **Projectile**: flies the arc of its component, with the pack's inaccuracy. On impact it can
  damage, burn, poison, stick in the ground, drop experience, show particles and fire an event. A
  `minecraft:shooter`, a bow or a throwable item fires it. The thrower is safe from it for the ticks
  the pack sets.
- **Rail rider**: stays on rails at its speed, answers a powered rail with its event, and drops
  riders where told.
- **Memory**: records what came near and where it heard something. Memory behaviours walk to it,
  flee it or hold a place.
- **Dragon death**: takes the ender dragon's 200 ticks to die. A mob takes 20.
- **Hit boxes** wider than the collision box: hit where it looks hit, by swings and arrows, and still
  fits through the same gap.
- `isolated_physics` off: moves like any entity, so it stops against what it hits and cobwebs slow
  it.

## What the world does to an entity

- Powder snow freezes it unless `minecraft:freezing_immune`.
- `minecraft:freezing_vulnerable`: cold does five times the damage.
- `minecraft:can_stand_on_powder_snow`: powder snow holds it up.
- `minecraft:block_movement_slowdown_immunity`: listed blocks do not slow it. Bedrock's `web` is
  Java's cobweb.
- `minecraft:uses_legacy_ambient_sounds`: the idle sound depends on head under water, off the
  ground, or young, from the moments in `sounds.json`.
- Water slows it by `drag_factor`. Honey lowers its jump to `jump_prevented_value`.
- It despawns only as its `minecraft:despawn` says. Without one it never does, however far the
  nearest player is.
- A block it overlaps from the side by up to half its width pushes it back out. Java lets a box walk
  on into a block it already reaches into, so a mob wider than a block, placed beside a wall, would
  otherwise suffocate in the wall.

## Fields read inside components

- **Breathing**: holds its breath for `total_supply` seconds. With nothing to breathe it never
  drowns.
- **Navigation**: prices water, lava and damaging blocks as its allowances say. A generic navigation
  that swims and never walks plans like a fish.
- **Movement**: turns no faster than `max_turn` (default 30 degrees per tick). Strolls and swims as
  far as `xz_dist` and `y_dist`. Jumps at `jump_power`. `minecraft:movement.hover` holds its height
  with gravity off, whatever `minecraft:physics` says, as Mojang's bee and allay do.
- **Looking and temptation**: looks at a player for `look_time`. Is tempted from `within_radius`.
- **Item pickup**: walks to an item at the behaviour's own priority.
- **Targets**: hunts and answers what `entity_types` filters name. Vanilla mobs have the families of
  Mojang's behaviour pack. An angry entity passes its target to its own kind.
- **Taming and breeding**: follows its owner. Breeds only when tamed unless `require_tame` says
  otherwise. Rests for `breed_cooldown`. Fires the event that `breeds_with` names.
- **Offspring** (`minecraft:offspring`): takes a tamed parent's owner, a parent's variant, mark
  variant, colour and listed properties (unless a mutation keeps its own), the mixed dye colour, and
  attributes blended as Java blends a horse's. A bred child fires `minecraft:entity_born`, a placed
  one `minecraft:entity_spawned`.
- **Growing up**: drops `drop_items` when it grows up. Is fed only by those `interact_filters`
  allow. A feed item's `growth` is a share of the whole wait.
- **Timers**: a transformation waits its delay. A lit fuse goes off when its time ends.
- **Home**: holds the entity only when `restriction_type` says so.
- **Pushing**: bumps and pistons push it by its presets.
- **Leads**: only on `minecraft:leashable`. From 26, stretch and break where the holder's preset
  says. `can_be_stolen` says if a player can take it from its holder.
- **Names**: a name tag works only where `minecraft:nameable` allows. The name fires the event in
  `name_actions`.
- **Inventory**: holds no more of an item than its shareables allow.

## Combat

- **Melee**: waits `cooldown_time`, reaches by `reach_multiplier` or `horizontal_reach`, hits only
  inside `melee_fov`, fires `on_attack` and `on_kill`, and passes on fire under
  `can_spread_on_fire`. The blow carries the effect `minecraft:attack` names.
- **Ranged**: reloads for `attack_interval`, spread across `attack_range`. Holds fire inside the low
  end of the range. Closes in until it has seen the target for `target_in_sight_time`. With
  `charge_shoot_trigger` it charges while it sees the target (`query.is_charged`) and fires
  `burst_shots`. Fires only at a target inside `ranged_fov`, from and at the anchors and offsets the
  behaviour names.
- **Arrows**: carry the potion `minecraft:shooter` names in `aux_val`. The first entry of
  `projectiles` whose filters pass is fired. `lose_target` drops the target.
- **Ocelot attack**: sneaks, sprints and walks at three speeds by three ranges. Strikes in reach
  every `cooldown_time`.
- **Pounce**: stalks at `stalk_speed`, stays interested for `interest_time`, leaps by `leap_dist`
  and `leap_height`, strikes within `strike_dist`, and is stuck for `stuck_time` on `stuck_blocks`.
- **Leap**: can start in the air when `must_be_on_ground` is false. Leaps at `yd`.
- **Ram**: runs at `run_speed` and plays its two sounds.
- **Aquatic charge**: rests `charge_cooldown_time`.
- **Mob effect**: given from a component group, as ambient under `ambient`, waits `cooldown_time`.
- **Strength**: stops at its `max`.
- **Delayed attack**: lands part way through the swing. **Stomp**: hurts only within its range.
- **Jump around target**: crouches, then leaps on an arc it can clear to a point behind the target.
  Rests less between jumps when hurt recently.
- `target_when_pushed`: turns on what pushes it.
- **Area attack**: can hurt without naming itself as the attacker.
- **Knockback roar**: throws back and hurts what its two filters name, `attack_time` into the roar,
  then fires `on_roar_end`.
- **Teleport**: closes on a far target and blinks away from projectiles.
- **Projectile hit by a player**: batted back, stopped or removed. It hits what its box sweeps
  through. It stops at the first entity unless `multiple_targets`, and bounces under
  `should_bounce`.
- **Explosion**: breaks blocks whatever the griefing rule says unless the pack asks for the rule.
  Caps the blast resistance it can break. `allow_underwater`: water does not stop it. From 1.21 it
  scales damage and knockback, toggles switches when it breaks nothing, spares a thrown player the
  fall, and uses a wind charge's gusts and sound when the pack names them.

## Fleeing and moving

- **Panic**: starts from `damage_sources`. Can ignore mob attacks. Goes to water under
  `prefer_water`. Needs no damage under `force`. Plays the pack's sound.
- **Escape from a block**: fires `on_escape` when clear.
- **Flocking**: separation, cohesion, variant matching, flock limit and loners. Keeps away from
  blocks, between its heights over the ground, and under the water surface.
- **Fish**: swims off with an `interval` of 0. Stays under the surface unless `avoid_surface` is
  false.
- `swim_wander`: `interval` is a chance. Gives up after `wander_time`.
- **Fly and hover**: aim `y_offset` above the chosen spot, within `hover_height`.
- **Float**: lasts `float_duration`. `random_reselect` picks a new spot on the way.
- **Follow mob**: searches `search_range`, stops `stop_distance` short.
- **Raider**: keeps `follow_distance` from its captain.
- **Move to block**: checks every `tick_interval` at `start_chance`, ends at `target_offset`, fires
  `on_reach` and `on_stay_completed`. The default `goal_radius` is half a block.
- **Shelter**: walks out under the sky or in under a roof.
- **Water**: looks for water as far as it says.
- **Drink potion**: picks the potion by Bedrock's number.
- **Steered**: steps higher while a player steers it.
- **Stomp turtle egg**: searches every `interval`, no further than `search_range`.
- **Eat block**: eats the blocks its pairs name, at its own Molang chance.
- **Snacking**: eats only its `items`, then waits its cooldown.
- **Lay egg**: lays only under the materials it names.
- **Take flower**: only while its filters pass.
- **Admire and barter**: stop until `cooldown_after_being_attacked` ends after a hit. Admiring and
  egg laying play the pack's sounds.
- `minecraft:equip_item` with `can_wear_armor` false leaves armour on the ground.
- **Celebrate hunt**: by the entity that made the kill and, with `broadcast`, the hunters within
  `radius`.
- **Raid**: a won raid fires `minecraft:start_celebrating` on add-on entities in it. While the group
  is on, a raider jumps and plays `celebration_sound`, a villager shoots fireworks, and the end event
  fires after `duration`.
- **Village**: Java has no village object. A village is the beds, job sites and bells in reach. A
  villager explores past its edge by `explore_dist`. A fox walks to the nearest village. A child
  plays with `friend_types`. `minecraft:behavior.hide` runs to the `poi_type` place when a bell rings
  or a raid is on.
- **Trader**: a trader without `minecraft:behavior.trade_with_player` stands still while its shop is
  open.

## Damage causes

Damage causes use Bedrock's names (`entity_attack`, `fire_tick`, `suffocation`) in damage sensors,
`has_damage`, panic, playing dead, damage absorption, `minecraft:hurt_on_condition`, block event
damage, the `damage` command, `applyDamage` and `entityHurt`. Each matches the Java damage types
that are the same harm. A cause Java has no type for, such as `temperature`, is dealt as the nearest
Java type.

## Buckets

`minecraft:bucketable` lets a player catch the entity with a water bucket and release it with the
same component groups. Each such entity gets a bucket item named `<entity id>_bucket`, drawn as a
water bucket.

## The player definition

A pack's `entities/player.json` can state `minecraft:player.hunger`, `player.saturation`,
`player.exhaustion`, `player.level` and `player.experience`, each with a `value` and a `max`.

- The values are set at a player's first join and at each respawn. A later login keeps what the
  player had.
- The maximums apply every time the value is read.
- `minecraft:exhaustion_values` prices ten actions against the food bar: attack, hurt, heal on a full
  bar, jump, sprint jump, mine, sprint, swim, walk or crouch, and lunge. Each price scales Java's own
  charge. A field the pack leaves out keeps Java's value.

## What does not work

- `lunge` in `minecraft:exhaustion_values` on 1.20.1 and 1.21.1. Those versions have no lunge
  enchantment.
- A player cannot open an entity's inventory. Java builds that screen only for its own horses,
  minecarts and hoppers.
- `suffocate_time`: Java's drowning rhythm applies.
- `has_gravity` is ignored. Mojang's fish declare it off and still fall.
- A dweller holds its place without joining a Java village.
- A tempt sound's interval is ignored.
- How often an entity is sent over the network is Java's choice.
- `has_damage` with `fatal` works only inside a damage sensor. There it compares the damage before
  armour with the health left.
- `minecraft:camera` (Education Edition) and `minecraft:interact_button` (touch controls).
- `tools/census/fields.py` lists every other unread field with its reason.
