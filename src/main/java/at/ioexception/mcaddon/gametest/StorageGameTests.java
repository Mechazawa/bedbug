package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.registry.AddonItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a pack's bag holds, put in the way a player puts a stack into a bundle with the cursor.
 *
 * `tp:pouch` states a `minecraft:storage_weight_limit` of 48 of Bedrock's 64, `tp:dust` weighs 16
 * by its `minecraft:storage_weight_modifier` and `tp:berry` states a weight of nothing, which keeps
 * it out of every bag. With no pack loaded each of these succeeds without asserting.
 */
public final class StorageGameTests {
    private static final String BAG = "tp:pouch";

    @GameTest()
    public void aBagHoldsWhatItsLimitSays(GameTestHelper helper) {
        int left = leftAfterPutting(helper, "minecraft:dirt", 64);
        if (left < 0) return;

        helper.assertTrue(left == 16, BAG + " holds 48 of the 64 a bundle holds, so 48 of 64 dirt go in and 16"
            + " stay on the cursor, and " + left + " stayed.");
        helper.succeed();
    }

    @GameTest()
    public void anItemWeighsWhatItsModifierSays(GameTestHelper helper) {
        int left = leftAfterPutting(helper, "tp:dust", 64);
        if (left < 0) return;

        helper.assertTrue(left == 61, "tp:dust weighs 16 and " + BAG + " holds 48, so three of 64 go in and 61"
            + " stay on the cursor, and " + left + " stayed.");
        helper.succeed();
    }

    @GameTest()
    public void anItemWeighingNothingStaysOutOfABag(GameTestHelper helper) {
        int left = leftAfterPutting(helper, "tp:berry", 16);
        if (left < 0) return;

        helper.assertTrue(left == 16, "tp:berry states a weight of nothing, which keeps it out of every bag, and "
            + (16 - left) + " of 16 went in.");
        helper.succeed();
    }

    /**
     * Clicks a stack held on the cursor onto a fresh bag and answers how many stayed on the cursor,
     * or -1, having succeeded, where the pack declares no bag or no such item.
     */
    private static int leftAfterPutting(GameTestHelper helper, String item, int count) {
        Item bag = AddonItems.named(BAG);
        Item put = AddonItems.named(item);
        if (bag == null || put == null) {
            helper.succeed();
            return -1;
        }

        ItemStack carried = new ItemStack(put, count);
        ItemStack held = new ItemStack(bag);
        Slot slot = new Slot(new SimpleContainer(held), 0, 0, 0);
        //? if >=1.21.2 {
        ClickAction putting = ClickAction.PRIMARY;
        //?} else {
        /*ClickAction putting = ClickAction.SECONDARY;
        *///?}
        // A bundle tells the player's open menu about the change, which only a server player has.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            held.getItem().overrideOtherStackedOnMe(held, carried, slot, putting, player, null);
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        return carried.getCount();
    }
}
