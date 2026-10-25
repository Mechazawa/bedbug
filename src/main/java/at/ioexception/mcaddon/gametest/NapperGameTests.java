package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.entity.AvoidBlockGoal;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * The sleeping, sitting, mingling and block avoiding goals, read through the pose and the place a
 * goal leaves on the entity.
 *
 * `tp:napper` carries one group per behaviour, and every test switches on the one it asks about.
 */
public final class NapperGameTests {
    private static final String NAPPER = "tp:napper";

    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    private static final BlockPos MIDDLE = new BlockPos(4, 0, 4);

    private static final String NIGHT = "time set 18000";

    private static final String DAY = "time set day";

    /** Past Java's check cadence, which looks at a goal that is not running on every second tick. */
    private static final int SLACK = 6;

    private static final int AVOID_TRIALS = 30;

    /**
     * Sleeping follows the night and the goal's own fields: a goal at a lower priority number than a
     * sitting one lies down over it and one at a higher number is held off, a rider sleeps only with
     * `can_sleep_while_riding`, and `cooldown_time` keeps a second night from being slept at once.
     *
     * The clock is shared by every test running beside this one, so the phases of one night and
     * day live in a single test that sets the time on every tick of its own window.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void aSleepingGoalFollowsTheNightAndItsFields(GameTestHelper helper) {
        AddonEntity first = napper(helper, "tp:napper_make_sitting", STAND);
        AddonEntity last = napper(helper, "tp:napper_make_sitting", STAND.east(3));
        AddonEntity refusing = napper(helper, "tp:napper_make_sleeping_first", STAND.south(2));
        AddonEntity allowed = napper(helper, "tp:napper_make_sleeping_riding", STAND.south(2).east(3));
        AddonEntity rested = napper(helper, "tp:napper_make_sleeping_rested", STAND.south(4));
        if (first == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(first, "tp:napper_make_sleeping_first", null, ServerHooks.state());
        EntityEvents.trigger(last, "tp:napper_make_sleeping_last", null, ServerHooks.state());
        refusing.startRiding(boat(helper, STAND.south(2)), true, false);
        allowed.startRiding(boat(helper, STAND.south(2).east(3)), true, false);
        helper.onEachTick(() -> {
            long now = helper.getTick();
            boolean night = now >= NIGHT_FROM && now < NIGHT_UNTIL || now >= SECOND_NIGHT_FROM;
            at(helper, night ? NIGHT : DAY);
        });
        helper.runAtTickTime(NIGHT_FROM, () -> {
            helper.assertTrue(first.getPose() == Pose.SITTING && last.getPose() == Pose.SITTING,
                NAPPER + " does not sit by day, which the night's contest needs.");
            helper.assertTrue(Boolean.TRUE.equals(first.value("is_looking_and_sitting")),
                NAPPER + " sits and does not say it is looking around.");
        });
        helper.runAtTickTime(NIGHT_UNTIL - 1, () -> {
            helper.assertTrue(first.getPose() == Pose.SLEEPING,
                NAPPER + " sleeps at priority 3 over a sitting goal at 4 and is " + first.getPose() + ".");
            helper.assertTrue(last.getPose() == Pose.SITTING,
                NAPPER + " sleeps at priority 5 under a sitting goal at 4 and is " + last.getPose() + ".");
            helper.assertTrue(refusing.getPose() != Pose.SLEEPING, NAPPER + " slept while riding without leave.");
            helper.assertTrue(allowed.getPose() == Pose.SLEEPING, NAPPER + " did not sleep riding with leave.");
            helper.assertTrue(rested.getPose() == Pose.SLEEPING, NAPPER + " did not sleep its first night.");
        });
        helper.runAtTickTime(SECOND_NIGHT_FROM - 1, () -> helper.assertTrue(rested.getPose() != Pose.SLEEPING,
            NAPPER + " went on sleeping by day."));
        helper.runAtTickTime(SECOND_NIGHT_FROM + 40, () -> {
            helper.assertTrue(rested.getPose() != Pose.SLEEPING,
                NAPPER + " slept again inside the thirty seconds its cooldown_time states.");
            at(helper, DAY);
            helper.succeed();
        });
    }

    private static final int NIGHT_FROM = 20;

    private static final int NIGHT_UNTIL = 60;

    private static final int SECOND_NIGHT_FROM = 80;

    /** An entity that was sitting when it was stored sits again only with `continue_sitting_on_reload`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void aReloadedSitterContinuesOnlyWhenItMay(GameTestHelper helper) {
        AddonEntity kept = napper(helper, "tp:napper_make_sitting_kept", STAND);
        AddonEntity dropped = napper(helper, "tp:napper_make_sitting_dropped", STAND.east(3));
        if (kept == null) {
            helper.succeed();
            return;
        }

        kept.setValue("is_looking_and_sitting", true);
        dropped.setValue("is_looking_and_sitting", true);
        AddonEntity keptAgain = reloaded(helper, kept);
        AddonEntity droppedAgain = reloaded(helper, dropped);
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(keptAgain.getPose() == Pose.SITTING,
                NAPPER + " stood after a reload although continue_sitting_on_reload is true.");
            helper.assertTrue(droppedAgain.getPose() != Pose.SITTING,
                NAPPER + " sat after a reload although continue_sitting_on_reload is false.");
            helper.assertFalse(Boolean.TRUE.equals(droppedAgain.value("is_looking_and_sitting")),
                NAPPER + " kept the mark of a sit it no longer continues.");
            helper.succeed();
        });
    }

    /**
     * An entity mingling walks up to the nearest partner of its type, faces it for `duration`
     * seconds and starts again only after `cooldown_time`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void aMinglerChatsWithItsPartnerThenRests(GameTestHelper helper) {
        AddonEntity mingler = napper(helper, "tp:napper_make_mingling", STAND);
        if (mingler == null) {
            helper.succeed();
            return;
        }

        mingler.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.4);
        Mob pig = pig(helper, STAND.east(6));
        long begun = helper.getLevel().getGameTime();
        long[] chat = {-1, -1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            boolean mingling = mingler.runningGoals().stream().anyMatch(goal -> goal.startsWith("MingleGoal"));
            if (mingling && mingler.distanceTo(pig) <= 3.5 && chat[0] < 0) chat[0] = now;
            if (chat[0] >= 0 && chat[1] < 0 && !mingling) chat[1] = now;
        });
        helper.runAtTickTime(300, () -> {
            helper.assertTrue(chat[0] >= 0, NAPPER + " never walked up to the pig.");
            helper.assertTrue(chat[1] >= 0, NAPPER + " never stopped chatting.");
            helper.assertTrue(Math.abs(chat[1] - chat[0] - 40) <= SLACK,
                NAPPER + " chats for two seconds and chatted for " + (chat[1] - chat[0]) + " ticks.");
            helper.succeed();
        });
    }

    /** `target_selection_method` picks the nearest block by default and any block in the box when it is `random`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void anAvoiderPicksTheBlockItsSelectionMethodSays(GameTestHelper helper) {
        AddonEntity avoider = napper(helper, "tp:napper_make_avoiding_nearest", MIDDLE);
        if (avoider == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(MIDDLE.west(2), Blocks.GOLD_BLOCK);
        helper.setBlock(MIDDLE.east(3), Blocks.GOLD_BLOCK);
        helper.runAtTickTime(10, () -> checkFlights(helper, avoider));
    }

    private static void checkFlights(GameTestHelper helper, AddonEntity avoider) {
        int[] nearest = flights(avoider, "nearest");
        int[] random = flights(avoider, "random");
        helper.assertTrue(nearest[0] > 0 && nearest[1] == 0,
            NAPPER + " fled a block other than the nearest: " + nearest[0] + " near, " + nearest[1] + " far.");
        helper.assertTrue(random[0] > 0 && random[1] > 0,
            NAPPER + " picks a random block and always fled the same one: " + random[0] + " near, "
                + random[1] + " far.");
        helper.succeed();
    }

    /** How often a fresh goal picked the block west of the entity (the near one) and east of it (the far one). */
    private static int[] flights(AddonEntity avoider, String method) {
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray blocks = new com.google.gson.JsonArray();
        blocks.add("minecraft:gold_block");
        body.add("target_blocks", blocks);
        body.addProperty("search_range", 6);
        body.addProperty("search_height", 2);
        body.addProperty("target_selection_method", method);
        int[] ran = {0, 0};
        for (int i = 0; i < AVOID_TRIALS; i++) {
            AvoidBlockGoal goal = new AvoidBlockGoal(avoider, body);
            if (!goal.canUse()) continue;

            ran[goal.fleeing().getX() < avoider.blockPosition().getX() ? 0 : 1]++;
        }
        return ran;
    }

    private static void at(GameTestHelper helper, String time) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), time);
    }

    private static AddonEntity napper(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity napper = BehaviourGameTests.spawn(helper, NAPPER, where);
        if (napper == null) return null;

        napper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        EntityEvents.trigger(napper, event, null, ServerHooks.state());
        napper.setNoAi(false);
        return napper;
    }

    private static AddonEntity reloaded(GameTestHelper helper, AddonEntity stored) {
        UUID id = stored.getUUID();
        EntityType<?> type = stored.getType();
        BlockPos at = stored.blockPosition();
        stored.discard();
        ServerLevel level = helper.getLevel();
        Entity fresh = Platform.createMob(type, level);
        fresh.setUUID(id);
        Platform.placeAt(fresh, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(fresh);
        ((AddonEntity) fresh).getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        return (AddonEntity) fresh;
    }

    private static Entity boat(GameTestHelper helper, BlockPos where) {
        Entity boat = Platform.createMob(named("oak_boat"), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(boat, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(boat);
        return boat;
    }

    private static EntityType<?> named(String name) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", name)).orElseThrow();
    }

    private static Mob pig(GameTestHelper helper, BlockPos where) {
        Mob pig = (Mob) Platform.createMob(named("pig"), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }
}
