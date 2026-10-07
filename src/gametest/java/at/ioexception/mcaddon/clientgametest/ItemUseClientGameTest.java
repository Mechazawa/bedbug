package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A pack's items, used the way a player uses them: by holding the item and pressing the use key.
 *
 * `tp:berry` is food worth four points that takes 0.8 seconds to eat, half the time Java gives a
 * food that states none. `tp:visor` is worn on the head. `tp:pebble` throws `tp:spark`, whose own
 * `minecraft:projectile` flies at 1.4 blocks a tick. `tp:ember_spawn_egg` releases `tp:ember`. Each
 * runs in survival, where using an item costs one of it.
 */
public final class ItemUseClientGameTest implements FabricClientGameTest {
    private static final int BERRY_NUTRITION = 4;
    private static final int BERRY_TICKS = 16;
    private static final int STARTING_FOOD = 10;

    /** How far a reading taken once a client tick may sit from the server tick it is about. */
    private static final int SAMPLING = 2;

    private static final double SPARK_POWER = 1.4;

    /** How far from its thrower the client has to see a spark, in blocks, to call it on its way. */
    private static final double SEEN_FLYING = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            eat(context, world);
            wear(context, world);
            throwPebble(context, world);
            spawnEgg(context, world);
        }
    }

    private static void eat(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.build(context, world, "survival");
        Stage.hold(context, world, "tp:berry", 3);
        world.getServer().runOnServer(server -> Stage.player(server).getFoodData().setFoodLevel(STARTING_FOOD));
        context.getInput().lookAt(0, -90);

        // Counted on the server's own clock, from the tick it saw the player start eating to the tick
        // the food arrived, so the time the key press takes to reach it is left out.
        context.getInput().holdKey(options -> options.keyUse);
        int began = -1;
        int ate = -1;
        for (int waited = 0; waited < BERRY_TICKS * 4 && ate < 0; waited++) {
            context.waitTick();
            int[] now = world.getServer().computeOnServer(server -> new int[] {server.getTickCount(),
                Stage.player(server).isUsingItem() ? 1 : 0, Stage.player(server).getFoodData().getFoodLevel()});
            if (began < 0 && now[1] == 1) began = now[0];
            if (now[2] != STARTING_FOOD) ate = now[0];
        }
        context.getInput().releaseKey(options -> options.keyUse);

        Assert.that(food(world) == STARTING_FOOD + BERRY_NUTRITION, "Expected eating tp:berry to raise the food"
            + " level from " + STARTING_FOOD + " to " + (STARTING_FOOD + BERRY_NUTRITION) + " and found "
            + food(world) + ".");
        Assert.that(began >= 0 && Math.abs(ate - began - BERRY_TICKS) <= SAMPLING, "Expected tp:berry to take "
            + BERRY_TICKS + " server ticks to eat, its pack's 0.8 seconds, and it took " + (ate - began) + ".");
        int left = world.getServer().computeOnServer(server -> Stage.held(server).getCount());
        Assert.that(left == 2, "Expected one of three tp:berry eaten, leaving 2, and found " + left + ".");
    }

    private static int food(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> Stage.player(server).getFoodData().getFoodLevel());
    }

    private static void wear(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.build(context, world, "survival");
        Stage.hold(context, world, "tp:visor", 1);
        context.getInput().lookAt(0, -90);
        context.getInput().pressKey(options -> options.keyUse);

        Worlds.waitForServer(context, world,
            server -> !Stage.player(server).getItemBySlot(EquipmentSlot.HEAD).isEmpty(), 40,
            "tp:visor was used from the hotbar and nothing went onto the player's head.");
        String worn = world.getServer().computeOnServer(server ->
            id(Stage.player(server).getItemBySlot(EquipmentSlot.HEAD)));
        Assert.that(worn.equals("tp:visor"), "Expected tp:visor on the player's head and found " + worn + ".");
        String hand = world.getServer().computeOnServer(server -> id(Stage.held(server)));
        Assert.that(hand.equals("minecraft:air"), "Expected the hand that wore tp:visor to be empty and found "
            + hand + ".");
    }

    private static void throwPebble(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.build(context, world, "survival");
        Stage.hold(context, world, "tp:pebble", 4);
        // Due north and level, so the throw travels along negative z.
        context.getInput().lookAt(180, 0);
        context.getInput().pressKey(options -> options.keyUse);

        Worlds.waitForServer(context, world, server -> !sparks(server).isEmpty(), 40,
            "tp:pebble was thrown and no tp:spark came into the world.");
        AddonEntity spark = world.getServer().computeOnServer(server -> sparks(server).get(0));
        boolean thrownByPlayer = world.getServer().computeOnServer(server -> spark.firedBy() == Stage.player(server));
        Assert.that(thrownByPlayer, "Expected the tp:spark a player threw to name that player as its shooter and it"
            + " named " + spark.firedBy() + ".");

        double speed = world.getServer().computeOnServer(server -> spark.getDeltaMovement().horizontalDistance());
        Assert.near(speed, SPARK_POWER, 0.1, "the horizontal speed of a thrown tp:spark, which its own projectile"
            + " component states");
        double heading = world.getServer().computeOnServer(server -> spark.getDeltaMovement().z);
        Assert.that(heading < 0, "Expected a tp:spark thrown due north to travel along negative z and its z speed"
            + " was " + heading + ".");
        AddonEntity seen = Entities.waitForClient(context, "tp:spark");
        int flown = context.waitFor(client -> seen.distanceTo(client.player) > SEEN_FLYING, 40);
        Assert.that(flown < 40, "The client's copy of a thrown tp:spark never got further than " + SEEN_FLYING
            + " blocks from the player who threw it.");

        int left = world.getServer().computeOnServer(server -> Stage.held(server).getCount());
        Assert.that(left == 3, "Expected one of four tp:pebble thrown, leaving 3, and found " + left + ".");
    }

    private static List<AddonEntity> sparks(MinecraftServer server) {
        return Stage.player(server).level().getEntitiesOfClass(AddonEntity.class,
            Stage.player(server).getBoundingBox().inflate(48),
            entity -> entity.definition() != null && entity.definition().identifier().equals("tp:spark"));
    }

    private static void spawnEgg(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.build(context, world, "survival");
        Stage.hold(context, world, "tp:ember_spawn_egg", 2);
        BlockPos target = Stage.CENTRE.east();
        context.getInput().lookAt(target);
        context.getInput().pressKey(options -> options.keyUse);

        Worlds.waitForServer(context, world, server -> !embers(server).isEmpty(), 40,
            "tp:ember_spawn_egg was used on the floor and no tp:ember came into the world.");
        BlockPos stands = world.getServer().computeOnServer(server -> embers(server).get(0).blockPosition());
        Assert.that(stands.equals(target.above()), "Expected the tp:ember a spawn egg released to stand on the block"
            + " it was used on, at " + target.above() + ", and it stands at " + stands + ".");
        int left = world.getServer().computeOnServer(server -> Stage.held(server).getCount());
        Assert.that(left == 1, "Expected one of two tp:ember_spawn_egg spent, leaving 1, and found " + left + ".");
    }

    private static List<AddonEntity> embers(MinecraftServer server) {
        return Stage.player(server).level().getEntitiesOfClass(AddonEntity.class,
            Stage.player(server).getBoundingBox().inflate(48),
            entity -> entity.definition() != null && entity.definition().identifier().equals("tp:ember"));
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
