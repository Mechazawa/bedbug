package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.entity.AddonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a renderer handed the frame, kept instead of drawn.
 *
 * The collector answers every call the game's own one does and keeps two kinds: custom geometry,
 * which is how a Bedrock model is submitted, with the render type it asked for and every vertex it
 * wrote; and items, with the pose they were hung at, their light and their tints.
 *
 * A Java model is drawn only once the frame's models are drawn, posed again by its own `setupAnim`
 * there, so what a watched entity's models put into the buffer is kept from the real frame instead:
 * `ModelDrawMixin` hands each one over as it is drawn, as one piece of geometry per model drawn.
 */
public final class Submitted {
    /** The light and the overlay are packed the way a vertex carries them, U in the low half. */
    public record Vertex(float x, float y, float z, int colour, float u, float v, int light, int overlay) {
    }

    public record Geometry(RenderType renderType, List<Vertex> vertices) {
        public AABB box() {
            return Submitted.box(vertices);
        }
    }

    public record Item(Matrix4f pose, int light, int[] tints, List<BakedQuad> quads) {
        /** How much the pose grows a unit length, which is the item's drawn scale. */
        public float scale() {
            return pose.getScale(new org.joml.Vector3f()).x;
        }
    }

    private final List<Geometry> geometry = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Model<?>> models = new ArrayList<>();
    private int light;

    private static final Map<Integer, Submitted> WATCHED = new ConcurrentHashMap<>();

    /**
     * One frame of an add-on entity, submitted from the render state its renderer extracts.
     *
     * The pose stack starts at the entity's feet, where the dispatcher would have moved it to, so
     * every position is measured from there in blocks.
     */
    public static Submitted entity(AddonEntity entity) {
        AddonEntityRenderer renderer = (AddonEntityRenderer) Minecraft.getInstance()
            .getEntityRenderDispatcher().getRenderer(entity);
        AddonEntityRenderer.State state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0f);

        Submitted submitted = new Submitted();
        submitted.light = state.lightCoords;
        renderer.submit(state, new PoseStack(), submitted.collector(), new CameraRenderState());
        return submitted;
    }

    /**
     * One frame of a mob, submitted from the render state its renderer extracts, keeping the items
     * its layers hang on it. Its models are drawn later in the frame, so `watch` keeps those.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Submitted living(LivingEntity entity) {
        net.minecraft.client.renderer.entity.LivingEntityRenderer renderer =
            (net.minecraft.client.renderer.entity.LivingEntityRenderer) Minecraft.getInstance()
                .getEntityRenderDispatcher().getRenderer(entity);
        net.minecraft.client.renderer.entity.state.LivingEntityRenderState state =
            (net.minecraft.client.renderer.entity.state.LivingEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0f);

        Submitted submitted = new Submitted();
        submitted.light = state.lightCoords;
        renderer.submit(state, new PoseStack(), submitted.collector(), new CameraRenderState());
        return submitted;
    }

    /** Starts keeping every model the frames draw for this entity, from nothing. */
    public static void watch(Entity entity) {
        WATCHED.put(entity.getId(), new Submitted());
    }

    /** What the frames drew for the entity since `watch`, or null when it is not watched. */
    public static Submitted watched(Entity entity) {
        return WATCHED.get(entity.getId());
    }

    public static void unwatch(Entity entity) {
        WATCHED.remove(entity.getId());
    }

    /**
     * Keeps a model the frame is about to draw for a watched entity, posed as it is handed to the
     * buffer, measured from the model's own origin.
     */
    public static void drawn(Object state, Model<?> model, RenderType renderType, int light, int overlay, int colour) {
        LivingEntity entity = RenderStates.of(state);
        Submitted submitted = entity == null ? null : WATCHED.get(entity.getId());
        if (submitted == null) return;

        Recording recording = new Recording();
        model.renderToBuffer(new PoseStack(), recording, light, overlay, colour);
        recording.close();
        submitted.geometry.add(new Geometry(renderType, recording.vertices));
        submitted.models.add(model);
    }

    /** Every model a watched entity was drawn with, once per time it was drawn. */
    public List<Model<?>> models() {
        return models;
    }

    public List<Geometry> geometry() {
        return geometry;
    }

    public List<Item> items() {
        return items;
    }

    /** The light the entity stood in, packed the way a vertex carries it. */
    public int light() {
        return light;
    }

    /** The box around every vertex of every piece of geometry, null when there was none. */
    public AABB box() {
        return box(geometry.stream().flatMap(piece -> piece.vertices().stream()).toList());
    }

    /** The collector to submit to; everything it is handed lands here. */
    public SubmitNodeCollector collector() {
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
            new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "order" -> proxy;
                case "submitCustomGeometry" -> {
                    keepGeometry((PoseStack) arguments[0], (RenderType) arguments[1],
                        (SubmitNodeCollector.CustomGeometryRenderer) arguments[2]);
                    yield null;
                }
                case "submitItem" -> {
                    keepItem(arguments);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "a collector keeping what it is handed";
                default -> null;
            });
    }

    private void keepGeometry(PoseStack poseStack, RenderType renderType,
        SubmitNodeCollector.CustomGeometryRenderer renderer) {
        Recording recording = new Recording();
        renderer.render(poseStack.last(), recording);
        recording.close();
        geometry.add(new Geometry(renderType, recording.vertices));
    }

    @SuppressWarnings("unchecked")
    private void keepItem(Object[] arguments) {
        PoseStack poseStack = (PoseStack) arguments[0];
        //? if >=26.3 {
        /*List<BakedQuad> quads = ((net.minecraft.client.resources.model.geometry.ItemQuads) arguments[6]).all();
        *///?} else {
        List<BakedQuad> quads = (List<BakedQuad>) arguments[6];
        //?}
        items.add(new Item(new Matrix4f(poseStack.last().pose()), (int) arguments[2],
            ((int[]) arguments[5]).clone(), List.copyOf(quads)));
    }

    static AABB box(List<Vertex> vertices) {
        if (vertices.isEmpty()) return null;

        AABB box = null;
        for (Vertex vertex : vertices) {
            AABB point = new AABB(vertex.x(), vertex.y(), vertex.z(), vertex.x(), vertex.y(), vertex.z());
            box = box == null ? point : box.minmax(point);
        }
        return box;
    }

    /** A buffer that keeps each vertex it is given, with the colour, the texture point and the light. */
    private static final class Recording implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private int colour;
        private float u;
        private float v;
        private int light;
        private int overlay;
        private boolean open;

        private void close() {
            if (open) vertices.add(new Vertex(x, y, z, colour, u, v, light, overlay));
            open = false;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            close();
            this.x = x;
            this.y = y;
            this.z = z;
            open = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor(alpha << 24 | red << 16 | green << 8 | blue);
        }

        @Override
        public VertexConsumer setColor(int colour) {
            this.colour = colour;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.overlay = u | v << 16;
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.light = u | v << 16;
            return this;
        }

        //? if >=26.3 {
        /*@Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
        *///?}

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            close();
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
