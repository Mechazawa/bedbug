package at.ioexception.mcaddon.bedrock.animation;

import java.util.List;

/**
 * The three channels an animation can drive on one bone.
 *
 * `rotationRelativeToEntity` marks a bone whose rotation is given in the entity's frame rather than
 * its parent's, which is how a head keeps facing a target while the body turns underneath it.
 */
public record BoneAnimation(
    AnimationChannel rotation,
    AnimationChannel position,
    AnimationChannel scale,
    boolean rotationRelativeToEntity
) {
    public List<AnimationChannel> channels() {
        return List.of(rotation, position, scale);
    }
}
