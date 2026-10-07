package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Consumer;

/**
 * What the test pack's own script reads back about the world when a player runs one of its probes.
 *
 * Each probe is a script event the pack's script answers by tagging whoever ran it, so the whole
 * path is under test: the module exporting the name, the script calling it, the bridge carrying the
 * call across and the game doing what was asked. With no pack script loaded each test succeeds
 * without asserting.
 */
public final class ScriptGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The platform's floor, which the older versions place one block higher. */
    //? if >=26 {
    private static final int FLOOR = 0;
    //?} else {
    /*private static final int FLOOR = 1;
    *///?}

    /** Standing on the platform's floor. */
    private static final BlockPos ON_FLOOR = new BlockPos(1, FLOOR + 1, 1);

    /** Long enough for a script event and the world events it raises to have been delivered. */
    private static final int DELIVERED = 3;

    /**
     * A player's fog stack answers a script that pushes, pops and removes layers on it.
     *
     * The pack's fog goes on twice, once under a tag of the pack's own and once under none, which
     * Bedrock files as `untagged`; popping the tag takes off the one layer carrying it.
     */
    @GameTest()
    public void aPlayersFogStackAnswersTheScriptStackingIt(GameTestHelper helper) {
        probe(helper, "tp:fog", "", player -> {
            expectTag(helper, player, "tp:fog_pushed_0_1_probe+untagged");
            expectTag(helper, player, "tp:fog_popped_tp:dusk_1");
            expectTag(helper, player, "tp:fog_left_0");
        });
    }

    /**
     * A script's clone keeps what its filter names and a move leaves the place it moved from empty.
     *
     * The row is a stone with a dirt block east of it. Copying it with a filter naming stone puts
     * the stone two blocks up and nothing beside it, and moving the whole row puts both four blocks
     * up and leaves air where they were.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aScriptClonesWhatItsFilterKeepsAndMovesTheRest(GameTestHelper helper) {
        BlockPos stone = ON_FLOOR;
        BlockPos dirt = ON_FLOOR.east();
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(dirt, Blocks.DIRT);
        BlockPos at = helper.absolutePos(stone);

        probe(helper, "tp:clone", at.getX() + " " + at.getY() + " " + at.getZ(), player -> {
            helper.assertBlockPresent(Blocks.STONE, stone.above(2));
            helper.assertBlockPresent(Blocks.AIR, dirt.above(2));
            helper.assertBlockPresent(Blocks.STONE, stone.above(4));
            helper.assertBlockPresent(Blocks.DIRT, dirt.above(4));
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertBlockPresent(Blocks.AIR, dirt);
        });
    }

    /** A row of three blocks hands back its first block as the nearest to it and its last as farthest. */
    @GameTest()
    public void aVolumeHandsBackItsNearestAndFarthestPositions(GameTestHelper helper) {
        probe(helper, "tp:row", "", player -> expectTag(helper, player, "tp:row_0_2"));
    }

    /**
     * A block tells a script what a note block plays off it, and keeps nothing a block entity of its
     * own would. Stone carries the bass drum and plays nothing under a note block.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aBlockAnswersWhatANoteBlockPlaysOffIt(GameTestHelper helper) {
        helper.setBlock(ON_FLOOR, Blocks.STONE);
        BlockPos stone = helper.absolutePos(ON_FLOOR);
        probe(helper, "tp:block", stone.getX() + " " + stone.getY() + " " + stone.getZ(), player -> {
            expectTag(helper, player, "tp:plays_note.bd_note.none");
            expectTag(helper, player, "tp:keeps_nothing");
        });
    }

    /** The pack's own lamp asks for a store, which `minecraft:dynamic_properties` writes into. */
    @GameTest(structure = "mcaddon:platform")
    public void aBlockKeepingAStoreKeepsWhatAScriptPutsInIt(GameTestHelper helper) {
        net.minecraft.world.level.block.Block lamp = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        if (lamp == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(ON_FLOOR, lamp.defaultBlockState());
        BlockPos at = helper.absolutePos(ON_FLOOR);
        probe(helper, "tp:block", at.getX() + " " + at.getY() + " " + at.getZ(),
            player -> expectTag(helper, player, "tp:keeps_7"));
    }

    /**
     * A script hears a wolf being tamed and may refuse to let another be.
     *
     * The pack refuses to let anything carrying `tp:untameable` be tamed and tags whatever a player
     * tamed, so one of the two wolves ends up tame and tagged and the other neither.
     */
    @GameTest()
    public void aScriptHearsATameAndMayRefuseOne(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        TamableAnimal willing = wolf(helper);
        TamableAnimal unwilling = wolf(helper);
        unwilling.addTag("tp:untameable");
        willing.tame(player);
        unwilling.tame(player);
        helper.runAfterDelay(DELIVERED, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.assertTrue(willing.isTame(), "A wolf nothing refused to have tamed was not tamed.");
            helper.assertTrue(Platform.tagsOf(willing).contains("tp:tamed_by_a_player"),
                "The pack's script never heard a wolf being tamed.");
            helper.assertFalse(unwilling.isTame(),
                "The pack's script refused a tame and the wolf was tamed anyway.");
            helper.succeed();
        });
    }

    /**
     * A script hears a player crouch and stand up again.
     *
     * A crouch is found by comparing the player against the tick before, so the player stands for a
     * few ticks first: one already crouching when first looked at has done nothing to report.
     */
    @GameTest()
    public void aScriptHearsAPlayerCrouchAndStand(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runAfterDelay(DELIVERED, () -> player.setShiftKeyDown(true));
        helper.runAfterDelay(DELIVERED * 2, () -> player.setShiftKeyDown(false));
        helper.runAfterDelay(DELIVERED * 3, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            expectTag(helper, player, "tp:crouched");
            expectTag(helper, player, "tp:stood_up");
            helper.succeed();
        });
    }

    /**
     * Using an item reaches the custom component it names as a key of its own, with its parameters.
     *
     * `tp:tally` writes `tp:counting` the way a pack does from 1.21.90, and the pack's script tags
     * whoever used the item with the step the item gave the component.
     */
    @GameTest()
    public void anItemsOwnComponentRunsWithItsParameters(GameTestHelper helper) {
        net.minecraft.world.item.Item tally = BuiltInRegistries.ITEM
            .getOptional(Platform.id("tp", "tally")).orElse(null);
        if (tally == null || ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.minecraft.world.item.ItemStack held = new net.minecraft.world.item.ItemStack(tally);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        held.use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.getLevel().getServer().getPlayerList().remove(player);
        expectTag(helper, player, "tp:counted_3");
        helper.succeed();
    }

    private static TamableAnimal wolf(GameTestHelper helper) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "wolf")).orElseThrow();
        ServerLevel level = helper.getLevel();
        Entity wolf = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(wolf, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(wolf);
        return (TamableAnimal) wolf;
    }

    /**
     * Runs one of the pack's probes as a player standing in the test, then checks what it left.
     *
     * The player is taken out of the world again whatever the checks find, so no later test meets a
     * stranger in the player list.
     */
    private static void probe(GameTestHelper helper, String id, String message,
        Consumer<ServerPlayer> check) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute(
                "scriptevent \"" + id + "\"" + (message.isEmpty() ? "" : " " + message),
                Platform.commandSourceOf(player, helper.getLevel()));
        } catch (CommandSyntaxException error) {
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.fail("The probe " + id + " did not run: " + error.getMessage());
            return;
        }
        helper.runAfterDelay(DELIVERED, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            check.accept(player);
            helper.succeed();
        });
    }

    private static void expectTag(GameTestHelper helper, Entity entity, String tag) {
        helper.assertTrue(Platform.tagsOf(entity).contains(tag),
            "The pack's script left " + Platform.tagsOf(entity) + " rather than " + tag + ".");
    }
}
