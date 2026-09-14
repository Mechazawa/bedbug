package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;
import at.ioexception.mcaddon.pack.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The engine this definition was written for, which decides which of several is drawn. */
    SemVer minEngineVersion,
    /** Whether what the entity has equipped is drawn on it at all. */
    boolean attachablesEnabled,
    /** Hides what is worn in an armour slot, whatever `enable_attachables` allowed. */
    boolean armourHidden,
    /** Whether the item the entity holds is left undrawn, asked every frame. Null keeps it drawn. */
    MolangExpression heldItemsHidden,
    /** Draws whatever is in a hand at full brightness rather than at the light around the entity. */
    boolean heldItemLit,
    /** How big the item in a hand is drawn, null where the pack asked for the ordinary size. */
    MolangExpression heldItemScale,
    /** Keeps the entity's sounds and particles firing while nobody can see it. */
    boolean effectsRunOffscreen,
    /** Keeps its bones moving as well, so it is posed where it should be when it comes back. */
    boolean bonesRunOffscreen,
    Map<String, String> geometry,
    Map<String, String> textures,
    Map<String, String> materials,
    Map<String, String> animations,
    Map<String, String> animationControllers,
    Map<String, String> soundEffects,
    Map<String, String> particleEffects,
    Map<String, String> particleEmitters,
    /** Which items an attachable is drawn for, each against the test that has to hold. */
    Map<String, MolangExpression> items,
    List<Gated> animate,
    List<Gated> renderControllers,
    List<MolangExpression> initialize,
    List<MolangExpression> preAnimation,
    /** What an attachable writes into whoever is carrying it, run in that entity's own scope. */
    List<MolangExpression> parentSetup,
    /** The variables whatever this entity carries may read back through `c.owning_entity->v.name`. */
    Set<String> publishedVariables,
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

    /**
     * Whether this entity's animation script runs while it is out of view.
     *
     * One pass advances the clips, fires their effects and poses the bones, so an entity asking
     * only for its effects has its bones posed as well, which nothing draws while it is away.
     */
    public boolean runsOffscreen() {
        return effectsRunOffscreen || bonesRunOffscreen;
    }

    public String resolveAnimation(String shortName) {
        return animations.getOrDefault(shortName, animationControllers.getOrDefault(shortName, shortName));
    }

    public String resolveSound(String shortName) {
        return soundEffects.getOrDefault(shortName, shortName);
    }

    /**
     * The effect a short name stands for.
     *
     * A pack names its effects in either of two maps, and an animation writing a short name means
     * whichever of the two carries it: `particle_effects` for the ones fired off a timeline and
     * `particle_emitters` for the ones the entity keeps running.
     */
    public String resolveParticle(String shortName) {
        return particleEffects.getOrDefault(shortName,
            particleEmitters.getOrDefault(shortName, shortName));
    }
}
