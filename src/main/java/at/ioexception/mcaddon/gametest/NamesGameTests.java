package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The game's own items and blocks named the way Bedrock spells them, across every path a pack names
 * one by: a script, a function, a loot table and a recipe.
 *
 * Each of the four is the test pack's own, and each names something Bedrock and Java spell
 * differently: `golden_rail` is Java's powered rail, `lit_pumpkin` its jack o'lantern, `record_13`
 * its thirteenth music disc and `muttonRaw` its mutton. A name Java's parser has not got costs a
 * function or a loot table whole, so what is asserted is what each one hands out. With no pack
 * installed there is nothing to ask, and each test succeeds without asserting.
 */
public final class NamesGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * A script reads a powered rail as `minecraft:golden_rail` and places `minecraft:lit_pumpkin`.
     *
     * The pack's script answers `/scriptevent "tp:names"` by placing a pumpkin it resolved under
     * Bedrock's id above the position, and a gold block above that only when the block at the
     * position read back under Bedrock's id.
     */
    @GameTest()
    public void aScriptNamesTheGamesOwnBlocksTheWayBedrockDoes(GameTestHelper helper) {
        if (!packLoaded() || ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(STAND.below(), Blocks.STONE);
        helper.setBlock(STAND, Blocks.POWERED_RAIL);
        BlockPos at = helper.absolutePos(STAND);
        run(helper, "scriptevent \"tp:names\" " + at.getX() + " " + at.getY() + " " + at.getZ());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.JACK_O_LANTERN, STAND.above());
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, STAND.above(2));
        });
    }

    /**
     * A function naming items and blocks by Bedrock's ids loads and places the Java ones.
     *
     * `give @p record_13`, `setblock ~ ~ ~ golden_rail` and a block written with Bedrock's states
     * are each a line Java's parser refuses as written, and a function holding one is never loaded
     * at all. Nobody is there to be given the disc, so the line is proven by the function loading,
     * and `FunctionsTest` holds the Java line it becomes.
     */
    @GameTest()
    public void aFunctionLoadsAndPlacesTheBlocksBedrocksIdsName(GameTestHelper helper) {
        if (!packLoaded()) {
            helper.succeed();
            return;
        }

        helper.setBlock(STAND.below(), Blocks.STONE);
        BlockPos at = helper.absolutePos(STAND);
        // A function runs through the game's own command path, which is the one that can run one.
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
            "execute positioned " + at.getX() + " " + at.getY() + " " + at.getZ() + " run function tp:names");

        helper.assertBlockPresent(Blocks.POWERED_RAIL, STAND);
        helper.assertBlockPresent(block("red_wool"), STAND.above());
        helper.succeed();
    }

    /** A loot table naming Bedrock's ids, and a data value beside one, drops the Java items. */
    @GameTest()
    public void aLootTableDropsTheItemsBedrocksIdsName(GameTestHelper helper) {
        if (!packLoaded()) {
            helper.succeed();
            return;
        }

        List<ItemStack> rolled = Platform.rollLoot(helper.getLevel(), Platform.id("tp", "names"),
            Platform.mockPlayer(helper));
        for (String wanted : List.of("powered_rail", "mutton", "red_wool")) {
            helper.assertTrue(rolled.stream().anyMatch(stack -> stack.is(item(wanted))),
                "tp:names dropped no " + wanted + ", it dropped " + rolled + ".");
        }
        helper.succeed();
    }

    /** A recipe naming Bedrock's ids crafts the Java item out of the Java ingredient. */
    @GameTest()
    public void aRecipeCraftsTheItemsBedrocksIdsName(GameTestHelper helper) {
        if (!packLoaded()) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        ItemStack made = Platform.crafted(level, List.of(new ItemStack(item("powered_rail"))));
        helper.assertTrue(made.is(item("jack_o_lantern")),
            "A powered rail crafted into " + made + " rather than the jack o'lantern tp:lantern_from_rail names.");
        helper.succeed();
    }

    private static boolean packLoaded() {
        return AddonEntities.typeOf("tp:ember") != null;
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.getOptional(Platform.id("minecraft", name)).orElseThrow();
    }

    private static Block block(String name) {
        return BuiltInRegistries.BLOCK.getOptional(Platform.id("minecraft", name)).orElseThrow();
    }

    private static int run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (CommandSyntaxException error) {
            helper.fail("/" + command + " did not run: " + error.getMessage());
            return 0;
        }
    }
}
