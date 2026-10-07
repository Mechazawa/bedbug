package at.ioexception.mcaddon.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * The parts of the Java model drawing an entity, by Java's own names, posed for one draw.
 *
 * Built the way the renderer builds a draw: the state extracted from the entity, the model posed
 * from the state, then whatever clip is playing on the entity laid over it. The parts are read and
 * the model put back, so a test sees what one frame would draw.
 */
public final class PosedParts {
    /** Where one part stands, its rotation in radians. */
    public record Part(float x, float y, float z, float xRot, float yRot, float zRot,
        float xScale, float yScale, float zScale) {
        static Part of(ModelPart part) {
            return new Part(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale);
        }
    }

    private PosedParts() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Map<String, Part> of(LivingEntity entity) {
        LivingEntityRenderer renderer = (LivingEntityRenderer) Minecraft.getInstance()
            .getEntityRenderDispatcher().getRenderer(entity);
        LivingEntityRenderState state = (LivingEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0f);

        EntityModel model = renderer.getModel();
        model.setupAnim(state);
        JavaModelPoses.pose(RenderStates.of(state), model.root());

        Map<String, Part> parts = new HashMap<>();
        collect(model.root(), parts);
        JavaModelPoses.restore(model.root());
        return parts;
    }

    private static void collect(ModelPart part, Map<String, Part> parts) {
        ((PartTree) (Object) part).mcaddon$children().forEach((name, child) -> {
            parts.put(name, Part.of(child));
            collect(child, parts);
        });
    }
}
