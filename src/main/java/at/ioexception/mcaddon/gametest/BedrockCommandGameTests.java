package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bedrock commands whose forms Java's own command of the same name has no word for, run through
 * `/bedrock` and read back off the world.
 *
 * Each line is written the way a pack writes it. The entities a line acts on are named by their uuid
 * or by a tag of the test's own, because the tests of one run share a level.
 */
public final class BedrockCommandGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * `/ride` mounts a group only where the whole of it fits, fills a vehicle until it is full,
     * empties it again, brings a vehicle to its rider and summons either half of the pair.
     */
    @GameTest()
    public void rideMountsEmptiesAndSummons(GameTestHelper helper) {
        Entity boat = place(helper, "minecraft:boat", STAND);
        List<Entity> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Entity pig = place(helper, "minecraft:pig", STAND.offset(i + 1, 0, 3));
            pig.addTag("t_ride_three");
            three.add(pig);
        }
        String vehicle = boat.getStringUUID();

        helper.assertTrue(BedrockLines.run(helper, "ride @e[tag=t_ride_three] start_riding " + vehicle
            + " teleport_rider if_group_fits") == 0, "three pigs were put in a boat with two seats.");
        helper.assertTrue(boat.getPassengers().isEmpty(), "a group that did not fit left some of itself aboard.");

        helper.assertTrue(BedrockLines.run(helper, "ride @e[tag=t_ride_three] start_riding " + vehicle) == 2,
            "until_full did not seat two of three pigs.");
        helper.assertTrue(boat.getPassengers().size() == 2, "the boat carries " + boat.getPassengers().size() + ".");

        helper.assertTrue(BedrockLines.run(helper, "ride " + vehicle + " evict_riders") == 1,
            "evict_riders emptied nothing.");
        helper.assertTrue(boat.getPassengers().isEmpty(), "evict_riders left riders in the boat.");

        Entity walker = three.get(2);
        BedrockLines.run(helper, "ride " + walker.getStringUUID() + " start_riding " + vehicle + " teleport_ride");
        helper.assertTrue(walker.getVehicle() == boat, "teleport_ride did not seat the pig.");
        helper.assertTrue(boat.position().distanceTo(walker.position()) < 1.5,
            "teleport_ride left the boat where it was rather than bringing it to the pig.");
        BedrockLines.run(helper, "ride " + walker.getStringUUID() + " stop_riding");
        helper.assertFalse(walker.isPassenger(), "stop_riding left the pig in the boat.");

        BedrockLines.run(helper, "ride " + vehicle + " summon_rider pig minecraft:entity_born t_ride_rosie");
        helper.assertTrue(boat.getPassengers().stream().anyMatch(rider -> named(rider, "t_ride_rosie")),
            "summon_rider did not seat a pig named by the line: " + boat.getPassengers());

        Entity rider = three.get(0);
        String pig = rider.getStringUUID();
        BedrockLines.run(helper, "ride " + pig + " summon_ride minecart");
        Entity first = rider.getVehicle();
        helper.assertTrue(first != null && first.getType() == type("minecraft:minecart"),
            "summon_ride did not put the pig in a minecart: " + first);
        helper.assertTrue(BedrockLines.run(helper, "ride " + pig + " summon_ride minecart no_ride_change") == 0
                && BedrockLines.run(helper, "ride " + pig + " summon_ride minecart skip_riders") == 0
                && rider.getVehicle() == first,
            "no_ride_change or skip_riders moved a pig that was already riding.");
        BedrockLines.run(helper, "ride " + pig + " summon_ride minecart reassign_rides");
        helper.assertTrue(rider.getVehicle() != null && rider.getVehicle() != first,
            "reassign_rides did not move the pig into a minecart of its own.");
        helper.succeed();
    }

    /**
     * `/summon` turns, names and starts what it makes: a rotation, a point and an entity to face, a
     * name written before the position, and a spawn event the pack's own entity answers.
     */
    @GameTest()
    public void summonTurnsNamesAndStartsWhatItMakes(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(STAND.offset(2, 0, 2));
        // Whole numbers, which a position argument puts in the middle of the block the way Bedrock does.
        String where = at.getX() + " " + at.getY() + " " + at.getZ();

        BedrockLines.run(helper, "summon pig " + where + " 90 10 minecraft:entity_born t_summon_turned");
        Entity turned = find(helper, "t_summon_turned");
        helper.assertTrue(Math.abs(Mth.wrapDegrees(turned.getYRot() - 90f)) < 0.01f
                && Math.abs(turned.getXRot() - 10f) < 0.01f,
            "the pig faces " + turned.getYRot() + " / " + turned.getXRot() + " rather than 90 / 10.");

        BedrockLines.run(helper, "summon pig t_summon_named " + where);
        helper.assertTrue(find(helper, "t_summon_named").position().distanceTo(turned.position()) < 0.01,
            "a name written before the position did not put the pig there.");

        BedrockLines.run(helper, "summon pig " + where + " facing " + (at.getX() + 6) + " " + at.getY() + " "
            + at.getZ() + " minecraft:entity_born t_summon_east");
        float east = find(helper, "t_summon_east").getYRot();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(east + 90f)) < 1f, "facing a point east left the pig at " + east);

        Entity north = place(helper, "minecraft:armor_stand", STAND.offset(2, 0, -1));
        BedrockLines.run(helper, "summon pig " + where + " facing " + north.getStringUUID()
            + " minecraft:entity_born t_summon_north");
        float facing = find(helper, "t_summon_north").getYRot();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(facing - 180f)) < 1f,
            "facing an entity to the north left the pig at " + facing);

        if (AddonEntities.typeOf("tp:ember") != null) {
            BedrockLines.run(helper, "summon tp:ember " + where + " 0 0 tp:ember_command t_summon_ember");
            helper.succeedWhen(() -> helper.assertTrue(
                Platform.tagsOf(find(helper, "t_summon_ember")).contains("tp_event_command_translated"),
                "the spawn event the line named never ran on the ember."));
            return;
        }
        helper.succeed();
    }

    /**
     * `/replaceitem` fills a horse's two equippable slots, the saddle and then the armour, and `keep`
     * leaves an occupied slot as it was while it fills an empty one. 1.20.1 has no test of a slot,
     * so the line keeping one is reported there instead.
     */
    @GameTest()
    public void replaceitemFillsEquippableSlotsAndKeepsAnOccupiedOne(GameTestHelper helper) {
        //? if >=26 {
        net.minecraft.world.entity.animal.equine.AbstractHorse horse =
            (net.minecraft.world.entity.animal.equine.AbstractHorse) place(helper, "minecraft:horse", STAND);
        //?} else {
        /*net.minecraft.world.entity.animal.horse.AbstractHorse horse =
            (net.minecraft.world.entity.animal.horse.AbstractHorse) place(helper, "minecraft:horse", STAND);
        *///?}
        horse.setTamed(true);
        String uuid = horse.getStringUUID();

        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 0 saddle");
        helper.assertTrue(horse.isSaddled(), "slot.equippable 0 did not saddle the horse.");
        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 1 iron_horse_armor");
        helper.assertTrue(Platform.horseArmourOf(horse).is(Items.IRON_HORSE_ARMOR),
            "slot.equippable 1 did not put armour on the horse: " + Platform.horseArmourOf(horse));

        if (!Platform.testsSlots()) {
            helper.succeed();
            return;
        }
        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 1 keep golden_horse_armor");
        helper.assertTrue(Platform.horseArmourOf(horse).is(Items.IRON_HORSE_ARMOR),
            "keep replaced the armour the horse already wore.");

        LivingEntity stand = (LivingEntity) place(helper, "minecraft:armor_stand", STAND.offset(3, 0, 3));
        BedrockLines.run(helper, "replaceitem entity " + stand.getStringUUID()
            + " slot.armor.head 0 keep diamond_helmet");
        helper.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
            "keep did not fill an empty slot.");
        helper.succeed();
    }

    private static boolean named(Entity entity, String name) {
        return entity.getCustomName() != null && entity.getCustomName().getString().equals(name);
    }

    /** The entity the test summoned under that name, which a failed lookup fails the test over. */
    private static Entity find(GameTestHelper helper, String name) {
        for (Entity entity : helper.getLevel().getAllEntities()) {
            if (named(entity, name)) return entity;
        }
        helper.fail("nothing named " + name + " was summoned.");
        throw new IllegalStateException();
    }

    private static EntityType<?> type(String bedrock) {
        return AddonEntities.typeOf(bedrock);
    }

    private static Entity place(GameTestHelper helper, String bedrock, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Entity entity = Platform.createMob(type(bedrock), level);
        helper.assertTrue(entity != null, bedrock + " could not be created.");

        BlockPos where = helper.absolutePos(relative);
        Platform.placeAt(entity, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(entity);
        return entity;
    }
}
