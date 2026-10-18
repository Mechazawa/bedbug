package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/**
 * What the camera tests read off the client: where its camera stands and which way it looks, and
 * the armour stand a camera looks at, follows or hangs off.
 *
 * A placement is waited for rather than read once, because the server's order reaches the client a
 * few ticks after the command ran.
 */
final class Cameras {
    static final int PATIENCE = 200;
    private static final double TOLERANCE = 1e-2;
    private static final float DEGREES = 0.1f;

    private Cameras() {
    }

    /** Waits for the camera to stand at the place the function works out from the client, turned as asked. */
    static void placed(ClientGameTestContext context, Function<Minecraft, Vec3> place, float pitch, float yaw,
        String what) {
        Worlds.waitForClient(context, client -> at(client, place.apply(client)) && turned(client, pitch, yaw),
            PATIENCE, () -> {
                Minecraft client = Minecraft.getInstance();
                return "Expected the camera under " + what + " at " + place.apply(client) + " with a pitch of "
                    + pitch + " and a yaw of " + yaw + ", and it stands at " + position(client) + " with "
                    + Worlds.camera(client).xRot() + " and " + Worlds.camera(client).yRot() + ".";
            });
    }

    static void placed(ClientGameTestContext context, Vec3 place, float pitch, float yaw, String what) {
        placed(context, client -> place, pitch, yaw, what);
    }

    static boolean at(Minecraft client, Vec3 place) {
        return position(client).distanceTo(place) < TOLERANCE;
    }

    static boolean turned(Minecraft client, float pitch, float yaw) {
        return Math.abs(Worlds.camera(client).xRot() - pitch) < DEGREES
            && Math.abs(Mth.wrapDegrees(Worlds.camera(client).yRot() - yaw)) < DEGREES;
    }

    static Vec3 position(Minecraft client) {
        return Worlds.camera(client).position();
    }

    /** The middle of the player's box, which Bedrock's orbit cameras turn about. */
    static Vec3 middle(Minecraft client) {
        return client.player.getBoundingBox().getCenter();
    }

    /** The place a camera `radius` blocks back from `pivot` along a view turned to `pitch` and `yaw`. */
    static Vec3 behind(Vec3 pivot, float pitch, float yaw, double radius) {
        return pivot.subtract(Vec3.directionFromRotation(pitch, yaw).scale(radius));
    }

    /** One block to the camera's right at this turn. */
    static Vec3 right(float yaw) {
        return Vec3.directionFromRotation(0, yaw + 90);
    }

    /** One block up the camera's own screen at this turn. */
    static Vec3 up(float pitch, float yaw) {
        return Vec3.directionFromRotation(pitch - 90, yaw);
    }

    /**
     * Summons the armour stand the camera tests use, floating where its feet are put, and waits for
     * the client to have it.
     */
    static void stand(ClientGameTestContext context, TestSingleplayerContext world, Vec3 feet, float yaw) {
        world.getServer().runCommand("kill @e[type=minecraft:armor_stand]");
        world.getServer().runCommand("summon minecraft:armor_stand " + feet.x + " " + feet.y + " " + feet.z
            + " {NoGravity:1b,Rotation:[" + yaw + "f,0f]}");
        Worlds.waitForClient(context,
            client -> stand(client) != null && stand(client).position().distanceTo(feet) < 1e-3,
            PATIENCE, () -> "an armour stand was summoned at " + feet + " and the client never had it there.");
    }

    /** The client's copy of the one armour stand, or null while it has none. */
    static Entity stand(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand) return entity;
        }
        return null;
    }

    /** The yaw `/teleport ... facing` turns a player by to look along a direction. */
    static float yaw(Vec3 towards) {
        return (float) (Math.toDegrees(Math.atan2(towards.z, towards.x)) - 90);
    }

    static float pitch(Vec3 towards) {
        return (float) -Math.toDegrees(Math.atan2(towards.y, Math.sqrt(towards.x * towards.x + towards.z * towards.z)));
    }
}
