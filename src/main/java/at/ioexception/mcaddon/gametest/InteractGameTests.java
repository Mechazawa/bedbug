package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** What a pack's `minecraft:interact` does besides raising an event, on the test pack's `tp:wooly`. */
public final class InteractGameTests {
    private static final String WOOLY = "tp:wooly";
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    private static AddonEntity wooly(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(WOOLY);
        if (type == null) return null;

        AddonEntity wooly = (AddonEntity) Platform.createMob(type, helper.getLevel());
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(wooly, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        wooly.setNoAi(true);
        helper.getLevel().addFreshEntity(wooly);
        return wooly;
    }

    private static ServerPlayer survivor(GameTestHelper helper, ItemStack hand) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, hand);
        return player;
    }

    private static long fleece(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).size();
    }

    /**
     * A shearing interaction swings the arm, wears the shears by `hurt_item`, drops the loot table it
     * names, raises its event and then refuses a second click until its `cooldown` is over, while an
     * interaction stating `use_item` spends one of what the player holds.
     */
    @GameTest(maxTicks = 80)
    public void anInteractionDoesWhatItStates(GameTestHelper helper) {
        AddonEntity wooly = wooly(helper);
        if (wooly == null) {
            helper.succeed();
            return;
        }

        ItemStack shears = new ItemStack(Items.SHEARS);
        ServerPlayer player = survivor(helper, shears);
        helper.runAfterDelay(1, () -> Platform.interactOn(player, wooly, InteractionHand.MAIN_HAND));
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(shears.getDamageValue() == 1, "The shears took " + shears.getDamageValue() + " damage.");
            helper.assertTrue(fleece(helper) == 2, "It dropped " + fleece(helper) + " stacks of its table.");
            helper.assertTrue(Platform.tagsOf(wooly).contains("tp_wooly_sheared"), "Its event did not run.");
            Platform.interactOn(player, wooly, InteractionHand.MAIN_HAND);
        });
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(shears.getDamageValue() == 1, "A second click inside the cooldown wore the shears.");
        });
        helper.runAfterDelay(20, () -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 3));
            Platform.interactOn(player, wooly, InteractionHand.MAIN_HAND);
        });
        helper.runAfterDelay(22, () -> {
            helper.assertTrue(player.getMainHandItem().getCount() == 2,
                "use_item left " + player.getMainHandItem().getCount() + " of three wheat.");
            player.setItemInHand(InteractionHand.MAIN_HAND, shears);
            Platform.interactOn(player, wooly, InteractionHand.MAIN_HAND);
        });
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(shears.getDamageValue() == 2, "The cooldown never ended.");
            helper.succeed();
        });
    }

    /** The experience a breeding pays is read from a group that is on, which is where packs write it. */
    @GameTest()
    public void breedingPaysWhatTheLiveGroupStates(GameTestHelper helper) {
        AddonEntity wooly = wooly(helper);
        if (wooly == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(wooly.experienceWhenBred() == 0, "It paid " + wooly.experienceWhenBred() + " early.");
        EntityEvents.trigger(wooly, "tp:wooly_reward", null, ServerHooks.state());
        helper.assertTrue(wooly.experienceWhenBred() == 7, "It paid " + wooly.experienceWhenBred() + " late.");
        helper.succeed();
    }

    /** A loot table path no pack ships is Java's own table for that mob. */
    @GameTest()
    public void aVanillaLootPathNoPackShipsIsJavasOwn(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(WOOLY);
        if (type == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(AddonEntities.lootTable(type, "loot_tables/entities/sheep.json")
            .equals(Platform.id("minecraft", "entities/sheep")), "sheep.json is "
            + AddonEntities.lootTable(type, "loot_tables/entities/sheep.json"));
        helper.assertTrue(AddonEntities.lootTable(type, "loot_tables/entities/sheep_shear.json")
            .equals(Platform.id("minecraft", "shearing/sheep")), "sheep_shear.json is "
            + AddonEntities.lootTable(type, "loot_tables/entities/sheep_shear.json"));
        helper.assertTrue(AddonEntities.lootTable(type, "loot_tables/entities/fleece.json").getNamespace().equals("tp"),
            "The pack's own table was replaced.");
        helper.succeed();
    }
}
