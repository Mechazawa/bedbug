package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.PosedParts;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.function.Predicate;

/**
 * A Bedrock clip played on a mob Java draws moves the parts of Java's model of it.
 *
 * The test pack's two clips name the bones of Bedrock's own villager. `animation.tp.vanilla.duck`
 * runs once for two seconds: it scales `head` to nothing, turns `leg0` and lifts `helmet`, which Java
 * calls the head, the right leg and the hat, and scales `held_item`, which Java's villager has no
 * part for. `animation.tp.vanilla.sway` loops, turning `leg1`, so only the stop expression it is
 * played with can end it, and the blend out it is played with lets it go over two seconds.
 */
public final class JavaModelPoseClientGameTest implements FabricClientGameTest {
    private static final float TOLERANCE = 1e-4f;
    private static final float LEG_TURN = (float) Math.toRadians(45);
    private static final float HAT_LIFT = 2f;
    private static final float SWAY = (float) Math.toRadians(30);

    /** Ticks to wait for a pose, which covers the command reaching the client and the clip's length. */
    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon minecraft:villager ~ ~ ~3 {NoAI:1b}");
            LivingEntity villager = Entities.waitForClientLiving(context, "minecraft:villager");
            Map<String, PosedParts.Part> own = context.computeOnClient(client -> PosedParts.of(villager));
            Assert.near(own.get("head").xScale(), 1, TOLERANCE, "the head's scale before any clip is played");

            duck(context, world, villager, own);
            sway(context, world, villager, own);
        }
    }

    private static void duck(ClientGameTestContext context, TestSingleplayerContext world, LivingEntity villager,
        Map<String, PosedParts.Part> own) {
        world.getServer().runCommand("playanimation @e[type=minecraft:villager] animation.tp.vanilla.duck");
        Map<String, PosedParts.Part> ducked = waitForPose(context, villager,
            parts -> parts.get("head").xScale() == 0f,
            "animation.tp.vanilla.duck was played on a villager and its head was never scaled to nothing.");

        PosedParts.Part head = ducked.get("head");
        Assert.near(head.yScale() + head.zScale(), 0, TOLERANCE, "the head's other two scales under the clip");
        Assert.near(ducked.get("right_leg").xScale(), 1, TOLERANCE, "the right leg's scale under the clip");
        Assert.near(ducked.get("left_leg").yScale(), 1, TOLERANCE, "the left leg's scale under the clip");
        Assert.near(ducked.get("right_leg").xRot(), own.get("right_leg").xRot() + LEG_TURN, TOLERANCE,
            "the right leg's pitch with leg0 turned by 45 degrees");
        Assert.near(ducked.get("left_leg").xRot(), own.get("left_leg").xRot(), TOLERANCE,
            "the left leg's pitch, which the clip leaves alone");
        Assert.near(ducked.get("hat").y(), own.get("hat").y() - HAT_LIFT, TOLERANCE,
            "the hat's height with helmet lifted by two pixels, Java's Y running downwards");
        Assert.that(AddonEntities.report().stream().anyMatch(line -> line.contains("bone held_item")),
            "held_item has no part in Java's villager and was never reported.");

        Map<String, PosedParts.Part> after = waitForPose(context, villager,
            parts -> parts.get("head").xScale() == 1f,
            "animation.tp.vanilla.duck runs once for two seconds and the head was still hidden after it.");
        Assert.near(after.get("right_leg").xRot(), own.get("right_leg").xRot(), TOLERANCE,
            "the right leg's pitch once the clip is over");
        Assert.near(after.get("hat").y(), own.get("hat").y(), TOLERANCE, "the hat's height once the clip is over");
    }

    private static void sway(ClientGameTestContext context, TestSingleplayerContext world, LivingEntity villager,
        Map<String, PosedParts.Part> own) {
        float rest = own.get("left_leg").zRot();
        world.getServer().runCommand("playanimation @e[type=minecraft:villager] animation.tp.vanilla.sway"
            + " none 2 \"q.anim_time > 1\"");
        waitForPose(context, villager, parts -> Math.abs(parts.get("left_leg").zRot() - rest - SWAY) < TOLERANCE,
            "animation.tp.vanilla.sway was played on a villager and its left leg never rolled by 30 degrees.");

        waitForPose(context, villager, parts -> {
            float roll = parts.get("left_leg").zRot() - rest;
            return roll > SWAY * 0.05f && roll < SWAY * 0.95f;
        }, "animation.tp.vanilla.sway was played with a blend out of two seconds and let go of the leg at once.");

        waitForPose(context, villager, parts -> Math.abs(parts.get("left_leg").zRot() - rest) < TOLERANCE,
            "animation.tp.vanilla.sway loops, and its stop expression never ended it.");
    }

    /** Waits a tick at a time for the villager's posed parts to satisfy the test, and returns them. */
    private static Map<String, PosedParts.Part> waitForPose(ClientGameTestContext context, LivingEntity villager,
        Predicate<Map<String, PosedParts.Part>> holds, String failure) {
        for (int tick = 0; tick < PATIENCE; tick++) {
            Map<String, PosedParts.Part> parts = context.computeOnClient(client -> PosedParts.of(villager));
            if (holds.test(parts)) return parts;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }
}
