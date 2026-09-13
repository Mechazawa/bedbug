package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;

import java.util.List;
import java.util.Map;

/**
 * A Bedrock client entity: everything about how one entity is drawn.
 *
 * `animate` is the running order. Each entry names either a clip or a controller, optionally gated
 * by a condition, and they are evaluated in sequence so a later one can override an earlier.
 *
 * `animationControllers` is the older way of saying the same thing: every controller it names runs,
 * in the order given, without a condition and without an `animate` list. Packs written either way
 * are still published, and a few use both at once.
 */
public record ClientEntityDefinition(
    String identifier,
    Map<String, String> geometry,
    Map<String, String> textures,
    Map<String, String> materials,
    Map<String, String> animations,
    Map<String, String> animationControllers,
    Map<String, String> soundEffects,
    Map<String, String> particleEffects,
    List<Gated> animate,
    List<Gated> renderControllers,
    List<MolangExpression> initialize,
    List<MolangExpression> preAnimation,
    MolangExpression scale,
    /** Per axis scale, which a pack uses on top of `scale` to squash a jumping slime. */
    MolangExpression scaleX,
    MolangExpression scaleY,
    MolangExpression scaleZ,
    /** Null when the pack gave this entity no spawn egg. */
    SpawnEggAppearance spawnEgg
) {
    /** A name plus the condition under which it applies. A null condition always applies. */
    public record Gated(String name, MolangExpression condition) {
    }

    public String resolveAnimation(String shortName) {
        return animations.getOrDefault(shortName, animationControllers.getOrDefault(shortName, shortName));
    }

    public String resolveSound(String shortName) {
        return soundEffects.getOrDefault(shortName, shortName);
    }

    public String resolveParticle(String shortName) {
        return particleEffects.getOrDefault(shortName, shortName);
    }
}
