package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Blocks;

/** `minecraft:dweller` read back off `tp:settler` through the tickets Java's point of interest manager keeps. */
public final class SettlerGameTests {
    private static final String SETTLER = "tp:settler";
    private static final BlockPos STAND = new BlockPos(2, 0, 2);
    private static final BlockPos BED = new BlockPos(0, 0, 0);
    private static final BlockPos BELL = new BlockPos(4, 0, 0);
    private static final BlockPos COMPOSTER = new BlockPos(3, 0, 2);
    private static final BlockPos LECTERN = new BlockPos(4, 0, 4);

    /**
     * An inhabitant that `can_find_poi` claims a bed, a bell and the job site of its
     * `preferred_profession`: the lectern, though a composter stands nearer. It gives them back as
     * it dies.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void anInhabitantClaimsABedABellAndItsProfessionsJobSite(GameTestHelper helper) {
        village(helper);
        AddonEntity settler = settler(helper, "tp:settler_inhabit");
        if (settler == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertFalse(hasSpace(helper, BED), SETTLER + " is an inhabitant and has not claimed the bed.");
                helper.assertFalse(hasSpace(helper, LECTERN),
                    SETTLER + " is a librarian and has not claimed the lectern.");
                helper.assertTrue(bellTickets(helper) < BELL_TICKETS, SETTLER + " has not claimed the bell.");
            })
            .thenExecute(() -> {
                helper.assertTrue(hasSpace(helper, COMPOSTER), SETTLER + " is a librarian and claimed a composter.");
                settler.kill(helper.getLevel());
            })
            .thenWaitUntil(() -> helper.assertTrue(hasSpace(helper, BED) && hasSpace(helper, LECTERN),
                SETTLER + " died and kept its claims."))
            .thenSucceed();
    }

    /** A dweller of another role claims nothing, though it can find points of interest. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aDefenderClaimsNothing(GameTestHelper helper) {
        village(helper);
        AddonEntity settler = settler(helper, "tp:settler_defend");
        if (settler == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(LOOKED_ROUND, () -> {
            helper.assertTrue(hasSpace(helper, BED) && hasSpace(helper, LECTERN) && hasSpace(helper, COMPOSTER),
                SETTLER + " is a defender and claimed a point of interest.");
            helper.succeed();
        });
    }

    private static final int LOOKED_ROUND = 40;
    private static final int BELL_TICKETS = 32;

    private static void village(GameTestHelper helper) {
        NapperGameTests.bed(helper, BED);
        helper.setBlock(BELL, Blocks.BELL);
        helper.setBlock(COMPOSTER, Blocks.COMPOSTER);
        helper.setBlock(LECTERN, Blocks.LECTERN);
    }

    private static boolean hasSpace(GameTestHelper helper, BlockPos at) {
        return helper.getLevel().getPoiManager()
            .getCountInRange(type -> true, helper.absolutePos(at), 0, PoiManager.Occupancy.HAS_SPACE) > 0;
    }

    /** The bell takes many claims, so its free tickets are counted by taking them all. */
    private static int bellTickets(GameTestHelper helper) {
        BlockPos bell = helper.absolutePos(BELL);
        PoiManager manager = helper.getLevel().getPoiManager();
        int free = 0;
        while (manager.take(type -> true, (type, at) -> at.equals(bell), bell, 0).isPresent()) free++;
        for (int i = 0; i < free; i++) manager.release(bell);
        return free;
    }

    private static AddonEntity settler(GameTestHelper helper, String event) {
        AddonEntity settler = BehaviourGameTests.spawn(helper, SETTLER, STAND);
        if (settler == null) return null;

        EntityEvents.trigger(settler, event, null, ServerHooks.state());
        return settler;
    }
}
