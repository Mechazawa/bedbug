package at.ioexception.mcaddon.network;

import at.ioexception.mcaddon.McAddon;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

//? if >=1.21 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}

/**
 * What the server tells one client to draw, and the one answer that comes back.
 *
 * The server owns every entity property a behaviour pack declares, because a pack's script sets them
 * and scripts only run there. The client needs them to answer `q.property` while it draws, so each
 * change and each newly tracked entity is pushed across. The same holds for everything else here: a
 * form, an effect, a clip and a camera are all decided on the server and sent to each player alone.
 *
 * Networking was rewritten in 1.21: payload objects with codecs replaced raw channel buffers.
 */
public final class Channels {
    private static final Identifier PROPERTIES = McAddon.id("entity_properties");
    private static final Identifier PACKS = McAddon.id("pack_list");
    private static final Identifier FORM = McAddon.id("form");
    private static final Identifier FORM_RESPONSE = McAddon.id("form_response");
    private static final Identifier ANIMATION = McAddon.id("play_animation");
    private static final Identifier PARTICLE = McAddon.id("start_particle");
    private static final Identifier CAMERA = McAddon.id("camera_view");
    private static final Identifier SHAKE = McAddon.id("camera_shake");
    private static final Identifier FOG = McAddon.id("fog");

    /**
     * The longest form description, form answer or pack list in characters.
     *
     * A plain network string stops at 32767, and a shop's form of a few hundred buttons describes
     * itself in more than that. A connection to a dedicated server encodes every message and drops
     * the player on one that is too long; the in-memory connection a single player world uses never
     * encodes, so it never shows. This many characters still fit in one custom payload.
     */
    private static final int LONG_TEXT = 262144;

    private Channels() {
    }

    //? if >=1.21 {
    private record Properties(int entityId, String values) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Properties> TYPE = new CustomPacketPayload.Type<>(PROPERTIES);

        private static final StreamCodec<FriendlyByteBuf, Properties> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Properties::entityId,
            ByteBufCodecs.STRING_UTF8, Properties::values,
            Properties::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** What the server has loaded, so a client can say which add-ons it is missing. */
    private record Packs(String packs) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Packs> TYPE = new CustomPacketPayload.Type<>(PACKS);

        private static final StreamCodec<FriendlyByteBuf, Packs> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(LONG_TEXT), Packs::packs,
            Packs::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** A form the server asks a client to show, and the answer that comes back. */
    private record Form(int formId, String description) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Form> TYPE = new CustomPacketPayload.Type<>(FORM);

        private static final StreamCodec<FriendlyByteBuf, Form> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Form::formId,
            ByteBufCodecs.stringUtf8(LONG_TEXT), Form::description,
            Form::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record FormResponse(int formId, String answer) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<FormResponse> TYPE =
            new CustomPacketPayload.Type<>(FORM_RESPONSE);

        private static final StreamCodec<FriendlyByteBuf, FormResponse> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FormResponse::formId,
            ByteBufCodecs.stringUtf8(LONG_TEXT), FormResponse::answer,
            FormResponse::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** A clip the server asked an entity to play, which only the client can actually run. */
    private record Animation(int entityId, String animation, String stopExpression, float blendOut)
        implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Animation> TYPE =
            new CustomPacketPayload.Type<>(ANIMATION);

        private static final StreamCodec<FriendlyByteBuf, Animation> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Animation::entityId,
            ByteBufCodecs.STRING_UTF8, Animation::animation,
            ByteBufCodecs.STRING_UTF8, Animation::stopExpression,
            ByteBufCodecs.FLOAT, Animation::blendOut,
            Animation::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** An effect the server asked for, which only a client can run because an emitter is drawn. */
    private record Particle(String effect, double x, double y, double z) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Particle> TYPE =
            new CustomPacketPayload.Type<>(PARTICLE);

        private static final StreamCodec<FriendlyByteBuf, Particle> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Particle::effect,
            ByteBufCodecs.DOUBLE, Particle::x,
            ByteBufCodecs.DOUBLE, Particle::y,
            ByteBufCodecs.DOUBLE, Particle::z,
            Particle::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** What `/camera`, `/camerashake` and `/fog` set for one player, each in its own message. */
    private record View(String view) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<View> TYPE = new CustomPacketPayload.Type<>(CAMERA);

        private static final StreamCodec<FriendlyByteBuf, View> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, View::view,
            View::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record Shake(String shake) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Shake> TYPE = new CustomPacketPayload.Type<>(SHAKE);

        private static final StreamCodec<FriendlyByteBuf, Shake> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Shake::shake,
            Shake::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record Fog(String fog) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Fog> TYPE = new CustomPacketPayload.Type<>(FOG);

        private static final StreamCodec<FriendlyByteBuf, Fog> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Fog::fog,
            Fog::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    /** The fog now on top of a player's stack, or an empty description when nothing is left on it. */
    public static void sendFog(ServerPlayer player, String fog) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Fog(fog));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(fog);
        ServerPlayNetworking.send(player, FOG, buffer);
        *///?}
    }

    public static void receiveFog(java.util.function.Consumer<String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Fog.TYPE,
            (payload, context) -> context.client().execute(() -> handler.accept(payload.fog())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            FOG,
            (client, network, buffer, sender) -> {
                String fog = buffer.readUtf();
                client.execute(() -> handler.accept(fog));
            });
        *///?}
    }

    public static void sendCameraView(ServerPlayer player, String view) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new View(view));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(view);
        ServerPlayNetworking.send(player, CAMERA, buffer);
        *///?}
    }

    public static void receiveCameraView(java.util.function.Consumer<String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            View.TYPE,
            (payload, context) -> context.client().execute(() -> handler.accept(payload.view())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            CAMERA,
            (client, network, buffer, sender) -> {
                String view = buffer.readUtf();
                client.execute(() -> handler.accept(view));
            });
        *///?}
    }

    public static void sendCameraShake(ServerPlayer player, String shake) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Shake(shake));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(shake);
        ServerPlayNetworking.send(player, SHAKE, buffer);
        *///?}
    }

    public static void receiveCameraShake(java.util.function.Consumer<String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Shake.TYPE,
            (payload, context) -> context.client().execute(() -> handler.accept(payload.shake())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            SHAKE,
            (client, network, buffer, sender) -> {
                String shake = buffer.readUtf();
                client.execute(() -> handler.accept(shake));
            });
        *///?}
    }

    /** What a client needs to start one effect: which one, and where. */
    @FunctionalInterface
    public interface ParticleHandler {
        void start(String effect, double x, double y, double z);
    }

    public static void sendParticle(ServerPlayer player, String effect, double x, double y, double z) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Particle(effect, x, y, z));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(effect);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        ServerPlayNetworking.send(player, PARTICLE, buffer);
        *///?}
    }

    public static void receiveParticle(ParticleHandler handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Particle.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.start(payload.effect(), payload.x(), payload.y(), payload.z())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PARTICLE,
            (client, network, buffer, sender) -> {
                String effect = buffer.readUtf();
                double x = buffer.readDouble();
                double y = buffer.readDouble();
                double z = buffer.readDouble();
                client.execute(() -> handler.start(effect, x, y, z));
            });
        *///?}
    }

    /** What a client needs to run one clip: which entity, which animation, and how it ends. */
    @FunctionalInterface
    public interface AnimationHandler {
        void play(int entityId, String animation, String stopExpression, float blendOut);
    }

    public static void sendAnimation(ServerPlayer player, int entityId, String animation,
        String stopExpression, float blendOut) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Animation(entityId, animation, stopExpression, blendOut));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(entityId);
        buffer.writeUtf(animation);
        buffer.writeUtf(stopExpression);
        buffer.writeFloat(blendOut);
        ServerPlayNetworking.send(player, ANIMATION, buffer);
        *///?}
    }

    public static void receiveAnimation(AnimationHandler handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Animation.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.play(payload.entityId(), payload.animation(),
                    payload.stopExpression(), payload.blendOut())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            ANIMATION,
            (client, network, buffer, sender) -> {
                int entityId = buffer.readVarInt();
                String animation = buffer.readUtf();
                String stopExpression = buffer.readUtf();
                float blendOut = buffer.readFloat();
                client.execute(() -> handler.play(entityId, animation, stopExpression, blendOut));
            });
        *///?}
    }

    /** Called from the common initialiser, because the payload type has to exist on both sides. */
    public static void register() {
        //? if >=1.21 {
        PayloadTypeRegistry.clientboundPlay().register(Animation.TYPE, Animation.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Particle.TYPE, Particle.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Properties.TYPE, Properties.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packs.TYPE, Packs.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Form.TYPE, Form.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(View.TYPE, View.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Shake.TYPE, Shake.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Fog.TYPE, Fog.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FormResponse.TYPE, FormResponse.CODEC);
        //?}
    }

    public static void sendForm(ServerPlayer player, int formId, String description) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Form(formId, description));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(formId);
        buffer.writeUtf(description, LONG_TEXT);
        ServerPlayNetworking.send(player, FORM, buffer);
        *///?}
    }

    /** The server side of the answer: who answered, which form, and what they chose. */
    @FunctionalInterface
    public interface FormAnswerHandler {
        void answer(ServerPlayer player, int formId, String answer);
    }

    public static void receiveFormResponse(FormAnswerHandler handler) {
        //? if >=1.21 {
        ServerPlayNetworking.registerGlobalReceiver(FormResponse.TYPE,
            (payload, context) -> context.server().execute(
                () -> handler.answer(context.player(), payload.formId(), payload.answer())));
        //?} else {
        /*ServerPlayNetworking.registerGlobalReceiver(FORM_RESPONSE,
            (server, player, network, buffer, sender) -> {
                int formId = buffer.readVarInt();
                String answer = buffer.readUtf(LONG_TEXT);
                server.execute(() -> handler.answer(player, formId, answer));
            });
        *///?}
    }

    public static void receiveForm(java.util.function.BiConsumer<Integer, String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Form.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.accept(payload.formId(), payload.description())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            FORM,
            (client, network, buffer, sender) -> {
                int formId = buffer.readVarInt();
                String description = buffer.readUtf(LONG_TEXT);
                client.execute(() -> handler.accept(formId, description));
            });
        *///?}
    }

    public static void sendFormResponse(int formId, String answer) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new FormResponse(formId, answer));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(formId);
        buffer.writeUtf(answer, LONG_TEXT);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(FORM_RESPONSE, buffer);
        *///?}
    }

    public static void sendPacks(ServerPlayer player, String packs) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Packs(packs));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(packs, LONG_TEXT);
        ServerPlayNetworking.send(player, PACKS, buffer);
        *///?}
    }

    public static void receivePacks(java.util.function.Consumer<String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Packs.TYPE,
            (payload, context) -> context.client().execute(() -> handler.accept(payload.packs())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PACKS,
            (client, network, buffer, sender) -> {
                String packs = buffer.readUtf(LONG_TEXT);
                client.execute(() -> handler.accept(packs));
            });
        *///?}
    }

    public static void sendProperties(ServerPlayer player, int entityId, String values) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Properties(entityId, values));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(entityId);
        buffer.writeUtf(values);
        ServerPlayNetworking.send(player, PROPERTIES, buffer);
        *///?}
    }

    /** The client hands in what to do with an update; the wire shape stays in here. */
    public static void receiveProperties(BiConsumer<Integer, String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Properties.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.accept(payload.entityId(), payload.values())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PROPERTIES,
            (client, network, buffer, sender) -> {
                int entityId = buffer.readVarInt();
                String values = buffer.readUtf();
                client.execute(() -> handler.accept(entityId, values));
            });
        *///?}
    }
}
