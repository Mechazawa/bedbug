# Every line here is a command or a selector argument the converter has to rewrite for Java.
testfor @a[r=10]
testforblock 0 64 0 minecraft:stone
titleraw @a actionbar {"rawtext":[{"text":"Test Add-On loaded"}]}
say @a[r=32,rm=4,c=1,m=survival]
say @e[type=tp:sprite,r=16,c=-3]
summon minecraft:villager_v2 ~ ~ ~
summon tp:ember ~ ~1 ~
effect @s speed 30 2
effect @s clear
xp 12L @s
playsound note.harp @a
stopsound @a note.harp
gamerule dodaylightcycle true
gamemode 0 @s
difficulty p
title @a actionbar the pack is here
replaceitem entity @s slot.weapon.mainhand 0 minecraft:stone
spreadplayers 0 0 8 24 @a
tickingarea add 0 0 0 16 0 16 demo
tickingarea remove_all
time set sunrise
execute @a ~ ~ ~ say the old execute
execute as @a at @s run effect @s speed 5
execute in nether run say the nether is spelled differently
changesetting difficulty h
damage @s 1 fall entity @p
locate structure minecraft:village_plains
loot replace entity @s slot.armor.head 0 loot minecraft:entities/sheep
music play tp:sprite.chime 0.5
mobevent minecraft:pillager_patrols_event false
gametest stopall
particle tp:sparkle ~ ~ ~
scriptevent tp:demo the script bridge takes a namespaced id
dialogue change @e[type=tp:ember] tp:greeting
dialogue open @e[type=tp:ember,c=1] @p tp:greeting
dialogue change @e[type=tp:ember] tp:greeting @a
structure save tp:hut 0 0 0 4 4 4
structure load tp:hut 0 64 0 90_degrees x
replaceitem entity @s slot.armor 0 minecraft:leather_helmet
ride @e[type=zombie] start_riding @e[type=pig] teleport_rider
tickingarea remove 0 0 0
gamerule dofiretick true
ride @e[type=pig] evict_riders
summon pig ~ ~ ~ facing ~ ~1 ~
structure load tp:hut 0 64 0 0_degrees xz
gametest runthis
# Each of these is a form Java has no way to say, so the line goes and the rest of the file stays.
music play tp:sprite.chime 1 0 loop
