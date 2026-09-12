package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.client.render.BedrockParticles;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.Objective;
import org.graalvm.polyglot.HostAccess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The part of `@minecraft/server` that answers about one player.
 *
 * A player reaches here as a UUID string the way every other entity does, and anything richer than a
 * primitive crosses as JSON: a raw message, a spawn point, a bag of music options. What Bedrock
 * exposes and Java Edition has no counterpart for is left to the script side to report.
 */
public final class Players extends Surface {
    /**
     * When each cooldown a script started runs out, by player and category.
     *
     * Bedrock's categories are names a pack invents, so nothing in Java's own cooldown map answers
     * for them and the ticks are counted here against the server's own clock.
     */
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();

    public Players(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    // Spawn point

    /** Where the player respawns, as JSON, or null while they respawn wherever the world says. */
    @HostAccess.Export
    public String spawnPoint(String id) {
        ServerPlayer player = player(id);
        return player == null ? null : respawnJson(player);
    }

    /** A null dimension clears the spawn point, which sends the player back to the world's own. */
    @HostAccess.Export
    public void setSpawnPoint(String id, String dimension, int x, int y, int z) {
        ServerPlayer player = player(id);
        if (player == null) return;

        ServerLevel level = dimension == null ? null : level(dimension);
        if (dimension != null && level == null) {
            unsupported("Player.setSpawnPoint(" + dimension + ")");
            return;
        }
        respawnAt(player, level, new BlockPos(x, y, z));
    }

    // Item cooldowns

    @HostAccess.Export
    public void startItemCooldown(String id, String category, int ticks) {
        ServerPlayer player = player(id);
        if (player == null || category == null) return;

        long now = server.getTickCount();
        cooldowns.values().removeIf(end -> end <= now);
        cooldowns.put(id + "/" + category, now + Math.max(0, ticks));
    }

    @HostAccess.Export
    public int itemCooldown(String id, String category) {
        Long end = cooldowns.get(id + "/" + category);
        return end == null ? 0 : (int) Math.max(0, end - server.getTickCount());
    }

    // Experience

    @HostAccess.Export
    public int experienceLevel(String id) {
        ServerPlayer player = player(id);
        return player == null ? 0 : player.experienceLevel;
    }

    @HostAccess.Export
    public int xpEarnedAtCurrentLevel(String id) {
        ServerPlayer player = player(id);
        return player == null ? 0 : Math.round(player.experienceProgress * player.getXpNeededForNextLevel());
    }

    @HostAccess.Export
    public int totalXpNeededForNextLevel(String id) {
        ServerPlayer player = player(id);
        return player == null ? 0 : player.getXpNeededForNextLevel();
    }

    @HostAccess.Export
    public int totalXp(String id) {
        ServerPlayer player = player(id);
        return player == null ? 0 : player.totalExperience;
    }

    @HostAccess.Export
    public int addExperience(String id, int amount) {
        ServerPlayer player = player(id);
        if (player == null) return 0;
        player.giveExperiencePoints(amount);
        return player.totalExperience;
    }

    @HostAccess.Export
    public int addLevels(String id, int amount) {
        ServerPlayer player = player(id);
        if (player == null) return 0;
        player.giveExperienceLevels(amount);
        return player.experienceLevel;
    }

    @HostAccess.Export
    public void resetLevel(String id) {
        ServerPlayer player = player(id);
        if (player == null) return;
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
    }

    // Movement state

    @HostAccess.Export
    public boolean isFlying(String id) {
        ServerPlayer player = player(id);
        return player != null && player.getAbilities().flying;
    }

    @HostAccess.Export
    public boolean isGliding(String id) {
        ServerPlayer player = player(id);
        return player != null && player.isFallFlying();
    }

    @HostAccess.Export
    public boolean isJumping(String id) {
        ServerPlayer player = player(id);
        return player != null && jumping(player);
    }

    // Music, which is a sound in the music category: the slider a player turns down

    @HostAccess.Export
    public void playMusic(String id, String track, String optionsJson) {
        ServerPlayer player = player(id);
        Identifier parsed = track == null ? null : Identifier.tryParse(track);
        if (player == null || parsed == null) return;

        JsonObject options = JsonParser.parseString(optionsJson).getAsJsonObject();
        if (flag(options, "loop")) unsupported("MusicOptions.loop");
        if (options.has("fade")) unsupported("MusicOptions.fade");

        float volume = options.has("volume") ? options.get("volume").getAsFloat() : 1f;
        BuiltInRegistries.SOUND_EVENT.getOptional(parsed).ifPresentOrElse(
            event -> player.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), SoundSource.MUSIC,
                player.getX(), player.getY(), player.getZ(), volume, 1f, player.getRandom().nextLong())),
            () -> unsupported("Player.playMusic(" + parsed + ")"));
    }

    @HostAccess.Export
    public void stopMusic(String id) {
        ServerPlayer player = player(id);
        if (player != null) player.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
    }

    // Particles

    /** An effect one player sees: a pack's own if it describes one, otherwise the Java equivalent. */
    @HostAccess.Export
    public void spawnParticle(String id, String effect, double x, double y, double z) {
        ServerPlayer player = player(id);
        if (player == null || effect == null || !(player.level() instanceof ServerLevel level)) return;

        ParticleOptions options = AddonParticles.typeOf(effect);
        if (options == null) options = BedrockParticles.vanilla(effect);
        if (options == null) {
            unsupported("Player.spawnParticle(" + effect + ")");
            return;
        }
        showParticle(level, player, options, x, y, z);
    }

    // Permission

    /**
     * How far up Bedrock's command ladder the player stands.
     *
     * Java has one operator flag and a level that comes from the server's own settings, so the
     * answer is the highest rung the player's command source is still allowed at.
     */
    @HostAccess.Export
    public int commandPermissionLevel(String id) {
        ServerPlayer player = player(id);
        if (player == null || !(player.level() instanceof ServerLevel level)) return 0;

        for (int rung = 4; rung >= 1; rung--) {
            if (Platform.allowedAt(Platform.commandSourceOf(player, level), rung)) return rung;
        }
        return 0;
    }

    /** Java has operators and everyone else, which are Bedrock's `Operator` and `Member`. */
    @HostAccess.Export
    public int playerPermissionLevel(String id) {
        return commandPermissionLevel(id) >= 1 ? 2 : 1;
    }

    // Text

    @HostAccess.Export
    public void sendMessage(String id, String messageJson) {
        ServerPlayer player = player(id);
        if (player != null) player.sendSystemMessage(message(parse(messageJson), player));
    }

    @HostAccess.Export
    public void setActionBar(String id, String messageJson) {
        ServerPlayer player = player(id);
        if (player != null) player.sendSystemMessage(message(parse(messageJson), player), true);
    }

    /**
     * Shows a title, with the subtitle and the three durations the options may carry.
     *
     * An empty title clears whatever is showing, which is what Bedrock does with an empty string.
     * Durations are only sent when the pack gave them, so a title without options keeps whatever
     * timing the player's client is already using.
     */
    @HostAccess.Export
    public void setTitle(String id, String titleJson, String optionsJson) {
        ServerPlayer player = player(id);
        if (player == null) return;

        Component title = message(parse(titleJson), player);
        if (title.getString().isEmpty()) {
            player.connection.send(new ClientboundClearTitlesPacket(false));
            return;
        }

        JsonElement given = parse(optionsJson);
        JsonObject options = given != null && given.isJsonObject() ? given.getAsJsonObject() : null;
        if (options != null) {
            setTitleTimes(player, options);
            if (options.has("subtitle")) {
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                    message(options.get("subtitle"), player)));
            }
        }
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    /** Bedrock's own defaults stand in for a duration the options leave out. */
    private static void setTitleTimes(ServerPlayer player, JsonObject options) {
        if (!options.has("fadeInDuration") && !options.has("stayDuration")
            && !options.has("fadeOutDuration")) {
            return;
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(
            duration(options, "fadeInDuration", 10), duration(options, "stayDuration", 70),
            duration(options, "fadeOutDuration", 20)));
    }

    @HostAccess.Export
    public void updateSubtitle(String id, String messageJson) {
        ServerPlayer player = player(id);
        if (player != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(message(parse(messageJson), player)));
        }
    }

    // Inventory and game mode

    @HostAccess.Export
    public void setSelectedSlot(String id, int index) {
        ServerPlayer player = player(id);
        if (player != null) selectSlot(player, Math.clamp(index, 0, 8));
    }

    /** What `setGameMode` with nothing to set falls back to. Bedrock capitalises its names. */
    @HostAccess.Export
    public String defaultGameMode() {
        String name = server.getDefaultGameType().getName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // Helpers, not exported

    private ServerPlayer player(String id) {
        return entity(id) instanceof ServerPlayer player ? player : null;
    }

    /**
     * A `RawMessage` as the component the player is sent.
     *
     * Built here rather than flattened to a string on the script side so a `translate` reaches the
     * client as a translatable component and is read in the language that client is set to.
     */
    private Component message(JsonElement raw, ServerPlayer viewer) {
        if (raw == null || raw.isJsonNull()) return Component.empty();
        if (raw.isJsonPrimitive()) return Component.literal(raw.getAsString());
        if (raw.isJsonArray()) return join(raw.getAsJsonArray(), viewer);

        JsonObject message = raw.getAsJsonObject();
        if (message.has("rawtext")) return message(message.get("rawtext"), viewer);
        if (message.has("translate")) {
            return Component.translatable(message.get("translate").getAsString(),
                arguments(message.get("with"), viewer));
        }
        if (message.has("score")) return score(message.getAsJsonObject("score"), viewer);
        return Component.literal(message.has("text") ? message.get("text").getAsString() : "");
    }

    private MutableComponent join(JsonArray parts, ServerPlayer viewer) {
        MutableComponent joined = Component.empty();
        for (JsonElement part : parts) joined.append(message(part, viewer));
        return joined;
    }

    private Object[] arguments(JsonElement with, ServerPlayer viewer) {
        JsonArray parts = argumentList(with);
        return parts == null ? new Object[0]
            : parts.asList().stream().map(part -> (Object) message(part, viewer)).toArray();
    }

    /** `with` is either the list itself or an object carrying one under `rawtext`. */
    private static JsonArray argumentList(JsonElement with) {
        if (with == null) return null;
        if (with.isJsonArray()) return with.getAsJsonArray();
        if (!with.isJsonObject()) return null;

        JsonObject wrapper = with.getAsJsonObject();
        return wrapper.has("rawtext") && wrapper.get("rawtext").isJsonArray()
            ? wrapper.getAsJsonArray("rawtext") : null;
    }

    /** `*` as the name means the reader's own score, which is why a message is built per player. */
    private Component score(JsonObject score, ServerPlayer viewer) {
        String name = score.has("name") ? score.get("name").getAsString() : null;
        String objective = score.has("objective") ? score.get("objective").getAsString() : null;
        String participant = "*".equals(name) ? viewer.getScoreboardName() : name;

        Objective found = objective == null ? null : server.getScoreboard().getObjective(objective);
        Integer value = found == null || participant == null ? null
            : Platform.score(server.getScoreboard(), found, participant);
        return Component.literal(value == null ? "" : String.valueOf(value));
    }

    private static JsonElement parse(String json) {
        return json == null ? null : JsonParser.parseString(json);
    }

    private static boolean flag(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() && options.get(key).getAsBoolean();
    }

    private static int duration(JsonObject options, String key, int fallback) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsInt() : fallback;
    }

    // Version splits. These belong in Platform once the file settles.

    /** The respawn position and its dimension, which became one record in 26. */
    private static String respawnJson(ServerPlayer player) {
        //? if >=26 {
        net.minecraft.server.level.ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config == null) return null;
        net.minecraft.world.level.storage.LevelData.RespawnData data = config.respawnData();
        return spawnJson(Platform.idOf(data.dimension()).toString(), data.pos());
        //?} else {
        /*BlockPos at = player.getRespawnPosition();
        if (at == null) return null;
        return spawnJson(Platform.idOf(player.getRespawnDimension()).toString(), at);
        *///?}
    }

    /** A null level clears the spawn point. The setter took a record from 26 and five values before. */
    private static void respawnAt(ServerPlayer player, ServerLevel level, BlockPos at) {
        //? if >=26 {
        net.minecraft.server.level.ServerPlayer.RespawnConfig config = level == null ? null
            : new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(), at, 0f, 0f),
                false);
        player.setRespawnPosition(config, false);
        //?} else {
        /*player.setRespawnPosition(
            level == null ? net.minecraft.world.level.Level.OVERWORLD : level.dimension(),
            level == null ? null : at, 0f, false, false);
        *///?}
    }

    /** 26 added a flag for whether the effect is drawn however far away the player is standing. */
    private static void showParticle(ServerLevel level, ServerPlayer player, ParticleOptions options,
        double x, double y, double z) {
        //? if >=26 {
        level.sendParticles(player, options, false, false, x, y, z, 1, 0, 0, 0, 0);
        //?} else {
        /*level.sendParticles(player, options, false, x, y, z, 1, 0, 0, 0, 0);
        *///?}
    }

    /** The flag became readable in 26; before that nothing on the server can see it. */
    private boolean jumping(ServerPlayer player) {
        //? if >=26 {
        return player.isJumping();
        //?} else {
        /*unsupported("Player.isJumping");
        return false;
        *///?}
    }

    /** The held slot got an accessor in 26, and the packet that tells the client was renamed with it. */
    private static void selectSlot(ServerPlayer player, int index) {
        //? if >=26 {
        player.getInventory().setSelectedSlot(index);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(index));
        //?} else {
        /*player.getInventory().selected = index;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(index));
        *///?}
    }

    private static String spawnJson(String dimension, BlockPos at) {
        JsonObject spawn = new JsonObject();
        spawn.addProperty("dimension", dimension);
        spawn.addProperty("x", at.getX());
        spawn.addProperty("y", at.getY());
        spawn.addProperty("z", at.getZ());
        return spawn.toString();
    }
}
