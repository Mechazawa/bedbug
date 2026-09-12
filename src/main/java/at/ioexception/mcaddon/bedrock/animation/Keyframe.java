package at.ioexception.mcaddon.bedrock.animation;

/**
 * One moment in a channel.
 *
 * A keyframe can hold two values: `pre` is what the channel reads as it arrives, `post` what it
 * leaves with. They differ only where an animation snaps, and are the same object otherwise.
 */
public record Keyframe(float time, Vector3Expression pre, Vector3Expression post, LerpMode lerpMode) {
}
