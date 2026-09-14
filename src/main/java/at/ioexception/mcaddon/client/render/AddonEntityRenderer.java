package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}

/**
 * Draws an add-on's entity from its Bedrock model.
 *
 * The geometry is baked into Java's model space, which is the space vanilla sets up for a mob: the
 * body rotation applied, the axes flipped, and the origin a block and a half above the feet. Those
 * three steps are reproduced here so a baked model lands where the entity is.
 *
 * Rendering was rewritten in 1.21.2 around a render state extracted before drawing, so the class has
 * two whole forms rather than a branch inside one.
 */
//? if >=1.21.2 {
public class AddonEntityRenderer extends EntityRenderer<AddonEntity, AddonEntityRenderer.State> {
//?} else {
/*public class AddonEntityRenderer extends EntityRenderer<AddonEntity> {
*///?}

    /** How far the model origin sits above the feet, which is what a scale turns about. */
    private static final float MODEL_ORIGIN_BLOCKS = 1.501f;

    public AddonEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    static String identifierOf(AddonEntity entity) {
        return entity.definition() == null ? "" : entity.definition().identifier();
    }

    /**
     * Whether the entity's model is drawn at all.
     *
     * An invisible entity is drawn by nothing in either edition, which is what a potion of
     * invisibility and `minecraft:is_hidden_when_invisible` both ask for.
     * `minecraft:renders_when_invisible` is a pack asking for the opposite, for something whose
     * shape is the point of it.
     */
    private static boolean visible(AddonEntity entity) {
        return !entity.isInvisible() || (entity.definition() != null
            && entity.declares("minecraft:renders_when_invisible"));
    }

    /** Everything drawn on one entity this frame: its model with what it wears, and what it holds. */
    private record Drawing(List<Attachables.Drawn> passes, List<HeldItems.Held> held) {
        static final Drawing NOTHING = new Drawing(List.of(), List.of());
    }

    /**
     * Everything drawn on the entity: its own model, then whatever it is wearing or holding.
     *
     * What it is wearing is collected first, because an attachable writes into the entity's own
     * variables and its model has to be resolved after that rather than before. A slot an attachable
     * drew holds no item of its own afterwards, which is how Bedrock chooses between the two.
     */
    private static Drawing drawn(AddonEntity entity, AddonClientAssets.Binding binding,
        EntityAnimator animator, float partialTick) {
        List<Attachables.Drawn> worn = Attachables.on(entity, animator, partialTick);

        List<Attachables.Drawn> passes = new ArrayList<>();
        for (AddonModels.Pass pass : binding.models().resolve(animator)) {
            passes.add(new Attachables.Drawn(pass, animator, null));
        }
        BakedGeometry own = passes.isEmpty() ? null : passes.get(0).pass().geometry();
        worn.stream().map(each -> each.hungFrom(own, animator)).forEach(passes::add);

        return new Drawing(passes, HeldItems.on(entity, animator, own,
            worn.stream().map(Attachables.Drawn::slot).collect(Collectors.toSet())));
    }


    /**
     * Whether the entity is near enough and in view to be worth drawing.
     *
     * Java asks the frustum about the box the server gave the entity, and a Bedrock model may reach
     * well past that box, so a geometry that declares its own extent is asked about that as well.
     * Without it a model wider or taller than its hitbox disappears while part of it is still on the
     * screen. The distance test stays Java's, which is what bounds how far any mob is drawn.
     */
    @Override
    public boolean shouldRender(AddonEntity entity, Frustum frustum, double cameraX, double cameraY,
        double cameraZ) {
        if (super.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)) return true;

        AddonClientAssets.Binding binding = AddonClientAssets.of(identifierOf(entity));
        AABB declared = binding == null ? null : binding.models().visibleBounds(binding.definition());
        return declared != null
            && entity.shouldRender(cameraX, cameraY, cameraZ)
            && frustum.isVisible(declared.move(entity.getX(), entity.getY(), entity.getZ()));
    }

    /**
     * The render type a pass draws with, taken from the material its pack named.
     *
     * Without a material the pack meant Bedrock's default for an entity, which is an alpha test with
     * back faces kept.
     */
    private static RenderType renderTypeOf(AddonModels.Pass pass) {
        MaterialDefinition material = pass.material();
        return Platform.renderType(pass.texture(),
            material != null && material.blends(),
            material != null && material.culls(),
            material != null && material.emissive());
    }

    //? if >=1.21.2 {
    /** What drawing needs, pulled off the entity while the entity is still safe to touch. */
    public static class State extends EntityRenderState {
        EntityAnimator animator;
        Drawing drawing = Drawing.NOTHING;
        List<ItemStackRenderState> heldModels = new ArrayList<>();
        float bodyRotation;
        Vector3f scale = new Vector3f(1, 1, 1);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AddonEntity entity, State state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        String identifier = identifierOf(entity);
        AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
        state.animator = binding == null
            ? null
            : Animators.of(entity, identifier, partialTick, entity.tickCount + partialTick);
        state.drawing = Drawing.NOTHING;
        state.heldModels = new ArrayList<>();
        if (state.animator == null || !visible(entity)) return;

        state.drawing = drawn(entity, binding, state.animator, partialTick);
        state.heldModels = state.drawing.held().stream().map(held -> itemModel(entity, held)).toList();
        state.bodyRotation = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        state.scale = state.animator.scale();
    }

    /** The item's own model, resolved while the entity it belongs to is still in hand. */
    private static ItemStackRenderState itemModel(AddonEntity entity, HeldItems.Held held) {
        ItemStackRenderState item = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(item, held.stack(),
            held.offHand() ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, entity);
        return item;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (state.drawing.passes().isEmpty()) return;

        poseStack.pushPose();
        applyModelSpace(poseStack, state.bodyRotation, state.scale);

        int light = state.lightCoords;
        for (Attachables.Drawn each : state.drawing.passes()) {
            AddonModels.Pass pass = each.pass();
            EntityAnimator animator = each.animator();
            poseStack.pushPose();
            if (each.anchor() != null) Platform.mulPose(poseStack, each.anchor());
            collector.submitCustomGeometry(poseStack, renderTypeOf(pass),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), animator, pass.visibility()::hides, pass.shift(),
                        local, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
                });
            poseStack.popPose();
        }
        for (int index = 0; index < state.heldModels.size(); index++) {
            HeldItems.Held held = state.drawing.held().get(index);
            intoHand(poseStack, held);
            state.heldModels.get(index).submit(poseStack, collector, held.lit() ? FULL_BRIGHT : light,
                OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }
    //?} else {
    /*@Override
    public Identifier getTextureLocation(AddonEntity entity) {
        return Platform.id("minecraft", "textures/misc/white.png");
    }

    @Override
    public void render(AddonEntity entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource buffers, int light) {
        super.render(entity, yaw, partialTick, poseStack, buffers, light);

        String identifier = identifierOf(entity);
        AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
        if (binding == null || !visible(entity)) return;

        EntityAnimator animator = Animators.of(entity, identifier, partialTick, entity.tickCount + partialTick);
        if (animator == null) return;

        Drawing drawing = drawn(entity, binding, animator, partialTick);
        poseStack.pushPose();
        applyModelSpace(poseStack, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), animator.scale());
        for (Attachables.Drawn each : drawing.passes()) {
            AddonModels.Pass pass = each.pass();
            VertexConsumer buffer = buffers.getBuffer(renderTypeOf(pass));
            poseStack.pushPose();
            if (each.anchor() != null) Platform.mulPose(poseStack, each.anchor());
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass.visibility()::hides, pass.shift(),
                poseStack, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
            poseStack.popPose();
        }
        for (HeldItems.Held held : drawing.held()) {
            intoHand(poseStack, held);
            Minecraft.getInstance().getItemRenderer().renderStatic(entity, held.stack(),
                held.offHand() ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                held.offHand(), poseStack, buffers, entity.level(),
                held.lit() ? FULL_BRIGHT : light, OverlayTexture.NO_OVERLAY, entity.getId());
            poseStack.popPose();
        }
        poseStack.popPose();
    }
    *///?}

    /**
     * Puts the pose on the bone the item hangs off, turned the way Java holds a third person item.
     *
     * Bedrock's item bone rests unrotated in the same space a vanilla arm does, so the two rotations
     * vanilla's own hand layer applies after reaching the hand apply here as well. The caller pops.
     */
    private static void intoHand(PoseStack poseStack, HeldItems.Held held) {
        poseStack.pushPose();
        Platform.mulPose(poseStack, held.anchor());
        poseStack.mulPose(Axis.XP.rotationDegrees(-90f));
        poseStack.mulPose(Axis.YP.rotationDegrees(180f));
        poseStack.scale(held.scale(), held.scale(), held.scale());
    }

    /** Both light channels at their maximum, which is what `held_item_ignores_lighting` asks for. */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * The transform vanilla puts a mob's model into, reproduced so a baked Bedrock model lands in it.
     *
     * A scale turns about the model origin rather than the feet, so it has to be compensated, or the
     * entity floats above or sinks into the hitbox it can still be hit in.
     */
    static void applyModelSpace(PoseStack poseStack, float bodyRotation, Vector3f scale) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - bodyRotation));
        poseStack.scale(-1f, -1f, 1f);
        poseStack.scale(scale.x, scale.y, scale.z);
        poseStack.translate(0, -MODEL_ORIGIN_BLOCKS / scale.y, 0);
    }
}
