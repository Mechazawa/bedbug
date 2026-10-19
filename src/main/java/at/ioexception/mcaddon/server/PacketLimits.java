package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.mixin.SettingsAccessor;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Bedrock's packet rate limiter, read from `packetlimitconfig.json` beside `server.properties`.
 *
 * It runs on a dedicated server whose `server.properties` says `enable-packet-rate-limiter=true`,
 * which Bedrock leaves false. Each limit group holds one bucket per Bedrock packet id for every
 * connection, and the default algorithm one bucket per Java packet type of the play phase, on top of
 * the groups. A packet any of its buckets has no room for is dropped before the server reads it.
 * `reloadpacketlimitconfig` reads the file again, and every connection starts with empty buckets
 * under the new limits as its next packet arrives.
 */
public final class PacketLimits {
    private static final String FILE = "packetlimitconfig.json";
    private static final String BUCKET = "BucketPacketLimitAlgorithm";

    private static volatile PacketLimits active;

    private final List<Limit> limits;
    private final @Nullable Rate fallback;

    private PacketLimits(List<Limit> limits, @Nullable Rate fallback) {
        this.limits = limits;
        this.fallback = fallback;
    }

    public static @Nullable PacketLimits active() {
        return active;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!switchedOn(server)) return;
            try {
                active = read();
            } catch (IOException invalid) {
                McAddon.LOGGER.warn("Failed to load packet limit config: {}", invalid.getMessage());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> active = null);
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reloadpacketlimitconfig")
            .requires(source -> Platform.allowedAt(source, 4))
            .executes(PacketLimits::reload));
    }

    private static int reload(CommandContext<CommandSourceStack> command) {
        try {
            PacketLimits read = read();
            if (switchedOn(command.getSource().getServer())) active = read;
        } catch (IOException invalid) {
            command.getSource().sendFailure(Component.literal("Failed to load packet limit config: "
                + invalid.getMessage()));
            return 0;
        }
        command.getSource().sendSuccess(() -> Component.literal("Packet limit config updated"), true);
        return 1;
    }

    private static boolean switchedOn(MinecraftServer server) {
        return server instanceof DedicatedServer dedicated
            && Boolean.parseBoolean(((SettingsAccessor) (Object) dedicated.getProperties()).mcaddon$properties()
                .getProperty("enable-packet-rate-limiter"));
    }

    /**
     * A group Bedrock would skip is named and left out, and the file fails only when no group is
     * left or its default algorithm is wrong. A Bedrock packet id no Java client sends is named once.
     */
    private static PacketLimits read() throws IOException {
        Path file = FabricLoader.getInstance().getGameDir().resolve(FILE);
        if (!Files.isRegularFile(file)) throw new IOException(FILE + " is missing");
        JsonObject root = Json.parseObject(file);
        if (!root.has("limitGroups")) throw new IOException("Expected an object with 'limitGroups' key.");
        if (!root.get("limitGroups").isJsonArray()) throw new IOException("Expected 'limitGroups' to be an array.");

        List<Limit> limits = new ArrayList<>();
        List<JsonElement> groups = root.getAsJsonArray("limitGroups").asList();
        for (int group = 0; group < groups.size(); group++) {
            try {
                limits.addAll(Limit.ofGroup(group, groups.get(group)));
            } catch (IllegalArgumentException skipped) {
                McAddon.LOGGER.warn("{}: {}", FILE, skipped.getMessage());
            }
        }
        if (limits.isEmpty()) throw new IOException("Expected 'limitGroups' to have at least one valid entry.");

        try {
            return new PacketLimits(limits,
                root.has("defaultAlgorithm") ? Rate.of(root.get("defaultAlgorithm")) : null);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage());
        }
    }

    public Limiter limiterFor(@Nullable Limiter held) {
        return held != null && held.limits == this ? held : new Limiter(this);
    }

    /** One Bedrock id of one group, as the Java packet type carrying that id's message. */
    private record Limit(int group, int bedrockId, Class<?> carrier, Rate rate) {
        static List<Limit> ofGroup(int group, JsonElement element) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("minecraftPacketIds")
                || !element.getAsJsonObject().has("algorithm")
                || !element.getAsJsonObject().get("minecraftPacketIds").isJsonArray()) {
                throw new IllegalArgumentException(
                    "Expected limitGroup to be an object with 'minecraftPacketIds' and 'algorithm' keys.");
            }
            JsonObject object = element.getAsJsonObject();
            List<Integer> ids = object.getAsJsonArray("minecraftPacketIds").asList().stream()
                .filter(id -> id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber())
                .map(JsonElement::getAsDouble)
                .filter(id -> id >= 0 && id == Math.rint(id))
                .map(Double::intValue)
                .toList();
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("Expected 'minecraftPacketIds' to have at least one valid entry.");
            }
            Rate rate = Rate.of(object.get("algorithm"));
            ids.stream()
                .filter(id -> !BedrockPacketIds.JAVA_CARRIERS.containsKey(id))
                .forEach(id -> McAddon.LOGGER.warn(
                    "{}: Bedrock packet id {} names no message a Java client sends, so nothing is limited by it",
                    FILE, id));
            return ids.stream()
                .flatMap(id -> BedrockPacketIds.JAVA_CARRIERS.getOrDefault(id, List.of()).stream()
                    .map(carrier -> new Limit(group, id, carrier, rate)))
                .toList();
        }
    }

    private record Rate(float drainPerSecond, long capacity) {
        static Rate of(JsonElement algorithm) {
            JsonObject object = algorithm.isJsonObject() ? algorithm.getAsJsonObject() : new JsonObject();
            String name = object.has("name") && object.get("name").isJsonPrimitive()
                ? object.get("name").getAsString() : "";
            if (!name.equals(BUCKET)) {
                throw new IllegalArgumentException("Unknown packet limit algorithm '" + name + "'. Only " + BUCKET
                    + " is supported.");
            }
            JsonObject params = object.has("params") && object.get("params").isJsonObject()
                ? object.getAsJsonObject("params") : new JsonObject();
            if (!params.has("drainRatePerSec") || !params.has("maxBucketSize")) {
                throw new IllegalArgumentException(
                    "Expected BucketPacketAlgorithm to be an object with 'drainRatePerSec' and 'maxBucketSize' keys.");
            }
            if (!number(params.get("drainRatePerSec"))) {
                throw new IllegalArgumentException("Invalid 'drainRatePerSec' value for " + BUCKET
                    + ". Expected a float.");
            }
            float drain = params.get("drainRatePerSec").getAsFloat();
            if (drain < 0 || drain > 10000) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                    "Invalid 'drainRatePerSec' value for %s. Must be in the range [%f, %f], instead got %f",
                    BUCKET, 0f, 10000f, drain));
            }
            double size = number(params.get("maxBucketSize")) ? params.get("maxBucketSize").getAsDouble() : -1;
            if (size < 0 || size != Math.rint(size)) {
                throw new IllegalArgumentException("Invalid 'maxBucketSize' value for " + BUCKET
                    + ". Expected an unsigned integer.");
            }
            if (size < 1 || size > 10000) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                    "Invalid 'maxBucketSize' value for %s. Must be in the range [%d, %d], instead got %d",
                    BUCKET, 1, 10000, (long) size));
            }
            return new Rate(drain, (long) size);
        }

        private static boolean number(JsonElement value) {
            return value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber();
        }
    }

    /** Takes one packet each and drains at its rate; a full bucket takes nothing. */
    private static final class Bucket {
        private final Rate rate;
        private double level;
        private long drainedAt;

        Bucket(Rate rate, long now) {
            this.rate = rate;
            this.drainedAt = now;
        }

        boolean hasRoom(long now) {
            level = Math.max(0, level - rate.drainPerSecond() * (now - drainedAt) / 1e9);
            drainedAt = now;
            return level + 1 <= rate.capacity();
        }

        void fill() {
            level += 1;
        }
    }

    /** One connection's buckets under one reading of the file. */
    public static final class Limiter {
        private final PacketLimits limits;
        private final Map<Object, Bucket> buckets = new HashMap<>();

        private Limiter(PacketLimits limits) {
            this.limits = limits;
        }

        /** Whether every bucket the packet falls in has room, filling each of them when they all do. */
        public boolean admits(Packet<?> packet, boolean playing) {
            long now = System.nanoTime();
            Stream<Bucket> grouped = limits.limits.stream()
                .filter(limit -> limit.carrier().isInstance(packet))
                .map(limit -> buckets.computeIfAbsent(List.of(limit.group(), limit.bedrockId()),
                    key -> new Bucket(limit.rate(), now)));
            Stream<Bucket> fallback = playing && limits.fallback != null
                ? Stream.of(buckets.computeIfAbsent(packet.getClass(), key -> new Bucket(limits.fallback, now)))
                : Stream.empty();
            List<Bucket> applying = Stream.concat(grouped, fallback).distinct().toList();
            if (!applying.stream().allMatch(bucket -> bucket.hasRoom(now))) return false;
            applying.forEach(Bucket::fill);
            return true;
        }
    }
}
