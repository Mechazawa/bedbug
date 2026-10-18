package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.camera.AttachPoint;
import at.ioexception.mcaddon.bedrock.camera.CameraAttachment;
import at.ioexception.mcaddon.bedrock.camera.CameraInstruction;
import at.ioexception.mcaddon.bedrock.camera.CameraPreset;
import at.ioexception.mcaddon.bedrock.camera.CameraRequest;
import at.ioexception.mcaddon.bedrock.camera.CameraSpline;
import at.ioexception.mcaddon.bedrock.camera.CameraTarget;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.registry.AddonCameras;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The camera `/camera` and a script have set for each player, and every order about it.
 *
 * Only that player's client draws the camera, so each order is sent to that player alone. The preset
 * is kept for as long as the player is connected, because a target and a spline are only taken by a
 * free camera; Bedrock forgets a player's camera when they leave, and so does this.
 *
 * Each order answers why it was refused, in words a player reads, or null once it is on its way.
 */
public final class PlayerCameras {
    private static final Map<UUID, CameraPreset> SET = new HashMap<>();

    private PlayerCameras() {
    }

    public static String set(ServerPlayer player, String written, CameraRequest request) {
        CameraPreset preset = AddonCameras.presetOf(written);
        if (preset == null) return "There is no camera preset named " + written + ".";

        String refused = preset.refusal(request);
        if (refused != null) return refused;

        SET.put(player.getUUID(), preset);
        send(player, preset.view(request));
        PlayerAimAssists.send(player);
        return null;
    }

    /** Ends every camera order the player is under: the preset, its ease, a target, a spline and an attachment. */
    public static void clear(ServerPlayer player) {
        SET.remove(player.getUUID());
        send(player, CameraView.CLEARED);
        PlayerAimAssists.send(player);
    }

    /** Null while the player is on no preset. */
    public static CameraPreset presetOf(ServerPlayer player) {
        return SET.get(player.getUUID());
    }

    /** Turns the player's free camera after an entity, looking `offset` away from the middle of its box. */
    public static String target(ServerPlayer player, Entity target, Vec3 offset) {
        String refused = freeOnly(player, "target_entity");
        if (refused != null) return refused;

        send(player, new CameraTarget(target.getId(), offset));
        return null;
    }

    public static void removeTarget(ServerPlayer player) {
        send(player, CameraTarget.REMOVED);
    }

    /** Hangs the player's camera off an entity, at a named place on it or, with none, where the camera wants it. */
    public static String attach(ServerPlayer player, Entity entity, AttachPoint locator) {
        if (entity instanceof Player) {
            return "attach_to_entity hangs the camera off an entity that is not a player, and "
                + entity.getName().getString() + " is one.";
        }
        send(player, new CameraAttachment(entity.getId(), locator));
        return null;
    }

    public static void detach(ServerPlayer player) {
        send(player, CameraAttachment.DETACHED);
    }

    public static String play(ServerPlayer player, CameraSpline spline) {
        String refused = freeOnly(player, "A camera spline");
        if (refused == null) refused = spline.refusal();
        if (refused != null) return refused;

        send(player, spline);
        return null;
    }

    public static void forget(ServerPlayer player) {
        SET.remove(player.getUUID());
    }

    /** Why an order only a free camera takes is refused for this player, or null when they are on one. */
    private static String freeOnly(ServerPlayer player, String order) {
        CameraPreset preset = SET.get(player.getUUID());
        if (preset != null && preset.camera() == CameraView.Preset.FREE) return null;

        return order + " moves minecraft:free and the presets built on it, and "
            + player.getName().getString() + (preset == null ? " has no camera set." : " is on " + preset.identifier()
            + ".");
    }

    private static void send(ServerPlayer player, CameraInstruction instruction) {
        Channels.sendCameraView(player, instruction.encode());
    }
}
