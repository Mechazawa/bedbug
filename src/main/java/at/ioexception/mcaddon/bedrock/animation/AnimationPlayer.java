package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Runs the animations playing on one entity and works out where its bones end up.
 *
 * Several clips run at once: an idle loop, whatever gesture the controllers picked, and the clip
 * belonging to the line the villager is speaking. Their effects add together, weighted by the blend
 * weight each clip declares.
 */
public final class AnimationPlayer {
    /** Clip names already reported as missing, so the log names each of them once. */
    private static final java.util.Set<String> MISSING = new java.util.HashSet<>();

    private static final float TICKS_PER_SECOND = 20f;

    /** One clip in flight, with its own clock. */
    private static final class Running {
        final BedrockAnimation animation;
        float animTime;
        float lastFiredTime = -1;

        /** Where in the clip its clock currently leaves it, which is what the pose is read at. */
        float position;

        /** Seconds left of a start or loop delay, during which the clip holds where it stands. */
        float waiting;

        /** When this holds, the clip starts blending out. Null for a clip nothing is watching for. */
        MolangExpression stopExpression;
        float blendOutSeconds;

        /** Full strength until the clip is on its way out, then down to zero over `blendOutSeconds`. */
        float fade = 1f;

        Running(BedrockAnimation animation) {
            this.animation = animation;
        }

        /** Whether the clip is still worth drawing after this much time has passed. */
        boolean fadeOut(float deltaSeconds) {
            if (blendOutSeconds <= 0f) return false;
            fade -= deltaSeconds / blendOutSeconds;
            return fade > 0f;
        }
    }

    private final BedrockAssets assets;
    private final MolangEnvironment environment = new MolangEnvironment();
    private final Map<String, Running> running = new HashMap<>();
    private final Map<String, BonePose> poses = new HashMap<>();

    private final Vector3f scratch = new Vector3f();
    private EffectSink effects = EffectSink.NONE;

    /**
     * Where an animation's sounds and particles go.
     *
     * The player knows when an effect fires but not what its name means or how to show it, so it
     * hands both on. Nothing listens on a server, where an animation runs only for its variables.
     */
    public interface EffectSink {
        EffectSink NONE = new EffectSink() {
            @Override
            public void sound(String effect) {
            }

            @Override
            public void particle(String effect, String locator) {
            }
        };

        void sound(String effect);

        void particle(String effect, String locator);
    }

    public void setEffects(EffectSink effects) {
        this.effects = effects;
    }

    public AnimationPlayer(BedrockAssets assets) {
        this.assets = assets;
    }

    public MolangEnvironment environment() {
        return environment;
    }

    /**
     * Starts a clip, or restarts it from the beginning if it is already playing.
     *
     * `stopExpression` is what `/playanimation` takes: a Molang test that ends the clip once it
     * holds, whatever the clip's own length says. `blendOutSeconds` is how long the clip takes to
     * fade once it is over, so a gesture releases rather than snaps back.
     */
    public void play(String name, MolangExpression stopExpression, float blendOutSeconds) {
        BedrockAnimation animation = assets.animation(name);
        if (animation == null) {
            // A model whose pose comes from a clip is authored wherever the clip puts it, so a clip
            // that never plays leaves those bones wherever the geometry left them.
            if (MISSING.add(name)) {
                at.ioexception.mcaddon.McAddon.LOGGER.warn("No animation named {}.", name);
            }
            return;
        }

        Running clip = new Running(animation);
        clip.stopExpression = stopExpression;
        clip.blendOutSeconds = blendOutSeconds;
        clip.waiting = animation.startDelaySeconds(environment);
        running.put(name, clip);
    }

    /** Whether any clip in flight has run past its length, which `q.any_animation_finished` asks. */
    public boolean anyFinished() {
        for (Running clip : running.values()) {
            if (clip.animation.finished(clip.animTime)) return true;
        }
        return false;
    }

    public boolean allFinished() {
        for (Running clip : running.values()) {
            if (!clip.animation.finished(clip.animTime)) return false;
        }
        return true;
    }

    public void stop(String name) {
        running.remove(name);
    }

    public boolean isPlaying(String name) {
        return running.containsKey(name);
    }

    /** Keeps a looping clip running without restarting it each frame. */
    public void ensurePlaying(String name) {
        if (!running.containsKey(name)) play(name, null, 0f);
    }

    public void stopAll() {
        running.clear();
    }

    /**
     * Advances every clip by `deltaSeconds` and rebuilds the bone poses.
     *
     * Clips that have run out are dropped, except the ones told to hold their last frame, which is
     * what a spoken line does so a gesture does not snap back the moment the audio ends.
     */
    public void tick(float deltaSeconds) {
        for (BonePose pose : poses.values()) pose.reset();

        environment.setDeltaTime(deltaSeconds);

        Iterator<Map.Entry<String, Running>> entries = running.entrySet().iterator();
        while (entries.hasNext()) {
            Running clip = entries.next().getValue();
            advance(clip, deltaSeconds);
            fireTimeline(clip);
            applyBones(clip);

            boolean over = clip.stopExpression != null
                && clip.stopExpression.evaluate(environment).truthy();
            over |= clip.animation.loop() == BedrockAnimation.Loop.ONCE
                && clip.animTime >= clip.animation.length();
            if (over && !clip.fadeOut(deltaSeconds)) entries.remove();
        }
    }

    /**
     * Moves one clip's clock on and works out where in the clip that leaves it.
     *
     * A clip driving its own clock through `anim_time_update` decides its time outright, and reads
     * back whatever it last produced rather than the wrapped position, so an expression counting up
     * keeps counting. Neither delay applies to a clip like that.
     */
    private void advance(Running clip, float deltaSeconds) {
        MolangExpression update = clip.animation.animTimeUpdate();
        if (update != null) {
            environment.setAnimTime(clip.animTime);
            clip.animTime = update.asFloat(environment);
        } else if (clip.waiting > 0f) {
            waitOut(clip, deltaSeconds);
        } else {
            clip.animTime += deltaSeconds;
            loopOver(clip);
        }

        // A clip waiting out a loop delay holds the last frame it drew rather than the first.
        clip.position = clip.waiting > 0f && clip.animTime > 0f
            ? clip.animation.length()
            : clip.animation.positionAt(clip.animTime);
    }

    /** Counts a start or loop delay down, and starts the clip on whatever is left of the frame. */
    private static void waitOut(Running clip, float deltaSeconds) {
        clip.waiting -= deltaSeconds;
        if (clip.waiting > 0f) return;

        clip.animTime = -clip.waiting;
        clip.waiting = 0f;
        clip.lastFiredTime = -1;
    }

    /** Sends a looping clip round again once it passes its end, after the delay it asks for. */
    private void loopOver(Running clip) {
        float length = clip.animation.length();
        if (clip.animation.loop() != BedrockAnimation.Loop.LOOP || length <= 0 || clip.animTime < length) {
            return;
        }

        clip.waiting = clip.animation.loopDelaySeconds(environment);
        if (clip.waiting > 0f) return;

        clip.animTime -= length * (float) Math.floor(clip.animTime / length);
        clip.lastFiredTime = -1;
    }

    private void fireTimeline(Running clip) {
        float position = clip.position;
        environment.setAnimTime(position);
        if (position < clip.lastFiredTime) clip.lastFiredTime = -1;

        for (BedrockAnimation.TimelineEntry entry : clip.animation.timeline()) {
            if (entry.time() > position || entry.time() <= clip.lastFiredTime) continue;
            for (MolangExpression statement : entry.statements()) statement.evaluate(environment);
        }
        for (BedrockAnimation.EffectEntry entry : clip.animation.soundEffects()) {
            if (due(entry, position, clip.lastFiredTime)) effects.sound(entry.effect());
        }
        for (BedrockAnimation.EffectEntry entry : clip.animation.particleEffects()) {
            if (due(entry, position, clip.lastFiredTime)) effects.particle(entry.effect(), entry.locator());
        }
        clip.lastFiredTime = position;
    }

    /** Whether an effect's moment falls in the span just played, running its script if it does. */
    private boolean due(BedrockAnimation.EffectEntry entry, float position, float lastFired) {
        if (entry.effect() == null || entry.time() > position || entry.time() <= lastFired) return false;

        for (MolangExpression statement : entry.preEffectScript()) statement.evaluate(environment);
        return true;
    }

    private void applyBones(Running clip) {
        if (!clip.animation.hasBones()) return;

        float position = clip.position;
        float weight = clip.fade * (clip.animation.blendWeight() == null
            ? 1f
            : clip.animation.blendWeight().asFloat(environment));
        if (weight == 0f) return;

        environment.setAnimTime(position);

        for (Map.Entry<String, BoneAnimation> bone : clip.animation.bones().entrySet()) {
            BonePose pose = poses.computeIfAbsent(bone.getKey(), name -> new BonePose());
            BoneAnimation channels = bone.getValue();

            if (!channels.rotation().isEmpty()) {
                pose.addRotation(channels.rotation().evaluate(position, environment, pose.rotation, scratch), weight);
                pose.rotationRelativeToEntity |= channels.rotationRelativeToEntity();
            }
            if (!channels.position().isEmpty()) {
                pose.addPosition(channels.position().evaluate(position, environment, pose.position, scratch), weight);
            }
            if (!channels.scale().isEmpty()) {
                pose.multiplyScale(channels.scale().evaluate(position, environment, pose.scale, scratch), weight);
            }
        }
    }

    public BonePose poseOf(String bone) {
        return poses.get(bone);
    }

    public Map<String, BonePose> poses() {
        return poses;
    }

    /** The clips that are currently running, for a controller to reason about. */
    public List<String> playing() {
        return new ArrayList<>(running.keySet());
    }

    public static float secondsPerTick() {
        return 1f / TICKS_PER_SECOND;
    }
}
