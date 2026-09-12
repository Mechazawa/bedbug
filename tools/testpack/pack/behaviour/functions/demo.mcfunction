# Every line here is a command or a selector argument the converter has to rewrite for Java.
testfor @a[r=10]
testforblock 0 64 0 minecraft:stone
titleraw @a actionbar {"rawtext":[{"text":"Test Add-On loaded"}]}
say @a[r=32,rm=4,c=1,m=survival]
say @e[type=tp:sprite,r=16,c=-3]
summon minecraft:villager_v2 ~ ~ ~
summon tp:ember ~ ~1 ~
