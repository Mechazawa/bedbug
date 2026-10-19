package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.client.PlayerControl;
import at.ioexception.mcaddon.client.PlayerInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets go of the keys whose input the server has taken away, once the keyboard has been read for a
 * tick and before the player moves by it, and hands the movement to the player's control scheme.
 *
 * The keys are let go of rather than the movement stopped, so what Java works out from them, a jump,
 * a crouch, flying up or down, follows the keys as it would for a player who never pressed them.
 * Before 26 the keys are fields of the input itself and the movement it asks for is two impulses;
 * from 26 they are one record and the movement a vector.
 */
@Mixin(KeyboardInput.class)
//? if >=26 {
public abstract class KeyboardInputMixin extends net.minecraft.client.player.ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcaddon$letGoOfLockedKeys(CallbackInfo info) {
        net.minecraft.world.entity.player.Input pressed = keyPresses;
        keyPresses = new net.minecraft.world.entity.player.Input(
            pressed.forward() && PlayerInput.allows(InputPermission.MOVE_FORWARD),
            pressed.backward() && PlayerInput.allows(InputPermission.MOVE_BACKWARD),
            pressed.left() && PlayerInput.allows(InputPermission.MOVE_LEFT),
            pressed.right() && PlayerInput.allows(InputPermission.MOVE_RIGHT),
            pressed.jump() && PlayerInput.allows(InputPermission.JUMP),
            pressed.shift() && PlayerInput.allows(InputPermission.SNEAK),
            pressed.sprint());
        moveVector = PlayerControl.steer(new net.minecraft.world.phys.Vec2(
            mcaddon$impulse(keyPresses.left(), keyPresses.right()),
            mcaddon$impulse(keyPresses.forward(), keyPresses.backward())).normalized());
    }
//?} else {
/*public abstract class KeyboardInputMixin extends net.minecraft.client.player.Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcaddon$letGoOfLockedKeys(boolean slow, float slowFactor, CallbackInfo info) {
        up &= PlayerInput.allows(InputPermission.MOVE_FORWARD);
        down &= PlayerInput.allows(InputPermission.MOVE_BACKWARD);
        left &= PlayerInput.allows(InputPermission.MOVE_LEFT);
        right &= PlayerInput.allows(InputPermission.MOVE_RIGHT);
        jumping &= PlayerInput.allows(InputPermission.JUMP);
        shiftKeyDown &= PlayerInput.allows(InputPermission.SNEAK);
        float scale = slow ? slowFactor : 1f;
        net.minecraft.world.phys.Vec2 steered = PlayerControl.steer(new net.minecraft.world.phys.Vec2(
            mcaddon$impulse(left, right) * scale, mcaddon$impulse(up, down) * scale));
        leftImpulse = steered.x;
        forwardImpulse = steered.y;
    }
*///?}

    /** One axis of movement, which two keys pressed against each other cancel. */
    @Unique
    private static float mcaddon$impulse(boolean towards, boolean away) {
        return towards == away ? 0f : towards ? 1f : -1f;
    }
}
