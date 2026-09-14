package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What a client biome's `minecraft:ambient_sounds` plays, as much of it as a Java biome holds.
 *
 * Bedrock states six moments and Java holds three: a loop that runs while the player is inside the
 * biome, a mood that rarely plays at a dark block nearby, and an addition that occasionally plays at
 * the listener. The three underwater moments have no Java field on any of these versions and are
 * named against the pack.
 *
 * Each name here is already the Java sound event, because the pack's own definitions and the
 * engine's built-in names are resolved as the component is read. A moment whose sound has nothing
 * behind it comes out null and the game keeps whatever it plays there itself.
 *
 * @param additionChance the chance the addition plays on a tick, which both editions count the same
 *                       way
 */
public record BiomeSounds(String loop, String mood, String addition, float additionChance) {
    /** Nothing stated, which is what a biome with no such component gets. */
    public static final BiomeSounds NONE = new BiomeSounds(null, null, null, 0);

    /** The moments that reach a Java biome underwater, which is none of them. */
    private static final List<String> DROPPED =
        List.of("underwater_loop", "underwater_mood", "underwater_addition");

    /** Whether there is anything here to play. */
    public boolean heard() {
        return loop != null || mood != null || addition != null;
    }

    /** @param sound the Java sound event one of the pack's names becomes, or null for none */
    public static BiomeSounds read(JsonObject component, UnaryOperator<String> sound) {
        if (component == null) return NONE;

        AddonEntities.unsupportedFields("client biome minecraft:ambient_sounds", component, DROPPED);

        JsonElement addition = component.get("addition");
        JsonObject body = addition != null && addition.isJsonObject()
            ? addition.getAsJsonObject() : null;
        return new BiomeSounds(named(component, "loop", sound), named(component, "mood", sound),
            body == null ? null : named(body, "asset", sound),
            body == null ? 0 : (float) Json.number(body, "chance", 0));
    }

    private static String named(JsonObject body, String field, UnaryOperator<String> sound) {
        String bedrock = Json.text(body, field, null);
        if (bedrock == null || bedrock.isBlank()) return null;

        String found = sound.apply(bedrock);
        if (found == null) AddonEntities.unsupported("biome sound " + bedrock);
        return found;
    }
}
