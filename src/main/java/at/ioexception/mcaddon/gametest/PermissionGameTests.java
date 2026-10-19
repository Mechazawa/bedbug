package at.ioexception.mcaddon.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock's visitor level, set by `/bedrock permission set <player> visitor`, read back off the
 * world: a visitor's clicks build nothing, break nothing, open no door and no chest and hurt no mob,
 * where the same clicks from a member do each of those.
 */
public final class PermissionGameTests {
    private static final BlockPos DOOR = new BlockPos(1, 2, 1);
    private static final BlockPos CHEST = new BlockPos(3, 2, 1);
    private static final BlockPos SUPPORT = new BlockPos(5, 1, 1);
    private static final BlockPos MINED = new BlockPos(1, 2, 4);
    private static final BlockPos PEN = new BlockPos(4, 2, 4);

    @GameTest()
    public void aVisitorOnlyLooks(GameTestHelper helper) {
        tried(helper, "visitor", (player, pig) -> {
            helper.assertBlockPresent(Blocks.AIR, SUPPORT.above());
            helper.assertBlockPresent(Blocks.STONE, MINED);
            helper.assertFalse(helper.getBlockState(DOOR).getValue(DoorBlock.OPEN), "a visitor opened a door.");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "a visitor opened a chest.");
            helper.assertTrue(pig.getHealth() == pig.getMaxHealth(), "a visitor hurt a pig.");
            List<String> listed = listed(helper);
            helper.assertTrue(listed.stream().anyMatch(line -> line.contains(" visitor(s): ")
                && line.contains(player.getScoreboardName())), "permission list named no visitor: " + listed);
        });
    }

    @GameTest()
    public void aMemberBuildsMinesOpensAndAttacks(GameTestHelper helper) {
        tried(helper, "member", (player, pig) -> {
            helper.assertBlockPresent(Blocks.STONE, SUPPORT.above());
            helper.assertBlockPresent(Blocks.AIR, MINED);
            helper.assertTrue(helper.getBlockState(DOOR).getValue(DoorBlock.OPEN), "a member opened no door.");
            helper.assertTrue(player.containerMenu != player.inventoryMenu, "a member opened no chest.");
            helper.assertTrue(pig.getHealth() < pig.getMaxHealth(), "a member left a pig unhurt.");
        });
    }

    private interface Outcome {
        void check(ServerPlayer player, LivingEntity pig);
    }

    /** A player put at the level named uses a door and a chest, places a block, mines one and hits a pig. */
    private static void tried(GameTestHelper helper, String level, Outcome outcome) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.setBlock(DOOR, Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(DOOR.above(),
            Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(SUPPORT, Blocks.STONE.defaultBlockState());
        helper.setBlock(MINED, Blocks.STONE.defaultBlockState());
        //? if >=26.2 {
        LivingEntity pig = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.PIG, PEN);
        //?} else {
        /*LivingEntity pig = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, PEN);
        *///?}

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.addTag("t_level");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "bedrock permission set @a[tag=t_level] " + level);

            use(helper, player, ItemStack.EMPTY, DOOR, Direction.NORTH);
            use(helper, player, ItemStack.EMPTY, CHEST, Direction.UP);
            ItemStack stone = new ItemStack(Items.STONE);
            player.setItemInHand(InteractionHand.MAIN_HAND, stone);
            use(helper, player, stone, SUPPORT, Direction.UP);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.gameMode.destroyBlock(helper.absolutePos(MINED));
            player.attack(pig);

            outcome.check(player, pig);
        } finally {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "bedrock permission set @a[tag=t_level] member");
            player.closeContainer();
            server.getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static void use(GameTestHelper helper, ServerPlayer player, ItemStack held, BlockPos at, Direction face) {
        BlockPos absolute = helper.absolutePos(at);
        player.gameMode.useItemOn(player, helper.getLevel(), held, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(absolute), face, absolute, false));
    }

    private static List<String> listed(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        CommandSource console = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                heard.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(console),
            "bedrock permission list");
        return heard;
    }
}
