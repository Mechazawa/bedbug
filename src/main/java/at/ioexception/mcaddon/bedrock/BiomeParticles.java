package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.util.Arrays;

/**
 * What a client biome's `minecraft:precipitation` asks to be drawn in the air.
 *
 * Bedrock names one of four visuals and states a density for it; Java names a particle and states
 * the chance it is spawned on a tick. The two scales are not the same and the difference is not the
 * same for all four, so each case here holds both of the numbers Mojang itself writes for the one
 * biome that uses it. A pack writing Bedrock's own density then comes out at Java's own chance, and
 * a pack writing twice that comes out at twice the chance.
 *
 * @param particle the Java particle id, or null where the component names none of the four
 */
public record BiomeParticles(String particle, float probability) {
    /** Nothing drawn, which is what a biome with no such component gets. */
    public static final BiomeParticles NONE = new BiomeParticles(null, 0);

    /**
     * One visual, under the field Bedrock names it with and against the Java particle it is.
     *
     * `density` is what Bedrock's own biome states and `probability` what Java's own biome states
     * for the same air, so the two are one pair rather than two numbers.
     */
    private enum Kind {
        ASH("ash", "minecraft:ash", 0.05F, 0.00625F),
        BLUE_SPORES("blue_spores", "minecraft:warped_spore", 0.25F, 0.01428F),
        RED_SPORES("red_spores", "minecraft:crimson_spore", 0.25F, 0.025F),
        WHITE_ASH("white_ash", "minecraft:white_ash", 2.0F, 0.118093334F);

        private final String field;
        private final String particle;
        private final float density;
        private final float probability;

        Kind(String field, String particle, float density, float probability) {
            this.field = field;
            this.particle = particle;
            this.density = density;
            this.probability = probability;
        }
    }

    /** Whether there is anything here to draw. */
    public boolean painted() {
        return particle != null;
    }

    /**
     * What the component asks for, or nothing where it names none of the four.
     *
     * Bedrock draws one visual per biome, so the first of the four a component states is the one
     * that lands.
     */
    public static BiomeParticles read(JsonObject component) {
        if (component == null) return NONE;

        Kind found = Arrays.stream(Kind.values()).filter(kind -> component.has(kind.field))
            .findFirst().orElse(null);
        if (found == null) return NONE;

        // Java's field is a probability, so no density can ask for more than every tick.
        float density = (float) Json.number(component, found.field, 0);
        return new BiomeParticles(found.particle,
            Math.min(1.0F, density / found.density * found.probability));
    }
}
