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
    private static final float TICKS_PER_SECOND = 20f;

    /** One clip in flight, with its own clock. */
    private static final class Running {
        final BedrockAnimation animation;
        float animTime;
        float lastFiredTime = -1;

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

    public AnimationPlayer(BedrockAssets assets) {
        this.assets = assets;
    }

    public MolangEnvironment environment() {
        return environment;
    }

    /** Starts a clip, or restarts it from the beginning if it is already playing. */
    public void play(String name) {
        play(name, null, 0f);
    }

    /**
     * Starts a clip that ends on its own terms.
     *
     * `stopExpression` is what `/playanimation` takes: a Molang test that ends the clip once it
     * holds, whatever the clip's own length says. `blendOutSeconds` is how long the clip takes to
     * fade once it is over, so a gesture releases rather than snaps back.
     */
    public void play(String name, MolangExpression stopExpression, float blendOutSeconds) {
        BedrockAnimation animation = assets.animation(name);
        if (animation == null) return;

        Running clip = new Running(animation);
        clip.stopExpression = stopExpression;
        clip.blendOutSeconds = blendOutSeconds;
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
        if (!running.containsKey(name)) play(name);
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

    private void advance(Running clip, float deltaSeconds) {
        MolangExpression update = clip.animation.animTimeUpdate();
        if (update == null) {
            clip.animTime += deltaSeconds;
            return;
        }
        // The clip drives its own clock, so `q.anim_time` has to read as where it currently is.
        environment.setAnimTime(clip.animTime);
        clip.animTime = update.asFloat(environment);
    }

    private void fireTimeline(Running clip) {
        float position = clip.animation.positionAt(clip.animTime);
        environment.setAnimTime(position);
        if (position < clip.lastFiredTime) clip.lastFiredTime = -1;

        for (BedrockAnimation.TimelineEntry entry : clip.animation.timeline()) {
            if (entry.time() > position || entry.time() <= clip.lastFiredTime) continue;
            for (MolangExpression statement : entry.statements()) statement.evaluate(environment);
        }
        clip.lastFiredTime = position;
    }

    private void applyBones(Running clip) {
        if (!clip.animation.hasBones()) return;

        float position = clip.animation.positionAt(clip.animTime);
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
