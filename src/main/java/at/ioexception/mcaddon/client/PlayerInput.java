package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.bedrock.input.InputPermission;

import java.util.Set;

/**
 * The kinds of input the server has taken away from this client's player, which `KeyboardInputMixin`
 * and `MouseHandlerMixin` drop before the player moves or turns.
 */
public final class PlayerInput {
    private static Set<InputPermission> disabled = Set.of();

    private PlayerInput() {
    }

    public static void accept(int bits) {
        disabled = InputPermission.fromBits(bits);
    }

    public static void clear() {
        disabled = Set.of();
    }

    public static boolean allows(InputPermission permission) {
        return permission.allowedWith(disabled);
    }
}
