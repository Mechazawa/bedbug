package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;


/**
 * `/camera` and `/camerashake` move the camera a frame is drawn from.
 *
 * The first `/camera` is a line of a Java data pack's function this test mod ships, which reaches the
 * Bedrock command the way an operator's line does: through `/bedrock`.
 *
 * A free camera set at a position and a rotation is drawn from exactly there, an ease moves it there
 * over the seconds asked for rather than at once, a shake moves it off that place until it is
 * stopped, and clearing gives back the player's own first person view.
 *
 * The three gameplay presets switch the client to the camera Java already has for each, `facing`
 * turns the camera towards a point or the middle of an entity's box from wherever the camera stands,
 * and `default` sets a preset with nothing over it. What Bedrock itself refuses is refused here in
 * its words: an ease to a camera that follows the player, which leaves the view as it was.
 */
public final class CameraClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 1e-3;
    private static final int PATIENCE = 200;

    private static final Vec3 PLACED = new Vec3(0.5, 100, 0.5);
    private static final Vec3 EASED = new Vec3(0.5, 110, 0.5);

    /** Where the armour stand a camera faces stands, out of the way of the player and the camera. */
    private static final Vec3 STAND = new Vec3(0.5, 95, 10.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            CameraType chosen = context.computeOnClient(client -> client.options.getCameraType());

            world.getServer().runCommand("function mcaddon_gametest:free_camera");
            Worlds.waitForClient(context, client -> at(client, PLACED), PATIENCE,
                () -> "a Java function's /bedrock camera set the camera free at " + PLACED + " and it is at "
                    + position(Minecraft.getInstance()) + ".");
            Assert.near(context.computeOnClient(client -> Worlds.camera(client).xRot()), 30, TOLERANCE,
                "the camera's pitch under /camera ... rot 30 45");
            Assert.near(context.computeOnClient(client -> Worlds.camera(client).yRot()), 45, TOLERANCE,
                "the camera's yaw under /camera ... rot 30 45");

            world.getServer().runCommand(
                "bedrock camera @a set minecraft:free ease 2 linear pos 0.5 110 0.5 rot 30 45");
            Worlds.waitForClient(context, client -> {
                double height = position(client).y;
                return height > PLACED.y + 1 && height < EASED.y - 1;
            }, PATIENCE, () -> "the camera eases over two seconds from " + PLACED + " to " + EASED
                + " and was never seen between the two.");
            Worlds.waitForClient(context, client -> at(client, EASED), PATIENCE,
                () -> "the camera's ease never reached " + EASED + "; it is at "
                    + position(Minecraft.getInstance()) + ".");

            world.getServer().runCommand("bedrock camerashake add @a 1 10 positional");
            Worlds.waitForClient(context, client -> position(client).distanceTo(EASED) > 0.01, PATIENCE,
                () -> "a shake was added and the camera never moved off " + EASED + ".");
            world.getServer().runCommand("bedrock camerashake stop @a");
            Worlds.waitForClient(context, client -> at(client, EASED), PATIENCE,
                () -> "the shake was stopped and the camera never settled back at " + EASED + ".");

            world.getServer().runCommand("bedrock camera @a clear");
            Worlds.waitForClient(context, client -> position(client).distanceTo(client.player.getEyePosition()) < 0.01
                    && client.options.getCameraType() == chosen, PATIENCE,
                () -> "the camera was cleared and was not given back to the player's own " + chosen + " view.");

            presets(context, world, chosen);
            facing(context, world);
            refused(context, world);
            world.getServer().runCommand("bedrock camera @a clear");
        }
    }

    /** Each gameplay preset is the Java camera of the same name, and `default` is the preset alone. */
    private static void presets(ClientGameTestContext context, TestSingleplayerContext world, CameraType chosen) {
        preset(context, world, "minecraft:third_person", CameraType.THIRD_PERSON_BACK, true);
        preset(context, world, "minecraft:third_person_front", CameraType.THIRD_PERSON_FRONT, true);
        preset(context, world, "minecraft:first_person", CameraType.FIRST_PERSON, false);
        Worlds.waitForClient(context, client -> position(client).distanceTo(client.player.getEyePosition()) < 0.01,
            PATIENCE, () -> "minecraft:first_person was set and the camera stands at "
                + position(Minecraft.getInstance()) + ", away from the player's eyes.");

        preset(context, world, "minecraft:free default", CameraType.THIRD_PERSON_BACK, true);

        world.getServer().runCommand("bedrock camera @a clear");
        Worlds.waitForClient(context, client -> client.options.getCameraType() == chosen, PATIENCE,
            () -> "the camera was cleared after a preset and the player's own " + chosen + " view was not given back.");
    }

    private static void preset(ClientGameTestContext context, TestSingleplayerContext world, String preset,
        CameraType wanted, boolean detached) {
        world.getServer().runCommand("bedrock camera @a set " + preset);
        Worlds.waitForClient(context, client -> client.options.getCameraType() == wanted
                && Worlds.camera(client).isDetached() == detached, PATIENCE,
            () -> "/camera @a set " + preset + " asks for Java's " + wanted + " camera, " + (detached ? "" : "not ")
                + "away from the player, and the client has " + Minecraft.getInstance().options.getCameraType()
                + (Worlds.camera(Minecraft.getInstance()).isDetached() ? ", away from the player." : ", at the eyes."));
    }

    /**
     * `facing` with a position given looks from that position; without one it looks from wherever the
     * preset put the camera. An entity is faced at the middle of its box.
     */
    private static void facing(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand(
            "bedrock camera @a set minecraft:free pos 0.5 100 0.5 facing 10.5 100 0.5");
        rotated(context, -90, 0, "/camera ... pos 0.5 100 0.5 facing 10.5 100 0.5, a point due east");

        world.getServer().runCommand("summon minecraft:armor_stand " + STAND.x + " " + STAND.y + " " + STAND.z
            + " {NoGravity:1b,Invisible:1b}");
        Worlds.waitForServer(context, world.getServer(), server -> standMiddle(server) != null, PATIENCE,
            "an armour stand was summoned to be faced and never stood in the world.");
        Vec3 middle = world.getServer().computeOnServer(CameraClientGameTest::standMiddle);

        world.getServer().runCommand("bedrock camera @a set minecraft:free ease 1 linear pos 0.5 100 0.5"
            + " facing @e[type=minecraft:armor_stand,c=1]");
        Vec3 towards = middle.subtract(PLACED);
        rotated(context, yaw(towards), pitch(towards),
            "/camera ... ease 1 linear pos 0.5 100 0.5 facing an armour stand whose box is centred on " + middle);

        world.getServer().runCommand("bedrock camera @a set minecraft:third_person facing " + middle.x + " "
            + middle.y + " " + middle.z);
        Worlds.waitForClient(context, client -> {
            Vec3 from = middle.subtract(position(client));
            return client.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && turnedTo(client, yaw(from), pitch(from));
        }, PATIENCE, () -> {
            Minecraft client = Minecraft.getInstance();
            Vec3 from = middle.subtract(position(client));
            return "minecraft:third_person facing " + middle + " looks at it from where the third person camera"
                + " stands, " + position(client) + ", which is a yaw of " + yaw(from) + " and a pitch of "
                + pitch(from) + ", and the camera has " + Worlds.camera(client).yRot() + " and "
                + Worlds.camera(client).xRot() + ".";
        });
        world.getServer().runCommand("kill @e[type=minecraft:armor_stand]");
    }

    /** The middle of the box of the one armour stand in the overworld, or null while there is none. */
    private static Vec3 standMiddle(MinecraftServer server) {
        for (Entity entity : server.overworld().getAllEntities()) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("minecraft:armor_stand")) {
                return entity.getBoundingBox().getCenter();
            }
        }
        return null;
    }

    private static void rotated(ClientGameTestContext context, float yaw, float pitch, String what) {
        Worlds.waitForClient(context, client -> at(client, PLACED) && turnedTo(client, yaw, pitch), PATIENCE,
            () -> "Expected the camera under " + what + " at " + PLACED + " with a yaw of " + yaw + " and a pitch of "
                + pitch + ", and it stands at " + position(Minecraft.getInstance()) + " with "
                + Worlds.camera(Minecraft.getInstance()).yRot() + " and "
                + Worlds.camera(Minecraft.getInstance()).xRot() + ".");
    }

    private static boolean turnedTo(Minecraft client, float yaw, float pitch) {
        return Math.abs(Mth.wrapDegrees(Worlds.camera(client).yRot() - yaw)) < 0.1
            && Math.abs(Worlds.camera(client).xRot() - pitch) < 0.1;
    }

    /** The yaw `/teleport ... facing` turns a player by to look along a direction. */
    private static float yaw(Vec3 towards) {
        return (float) (Math.toDegrees(Math.atan2(towards.z, towards.x)) - 90);
    }

    private static float pitch(Vec3 towards) {
        return (float) -Math.toDegrees(Math.atan2(towards.y, Math.sqrt(towards.x * towards.x + towards.z * towards.z)));
    }

    /**
     * An ease to a camera that follows the player is refused in Bedrock's words and does not change
     * the view the player has.
     */
    private static void refused(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 100 0.5 rot 0 0");
        Worlds.waitForClient(context, client -> at(client, PLACED), PATIENCE,
            () -> "the camera was set free at " + PLACED + " and is at " + position(Minecraft.getInstance()) + ".");

        String eased = Worlds.said(world.getServer(), "bedrock camera @a set minecraft:third_person ease 1 linear");
        Assert.that(eased.contains("Easing to non-stationary cameras is not supported"), "an ease to"
            + " minecraft:third_person is refused the way Bedrock refuses it, and the command said: " + eased);

        context.waitTicks(5);
        Assert.that(context.computeOnClient(client -> at(client, PLACED)
                && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK),
            () -> "a refused /camera command moved the free camera at " + PLACED + " to "
                + position(Minecraft.getInstance()) + " under "
                + Minecraft.getInstance().options.getCameraType() + ".");
    }

    private static boolean at(Minecraft client, Vec3 place) {
        return position(client).distanceTo(place) < TOLERANCE;
    }

    private static Vec3 position(Minecraft client) {
        return Worlds.camera(client).position();
    }
}
