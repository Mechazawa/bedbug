package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.camera.CameraPreset;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.bedrock.input.ControlScheme;
import at.ioexception.mcaddon.network.Channels;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The control scheme `/controlscheme` and a script chose for each player, and the one each player is
 * on.
 *
 * A player is on the chosen scheme while their camera allows it, and otherwise on the scheme their
 * camera's preset names, or the locked one where the preset names none or one the camera does not
 * allow. Only the client turns input into movement, so the scheme in force is sent to that player's
 * client whenever it changes.
 */
public final class PlayerControlSchemes {
    /** The kind the scheme is sent to a client under. */
    public static final String KIND = "control_scheme";

    private static final Map<UUID, ControlScheme> CHOSEN = new HashMap<>();
    private static final Map<UUID, ControlScheme> SENT = new HashMap<>();

    private PlayerControlSchemes() {
    }

    /** Puts the player on a scheme, and answers whether their camera allows it; one it does not is refused. */
    public static boolean set(ServerPlayer player, ControlScheme scheme) {
        if (!scheme.allowedOn(camera(player))) return false;

        CHOSEN.put(player.getUUID(), scheme);
        refresh(player);
        return true;
    }

    /** Gives the player back the scheme their camera starts on. */
    public static void clear(ServerPlayer player) {
        CHOSEN.remove(player.getUUID());
        refresh(player);
    }

    public static ControlScheme of(ServerPlayer player) {
        ControlScheme chosen = CHOSEN.get(player.getUUID());
        if (chosen != null && chosen.allowedOn(camera(player))) return chosen;

        CameraPreset preset = PlayerCameras.presetOf(player);
        ControlScheme named = preset == null ? null : preset.controlScheme();
        return named != null && named.allowedOn(preset.camera()) ? named : ControlScheme.DEFAULT;
    }

    /** Sends the player's client the scheme in force, when it is not the one the client already has. */
    public static void refresh(ServerPlayer player) {
        ControlScheme current = of(player);
        if (SENT.getOrDefault(player.getUUID(), ControlScheme.DEFAULT) == current) return;

        SENT.put(player.getUUID(), current);
        Channels.sendInstruction(player, KIND, current.bedrockName());
    }

    public static void forget(ServerPlayer player) {
        CHOSEN.remove(player.getUUID());
        SENT.remove(player.getUUID());
    }

    private static CameraView.Preset camera(ServerPlayer player) {
        CameraPreset preset = PlayerCameras.presetOf(player);
        return preset == null ? null : preset.camera();
    }
}
