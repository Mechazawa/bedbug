package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which clips and controllers one entity runs, as its description declares them.
 *
 * `animations` gives each one a short name, and `scripts.animate` is the running order: only an
 * entry listed there runs, and an entry written as an object carries the condition under which it
 * does. A behaviour pack writes the same two fields a resource pack does, so a pack's state machine
 * is declared the same way on both sides.
 */
public record AnimationScript(Map<String, String> animations, List<Gated> animate) {
    public static final AnimationScript NONE = new AnimationScript(Map.of(), List.of());

    /** A name plus the condition under which it applies. A null condition always applies. */
    public record Gated(String name, MolangExpression condition) {
    }

    public static AnimationScript read(JsonObject description) {
        Map<String, String> named = new LinkedHashMap<>();
        JsonObject declared = description.getAsJsonObject("animations");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) named.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        JsonObject scripts = description.getAsJsonObject("scripts");
        List<Gated> animate = new ArrayList<>();
        for (JsonElement entry : scripts == null ? List.<JsonElement>of() : Json.list(scripts, "animate")) {
            if (entry.isJsonPrimitive()) {
                animate.add(new Gated(entry.getAsString(), null));
            } else if (entry.isJsonObject() && !entry.getAsJsonObject().entrySet().isEmpty()) {
                Map.Entry<String, JsonElement> gated = entry.getAsJsonObject().entrySet().iterator().next();
                animate.add(new Gated(gated.getKey(), MolangParser.parse(gated.getValue().getAsString())));
            }
        }

        return named.isEmpty() && animate.isEmpty() ? NONE
            : new AnimationScript(Map.copyOf(named), List.copyOf(animate));
    }

    public boolean isEmpty() {
        return animate.isEmpty();
    }

    /** The clip or controller a short name stands for, or the name itself where none is mapped. */
    public String resolve(String shortName) {
        return animations.getOrDefault(shortName, shortName);
    }
}
