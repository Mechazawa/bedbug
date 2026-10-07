package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A pack's blocks, placed, used, stood on, aimed at and broken by a player in survival.
 *
 * `tp:totem` stores the way its placer faced, turned a quarter counter-clockwise by its
 * `y_rotation_offset`, and counts the times it is used through `minecraft:on_interact`; its script
 * component tags whoever used it. Its collision and selection boxes are half a block high until the
 * third use, where a permutation makes both whole. It drops three `tp:dust` from its own table.
 * `tp:lamp` names no table, so it drops itself; it lights itself five ticks after it is placed, and a
 * permutation gives the lit lamp a light of fifteen and, once it is also brightened, a hardness of
 * 1.5 against the 0.5 of the plain one.
 */
public final class BlockClientGameTest implements FabricClientGameTest {
    private static final BlockPos BLOCK = Stage.CENTRE.south().above();

    /** Java mines a block the right tool is not needed for at a thirtieth of a hardness a tick. */
    private static final int TICKS_PER_HARDNESS = 30;

    /** How many ticks a break measured from the key press may run over the arithmetic. */
    private static final int BREAK_SLACK = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            placeTotem(context, world);
            halfHigh(context, world);
            useTotem(context, world);
            wholeBlock(context, world);
            breakFor(context, world, "tp:dust", 3, 1.0f);

            Stage.build(context, world, "survival");
            lamp(context, world);
        }
    }

    private static void placeTotem(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.hold(context, world, "tp:totem", 2);
        // The top of the floor in front of the player, so the player faces south as they place it.
        Stage.lookAt(context, Vec3.atCenterOf(Stage.CENTRE.south()).add(0, 0.5, 0));
        context.getInput().pressKey(options -> options.keyUse);

        Worlds.waitForServer(context, world, server -> Stage.blockAt(server, BLOCK).equals("tp:totem"), 40,
            "tp:totem was placed from the hotbar and the server holds no tp:totem in front of the player.");
        String facing = world.getServer().computeOnServer(server -> stateValue(server, "minecraft:cardinal_direction"));
        Assert.that("east".equals(facing), "Expected tp:totem placed facing south to store east, south turned a"
            + " quarter counter-clockwise by its y_rotation_offset of 90, and it stores " + facing + ".");
        int left = world.getServer().computeOnServer(server -> Stage.held(server).getCount());
        Assert.that(left == 1, "Expected one of two tp:totem placed, leaving 1, and found " + left + ".");
    }

    /** Before its third use the totem is a slab: the player stands half a block up and aims over it. */
    private static void halfHigh(ClientGameTestContext context, TestSingleplayerContext world) {
        Assert.near(standingOn(context, world), BLOCK.getY() + 0.5, 0.01,
            "the height of a player standing on tp:totem, whose collision box is half a block");
        BlockPos aimed = aimOverTop(context);
        Assert.that(!BLOCK.equals(aimed), "Expected the crosshair to pass over tp:totem's half-high selection box"
            + " and it stopped on the totem.");
    }

    private static void useTotem(ClientGameTestContext context, TestSingleplayerContext world) {
        Stage.emptyHand(context, world);
        Stage.lookAt(context, Vec3.atLowerCornerOf(BLOCK).add(0.5, 0.25, 0.5));
        for (int taps = 1; taps <= 3; taps++) {
            context.getInput().pressKey(options -> options.keyUse);
            int wanted = taps;
            Worlds.waitForServer(context, world,
                server -> String.valueOf(wanted).equals(stateValue(server, "tp:taps")), 40,
                "tp:totem was used " + taps + " times and its tp:taps state never reached " + taps + "; it holds "
                    + world.getServer().computeOnServer(server -> stateValue(server, "tp:taps")) + ".");
        }
        boolean tagged = world.getServer().computeOnServer(server ->
            Stage.player(server).entityTags().contains("tp:tapped"));
        Assert.that(tagged, "tp:totem names the script component tp:tapped, whose onPlayerInteract tags the"
            + " player using the block, and the player carries no tp:tapped tag.");
    }

    /** At three uses a permutation makes both boxes whole. */
    private static void wholeBlock(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos aimed = aimOverTop(context);
        Assert.that(BLOCK.equals(aimed), "Expected the crosshair to stop on tp:totem once its permutation made the"
            + " selection box whole, and it stopped on " + aimed + ".");
        Assert.near(standingOn(context, world), BLOCK.getY() + 1.0, 0.01,
            "the height of a player standing on tp:totem once its permutation made the collision box whole");
    }

    private static void lamp(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand(setblock(BLOCK, "tp:lamp"));
        Worlds.waitForServer(context, world,
            server -> "true".equals(stateValue(server, "tp:lit")), 40,
            "tp:lamp queues its own lighting five ticks after it is placed and it never lit.");
        int emitted = world.getServer().computeOnServer(server ->
            Stage.player(server).level().getBlockState(BLOCK).getLightEmission());
        Assert.that(emitted == 15, "Expected a lit tp:lamp to give the light of fifteen its permutation states and it"
            + " gives " + emitted + ".");
        context.waitFor(client -> client.level.getBrightness(LightLayer.BLOCK, BLOCK.above()) == 14, 100);

        breakFor(context, world, "tp:lamp", 1, 0.5f);

        world.getServer().runCommand(setblock(BLOCK, "tp:lamp[tp_lit=true,tp_level=2]"));
        Worlds.waitForServer(context, world, server -> Stage.blockAt(server, BLOCK).equals("tp:lamp"), 40,
            "The brightened tp:lamp was never set.");
        breakFor(context, world, "tp:lamp", 1, 1.5f);
    }

    /**
     * Mines the block in front of the player with a bare hand and checks how long it took and what it
     * dropped, counting what lies on the ground and what the player has already picked up.
     */
    private static void breakFor(ClientGameTestContext context, TestSingleplayerContext world, String drop,
        int count, float hardness) {
        Stage.emptyHand(context, world);
        context.getInput().lookAt(BLOCK);
        context.waitTick();
        String block = world.getServer().computeOnServer(server -> Stage.blockAt(server, BLOCK));

        int expected = (int) Math.ceil(hardness * TICKS_PER_HARDNESS);
        // Counted from the first tick that takes anything off the block, because Java waits out a
        // few ticks after the previous block broke before it starts on the next one.
        context.getInput().holdKey(options -> options.keyAttack);
        int ticks = 0;
        for (int waited = 0; waited < expected * 3; waited++) {
            context.waitTick();
            boolean[] now = context.computeOnClient(client -> new boolean[] {client.gameMode.getDestroyStage() >= 0,
                client.level.getBlockState(BLOCK).isAir()});
            if (now[0] || now[1] || ticks > 0) ticks++;
            if (now[1]) break;
        }
        context.getInput().releaseKey(options -> options.keyAttack);

        Assert.that(ticks >= expected - 1 && ticks <= expected + BREAK_SLACK, "Expected " + block + " at a hardness"
            + " of " + hardness + " to take " + expected + " ticks to mine by hand and it took " + ticks + ".");
        Worlds.waitForServer(context, world, server -> Stage.blockAt(server, BLOCK).equals("minecraft:air"), 40,
            "The client broke " + block + " and the server still holds it.");
        Worlds.waitForServer(context, world, server -> dropped(server, drop) >= count, 60,
            "Expected breaking " + block + " to drop " + count + " " + drop + " and "
                + world.getServer().computeOnServer(server -> dropped(server, drop)) + " came of it.");
        int found = world.getServer().computeOnServer(server -> dropped(server, drop));
        Assert.that(found == count, "Expected breaking " + block + " to drop " + count + " " + drop + " and "
            + found + " came of it.");
        world.getServer().runCommand("kill @e[type=item]");
        world.getServer().runCommand("clear @a");
    }

    /** How many of an item lie around the stage or are already in the player's inventory. */
    private static int dropped(MinecraftServer server, String item) {
        int lying = Stage.player(server).level().getEntitiesOfClass(ItemEntity.class,
                Stage.player(server).getBoundingBox().inflate(8),
                entity -> id(entity.getItem()).equals(item))
            .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        net.minecraft.world.entity.player.Inventory inventory = Stage.player(server).getInventory();
        int carried = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (id(inventory.getItem(slot)).equals(item)) carried += inventory.getItem(slot).getCount();
        }
        return lying + carried;
    }

    /** Stands the player on top of the block and says the height their feet came to rest at. */
    private static double standingOn(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("tp @a " + (BLOCK.getX() + 0.5) + " " + (BLOCK.getY() + 1.5) + " "
            + (BLOCK.getZ() + 0.5));
        // Landed once the height has stopped changing for a few ticks, because the flag saying the
        // player is on the ground is still the one from before the teleport for a tick or two.
        double height = Double.NaN;
        for (int still = 0, tick = 0; still < 5 && tick < 100; tick++) {
            context.waitTick();
            double now = world.getServer().computeOnServer(server -> Stage.player(server).getY());
            still = now == height ? still + 1 : 0;
            height = now;
        }
        world.getServer().runCommand("tp @a " + (Stage.CENTRE.getX() + 0.5) + " " + (Stage.FLOOR + 1) + " "
            + (Stage.CENTRE.getZ() + 0.5) + " 0 0");
        context.waitFor(client -> client.player.blockPosition().equals(Stage.CENTRE.above()));
        return height;
    }

    /**
     * Aims from the middle of the stage over the top half of the block's space, which a half-high
     * selection box leaves empty and a whole one fills, and says which block the crosshair stops on.
     */
    private static BlockPos aimOverTop(ClientGameTestContext context) {
        Stage.lookAt(context, Vec3.atLowerCornerOf(BLOCK).add(0.5, 0.9, 0.95));
        context.waitTicks(2);
        return context.computeOnClient(client -> client.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null);
    }

    private static String setblock(BlockPos position, String block) {
        return "setblock " + position.getX() + " " + position.getY() + " " + position.getZ() + " " + block;
    }

    /** One of the pack's states on the block in front of the player, as the value the pack wrote. */
    private static String stateValue(MinecraftServer server, String name) {
        BlockState state = Stage.player(server).level().getBlockState(BLOCK);
        String java = name.replace(':', '_');
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(java)) continue;
            if (!(property instanceof IntegerProperty index)) return String.valueOf(state.getValue(property));

            int at = state.getValue(index);
            java.util.Map<String, java.util.List<String>> trait = BlockDefinition.TRAIT_STATES.values().stream()
                .filter(states -> states.containsKey(name)).findFirst().orElse(null);
            return trait == null ? String.valueOf(at) : trait.get(name).get(at);
        }
        return null;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
