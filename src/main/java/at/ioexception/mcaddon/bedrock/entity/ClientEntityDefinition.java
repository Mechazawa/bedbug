package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;

import java.util.List;
import java.util.Map;

/**
 * A Bedrock client entity: everything about how one entity is drawn.
 *
 * `animate` is the running order. Each entry names either a clip or a controller, optionally gated
 * by a condition, and they are evaluated in sequence so a later one can override an earlier.
 */
public record ClientEntityDefinition(
    String identifier,
    Map<String, String> geometry,
    Map<String, String> textures,
    Map<String, String> materials,
    Map<String, String> animations,
    Map<String, String> soundEffects,
    Map<String, String> particleEffects,
    List<Gated> animate,
    List<Gated> renderControllers,
    List<MolangExpression> initialize,
    List<MolangExpression> preAnimation,
    MolangExpression scale
) {
    /** A name plus the condition under which it applies. A null condition always applies. */
    public record Gated(String name, MolangExpression condition) {
    }

    public String resolveAnimation(String shortName) {
        return animations.getOrDefault(shortName, shortName);
    }

    public String resolveSound(String shortName) {
        return soundEffects.getOrDefault(shortName, shortName);
    }

    public String resolveParticle(String shortName) {
        return particleEffects.getOrDefault(shortName, shortName);
    }
}
