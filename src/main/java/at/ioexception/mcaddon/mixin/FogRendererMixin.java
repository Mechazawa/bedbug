package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.client.render.PlayerFog;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

//? if >=26 {
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Paints the fog a pack's `/fog` pushed onto this player's stack.
 *
 * Only the air fog is overwritten. Java draws water, lava and powder snow its own way, which is what
 * the other blocks of a Bedrock fog describe, so a pushed fog replaces the one the player is looking
 * through in the open and leaves the rest alone.
 *
 * 26 gathers a frame's fog into one object and hands it back, so the colour and both distances are
 * written on the way out of the one call. Before that the fog lived in three static setters on the
 * render system, reached from two calls, which is why the injectors have two forms.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    //? if >=26 {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void mcaddon$paintAddonFog(Camera camera, int renderDistance, DeltaTracker delta,
        float skyDarken, ClientLevel level, CallbackInfoReturnable<FogData> info) {
        FogDefinition fog = PlayerFog.active();
        if (fog == null) return;

        float distance = PlayerFog.renderDistanceBlocks();
        FogData data = info.getReturnValue();
        data.environmentalStart = fog.startAt(distance);
        data.environmentalEnd = fog.endAt(distance);
        data.renderDistanceStart = data.environmentalStart;
        data.renderDistanceEnd = data.environmentalEnd;
        data.color.set(fog.red(), fog.green(), fog.blue(), 1f);
    }
    //?} else {
    /*@Inject(method = "setupFog", at = @At("RETURN"))
    private static void mcaddon$paintAddonFog(Camera camera, FogRenderer.FogMode mode,
        float renderDistance, boolean thick, float partialTick, CallbackInfo info) {
        FogDefinition fog = PlayerFog.active();
        if (fog == null) return;

        float distance = PlayerFog.renderDistanceBlocks();
        RenderSystem.setShaderFogStart(fog.startAt(distance));
        RenderSystem.setShaderFogEnd(fog.endAt(distance));
    }

    @Inject(method = "levelFogColor", at = @At("RETURN"))
    private static void mcaddon$paintAddonFogColour(CallbackInfo info) {
        FogDefinition fog = PlayerFog.active();
        if (fog == null) return;

        RenderSystem.setShaderFogColor(fog.red(), fog.green(), fog.blue());
    }
    *///?}
}
