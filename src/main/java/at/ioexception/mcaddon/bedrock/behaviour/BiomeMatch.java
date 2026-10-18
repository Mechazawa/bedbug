package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The biome filter a spawn rule carries, answered against a Java biome id.
 *
 * Bedrock names biomes by tag and Java names them by id, and the two sets were never meant to line
 * up. A tag this file knows about matches the Java biomes carrying the same idea; a tag it does not
 * know is counted through `/mcaddon entities` and then left out of the tree, so the rest of the
 * filter still decides where the entity spawns.
 *
 * A pack's own biome is the exception: it carries the tags its pack declared, so a filter is
 * answered on those alone, the world tags included, exactly as Bedrock answers it.
 *
 * The grammar is Bedrock's own filter grammar, where a bare array is an `all_of`.
 */
public sealed interface BiomeMatch {
    /** Whether one of Java's own biomes, named by the path of its id, is allowed. */
    boolean matches(String biome);

    /** Whether a biome carrying exactly these tags is allowed. */
    boolean matchesDeclared(Collection<String> tags);

    /**
     * Whether the biome with this id is allowed: a pack's own on the tags its pack gave it, and one
     * of Java's on its id, provided it lies in the world the filter points at.
     */
    default boolean allows(String id, BooleanSupplier inWorld) {
        List<String> declared = PackBiomes.tagsOf(id);
        if (declared != null) return matchesDeclared(declared);
        return inWorld.getAsBoolean() && matches(id.substring(id.indexOf(':') + 1));
    }

    /** A tag naming a world rather than a place. The biome selector already answers those. */
    Set<String> WORLDS = Set.of("overworld", "nether", "the_end", "monster", "animal");

    Set<String> NETHER = Set.of("nether", "netherwart_forest", "warped_forest", "crimson_forest",
        "soulsand_valley", "basalt_deltas");

    /** The Java biomes Bedrock would call cold. */
    List<String> COLD = List.of("snowy", "frozen", "ice_spikes", "cold_ocean", "taiga", "grove",
        "jagged_peaks", "snowy_slopes");

    /**
     * Tags naming a climate rather than a place, which hold for every biome not on their list.
     *
     * `warm` is the one Bedrock leans on, and it is on far more biomes than it is off: reading it
     * as a list of warm places would leave out every one a later version adds.
     */
    Map<String, List<String>> NOT_BIOMES = Map.of("warm", COLD);

    /**
     * What a Bedrock biome tag means among Java's biome ids.
     *
     * A biome matches a tag when its id contains one of the strings listed against it and none of the
     * strings `EXCEPT` lists against it. Several tags are spelled differently in the two editions, and
     * a few name a group of biomes rather than one. Bedrock's own biome files decide each list: every
     * taiga carries `forest`, the flower forest does not, and of the windswept biomes only the
     * gravelly hills and the savanna are `mutated`.
     */
    Map<String, List<String>> JAVA_BIOMES = Map.ofEntries(
        Map.entry("badlands", List.of("badlands")),
        Map.entry("bamboo", List.of("bamboo")),
        Map.entry("basalt_deltas", List.of("basalt_deltas")),
        Map.entry("beach", List.of("beach")),
        Map.entry("birch", List.of("birch")),
        Map.entry("caves", List.of("caves", "deep_dark")),
        Map.entry("cherry_grove", List.of("cherry_grove")),
        Map.entry("cold", COLD),
        Map.entry("crimson_forest", List.of("crimson_forest")),
        Map.entry("deep", List.of("deep")),
        Map.entry("deep_dark", List.of("deep_dark")),
        Map.entry("desert", List.of("desert")),
        Map.entry("dripstone_caves", List.of("dripstone_caves")),
        Map.entry("edge", List.of("sparse_jungle")),
        Map.entry("extreme_hills", List.of("windswept_hills", "windswept_gravelly_hills")),
        Map.entry("flower_forest", List.of("flower_forest")),
        Map.entry("forest", List.of("forest", "taiga")),
        Map.entry("frozen", List.of("frozen", "snowy", "ice_spikes")),
        Map.entry("frozen_peaks", List.of("frozen_peaks")),
        Map.entry("grove", List.of("grove")),
        Map.entry("hills", List.of("windswept", "hills")),
        Map.entry("ice", List.of("ice_spikes", "frozen", "snowy")),
        Map.entry("ice_plains", List.of("snowy_plains")),
        Map.entry("jagged_peaks", List.of("jagged_peaks")),
        Map.entry("jungle", List.of("jungle")),
        Map.entry("lukewarm", List.of("lukewarm")),
        Map.entry("lush_caves", List.of("lush_caves")),
        Map.entry("mangrove_swamp", List.of("mangrove_swamp")),
        Map.entry("meadow", List.of("meadow")),
        Map.entry("mega", List.of("old_growth_pine_taiga", "old_growth_spruce_taiga")),
        Map.entry("mesa", List.of("badlands")),
        Map.entry("mooshroom_island", List.of("mushroom_fields")),
        Map.entry("mountain", List.of("peaks", "slopes", "meadow", "grove", "windswept_hills")),
        Map.entry("mutated", List.of("eroded", "windswept_gravelly_hills", "windswept_savanna", "ice_spikes",
            "flower_forest", "old_growth_birch_forest", "old_growth_spruce_taiga", "sunflower")),
        Map.entry("netherwart_forest", List.of("crimson_forest")),
        Map.entry("ocean", List.of("ocean")),
        Map.entry("plains", List.of("plains")),
        Map.entry("plateau", List.of("plateau", "wooded_badlands")),
        Map.entry("river", List.of("river")),
        Map.entry("roofed", List.of("dark_forest")),
        Map.entry("savanna", List.of("savanna")),
        Map.entry("snowy_slopes", List.of("snowy_slopes")),
        Map.entry("soulsand_valley", List.of("soul_sand_valley")),
        Map.entry("stone", List.of("stony", "windswept_gravelly")),
        Map.entry("stony_peaks", List.of("stony_peaks")),
        Map.entry("sunflower_plains", List.of("sunflower_plains")),
        Map.entry("swamp", List.of("swamp")),
        Map.entry("taiga", List.of("taiga")),
        Map.entry("warped_forest", List.of("warped_forest")));

    /** Java biomes whose id carries a tag's string and which Bedrock leaves that tag off. */
    Map<String, List<String>> EXCEPT = Map.of("forest", List.of("flower_forest"));

    record All(List<BiomeMatch> parts) implements BiomeMatch {
        @Override
        public boolean matches(String biome) {
            return parts.stream().allMatch(part -> part.matches(biome));
        }

        @Override
        public boolean matchesDeclared(Collection<String> tags) {
            return parts.stream().allMatch(part -> part.matchesDeclared(tags));
        }
    }

    record Any(List<BiomeMatch> parts) implements BiomeMatch {
        @Override
        public boolean matches(String biome) {
            return parts.stream().anyMatch(part -> part.matches(biome));
        }

        @Override
        public boolean matchesDeclared(Collection<String> tags) {
            return parts.stream().anyMatch(part -> part.matchesDeclared(tags));
        }
    }

    record None(List<BiomeMatch> parts) implements BiomeMatch {
        @Override
        public boolean matches(String biome) {
            return parts.stream().noneMatch(part -> part.matches(biome));
        }

        @Override
        public boolean matchesDeclared(Collection<String> tags) {
            return parts.stream().noneMatch(part -> part.matchesDeclared(tags));
        }
    }

    /**
     * One `has_biome_tag` test, with the `!=` and `not` operators folded into `negated`.
     *
     * A tag only a pack's own biomes carry is carried by none of Java's.
     */
    record Tag(String name, boolean negated) implements BiomeMatch {
        @Override
        public boolean matches(String biome) {
            if (WORLDS.contains(name)) return !negated;

            List<String> excluded = NOT_BIOMES.get(name);
            if (excluded != null) return negated == excluded.stream().anyMatch(biome::contains);

            List<String> java = JAVA_BIOMES.get(name);
            boolean carries = java != null && java.stream().anyMatch(biome::contains)
                && EXCEPT.getOrDefault(name, List.of()).stream().noneMatch(biome::contains);
            return negated != carries;
        }

        @Override
        public boolean matchesDeclared(Collection<String> tags) {
            return negated != tags.contains(name);
        }
    }

    /** What an empty filter, and a tag with no Java meaning, become. */
    record Always() implements BiomeMatch {
        @Override
        public boolean matches(String biome) {
            return true;
        }

        @Override
        public boolean matchesDeclared(Collection<String> tags) {
            return true;
        }
    }

    static BiomeMatch parse(JsonElement json) {
        if (json == null || json.isJsonNull()) return new Always();
        if (json.isJsonArray()) return new All(parts(json));
        if (!json.isJsonObject()) return new Always();

        JsonObject body = json.getAsJsonObject();
        if (body.has("all_of")) return new All(parts(body.get("all_of")));
        if (body.has("any_of")) return new Any(parts(body.get("any_of")));
        if (body.has("none_of")) return new None(parts(body.get("none_of")));
        if (!body.has("test")) return new Always();

        String test = body.get("test").getAsString();
        if (!test.equals("has_biome_tag")) {
            AddonEntities.unsupported("spawn rule biome test " + test);
            return new Always();
        }

        String tag = body.has("value") ? body.get("value").getAsString() : "";
        if (!known(tag)) {
            AddonEntities.unsupported("spawn rule biome tag " + tag);
            return new Always();
        }
        return new Tag(tag, negated(body));
    }

    /** Whether a tag means anything here, which a tag no Java biome and no pack's biome carries does not. */
    static boolean known(String tag) {
        return WORLDS.contains(tag) || JAVA_BIOMES.containsKey(tag) || NOT_BIOMES.containsKey(tag)
            || PackBiomes.anyCarries(tag);
    }

    /** Which of the three worlds the filter points at, from the first tag naming one. */
    static String world(JsonElement json) {
        if (json == null || json.isJsonNull()) return "overworld";
        if (json.isJsonArray()) {
            for (JsonElement part : json.getAsJsonArray()) {
                String found = world(part);
                if (!found.equals("overworld")) return found;
            }
            return "overworld";
        }
        if (!json.isJsonObject()) return "overworld";

        JsonObject body = json.getAsJsonObject();
        if (body.has("none_of")) return "overworld";
        if (body.has("all_of")) return world(body.get("all_of"));
        if (body.has("any_of")) return world(body.get("any_of"));
        if (negated(body) || !body.has("value")) return "overworld";

        String tag = body.get("value").getAsString();
        if (NETHER.contains(tag)) return "nether";
        return tag.equals("the_end") ? "end" : "overworld";
    }

    private static boolean negated(JsonObject body) {
        String operator = body.has("operator") ? body.get("operator").getAsString() : "==";
        return operator.equals("!=") || operator.equals("not");
    }

    private static List<BiomeMatch> parts(JsonElement json) {
        List<BiomeMatch> parsed = new ArrayList<>();
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(part -> parsed.add(parse(part)));
        } else {
            parsed.add(parse(json));
        }
        return parsed;
    }
}
