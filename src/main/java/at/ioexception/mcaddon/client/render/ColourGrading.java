package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.grading.ColourGrade;
import at.ioexception.mcaddon.bedrock.grading.GradeUniforms;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which grade each biome takes, and the grade the frame is drawn with where the camera stands.
 *
 * A biome takes the collection its client biome names through `minecraft:color_grading_identifier`.
 * Bedrock's own client biomes name one each, so a pack shipping a collection under one of those
 * identifiers grades the game's biomes that name it; {@link #VANILLA} is that naming, read from
 * Mojang's resource pack and carried onto Java's spelling of each biome. A biome named by neither takes
 * the collection in a pack's `color_grading/color_grading.json`. An identifier no installed pack
 * defines is one of Bedrock's own collections, whose look is Java's own frame, so it grades nothing.
 *
 * Bedrock blends the grade by where the camera is, so a frame near the edge of a biome is graded
 * partly by its neighbour. The blend here weighs the biomes on a six by six by six lattice of quarter
 * chunks around the camera with the same falling weights Java blends its fog colour with.
 */
public final class ColourGrading {
    /** Bedrock's naming of its own biomes' grades, by the Java biome each one is. */
    static final Map<String, List<String>> VANILLA = Map.ofEntries(
        Map.entry("minecraft:cold_color_grading", List.of("deep_frozen_ocean", "frozen_ocean",
            "frozen_peaks", "frozen_river", "snowy_beach", "snowy_plains", "snowy_taiga")),
        Map.entry("minecraft:coolish_color_grading", List.of("cold_ocean", "deep_cold_ocean", "grove",
            "jagged_peaks", "old_growth_pine_taiga", "old_growth_spruce_taiga", "pale_garden",
            "snowy_slopes", "taiga", "windswept_forest", "windswept_gravelly_hills", "windswept_hills")),
        Map.entry("minecraft:default_color_grading", List.of("basalt_deltas", "beach", "birch_forest",
            "cherry_grove", "crimson_forest", "deep_dark", "deep_ocean", "dripstone_caves",
            "end_barrens", "end_highlands", "end_midlands", "flower_forest", "forest", "meadow",
            "nether_wastes", "ocean", "old_growth_birch_forest", "plains", "river", "small_end_islands",
            "soul_sand_valley", "stony_shore", "sunflower_plains", "the_end", "warped_forest")),
        Map.entry("minecraft:desert_color_grading", List.of("desert")),
        Map.entry("minecraft:ice_plains_spikes_color_grading", List.of("ice_spikes")),
        Map.entry("minecraft:mangrove_swamp_color_grading", List.of("mangrove_swamp")),
        Map.entry("minecraft:mesa_color_grading", List.of("badlands", "eroded_badlands", "wooded_badlands")),
        Map.entry("minecraft:mushroom_island_color_grading", List.of("mushroom_fields")),
        Map.entry("minecraft:roofed_forest_color_grading", List.of("dark_forest")),
        Map.entry("minecraft:swampland_color_grading", List.of("swamp")),
        Map.entry("minecraft:warmish_color_grading", List.of("bamboo_jungle", "deep_lukewarm_ocean",
            "jungle", "lukewarm_ocean", "lush_caves", "savanna", "savanna_plateau", "sparse_jungle",
            "stony_peaks", "sulfur_caves", "warm_ocean", "windswept_savanna")));

    /** Java's fog colour blend weights, read across a lattice point by how far the camera is past it. */
    private static final float[] KERNEL = {0, 1, 4, 6, 4, 1, 0};

    private static final Map<String, GradeUniforms> BY_BIOME = new HashMap<>();
    /** The same, looked up once per biome a frame samples rather than once per sample. */
    private static final Map<Holder<Biome>, GradeUniforms> BY_HOLDER = new java.util.IdentityHashMap<>();
    private static GradeUniforms unnamed = GradeUniforms.NONE;
    private static boolean graded;
    private static GradeUniforms current = GradeUniforms.NONE;

    private ColourGrading() {
    }

    public static void load() {
        Map<String, ColourGrade> grades = new HashMap<>();
        Map<String, String> named = new HashMap<>();
        VANILLA.forEach((grade, biomes) -> biomes.forEach(biome -> named.put("minecraft:" + biome, grade)));
        String wholeWorld = null;

        for (ConvertedPack converted : ActiveAddons.active().converted()) {
            grades.putAll(ColourGrade.readAll(converted.pack()));
            String packWide = ColourGrade.wholeWorld(converted.pack());
            if (packWide != null) wholeWorld = packWide;
            for (Path file : converted.pack().list("biomes", ".json")) {
                readNaming(file, converted.namespace(), named);
            }
        }

        graded = !grades.isEmpty();
        BY_BIOME.clear();
        BY_HOLDER.clear();
        Map<String, GradeUniforms> uniforms = new HashMap<>();
        grades.forEach((identifier, grade) -> uniforms.put(identifier, grade.uniforms()));
        named.forEach((biome, grade) -> BY_BIOME.put(biome, uniforms.getOrDefault(grade, GradeUniforms.NONE)));
        unnamed = wholeWorld == null ? GradeUniforms.NONE : uniforms.getOrDefault(wholeWorld, GradeUniforms.NONE);
    }

    private static void readNaming(Path file, String namespace, Map<String, String> into) {
        try {
            JsonObject body = Json.parseObject(file).getAsJsonObject("minecraft:client_biome");
            JsonObject description = body == null ? null : body.getAsJsonObject("description");
            JsonObject components = body == null ? null : body.getAsJsonObject("components");
            JsonObject naming = components == null ? null
                : components.getAsJsonObject("minecraft:color_grading_identifier");
            String biome = description == null ? null : Json.text(description, "identifier", null);
            String grade = naming == null ? null : Json.text(naming, "color_grading_identifier", null);
            if (biome == null || grade == null) return;

            Names.Identifier id = Names.split(biome, biome.indexOf(':') > 0 ? namespace : "minecraft");
            into.put(id.namespace() + ":" + id.path(), grade);
        } catch (IOException | RuntimeException error) {
            at.ioexception.mcaddon.McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
        }
    }

    /** The grade one biome takes on its own. */
    public static GradeUniforms of(Holder<Biome> biome) {
        return BY_HOLDER.computeIfAbsent(biome, holder -> holder.unwrapKey()
            .map(key -> BY_BIOME.getOrDefault(Platform.idOf(key).toString(), unnamed))
            .orElse(unnamed));
    }

    /** The grade the last frame was drawn with. */
    public static GradeUniforms current() {
        return current;
    }

    /** Works out the grade for a frame seen from this point. */
    public static GradeUniforms at(ClientLevel level, Vec3 camera) {
        if (!graded) {
            current = GradeUniforms.NONE;
            return current;
        }

        double quartX = camera.x / 4 - 0.5;
        double quartY = camera.y / 4 - 0.5;
        double quartZ = camera.z / 4 - 0.5;
        int baseX = (int) Math.floor(quartX);
        int baseY = (int) Math.floor(quartY);
        int baseZ = (int) Math.floor(quartZ);
        GradeUniforms.Blend blend = new GradeUniforms.Blend();
        for (int x = 0; x < 6; x++) {
            float weightX = weight(quartX - baseX, x);
            for (int y = 0; y < 6; y++) {
                float weightY = weightX * weight(quartY - baseY, y);
                for (int z = 0; z < 6; z++) {
                    float weight = weightY * weight(quartZ - baseZ, z);
                    if (weight <= 0) continue;
                    blend.add(of(level.getNoiseBiome(baseX - 2 + x, baseY - 2 + y, baseZ - 2 + z)), weight);
                }
            }
        }
        current = blend.result();
        return current;
    }

    private static float weight(double past, int index) {
        return (float) (KERNEL[index + 1] + (KERNEL[index] - KERNEL[index + 1]) * past);
    }
}
