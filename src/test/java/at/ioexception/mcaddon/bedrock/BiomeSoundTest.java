package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.convert.BedrockSoundNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a biome sounds like and what falls through its air, between the two editions' numbers.
 *
 * Neither half shows in a count: a biome with a sound event that exists and the wrong number beside
 * it loads exactly like one with the right number. The density Bedrock writes and the probability
 * Java reads are two scales, and the ratio between them differs per particle, so the four pairs are
 * asserted against the values Mojang itself ships for the four biomes that carry one.
 */
class BiomeSoundTest {
    private static final String PACK_SOUND = "tp:ambient.glade.loop";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Bedrock's own density for a visual comes out at Java's own probability for the same air.
     *
     * The four are not one scale: Mojang's basalt deltas ask for a white ash density of 2 and a
     * white ash probability of 0.118, while its crimson forest asks for a spore density of 0.25 and
     * a spore probability of 0.025.
     */
    @ParameterizedTest
    @CsvSource({
        "ash, 0.05, minecraft:ash, 0.00625",
        "white_ash, 2.0, minecraft:white_ash, 0.118093334",
        "red_spores, 0.25, minecraft:crimson_spore, 0.025",
        "blue_spores, 0.25, minecraft:warped_spore, 0.01428",
    })
    void aDensityBecomesTheChanceJavaSpawnsOn(String field, float density, String particle,
        float probability) {
        BiomeParticles read = BiomeParticles.read(
            Json.parse("{\"" + field + "\": " + density + "}").getAsJsonObject());

        assertEquals(particle, read.particle());
        assertEquals(probability, read.probability(), 1.0e-7);
    }

    /** Twice the density Mojang writes is twice as often, up to the certainty Java's field holds. */
    @Test
    void densityScalesAndStopsAtEveryTick() {
        assertEquals(0.05, BiomeParticles.read(
            Json.parse("{\"red_spores\": 0.5}").getAsJsonObject()).probability(), 1.0e-7);
        assertEquals(1.0f, BiomeParticles.read(
            Json.parse("{\"ash\": 400}").getAsJsonObject()).probability());
    }

    @Test
    void aBiomeStatingNoPrecipitationDrawsNothing() {
        assertNull(BiomeParticles.read(null).particle());
        assertNull(BiomeParticles.read(Json.parse("{\"rain\": 1}").getAsJsonObject()).particle());
    }

    /** The names the two editions spell differently, each with the Java event that plays it. */
    @ParameterizedTest
    @CsvSource({
        "ambient.cave, minecraft:ambient.cave",
        "ambient.soulsand_valley.loop, minecraft:ambient.soul_sand_valley.loop",
        "ambient.soulsand_valley.mood, minecraft:ambient.soul_sand_valley.mood",
        "ambient.underwater.additions, minecraft:ambient.underwater.loop.additions",
        "ambient.basalt_deltas.loop, minecraft:ambient.basalt_deltas.loop",
        "music.game, minecraft:music.game",
        "music.game.creative, minecraft:music.creative",
        "music.game.endboss, minecraft:music.dragon",
        "music.game.nether_wastes, minecraft:music.nether.nether_wastes",
        "music.game.soulsand_valley, minecraft:music.nether.soul_sand_valley",
        "music.game.swamp_music, minecraft:music.overworld.swamp",
        "music.game.water, minecraft:music.under_water",
        "music.overworld.mesa, minecraft:music.overworld.badlands",
        "music.overworld.jungle_edge, minecraft:music.overworld.sparse_jungle",
        "music.overworld.forest, minecraft:music.overworld.forest",
    })
    void anEngineSoundNamesTheJavaEvent(String bedrock, String expected) {
        assertEquals(expected, BedrockSoundNames.java(bedrock));
    }

    /**
     * A name no version of Java has an event under comes back as nothing.
     *
     * A biome is one entry: writing an id the registry does not hold costs the whole biome rather
     * than the one sound, so the answer has to be absent and the caller has to report the name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sprite.chime", "music.game.nowhere", "oreville:ambient.mine"})
    void aSoundJavaHasNoEventForIsNotNamed(String bedrock) {
        assertNull(BedrockSoundNames.java(bedrock));
    }

    /** A pack shipping a definition under one of the engine's names means its own file. */
    @Test
    void thePacksOwnDefinitionWinsOverTheEngines() {
        assertEquals("tp:ambient.cave",
            BedrockSoundNames.java("ambient.cave", "tp", List.of("tp:ambient.cave")));
        assertEquals("minecraft:ambient.cave",
            BedrockSoundNames.java("ambient.cave", "tp", List.of()));
    }

    /**
     * The three moments a Java biome holds, read off the component that states six.
     *
     * The addition's chance is the one number that crosses untouched, because both editions count
     * it as the chance of playing on a tick.
     */
    @Test
    void aComponentStatesTheThreeMomentsJavaHolds(@TempDir Path root) throws IOException {
        BiomeSounds sounds = glade(root, """
            "minecraft:ambient_sounds": {
              "loop": "tp:ambient.glade.loop",
              "mood": "ambient.cave",
              "addition": {"asset": "ambient.crimson_forest.additions", "chance": 0.0111},
              "underwater_loop": "ambient.underwater.loop"
            }
            """).sounds();

        assertEquals(PACK_SOUND, sounds.loop());
        assertEquals("minecraft:ambient.cave", sounds.mood());
        assertEquals("minecraft:ambient.crimson_forest.additions", sounds.addition());
        assertEquals(0.0111f, sounds.additionChance(), 1.0e-7);
    }

    /**
     * The music definition a biome names carries the delays, in the ticks Java counts.
     *
     * Bedrock states a gap in seconds, so a minute of silence is 1200 ticks and not 60 of them.
     */
    @Test
    void aBiomeMusicNamesADefinitionAndItsDelaysBecomeTicks(@TempDir Path root) throws IOException {
        BiomeMusic music = glade(root, """
            "minecraft:biome_music": {
              "music_definition": "glade",
              "underwater_music": true,
              "volume_multiplier": 0.5
            }
            """).music();

        assertEquals(PACK_SOUND, music.track().event());
        assertEquals(1200, music.track().minDelay());
        assertEquals(3600, music.track().maxDelay());
        assertEquals("minecraft:music.under_water", music.underwater().event());
        assertEquals(0.5f, music.volume(), 1.0e-7);
    }

    /** A definition the pack does not ship leaves the biome with whatever the game plays there. */
    @Test
    void musicNamingADefinitionThePackHasNotGotPlaysNothing(@TempDir Path root) throws IOException {
        assertNull(glade(root, """
            "minecraft:biome_music": {"music_definition": "nowhere"}
            """).music().track());
    }

    /** One biome out of a pack holding the named component beside the music definitions it uses. */
    private static ClientBiomeDefinition glade(Path root, String component) throws IOException {
        String biome = """
            {
              "format_version": "1.21.120",
              "minecraft:client_biome": {
                "description": {"identifier": "tp:glade"},
                "components": {%s}
              }
            }
            """.formatted(component);
        String definitions = """
            {
              "glade": {"event_name": "tp:ambient.glade.loop", "min_delay": 60, "max_delay": 180},
              "water": {"event_name": "music.game.water", "min_delay": 10, "max_delay": 20}
            }
            """;

        BedrockPack pack = packHolding(root, Map.of(
            "biomes/glade.client_biome.json", biome,
            "sounds/music_definitions.json", definitions));
        return ClientBiomeDefinition.readAll(pack,
            name -> BedrockSoundNames.java(name, "tp", List.of(PACK_SOUND))).get("tp:glade");
    }

    private static BedrockPack packHolding(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000fb", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000fa"}]
            }
            """).getAsJsonObject());
        return new BedrockPack(root, manifest, "test");
    }
}
