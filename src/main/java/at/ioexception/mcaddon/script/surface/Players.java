package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.bedrock.aim.AimAssistSettings;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.camera.AttachPoint;
import at.ioexception.mcaddon.bedrock.camera.CameraRequest;
import at.ioexception.mcaddon.bedrock.camera.CameraSpline;
import at.ioexception.mcaddon.bedrock.camera.Easing;
import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.client.render.BedrockParticles;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.server.PlayerAimAssists;
import at.ioexception.mcaddon.server.PlayerCameras;
import at.ioexception.mcaddon.server.PlayerHuds;
import at.ioexception.mcaddon.server.PlayerInputs;
import at.ioexception.mcaddon.server.PropertyOverrides;
import at.ioexception.mcaddon.util.Json;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import org.graalvm.polyglot.HostAccess;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Platform.respawnAt(player, level, new BlockPos(x, y, z));
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

    // The client

    /**
     * `Camera.attachToEntity`, which is `/camera attach_to_entity` with a place on the entity named.
     * The answer is why it was refused, or null once the order is on its way.
     */
    @HostAccess.Export
    public String attachCamera(String id, String target, String locator) {
        ServerPlayer player = player(id);
        net.minecraft.world.entity.Entity attached = entity(target);
        if (player == null || attached == null) return null;
        return PlayerCameras.attach(player, attached, locator == null ? null : AttachPoint.tryFrom(locator));
    }

    /**
     * `Camera.setCamera` and `Camera.setDefaultCamera`, which are `/camera set` for one player.
     *
     * The options are the ones the script stated: a `location`, a `rotation`, a `facingLocation`, the
     * id of a `facingEntity`, a fixed boom's `viewOffset` and `entityOffset`, the id of a
     * `targetEntity` with its `offsetFromTargetCenter`, and an `easeType` in the spelling `/camera`
     * takes with its `easeTime`. The answer is why the preset was refused, or null once the view is on
     * its way.
     */
    @HostAccess.Export
    public String setCamera(String id, String preset, String optionsJson) {
        ServerPlayer player = player(id);
        if (player == null) return null;

        JsonObject options = JsonParser.parseString(optionsJson).getAsJsonObject();
        String easeType = Json.text(options, "easeType", null);
        Easing easing = easeType == null ? null : Easing.tryFrom(easeType);
        if (easeType != null && easing == null) return "There is no ease named " + easeType + ".";

        net.minecraft.world.entity.Entity faced = options.has("facingEntity")
            ? entity(options.get("facingEntity").getAsString()) : null;
        JsonObject rotation = options.getAsJsonObject("rotation");
        JsonObject viewOffset = options.getAsJsonObject("viewOffset");
        CameraRequest request = new CameraRequest(
            vector(options.getAsJsonObject("location")),
            rotation == null ? null : (float) Json.number(rotation, "x", 0),
            rotation == null ? null : (float) Json.number(rotation, "y", 0),
            faced != null ? faced.getBoundingBox().getCenter() : vector(options.getAsJsonObject("facingLocation")),
            easing == null ? 0f : (float) Json.number(options, "easeTime", 1), easing,
            viewOffset == null ? null : (float) Json.number(viewOffset, "x", 0),
            viewOffset == null ? null : (float) Json.number(viewOffset, "y", 0),
            vector(options.getAsJsonObject("entityOffset")));
        String refused = PlayerCameras.set(player, preset, request);
        if (refused != null || !options.has("targetEntity")) return refused;

        net.minecraft.world.entity.Entity target = entity(options.get("targetEntity").getAsString());
        Vec3 offset = vector(options.getAsJsonObject("offsetFromTargetCenter"));
        return target == null ? null : PlayerCameras.target(player, target, offset == null ? Vec3.ZERO : offset);
    }

    /** `Camera.clear`, which ends every camera order the player is under. */
    @HostAccess.Export
    public void clearCamera(String id) {
        ServerPlayer player = player(id);
        if (player != null) PlayerCameras.clear(player);
    }

    /**
     * `Camera.playAnimation`, whose curve and key frames the script side has written in the spelling
     * a pack's spline file uses. The answer is why it was refused, or null once it is on its way.
     */
    @HostAccess.Export
    public String playCameraSpline(String id, String splineJson) {
        ServerPlayer player = player(id);
        if (player == null) return null;
        return PlayerCameras.play(player, CameraSpline.read(JsonParser.parseString(splineJson).getAsJsonObject()));
    }

    /**
     * `Camera.addShake`, which is `/camerashake add` for one player, and `Camera.stopShaking`, which
     * is a shake of no length.
     */
    @HostAccess.Export
    public void shakeCamera(String id, double intensity, double seconds, boolean rotational) {
        ServerPlayer player = player(id);
        if (player == null) return;

        Channels.sendCameraShake(player,
            new at.ioexception.mcaddon.bedrock.camera.CameraShake((float) intensity, (float) seconds,
                rotational).encode());
    }

    // Fog

    /** Where the fog went on the stack, or -1 when no pack describes it or the stack is full. */
    @HostAccess.Export
    public int pushFog(String id, String fogId, String tag) {
        ServerPlayer player = player(id);
        return player == null ? -1 : at.ioexception.mcaddon.server.PlayerFogs.push(player, fogId, tag);
    }

    @HostAccess.Export
    public String popFog(String id, String tag) {
        ServerPlayer player = player(id);
        return player == null ? null : at.ioexception.mcaddon.server.PlayerFogs.pop(player, tag);
    }

    @HostAccess.Export
    public boolean removeFog(String id, String tag) {
        ServerPlayer player = player(id);
        return player != null && at.ioexception.mcaddon.server.PlayerFogs.remove(player, tag);
    }

    @HostAccess.Export
    public boolean replaceFogs(String id, String fogIdsJson, String tag) {
        ServerPlayer player = player(id);
        if (player == null) return false;

        JsonElement parsed = JsonParser.parseString(fogIdsJson);
        if (!parsed.isJsonArray()) return false;
        return at.ioexception.mcaddon.server.PlayerFogs.replace(player,
            parsed.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList(), tag);
    }

    @HostAccess.Export
    public String[] fogs(String id) {
        ServerPlayer player = player(id);
        return player == null ? new String[0]
            : at.ioexception.mcaddon.server.PlayerFogs.fogs(player).toArray(String[]::new);
    }

    @HostAccess.Export
    public String[] fogTags(String id) {
        ServerPlayer player = player(id);
        return player == null ? new String[0]
            : at.ioexception.mcaddon.server.PlayerFogs.tags(player).toArray(String[]::new);
    }

    // Heads-up display

    /**
     * `ScreenDisplay.setHudVisibility`, which is `/hud` for this one player, on the pieces a script
     * names by number or on every piece when it names none.
     *
     * A piece no Java client draws is reported when it is named, as `/hud` reports a line naming it.
     */
    @HostAccess.Export
    public void changeHud(String id, boolean hide, String elementsJson) {
        ServerPlayer player = player(id);
        if (player == null) return;

        List<HudElement> named = hudElements(elementsJson);
        named.stream().filter(element -> !element.drawnByJava())
            .forEach(element -> unsupported("ScreenDisplay.setHudVisibility(" + element.bedrockName() + ")"));
        List<HudElement> elements = elementsJson == null ? List.of(HudElement.values()) : named;
        if (hide) {
            PlayerHuds.hide(player, elements);
        } else {
            PlayerHuds.reset(player, elements);
        }
    }

    /** `ScreenDisplay.hideAllExcept`: every piece hidden but the ones named, which are drawn again. */
    @HostAccess.Export
    public void hideHudExcept(String id, String elementsJson) {
        ServerPlayer player = player(id);
        if (player == null) return;

        List<HudElement> kept = hudElements(elementsJson);
        PlayerHuds.reset(player, kept);
        PlayerHuds.hide(player, Arrays.stream(HudElement.values()).filter(element -> !kept.contains(element)).toList());
    }

    /** The numbers a script names each hidden piece by. */
    @HostAccess.Export
    public int[] hiddenHud(String id) {
        ServerPlayer player = player(id);
        return player == null ? new int[0]
            : PlayerHuds.hidden(player).stream().mapToInt(HudElement::scriptValue).sorted().toArray();
    }

    private static List<HudElement> hudElements(String elementsJson) {
        JsonElement parsed = elementsJson == null ? null : JsonParser.parseString(elementsJson);
        if (parsed == null || !parsed.isJsonArray()) return List.of();
        return parsed.getAsJsonArray().asList().stream()
            .map(number -> HudElement.tryFrom(number.getAsInt()))
            .filter(Objects::nonNull)
            .toList();
    }

    // Input permissions

    /** A category a script names by a number no category has is reported and reads as enabled. */
    @HostAccess.Export
    public boolean inputEnabled(String id, int category) {
        ServerPlayer player = player(id);
        InputPermission permission = InputPermission.tryFrom(category);
        if (permission == null) unsupported("InputPermissionCategory " + category);
        return player == null || permission == null || PlayerInputs.enabled(player, permission);
    }

    @HostAccess.Export
    public void setInputEnabled(String id, int category, boolean enabled) {
        ServerPlayer player = player(id);
        InputPermission permission = InputPermission.tryFrom(category);
        if (permission == null) {
            unsupported("InputPermissionCategory " + category);
        } else if (player != null) {
            PlayerInputs.set(player, permission, enabled);
        }
    }

    /** What `/aimassist` or a script set for the player, in a camera preset's `aim_assist` shape, or null. */
    @HostAccess.Export
    public String aimAssist(String id) {
        ServerPlayer player = player(id);
        AimAssistSettings settings = player == null ? null : PlayerAimAssists.of(player);
        return settings == null ? null : settings.toJson().toString();
    }

    /** Turns aim assist off for a null body. Answers why it was refused, in Bedrock's words, or null. */
    @HostAccess.Export
    public String setAimAssist(String id, String body) {
        ServerPlayer player = player(id);
        if (player == null) return "The player is not valid.";
        if (body == null) {
            PlayerAimAssists.clear(player);
            return null;
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray angle = json.getAsJsonArray("angle");
        AimAssistSettings.TargetMode mode = AimAssistSettings.TargetMode.tryFrom(Json.text(json, "target_mode", ""));
        return PlayerAimAssists.set(player, new AimAssistSettings(Json.text(json, "preset", ""),
            mode == null ? AimAssistSettings.TargetMode.ANGLE : mode, angle.get(0).getAsFloat(),
            angle.get(1).getAsFloat(), (float) Json.number(json, "distance", AimAssistSettings.DEFAULT_DISTANCE)));
    }

    /** The language the client asked for, which a 1.20.1 server is sent but keeps to itself. */
    @HostAccess.Export
    public String locale(String id) {
        ServerPlayer player = player(id);
        if (player == null) return null;

        //? if >=1.21 {
        return player.clientInformation().language();
        //?} else {
        /*return null;
        *///?}
    }

    /** The client's own render distance, which the server keeps only from 1.21 on. */
    @HostAccess.Export
    public Integer renderDistance(String id) {
        ServerPlayer player = player(id);
        if (player == null) return null;

        //? if >=1.21 {
        return player.requestedViewDistance();
        //?} else {
        /*return null;
        *///?}
    }

    // Property overrides

    /**
     * `Player.setPropertyOverrideForEntity`, or `removePropertyOverrideForEntity` when the value is
     * null. Answers why it was refused, which the script side raises as Bedrock does, or null.
     */
    @HostAccess.Export
    public String overrideProperty(String id, String entityId, String name, String valueJson) {
        ServerPlayer player = player(id);
        Entity entity = entity(entityId);
        if (player == null || entity == null) return "The entity is not valid.";

        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        ServerEntityDefinition.Property property = definition == null ? null : definition.properties().get(name);
        if (property == null) return "No property " + name + " on " + AddonEntities.identifierOf(entity.getType());
        if (!property.clientSync()) return name + " is not client_sync, so no client draws from it.";
        if (valueJson == null) {
            PropertyOverrides.remove(player, entity, name);
            return null;
        }

        JsonElement parsed = JsonParser.parseString(valueJson);
        Object value = !parsed.isJsonPrimitive() ? null
            : parsed.getAsJsonPrimitive().isBoolean() ? (Object) parsed.getAsBoolean()
            : parsed.getAsJsonPrimitive().isNumber() ? (Object) parsed.getAsDouble()
            : parsed.getAsString();
        String refusal = property.refusal(value);
        if (refusal == null) PropertyOverrides.set(player, entity, name, value);
        return refusal;
    }

    /** `Player.clearPropertyOverridesForEntity`, false where the entity is not valid. */
    @HostAccess.Export
    public boolean clearPropertyOverrides(String id, String entityId) {
        ServerPlayer player = player(id);
        Entity entity = entity(entityId);
        if (player == null || entity == null) return false;

        PropertyOverrides.clear(player, entity);
        return true;
    }

    // Helpers, not exported

    private ServerPlayer player(String id) {
        return entity(id) instanceof ServerPlayer player ? player : null;
    }

    private static Vec3 vector(JsonObject point) {
        return point == null ? null
            : new Vec3(Json.number(point, "x", 0), Json.number(point, "y", 0), Json.number(point, "z", 0));
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
