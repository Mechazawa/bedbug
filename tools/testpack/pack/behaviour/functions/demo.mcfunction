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
