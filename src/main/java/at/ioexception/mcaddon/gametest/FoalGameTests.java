package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** `minecraft:offspring` read back off a child `tp:foal` parents breed. */
public final class FoalGameTests {
    private static final String FOAL = "tp:foal";
    private static final BlockPos STAND = new BlockPos(2, 0, 2);
    private static final double PARENT_HEALTH = 30;
    private static final double OWN_HEALTH = 20;

    /**
     * `blend_attributes` false leaves the attributes `parent_centric_attribute_blending` lists alone:
     * both parents hold thirty health and the child keeps the twenty its own kind starts at.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aChildWhoseParentsDoNotBlendKeepsItsOwnAttributes(GameTestHelper helper) {
        AddonEntity first = BehaviourGameTests.spawn(helper, FOAL, STAND);
        AddonEntity second = BehaviourGameTests.spawn(helper, FOAL, STAND.east());
        if (first == null || second == null) {
            helper.succeed();
            return;
        }

        Player feeder = Platform.mockPlayer(helper);
        feeder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 2));
        for (AddonEntity parent : List.of(first, second)) {
            EntityEvents.trigger(parent, "tp:foal_unblend", null, ServerHooks.state());
            parent.setNoAi(false);
            parent.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PARENT_HEALTH);
            parent.mobInteract(feeder, InteractionHand.MAIN_HAND);
        }

        helper.succeedWhen(() -> {
            AddonEntity child = helper.getLevel().getEntitiesOfClass(AddonEntity.class,
                    first.getBoundingBox().inflate(4), found -> found != first && found != second)
                .stream().findFirst().orElse(null);
            helper.assertTrue(child != null, FOAL + " was fed in pairs and no child was born.");
            helper.assertTrue(child.getMaxHealth() == OWN_HEALTH,
                FOAL + " does not blend and was born with " + child.getMaxHealth() + " health.");
        });
    }
}
