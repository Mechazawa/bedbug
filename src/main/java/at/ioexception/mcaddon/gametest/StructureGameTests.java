package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.script.surface.Containers;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * What holds inside a scene the test was given rather than one it built.
 *
 * These run against the structures under `data/mcaddon/gametest`, which `tools/structures/build.py`
 * writes. A scene is what lets a test say something about an entity on solid ground or a container
 * that was already there, which a test standing in empty air has to fake and therefore cannot prove.
 */
public final class StructureGameTests {
    /**
     * Where the scene's own first layer lands once the framework has placed it.
     *
     * 26 puts a structure's bottom layer at the test's own origin; before that the framework leaves a
     * layer of its own underneath and everything the structure describes sits one block higher.
     */
    //? if >=26 {
    private static final int FLOOR = 0;
    //?} else {
    /*private static final int FLOOR = 1;
    *///?}

    /** The scene's floor, and the space above it a test can put something in. */
    private static final BlockPos GROUND = new BlockPos(3, FLOOR, 3);
    private static final BlockPos ABOVE_FLOOR = new BlockPos(3, FLOOR + 1, 3);

    /** Where the two copies of the pack's hut go, side by side on the platform's floor. */
    private static final BlockPos HUT_BY_FUNCTION = new BlockPos(0, FLOOR + 1, 0);
    private static final BlockPos HUT_BY_SCRIPT = new BlockPos(4, FLOOR + 1, 4);

    /** Where `chest_room` puts its chest, one layer above its own floor. */
    private static final BlockPos CHEST = new BlockPos(1, FLOOR + 1, 1);

    /**
     * The scene arrives with the floor its structure describes.
     *
     * Everything below depends on the structure having been read and placed, so this asserts that
     * first: a test that silently ran in empty air would pass for the wrong reason.
     */
    @GameTest(structure = "mcaddon:platform")
    public void theSceneIsBuiltBeforeTheTestRuns(GameTestHelper helper) {
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.STONE, GROUND);
        helper.assertBlockNotPresent(net.minecraft.world.level.block.Blocks.STONE, ABOVE_FLOOR);
        helper.succeed();
    }

    /**
     * A pack's entity stands on the ground it is put on.
     *
     * Spawning one in empty air proves it was created; spawning it onto a floor proves it settles
     * rather than falling out of the scene, which is what its collision box and its movement are for.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aPackEntityStandsOnTheFloor(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf("tp:ember");
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        helper.assertTrue(spawned != null, "tp:ember could not be created.");

        BlockPos at = helper.absolutePos(ABOVE_FLOOR);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        helper.runAfterDelay(10, () -> {
            helper.assertFalse(spawned.isRemoved(), "tp:ember left the world it was put in.");
            helper.assertTrue(spawned.onGround(), "tp:ember never came to rest on the floor.");
            helper.assertTrue(spawned.getY() >= at.getY() - 1,
                "tp:ember fell through the floor it was put on.");
            helper.succeed();
        });
    }

    /**
     * The container surface answers for a chest the scene placed.
     *
     * A chest a test puts down itself is one the test already knows about. One the structure placed
     * has to be found by position, which is the path a pack takes.
     */
    @GameTest(structure = "mcaddon:chest_room")
    public void aScenePlacedChestCanBeFilled(GameTestHelper helper) {
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.CHEST, CHEST);

        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(CHEST);
        Containers containers = new Containers(level.getServer(), new ScriptCoverage());

        String handle = containers.ofBlock(Platform.idOf(level.dimension()).toString(),
            at.getX(), at.getY(), at.getZ());
        helper.assertTrue(handle != null, "The chest the scene placed gave no container.");

        JsonObject stack = new JsonObject();
        stack.addProperty("typeId", "minecraft:diamond");
        stack.addProperty("amount", 3);
        containers.setItem(handle, 4, stack.toString());

        String read = containers.item(handle, 4);
        helper.assertTrue(read != null, "The chest forgot what it was given.");
        helper.assertTrue(Json.parse(read).getAsJsonObject().get("amount").getAsInt() == 3,
            "The chest changed the amount it was given.");
        helper.succeed();
    }

    /**
     * An entity inside a walled scene stays inside it.
     *
     * A pack's entity wanders under its own goals, and the pen is what turns that into something a
     * test can assert: after a hundred ticks of walking it has to still be in the box.
     */
    @GameTest(structure = "mcaddon:pen", maxTicks = 160)
    public void aPackEntityStaysInsideThePen(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf("tp:ember");
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(new BlockPos(4, FLOOR + 1, 4));
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        helper.runAfterDelay(100, () -> {
            helper.assertFalse(spawned.isRemoved(), "tp:ember did not survive a hundred ticks.");

            BlockPos corner = helper.absolutePos(BlockPos.ZERO);
            boolean inside = spawned.getX() >= corner.getX() && spawned.getX() <= corner.getX() + 8
                && spawned.getZ() >= corner.getZ() && spawned.getZ() <= corner.getZ() + 8;
            helper.assertTrue(inside, "tp:ember walked out through the wall of the pen.");
            helper.succeed();
        });
    }

    /**
     * A pack's block sits in the scene and can be broken out of it.
     *
     * Placing one is what the conversion proves; taking it out again goes through the block's own
     * hardness and its drops, which a pack states as components.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aPackBlockCanBePlacedAndRemoved(GameTestHelper helper) {
        net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries
            .BLOCK.getOptional(Platform.id("tp", "lamp")).orElse(null);
        if (block == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(ABOVE_FLOOR, block.defaultBlockState());
        helper.assertBlockPresent(block, ABOVE_FLOOR);

        helper.destroyBlock(ABOVE_FLOOR);
        helper.assertBlockNotPresent(block, ABOVE_FLOOR);
        helper.succeed();
    }

    /**
     * A pack's own `.mcstructure` is placed by the name its path gives it, from a function and from
     * a script.
     *
     * The hut is a stone floor, a layer holding the pack's lamp and four jack o'lanterns written by
     * their Bedrock name with the rest of it left alone, and a layer of air. Glass is put where the
     * hut leaves the world alone and where it writes air, so each kind of cell shows. Nothing places
     * a pack's structure unless the game's own structure manager holds it.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aPacksStructureLoadsByItsName(GameTestHelper helper) {
        Block lamp = AddonBlocks.named("tp:lamp");
        if (lamp == null) {
            helper.succeed();
            return;
        }

        List<BlockPos> origins = List.of(HUT_BY_FUNCTION, HUT_BY_SCRIPT);
        for (BlockPos origin : origins) {
            helper.setBlock(origin.offset(1, 1, 0), Blocks.GLASS);
            helper.setBlock(origin.offset(1, 2, 1), Blocks.GLASS);
        }

        BlockPos function = helper.absolutePos(HUT_BY_FUNCTION);
        run(helper, "execute positioned " + function.getX() + " " + function.getY() + " " + function.getZ()
            + " run function tp:hut");
        BlockPos script = helper.absolutePos(HUT_BY_SCRIPT);
        run(helper, "scriptevent \"tp:hut\" " + script.getX() + " " + script.getY() + " " + script.getZ());

        helper.succeedWhen(() -> origins.forEach(origin -> {
            helper.assertBlockPresent(Blocks.STONE, origin.offset(2, 0, 2));
            helper.assertBlockPresent(lamp, origin.offset(1, 1, 1));
            helper.assertBlockPresent(Blocks.JACK_O_LANTERN, origin.offset(0, 1, 2));
            helper.assertBlockPresent(Blocks.GLASS, origin.offset(1, 1, 0));
            helper.assertBlockPresent(Blocks.AIR, origin.offset(1, 2, 1));
        }));
    }

    private static void run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
            command);
    }
}
