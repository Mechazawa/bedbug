package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PartTree;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/** Lets a Bedrock clip find a Java model's parts by name. */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements PartTree {
    @Shadow
    @Final
    private Map<String, ModelPart> children;

    @Override
    public Map<String, ModelPart> mcaddon$children() {
        return children;
    }

    //? if <1.21.2 {
    /*@org.spongepowered.asm.mixin.Unique
    private ModelPart mcaddon$parent;

    @Override
    public ModelPart mcaddon$parent() {
        return mcaddon$parent;
    }

    // A part is built after its children, so the children are told who holds them as it is.
    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>",
        at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void mcaddon$adoptChildren(
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        for (ModelPart child : children.values()) {
            ((ModelPartMixin) (Object) child).mcaddon$parent = (ModelPart) (Object) this;
        }
    }
    *///?}
}
