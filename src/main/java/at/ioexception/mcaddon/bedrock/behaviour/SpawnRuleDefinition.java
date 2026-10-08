package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How a behaviour pack says its entity spawns naturally.
 *
 * Bedrock describes a spawn as a list of alternative condition blocks, and a spawn is allowed when
 * any one of them holds. Java splits the same question in two: the weight and the group size belong
 * to a biome's spawn list, built once at world load, and everything else is a test run against the
 * position the game is trying. Both halves are kept here, and a condition component with no Java
 * answer is counted so `/mcaddon entities` names it.
 */
public record SpawnRuleDefinition(String identifier, String category, List<Condition> conditions) {

    /** How far a density limit counts its own kind. Bedrock measures the cluster around the spawn. */
    private static final int DENSITY_RADIUS = 16;

    /** The condition components this mod acts on. Anything else a pack writes is counted. */
    private static final Set<String> READ = Set.of(
        "minecraft:weight", "minecraft:herd", "minecraft:biome_filter", "minecraft:brightness_filter",
        "minecraft:height_filter", "minecraft:difficulty_filter", "minecraft:distance_filter",
        "minecraft:world_age_filter", "minecraft:delay_filter", "minecraft:density_limit",
        "minecraft:spawns_on_surface", "minecraft:spawns_underground", "minecraft:spawns_underwater",
        "minecraft:spawns_lava", "minecraft:disallow_spawns_in_bubble",
        "minecraft:spawns_on_block_filter", "minecraft:spawns_on_block_prevented_filter",
        "minecraft:spawns_above_block_filter", "minecraft:spawn_event", "minecraft:is_persistent");

    /** `minecraft:delay_filter` is a cooldown shared by every rule writing the same identifier. */
    private static final Map<String, Long> DELAYS = new ConcurrentHashMap<>();

    public static List<SpawnRuleDefinition> readAll(BedrockPack pack) {
        List<SpawnRuleDefinition> rules = new ArrayList<>();
        for (Path file : pack.list("spawn_rules", ".json")) {
            try {
                SpawnRuleDefinition rule = read(Json.parseObject(file));
                if (rule != null) rules.add(rule);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return rules;
    }

    /**
     * The population each entity a pack writes a spawn rule for counts against, by identifier.
     *
     * Java fixes the category on the entity type, which is built before the rules are read in full,
     * and counts an entity against the cap of its type's category whichever spawn list placed it.
     */
    public static Map<String, MobCategory> populations(BedrockPack pack) {
        Map<String, MobCategory> found = new LinkedHashMap<>();
        for (Path file : pack.list("spawn_rules", ".json")) {
            try {
                JsonObject description = description(Json.parseObject(file));
                String identifier = description == null ? null : Json.text(description, "identifier", null);
                if (identifier != null) {
                    found.put(identifier, population(Json.text(description, "population_control", "animal")));
                }
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    /** The population the entity counts against, which Java calls its spawn category. */
    public MobCategory population() {
        return population(category);
    }

    /** Bedrock's population control names line up with Java's spawn categories. */
    private static MobCategory population(String control) {
        return switch (control) {
            case "monster" -> MobCategory.MONSTER;
            case "water_animal" -> MobCategory.WATER_CREATURE;
            case "ambient" -> MobCategory.AMBIENT;
            default -> MobCategory.CREATURE;
        };
    }

    private static JsonObject description(JsonObject root) {
        JsonObject body = root.has("minecraft:spawn_rules") && root.get("minecraft:spawn_rules").isJsonObject()
            ? root.getAsJsonObject("minecraft:spawn_rules") : null;
        return body == null ? null : body.getAsJsonObject("description");
    }

    private static SpawnRuleDefinition read(JsonObject root) {
        JsonObject description = description(root);
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        JsonObject body = root.getAsJsonObject("minecraft:spawn_rules");

        // A rule listing no conditions is how a pack says its entity has no natural spawn at all,
        // which is what Bedrock's own blaze, cat and pillager files say.
        List<Condition> conditions = new ArrayList<>();
        for (JsonElement element : Json.list(body, "conditions")) {
            if (element.isJsonObject()) conditions.add(Condition.read(element.getAsJsonObject()));
        }

        return new SpawnRuleDefinition(identifier,
            Json.text(description, "population_control", "animal"), List.copyOf(conditions));
    }

    /**
     * One of the alternatives a rule offers.
     *
     * The numbers Java needs when a biome's spawn list is built are fields. The rest is read back
     * out of the pack's own JSON at the position the game is testing, where the light level, the
     * block below and the difficulty are known, and where a map lookup costs far less than the
     * block state and lighting reads around it.
     */
    public record Condition(JsonObject source, int weight, int minimumHerd, int maximumHerd,
        BiomeMatch biomes, String world, List<Block> onBlocks, List<Block> notOnBlocks,
        List<Block> aboveBlocks, String spawnEvent, boolean persistent) {

        static Condition read(JsonObject source) {
            count(source);

            JsonObject weight = source.getAsJsonObject("minecraft:weight");
            JsonObject herd = firstObject(source, "minecraft:herd");
            JsonObject spawnEvent = source.getAsJsonObject("minecraft:spawn_event");
            JsonObject above = source.getAsJsonObject("minecraft:spawns_above_block_filter");
            JsonElement biomes = source.get("minecraft:biome_filter");

            return new Condition(source,
                weight == null ? 10 : integer(weight, "default", 10),
                herd == null ? 1 : Math.max(1, integer(herd, "min_size", 1)),
                herd == null ? 4 : Math.max(1, integer(herd, "max_size", 4)),
                BiomeMatch.parse(biomes),
                source.has("minecraft:spawns_lava") ? "nether" : BiomeMatch.world(biomes),
                blocks(source.get("minecraft:spawns_on_block_filter")),
                blocks(source.get("minecraft:spawns_on_block_prevented_filter")),
                above == null ? List.of() : blocks(above.get("blocks")),
                spawnEvent == null ? null : Json.text(spawnEvent, "event", null),
                source.has("minecraft:is_persistent"));
        }

        /** Whether the pack allows its entity at this spot. */
        public boolean allows(Level level, BlockPos pos, EntityType<?> type) {
            return height(pos) && difficulty(level) && worldAge(level) && placement(level, pos)
                && light(level, pos) && blocksUnder(level, pos) && distance(level, pos)
                && density(level, pos, type) && delay(level);
        }

        private boolean height(BlockPos pos) {
            JsonObject filter = source.getAsJsonObject("minecraft:height_filter");
            return filter == null
                || (pos.getY() >= integer(filter, "min", 24) && pos.getY() <= integer(filter, "max", 128));
        }

        private boolean difficulty(Level level) {
            JsonObject filter = source.getAsJsonObject("minecraft:difficulty_filter");
            if (filter == null) return true;

            int here = level.getDifficulty().ordinal();
            return here >= rank(Json.text(filter, "min", "peaceful"))
                && here <= rank(Json.text(filter, "max", "hard"));
        }

        private boolean worldAge(Level level) {
            JsonObject filter = source.getAsJsonObject("minecraft:world_age_filter");
            if (filter == null) return true;

            long age = level.getGameTime();
            return age >= integer(filter, "min", 0) && (!filter.has("max") || age <= integer(filter, "max", 0));
        }

        /**
         * Where in the world the pack allows the spawn.
         *
         * Surface and underground are read as whether the sky is visible overhead, which is the
         * nearest thing Java measures per block. Naming both, as most of Bedrock's own monsters do,
         * asks for neither and leaves the height and light filters to decide.
         */
        private boolean placement(Level level, BlockPos pos) {
            boolean surface = source.has("minecraft:spawns_on_surface");
            if (surface != source.has("minecraft:spawns_underground")
                && level.canSeeSky(pos) != surface) {
                return false;
            }
            if (source.has("minecraft:spawns_underwater") && !level.getFluidState(pos).is(FluidTags.WATER)) {
                return false;
            }
            if (source.has("minecraft:spawns_lava") && !level.getFluidState(pos).is(FluidTags.LAVA)) {
                return false;
            }
            return !source.has("minecraft:disallow_spawns_in_bubble")
                || !level.getBlockState(pos).is(Blocks.BUBBLE_COLUMN);
        }

        /** `adjust_for_weather` is what the game's own darkening under rain already answers. */
        private boolean light(Level level, BlockPos pos) {
            JsonObject filter = source.getAsJsonObject("minecraft:brightness_filter");
            if (filter == null) return true;

            boolean weather = filter.has("adjust_for_weather")
                && filter.get("adjust_for_weather").getAsBoolean();
            int light = weather ? level.getMaxLocalRawBrightness(pos) : level.getRawBrightness(pos, 0);
            return light >= integer(filter, "min", 0) && light <= integer(filter, "max", 15);
        }

        private boolean blocksUnder(Level level, BlockPos pos) {
            BlockPos below = pos.below();
            if (!onBlocks.isEmpty() && !standsOn(level, below, onBlocks)) return false;
            if (standsOn(level, below, notOnBlocks)) return false;
            if (aboveBlocks.isEmpty()) return true;

            JsonObject filter = source.getAsJsonObject("minecraft:spawns_above_block_filter");
            int reach = integer(filter, "distance", 1);
            for (int i = 1; i <= reach; i++) {
                if (standsOn(level, pos.below(i), aboveBlocks)) return true;
            }
            return false;
        }

        private static boolean standsOn(Level level, BlockPos pos, List<Block> blocks) {
            if (blocks.isEmpty()) return false;
            BlockState state = level.getBlockState(pos);
            return blocks.stream().anyMatch(state::is);
        }

        private boolean distance(Level level, BlockPos pos) {
            JsonObject filter = source.getAsJsonObject("minecraft:distance_filter");
            if (filter == null) return true;

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            Player player = level.getNearestPlayer(x, y, z, -1, false);
            if (player == null) return false;

            double away = Math.sqrt(player.distanceToSqr(x, y, z));
            return away >= integer(filter, "min", 24) && away <= integer(filter, "max", 128);
        }

        /** Counting costs a box query, so it only runs for a rule that asked for a limit. */
        private boolean density(Level level, BlockPos pos, EntityType<?> type) {
            JsonObject filter = source.getAsJsonObject("minecraft:density_limit");
            if (filter == null) return true;

            int cap = integer(filter, level.canSeeSky(pos) ? "surface" : "underground", -1);
            if (cap < 0) return true;

            AABB box = new AABB(pos).inflate(DENSITY_RADIUS);
            return level.getEntitiesOfClass(Entity.class, box, other -> other.getType() == type).size() < cap;
        }

        /**
         * The gap a pack wants between two spawns of the same rule.
         *
         * Bedrock keys the gap on the identifier the filter names rather than on the entity, so two
         * rules writing the same one share it. The roll comes first: a refused attempt must not
         * start the next gap.
         */
        private boolean delay(Level level) {
            JsonObject filter = source.getAsJsonObject("minecraft:delay_filter");
            if (filter == null) return true;

            if (ThreadLocalRandom.current().nextInt(100) >= integer(filter, "spawn_chance", 100)) {
                return false;
            }

            String key = Json.text(filter, "identifier", "");
            long now = level.getGameTime();
            if (now < DELAYS.getOrDefault(key, 0L)) return false;

            int low = integer(filter, "min", 0);
            int high = Math.max(low, integer(filter, "max", low));
            DELAYS.put(key, now + 20L * (low + ThreadLocalRandom.current().nextInt(high - low + 1)));
            return true;
        }

        private static int rank(String difficulty) {
            return switch (difficulty.toLowerCase(Locale.ROOT)) {
                case "easy" -> 1;
                case "normal" -> 2;
                case "hard" -> 3;
                default -> 0;
            };
        }

        /** Bedrock allows a single object where a list of them is meant, `herd` included. */
        private static JsonObject firstObject(JsonObject source, String key) {
            for (JsonElement element : Json.list(source, key)) {
                if (element.isJsonObject()) return element.getAsJsonObject();
            }
            return null;
        }

        private static void count(JsonObject source) {
            for (String component : source.keySet()) {
                if (!READ.contains(component)) AddonEntities.unsupported("spawn rule " + component);
            }

            JsonObject herd = firstObject(source, "minecraft:herd");
            if (herd != null && (herd.has("event") || herd.has("initial_event"))) {
                AddonEntities.unsupported("spawn rule minecraft:herd events");
            }
            JsonObject weight = source.getAsJsonObject("minecraft:weight");
            if (weight != null && weight.has("rarity")) {
                AddonEntities.unsupported("spawn rule minecraft:weight rarity");
            }
        }
    }

    /** A block descriptor, or the list of them Bedrock allows in the same place. */
    private static List<Block> blocks(JsonElement element) {
        if (element == null || element.isJsonNull()) return List.of();

        List<Block> found = new ArrayList<>();
        for (JsonElement part : element.isJsonArray() ? element.getAsJsonArray().asList() : List.of(element)) {
            String name = part.isJsonPrimitive() ? part.getAsString()
                : part.isJsonObject() ? Json.text(part.getAsJsonObject(), "name", null) : null;
            if (name == null) continue;

            if (part.isJsonObject() && part.getAsJsonObject().has("states")) {
                AddonEntities.unsupported("spawn rule block states");
            }
            Block block = block(name);
            if (block != null) found.add(block);
        }
        return List.copyOf(found);
    }

    /** A block a rule names, under Java's own id or the one Bedrock spells it with. */
    private static Block block(String name) {
        Block named = AddonBlocks.named(name);
        if (named == null) AddonEntities.unsupported("spawn rule block " + name);
        return named;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
    }
}
