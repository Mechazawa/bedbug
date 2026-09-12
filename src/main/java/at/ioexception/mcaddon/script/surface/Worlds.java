package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptBridge;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonArray;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.graalvm.polyglot.HostAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What `Dimension` and `World` answer with.
 *
 * A dimension is its id and a position is three numbers, so nothing here holds a handle to a level
 * between calls. Lists of block positions come back as a flat array of x, y, z triples and anything
 * with named fields comes back as JSON text, because a Java map reaches the guest as a host object
 * with no JavaScript members on it.
 */
public final class Worlds extends Surface {
    /** Bedrock's own ceiling on one fill, which also keeps a careless volume off the tick. */
    private static final int BLOCK_LIMIT = 32768;

    /** Biomes are stored per four blocks, so sampling closer than that reads the same value again. */
    private static final int BIOME_STEP = 4;

    /** Past this the traversal has left anything a script has a use for, loaded or not. */
    private static final double MAX_RAY = 512;

    /** A script event whose listener sends another has to stop somewhere. */
    private static final int MAX_EVENT_DEPTH = 8;

    private int eventDepth;

    public Worlds(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    // Dimension

    /**
     * Blast radius and whether it burns and breaks, which is all the simple overload carries.
     *
     * `allowUnderwater` names a damage calculator Java only builds internally, so a pack asking for
     * it is told rather than quietly given a dry explosion.
     */
    @HostAccess.Export
    public boolean createExplosion(String dimension, double x, double y, double z, double radius,
        String optionsJson) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        JsonObject options = parse(optionsJson);
        if (flag(options, "allowUnderwater")) unsupported("ExplosionOptions.allowUnderwater");

        String source = text(options, "source");
        boolean breaks = !options.has("breaksBlocks") || flag(options, "breaksBlocks");
        level.explode(source == null ? null : entity(source), x, y, z, (float) radius,
            flag(options, "causesFire"),
            breaks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        return true;
    }

    /** The positions that were filled, which is what the pack's `ListBlockVolume` is built from. */
    @HostAccess.Export
    public int[] fillBlocks(String dimension, int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
        String type, String optionsJson) {
        ServerLevel level = level(dimension);
        BlockState placed = blockState(type);
        if (level == null || placed == null) return new int[0];

        JsonObject options = parse(optionsJson);
        Predicate<BlockState> matches = blockFilter(object(options, "blockFilter"));
        boolean ignoreBounds = flag(options, "ignoreChunkBoundErrors");

        List<BlockPos> filled = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(fromX, fromY, fromZ, toX, toY, toZ)) {
            if (filled.size() >= BLOCK_LIMIT) break;
            if (!level.hasChunkAt(position)) {
                if (ignoreBounds) continue;
                break;
            }
            if (matches.test(level.getBlockState(position))
                && level.setBlockAndUpdate(position, placed)) {
                filled.add(position.immutable());
            }
        }
        return triples(filled);
    }

    @HostAccess.Export
    public int[] getBlocks(String dimension, int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
        String filterJson, boolean allowUnloadedChunks) {
        ServerLevel level = level(dimension);
        if (level == null) return new int[0];

        Predicate<BlockState> matches = blockFilter(parse(filterJson));
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(fromX, fromY, fromZ, toX, toY, toZ)) {
            if (found.size() >= BLOCK_LIMIT) break;
            if (!level.hasChunkAt(position)) {
                if (allowUnloadedChunks) continue;
                break;
            }
            if (matches.test(level.getBlockState(position))) found.add(position.immutable());
        }
        return triples(found);
    }

    @HostAccess.Export
    public boolean containsBlock(String dimension, int fromX, int fromY, int fromZ, int toX, int toY,
        int toZ, String filterJson, boolean allowUnloadedChunks) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        Predicate<BlockState> matches = blockFilter(parse(filterJson));
        for (BlockPos position : BlockPos.betweenClosed(fromX, fromY, fromZ, toX, toY, toZ)) {
            if (!level.hasChunkAt(position)) {
                if (allowUnloadedChunks) continue;
                return false;
            }
            if (matches.test(level.getBlockState(position))) return true;
        }
        return false;
    }

    /** The block a ray meets, with the face it entered through and where on that face it landed. */
    @HostAccess.Export
    public String blockFromRay(String dimension, double x, double y, double z,
        double towardsX, double towardsY, double towardsZ, String optionsJson) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        JsonObject options = parse(optionsJson);
        Double reach = number(options, "maxDistance");
        RayHit hit = rayCast(level, new Vec3(x, y, z), new Vec3(towardsX, towardsY, towardsZ),
            reach == null ? 16 : reach, rayStopper(options));
        if (hit == null) return null;

        JsonObject found = new JsonObject();
        found.addProperty("x", hit.x());
        found.addProperty("y", hit.y());
        found.addProperty("z", hit.z());
        found.addProperty("face", hit.face());
        found.addProperty("faceX", hit.point().x - hit.x());
        found.addProperty("faceY", hit.point().y - hit.y());
        found.addProperty("faceZ", hit.point().z - hit.z());
        return found.toString();
    }

    /** Every entity the ray passes through, nearest first, as their ids and how far away they are. */
    @HostAccess.Export
    public String entitiesFromRay(String dimension, double x, double y, double z,
        double towardsX, double towardsY, double towardsZ, String optionsJson) {
        ServerLevel level = level(dimension);
        JsonArray hits = new JsonArray();
        Vec3 direction = new Vec3(towardsX, towardsY, towardsZ);
        if (level == null || direction.lengthSqr() < 1.0E-7) return hits.toString();

        JsonObject options = parse(optionsJson);
        Double reach = number(options, "maxDistance");
        double range = Math.min(reach == null ? 16 : reach, MAX_RAY);

        Vec3 from = new Vec3(x, y, z);
        Vec3 to = from.add(direction.normalize().scale(range));
        double limit = range;
        if (!flag(options, "ignoreBlockCollision")) {
            RayHit blocked = rayCast(level, from, direction, range, rayStopper(options));
            if (blocked != null) limit = blocked.distance();
        }

        List<Entity> candidates = level.getEntities((Entity) null, new AABB(from, to).inflate(2),
            candidate -> true);
        for (Entity candidate : candidates) {
            Optional<Vec3> touched = candidate.getBoundingBox().clip(from, to);
            if (touched.isEmpty()) continue;

            double distance = from.distanceTo(touched.get());
            if (distance > limit) continue;

            JsonObject hit = new JsonObject();
            hit.addProperty("entity", candidate.getStringUUID());
            hit.addProperty("distance", distance);
            hits.add(hit);
        }
        return sortedByDistance(hits).toString();
    }

    /** The first block above the given one that stops a ray, which is what Bedrock calls valid. */
    @HostAccess.Export
    public int[] blockAbove(String dimension, int x, int y, int z, String optionsJson) {
        return blockAlong(dimension, x, y, z, 1, optionsJson);
    }

    @HostAccess.Export
    public int[] blockBelow(String dimension, int x, int y, int z, String optionsJson) {
        return blockAlong(dimension, x, y, z, -1, optionsJson);
    }

    private int[] blockAlong(String dimension, int x, int y, int z, int step, String optionsJson) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        JsonObject options = parse(optionsJson);
        Double reach = number(options, "maxDistance");
        Predicate<BlockState> stops = rayStopper(options);
        int[] range = Platform.heightRange(level);
        int furthest = reach == null ? range[1] - range[0] : (int) Math.ceil(reach);

        for (int travelled = 1; travelled <= furthest; travelled++) {
            int height = y + step * travelled;
            if (height < range[0] || height >= range[1]) return null;

            BlockPos position = new BlockPos(x, height, z);
            if (!level.hasChunkAt(position)) return null;
            if (stops.test(level.getBlockState(position))) return new int[] {x, height, z};
        }
        return null;
    }

    /** The highest block at or below `from`, or null when the column is empty down to bedrock. */
    @HostAccess.Export
    public Integer topmostBlockY(String dimension, int x, int z, int from) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        int[] range = Platform.heightRange(level);
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int start = Math.min(from, surface);
        for (int height = start; height >= range[0]; height--) {
            if (!level.getBlockState(new BlockPos(x, height, z)).isAir()) return height;
        }
        return null;
    }

    @HostAccess.Export
    public void setWeather(String dimension, String type, int duration) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        String java = switch (type == null ? "" : type) {
            case "Clear" -> "clear";
            case "Rain" -> "rain";
            case "Thunder" -> "thunder";
            default -> null;
        };
        if (java == null) {
            unsupported("WeatherType." + type);
            return;
        }
        run(level, duration > 0 ? "weather " + java + " " + duration : "weather " + java);
    }

    @HostAccess.Export
    public String weather(String dimension) {
        ServerLevel level = level(dimension);
        if (level == null) return "Clear";
        if (level.isThundering()) return "Thunder";
        return level.isRaining() ? "Rain" : "Clear";
    }

    @HostAccess.Export
    public int lightLevel(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level == null ? 0 : level.getMaxLocalRawBrightness(new BlockPos(x, y, z));
    }

    @HostAccess.Export
    public int skyLightLevel(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level == null ? 0
            : level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(new BlockPos(x, y, z));
    }

    @HostAccess.Export
    public String biome(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level == null ? null : biomeId(level.getBiome(new BlockPos(x, y, z)));
    }

    /**
     * Whether the biomes over a region answer the filter.
     *
     * A superset asks whether any wanted biome is in there; without it every biome in the region has
     * to be one of the wanted ones. An excluded biome anywhere in the region answers no either way.
     */
    @HostAccess.Export
    public boolean containsBiomes(String dimension, int fromX, int fromY, int fromZ, int toX, int toY,
        int toZ, String filterJson, boolean superset) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        JsonObject filter = parse(filterJson);
        Set<String> include = strings(filter, "includeBiomes");
        Set<String> includeTags = strings(filter, "includeTags");
        Set<String> exclude = strings(filter, "excludeBiomes");
        Set<String> excludeTags = strings(filter, "excludeTags");

        boolean nothingWanted = include.isEmpty() && includeTags.isEmpty();
        boolean anyWanted = false;
        for (BlockPos position : sampled(fromX, fromY, fromZ, toX, toY, toZ)) {
            if (!level.hasChunkAt(position)) return false;

            Holder<Biome> biome = level.getBiome(position);
            String id = biomeId(biome);
            Set<String> tags = tagNames(biome.tags().toList());
            if (exclude.contains(id) || tags.stream().anyMatch(excludeTags::contains)) return false;

            boolean matches = nothingWanted || include.contains(id)
                || !includeTags.isEmpty() && tags.containsAll(includeTags);
            if (!matches && !superset) return false;
            anyWanted = anyWanted || matches;
        }
        return anyWanted;
    }

    /**
     * Places one of the game's own configured features, through `/place feature`.
     *
     * The command takes the same arguments on every supported version where the feature registry's
     * own lookup does not, and a feature that will not sit where the pack asked reports itself by
     * failing, which is the false the pack reads.
     */
    @HostAccess.Export
    public boolean placeFeature(String dimension, String feature, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null || feature == null) return false;
        return run(level, "place feature " + feature + " " + x + " " + y + " " + z) > 0;
    }

    /** A feature rule is Bedrock's own way of deciding where a feature goes; Java has no registry. */
    @HostAccess.Export
    public boolean placeFeatureRule(String dimension, String rule, int x, int y, int z) {
        unsupported("Dimension.placeFeatureRule");
        return false;
    }

    /**
     * Spawns an entity, honouring the options a pack spawns a variant with.
     *
     * `spawnEvent` is how a pack picks which of an entity's component groups it starts in, and
     * Bedrock also lets it ride along inside the type as `namespace:type<namespace:event>`, so both
     * forms end up triggering the same event on the entity the moment it is in the world.
     */
    @HostAccess.Export
    public String spawnEntity(String dimension, String typeId, double x, double y, double z,
        String optionsJson) {
        ServerLevel level = level(dimension);
        if (level == null || typeId == null) return null;

        JsonObject options = parse(optionsJson);
        String name = typeId;
        String event = text(options, "spawnEvent");
        int opened = name.indexOf('<');
        if (opened >= 0 && name.endsWith(">")) {
            event = name.substring(opened + 1, name.length() - 1);
            name = name.substring(0, opened);
        }

        Identifier parsed = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        EntityType<?> type = parsed == null ? null
            : BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).orElse(null);
        if (type == null) return null;

        Entity spawned = Platform.createMob(type, level);
        if (spawned == null) return null;

        Double facing = number(options, "initialRotation");
        Platform.placeAt(spawned, x, y, z, facing == null ? 0 : facing.floatValue(), 0);
        if (flag(options, "initialPersistence") && spawned instanceof Mob mob) mob.setPersistenceRequired();
        if (!level.addFreshEntity(spawned)) return null;

        ScriptBridge.remember(spawned);
        if (event != null) EntityEvents.trigger(spawned, event, null, ServerHooks.state());
        return spawned.getStringUUID();
    }

    // World

    /** Bedrock capitalises its difficulty names where Java does not, so `easy` reads `Easy`. */
    @HostAccess.Export
    public String difficulty() {
        String name = server.getWorldData().getDifficulty().getSerializedName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @HostAccess.Export
    public void setDifficulty(String name) {
        Difficulty wanted = name == null ? null : Difficulty.byName(name.toLowerCase(Locale.ROOT));
        if (wanted == null) {
            unsupported("Difficulty." + name);
            return;
        }
        server.setDifficulty(wanted, true);
    }

    /** A string, because a Bedrock seed does not fit a double without losing its low bits. */
    @HostAccess.Export
    public String seed() {
        return String.valueOf(server.overworld().getSeed());
    }

    /**
     * Written through `/time set`, which moves the clock every dimension reads its day from.
     *
     * Java's game time counts ticks since the world was made and nothing can set it, so the clock a
     * pack means by absolute time is the day clock.
     */
    @HostAccess.Export
    public void setAbsoluteTime(long ticks) {
        run(server.overworld(), "time set " + Math.max(0, ticks));
    }

    @HostAccess.Export
    public void setDefaultSpawnLocation(int x, int y, int z) {
        run(server.overworld(), "setworldspawn " + x + " " + y + " " + z);
    }

    /**
     * Bedrock's tracks are sounds in the music category, which is the slider a player turns down.
     *
     * Java has no queue of its own, so a queued track starts when it is asked for rather than after
     * whatever is playing. `fade` and `loop` name a mixer Java's sound system does not expose.
     */
    @HostAccess.Export
    public void playMusic(String track, String optionsJson, boolean queued) {
        if (track == null) return;

        JsonObject options = parse(optionsJson);
        if (options.has("fade")) unsupported("MusicOptions.fade");
        if (options.has("loop")) unsupported("MusicOptions.loop");

        Double volume = number(options, "volume");
        String command = queued ? "music queue \"" + sanitised(track) + "\""
            : "music play \"" + sanitised(track) + "\" " + (volume == null ? 1 : volume);
        run(server.overworld(), command);
    }

    @HostAccess.Export
    public void stopMusic() {
        run(server.overworld(), "music stop");
    }

    /**
     * Raises `scriptEventReceive` for a script that sent one to itself or to another pack.
     *
     * Raised in place rather than through `/scriptevent`, because a Bedrock event id carries a
     * namespace and Brigadier's unquoted word stops at the colon. A listener that sends another
     * event is ordinary, so the chain is cut off once it is deeper than a pack could mean.
     */
    @HostAccess.Export
    public void sendScriptEvent(String id, String message) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || id == null) return;
        if (eventDepth >= MAX_EVENT_DEPTH) {
            unsupported("System.sendScriptEvent (" + id + ") nested past " + MAX_EVENT_DEPTH);
            return;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("message", message == null ? "" : message);
        fields.put("sourceType", "Server");

        eventDepth++;
        try {
            scripts.raiseSystemEvent("scriptEventReceive", fields);
        } finally {
            eventDepth--;
        }
    }

    // Helpers, not exported

    /** Walks the blocks a ray crosses in order and stops at the first one the test keeps. */
    private RayHit rayCast(ServerLevel level, Vec3 from, Vec3 direction, double maxDistance,
        Predicate<BlockState> stops) {
        if (direction.lengthSqr() < 1.0E-7) return null;

        Vec3 step = direction.normalize();
        double reach = Math.min(maxDistance, MAX_RAY);
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);

        Axis along = new Axis(step.x, from.x, x);
        Axis up = new Axis(step.y, from.y, y);
        Axis across = new Axis(step.z, from.z, z);

        String face = enteringFace(step);
        double travelled = 0;
        while (travelled <= reach) {
            BlockPos position = new BlockPos(x, y, z);
            if (!level.hasChunkAt(position)) return null;
            if (stops.test(level.getBlockState(position))) {
                return new RayHit(x, y, z, face, from.add(step.scale(travelled)), travelled);
            }

            if (along.next <= up.next && along.next <= across.next) {
                travelled = along.next;
                x += along.step;
                face = along.step > 0 ? "West" : "East";
                along.advance();
            } else if (up.next <= across.next) {
                travelled = up.next;
                y += up.step;
                face = up.step > 0 ? "Down" : "Up";
                up.advance();
            } else {
                travelled = across.next;
                z += across.step;
                face = across.step > 0 ? "North" : "South";
                across.advance();
            }
        }
        return null;
    }

    /** The block the ray starts inside has no crossed face, so the one it came from is reported. */
    private static String enteringFace(Vec3 step) {
        double x = Math.abs(step.x);
        double y = Math.abs(step.y);
        double z = Math.abs(step.z);
        if (y >= x && y >= z) return step.y > 0 ? "Down" : "Up";
        if (x >= z) return step.x > 0 ? "West" : "East";
        return step.z > 0 ? "North" : "South";
    }

    /** One axis of the traversal: how far to the next block boundary, and how far between them. */
    private static final class Axis {
        private final int step;
        private final double between;
        private double next;

        private Axis(double direction, double origin, int block) {
            step = direction > 0 ? 1 : direction < 0 ? -1 : 0;
            between = direction == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction);
            next = direction == 0 ? Double.POSITIVE_INFINITY
                : (direction > 0 ? block + 1 - origin : origin - block) * between;
        }

        private void advance() {
            next += between;
        }
    }

    private record RayHit(int x, int y, int z, String face, Vec3 point, double distance) {
    }

    /**
     * Whether a block stops a ray.
     *
     * Air never does, a liquid only when the pack asked for liquids, and something a player walks
     * through only when the pack asked for passable blocks. A waterlogged block still stops it,
     * which is why the liquid test also asks whether the block itself is passable.
     */
    private Predicate<BlockState> rayStopper(JsonObject options) {
        Predicate<BlockState> allowed = blockFilter(options);
        boolean liquids = flag(options, "includeLiquidBlocks");
        boolean passable = flag(options, "includePassableBlocks");

        return state -> {
            if (state.isAir()) return false;
            if (!state.getFluidState().isEmpty() && !state.blocksMotion()) {
                return liquids && allowed.test(state);
            }
            if (!state.blocksMotion() && !passable) return false;
            return allowed.test(state);
        };
    }

    /**
     * A `BlockFilter`, as one test.
     *
     * With nothing to include, everything the exclusions leave is kept; name one thing to include
     * and a block has to match it. A permutation is measured by its type, because the states a
     * permutation carries are not what a filter selects on here.
     */
    private Predicate<BlockState> blockFilter(JsonObject filter) {
        if (filter == null) return state -> true;

        Set<String> include = new LinkedHashSet<>(strings(filter, "includeTypes"));
        Set<String> exclude = new LinkedHashSet<>(strings(filter, "excludeTypes"));
        include.addAll(permutationTypes(filter, "includePermutations"));
        exclude.addAll(permutationTypes(filter, "excludePermutations"));
        Set<String> includeTags = strings(filter, "includeTags");
        Set<String> excludeTags = strings(filter, "excludeTags");

        if (include.isEmpty() && exclude.isEmpty() && includeTags.isEmpty() && excludeTags.isEmpty()) {
            return state -> true;
        }

        return state -> {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            Set<String> tags = tagNames(state.getBlock().builtInRegistryHolder().tags().toList());
            if (exclude.contains(id) || tags.stream().anyMatch(excludeTags::contains)) return false;
            if (include.isEmpty() && includeTags.isEmpty()) return true;
            return include.contains(id) || tags.stream().anyMatch(includeTags::contains);
        };
    }

    /** A pack writes a block tag without a namespace, so both spellings of a Java tag are offered. */
    private static <T> Set<String> tagNames(List<TagKey<T>> tags) {
        Set<String> names = new LinkedHashSet<>();
        for (TagKey<T> tag : tags) {
            names.add(tag.location().toString());
            names.add(tag.location().getPath());
        }
        return names;
    }

    private static Set<String> permutationTypes(JsonObject filter, String key) {
        if (!filter.has(key) || !filter.get(key).isJsonArray()) return Set.of();

        Set<String> types = new LinkedHashSet<>();
        for (JsonElement element : filter.getAsJsonArray(key)) {
            if (!element.isJsonObject()) continue;
            JsonObject type = object(element.getAsJsonObject(), "type");
            if (type != null && type.has("id")) types.add(type.get("id").getAsString());
        }
        return types;
    }

    private static String biomeId(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> Platform.idOf(key).toString()).orElse("minecraft:plains");
    }

    private static BlockState blockState(String type) {
        Identifier parsed = type == null ? null : Identifier.tryParse(type);
        return parsed == null ? null : BuiltInRegistries.BLOCK.getOptional(parsed)
            .map(net.minecraft.world.level.block.Block::defaultBlockState)
            .orElse(null);
    }

    /** The corners plus a point every four blocks, which is as fine as the biome storage gets. */
    private static List<BlockPos> sampled(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        List<BlockPos> points = new ArrayList<>();
        for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x += BIOME_STEP) {
            for (int y = Math.min(fromY, toY); y <= Math.max(fromY, toY); y += BIOME_STEP) {
                for (int z = Math.min(fromZ, toZ); z <= Math.max(fromZ, toZ); z += BIOME_STEP) {
                    points.add(new BlockPos(x, y, z));
                }
            }
        }
        points.add(new BlockPos(Math.max(fromX, toX), Math.max(fromY, toY), Math.max(fromZ, toZ)));
        return points;
    }

    private static int[] triples(List<BlockPos> positions) {
        int[] flat = new int[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            flat[i * 3] = positions.get(i).getX();
            flat[i * 3 + 1] = positions.get(i).getY();
            flat[i * 3 + 2] = positions.get(i).getZ();
        }
        return flat;
    }

    private static JsonArray sortedByDistance(JsonArray hits) {
        List<JsonElement> ordered = new ArrayList<>(hits.asList());
        ordered.sort(Comparator.comparingDouble(
            hit -> hit.getAsJsonObject().get("distance").getAsDouble()));

        JsonArray sorted = new JsonArray();
        ordered.forEach(sorted::add);
        return sorted;
    }

    /** A track name reaches a quoted command argument, so anything that could close it is dropped. */
    private static String sanitised(String track) {
        return track.replaceAll("[^A-Za-z0-9_.:/-]", "");
    }

    /** Zero when the command did not parse, which is the same nothing-happened a pack reads. */
    private int run(ServerLevel level, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command,
                server.createCommandSourceStack().withLevel(level).withSuppressedOutput());
        } catch (CommandSyntaxException ignored) {
            return 0;
        }
    }

    // Reading an option bag a script passed as JSON. A key the pack left out is absent, not null.

    /**
     * The options a pack passed, or none when it passed something that is not an object.
     *
     * What arrives here was written by a pack, so it has to be read the way every other pack-authored
     * value is: a malformed one means the pack asked for nothing in particular, not that the tick it
     * was called in ends.
     */
    private static JsonObject parse(String json) {
        if (json == null || json.isBlank()) return new JsonObject();

        try {
            JsonElement parsed = Json.parse(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException unreadable) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsString() : null;
    }

    private static Double number(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsDouble() : null;
    }

    private static boolean flag(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() && options.get(key).getAsBoolean();
    }

    private static JsonObject object(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonObject() ? options.getAsJsonObject(key) : null;
    }

    private static Set<String> strings(JsonObject options, String key) {
        if (options == null || !options.has(key) || !options.get(key).isJsonArray()) return Set.of();

        Set<String> values = new LinkedHashSet<>();
        options.getAsJsonArray(key).forEach(element -> values.add(element.getAsString()));
        return values;
    }
}
