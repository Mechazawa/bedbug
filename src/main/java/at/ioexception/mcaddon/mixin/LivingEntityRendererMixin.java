package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.AttachableLayer;
import at.ioexception.mcaddon.client.render.RenderStates;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every living entity a layer that draws what an add-on says it is wearing, and records which
 * entity a render state came from so that layer can find it.
 *
 * Done here rather than through Fabric's feature renderer callback, which 26 removed.
 *
 * `extractRenderState` only exists from 1.21.2, and Mixin checks the exact parameter types, so that
 * injector is written out only for the versions that have it. On the older ones a layer is handed the
 * entity itself and nothing needs recording.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow
    protected abstract boolean addLayer(RenderLayer layer);

    @Inject(method = "<init>", at = @At("RETURN"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mcaddon$addAttachableLayer(CallbackInfo info) {
        addLayer(new AttachableLayer((net.minecraft.client.renderer.entity.RenderLayerParent) this));
    }

    //? if >=1.21.2 {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void mcaddon$rememberEntity(LivingEntity entity,
        net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, float partialTick,
        CallbackInfo info) {
        RenderStates.remember(state, entity);
    }
    //?}
}
