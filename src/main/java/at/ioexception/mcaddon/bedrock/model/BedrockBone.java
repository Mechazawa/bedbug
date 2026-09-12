package at.ioexception.mcaddon.bedrock.model;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** A bone of a Bedrock model: a pivot, an optional resting rotation, and the boxes hung off it. */
public record BedrockBone(
    String name,
    String parent,
    Vector3f pivot,
    Vector3f rotation,
    boolean mirror,
    List<BedrockCube> cubes,
    Map<String, Vector3f> locators
) {
}
