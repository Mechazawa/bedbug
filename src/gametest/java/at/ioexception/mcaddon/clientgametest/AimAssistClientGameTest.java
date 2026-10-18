package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.PlayerAimAssist;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * `/aimassist` and the test pack's aim assist preset pick what a third person player's hand acts on.
 *
 * The player stands on one block in the air facing south with nothing ahead of them, so no block is
 * in the cone. A pig floats 20 degrees to one side of where they face, inside their reach, a cow 40
 * degrees to the other side, and a sheep straight ahead and out of reach. `tp:aim` uses `tp:cow_first`
 * for an empty hand, which takes the cow as ten times nearer the cone's middle, and leaves sheep out;
 * the camera preset `tp:aimed` states that preset as its own aim assist.
 */
public final class AimAssistClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            int floor = Stage.FLOOR;
            world.getServer().runCommand("fill -8 " + floor + " -8 8 " + floor + " 8 air");
            world.getServer().runCommand("setblock 0 " + floor + " 0 stone");
            world.getServer().runCommand("summon pig 1.355 " + (floor + 2.17) + " 2.85 0 0");
            world.getServer().runCommand("data merge entity @e[type=pig,limit=1] {NoAI:1b,NoGravity:1b}");
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            Worlds.waitForClient(context, client -> java.util.stream.StreamSupport.stream(
                    client.level.entitiesForRendering().spliterator(), false)
                    .anyMatch(entity -> named(entity, "pig") && entity.isNoGravity()),
                PATIENCE, () -> "Expected the floating pig to reach the client.");
            context.waitTicks(2);
            Assert.that(context.computeOnClient(client -> client.crosshairPickEntity == null),
                "Expected nothing under the crosshair before aim assist, with the pig 20 degrees to one side.");

            world.getServer().runCommand("bedrock aimassist @a set");
            picks(context, "pig", "/aimassist @a set, which takes the default preset");
            Assert.that(context.computeOnClient(client -> client.crosshairPickEntity != null
                    && named(client.crosshairPickEntity, "pig")
                    && client.hitResult instanceof EntityHitResult),
                "Expected the pig, within reach, to be what the player's hand acts on under aim assist.");

            world.getServer().runCommand("summon cow -1.107 " + (floor + 1.92) + " 2.415 0 0");
            world.getServer().runCommand("summon sheep 0.5 " + (floor + 2.0) + " 6.5 0 0");
            world.getServer().runCommand("data merge entity @e[type=cow,limit=1] {NoAI:1b,NoGravity:1b}");
            world.getServer().runCommand("data merge entity @e[type=sheep,limit=1] {NoAI:1b,NoGravity:1b}");
            picks(context, "sheep", "the default preset, with a sheep straight ahead");

            world.getServer().runCommand("bedrock aimassist @a set 50 50 8 angle tp:aim");
            picks(context, "cow", "tp:aim, which leaves sheep out and favours a cow tenfold");

            world.getServer().runCommand("bedrock aimassist @a clear");
            Worlds.waitForClient(context,
                client -> PlayerAimAssist.target() == null && client.crosshairPickEntity == null, PATIENCE, () -> "Expected /aimassist @a clear to give the player Java's own pick back, and the hand"
                    + " acts on " + describe(Minecraft.getInstance().hitResult) + ".");

            world.getServer().runCommand("bedrock aimassist @a set");
            picks(context, "sheep", "/aimassist @a set again");
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            Worlds.waitForClient(context,
                client -> PlayerAimAssist.target() == null && client.crosshairPickEntity == null, PATIENCE, () -> "Expected no aim assist in first person, as Bedrock has none there, and the hand"
                    + " acts on " + describe(Minecraft.getInstance().hitResult) + ".");

            world.getServer().runCommand("bedrock camera @a set tp:aimed");
            picks(context, "cow", "the camera preset tp:aimed, whose aim assist names tp:aim over the"
                + " command's default preset");
            world.getServer().runCommand("bedrock camera @a clear");
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            picks(context, "sheep", "the command's default preset once the camera preset is cleared");
        }
    }

    private static void picks(ClientGameTestContext context, String wanted, String under) {
        Worlds.waitForClient(context, client -> PlayerAimAssist.target() instanceof EntityHitResult hit
                && named(hit.getEntity(), wanted), PATIENCE,
            () -> "Expected aim assist under " + under + " to settle on a " + wanted + ", and it settled on "
                + describe(PlayerAimAssist.target()) + ".");
    }

    /** Asked by name, because 26.2 moved the entity type constants to a class of their own. */
    private static boolean named(Entity entity, String type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals(type);
    }

    private static String describe(HitResult hit) {
        if (hit instanceof EntityHitResult entity) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString();
        }
        return hit == null ? "nothing" : hit.getType() + " at " + hit.getLocation();
    }
}
