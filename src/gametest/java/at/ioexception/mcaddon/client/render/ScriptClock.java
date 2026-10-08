package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.entity.AddonEntity;

import java.util.List;

/**
 * An entity's whole animation script run on a clock of the test's own, a tick at a time.
 *
 * It is built the way the renderer builds the animator it draws an entity with, from the same pack
 * and against the same entity, and is then moved on by whole ticks rather than by however long a
 * frame took, so a bone's pose can be asked for at an exact moment. The clips' sounds and particles
 * go nowhere, so a clock run beside the real one leaves nothing in the world.
 */
public final class ScriptClock {
    private final AddonClientAssets.Binding binding;
    private final Animators.Entry entry;
    private int ticks;

    private ScriptClock(AddonClientAssets.Binding binding, Animators.Entry entry) {
        this.binding = binding;
        this.entry = entry;
    }

    public static ScriptClock of(AddonEntity entity) {
        AddonClientAssets.Binding binding = AddonClientAssets.of(AddonEntityRenderer.identifierOf(entity));
        Animators.Entry entry = Animators.Entry.of(entity, binding, false, 0f);
        entry.animator().setEffects(AnimationPlayer.EffectSink.NONE);
        return new ScriptClock(binding, entry);
    }

    public void tick() {
        ticks++;
        entry.advance(0f, ticks, "");
    }

    /** What the clips have done to a bone, or a pose at rest where nothing has touched it. */
    public BonePose pose(String bone) {
        BonePose pose = entry.animator().poseOf(bone);
        return pose == null ? new BonePose() : pose;
    }

    /** A variable of the entity's script as it stands now, zero where nothing has set it. */
    public float variable(String name) {
        return entry.animator().environment().variable(name).asFloat();
    }

    /** The passes the entity's render controllers pick from the script as it stands now. */
    public List<AddonModels.Pass> passes() {
        return binding.models().resolve(entry.animator());
    }
}
