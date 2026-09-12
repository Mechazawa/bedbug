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
 * What the server tells clients about add-on entities.
 *
 * The server owns every entity property a behaviour pack declares, because a pack's script sets them
 * and scripts only run there. The client needs them to answer `q.property` while it draws, so each
 * change and each newly tracked entity is pushed across.
 *
 * Networking was rewritten in 1.21: payload objects with codecs replaced raw channel buffers.
 */
public final class Channels {
    private static final Identifier PROPERTIES = McAddon.id("entity_properties");
    private static final Identifier PACKS = McAddon.id("pack_list");
    private static final Identifier FORM = McAddon.id("form");
    private static final Identifier FORM_RESPONSE = McAddon.id("form_response");
    private static final Identifier ANIMATION = McAddon.id("play_animation");

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
            ByteBufCodecs.STRING_UTF8, Packs::packs,
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
            ByteBufCodecs.STRING_UTF8, Form::description,
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
            ByteBufCodecs.STRING_UTF8, FormResponse::answer,
            FormResponse::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    //? if >=1.21 {
    /** A clip the server asked an entity to play, which only the client can actually run. */
    private record Animation(int entityId, String animation) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Animation> TYPE =
            new CustomPacketPayload.Type<>(ANIMATION);

        private static final StreamCodec<FriendlyByteBuf, Animation> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Animation::entityId,
            ByteBufCodecs.STRING_UTF8, Animation::animation,
            Animation::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    public static void sendAnimation(ServerPlayer player, int entityId, String animation) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Animation(entityId, animation));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(entityId);
        buffer.writeUtf(animation);
        ServerPlayNetworking.send(player, ANIMATION, buffer);
        *///?}
    }

    public static void receiveAnimation(java.util.function.BiConsumer<Integer, String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Animation.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.accept(payload.entityId(), payload.animation())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            ANIMATION,
            (client, network, buffer, sender) -> {
                int entityId = buffer.readVarInt();
                String animation = buffer.readUtf();
                client.execute(() -> handler.accept(entityId, animation));
            });
        *///?}
    }

    /** Called from the common initialiser, because the payload type has to exist on both sides. */
    public static void register() {
        //? if >=1.21 {
        PayloadTypeRegistry.clientboundPlay().register(Animation.TYPE, Animation.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Properties.TYPE, Properties.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packs.TYPE, Packs.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Form.TYPE, Form.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FormResponse.TYPE, FormResponse.CODEC);
        //?}
    }

    public static void sendForm(ServerPlayer player, int formId, String description) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Form(formId, description));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(formId);
        buffer.writeUtf(description, 262144);
        ServerPlayNetworking.send(player, FORM, buffer);
        *///?}
    }

    /** The server side of the answer: which form, and what the player chose. */
    public static void receiveFormResponse(java.util.function.BiConsumer<Integer, String> handler) {
        //? if >=1.21 {
        ServerPlayNetworking.registerGlobalReceiver(FormResponse.TYPE,
            (payload, context) -> context.server().execute(
                () -> handler.accept(payload.formId(), payload.answer())));
        //?} else {
        /*ServerPlayNetworking.registerGlobalReceiver(FORM_RESPONSE,
            (server, player, network, buffer, sender) -> {
                int formId = buffer.readVarInt();
                String answer = buffer.readUtf(262144);
                server.execute(() -> handler.accept(formId, answer));
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
                String description = buffer.readUtf(262144);
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
        buffer.writeUtf(answer, 262144);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(FORM_RESPONSE, buffer);
        *///?}
    }

    public static void sendPacks(ServerPlayer player, String packs) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Packs(packs));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(packs, 262144);
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
                String packs = buffer.readUtf(262144);
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
