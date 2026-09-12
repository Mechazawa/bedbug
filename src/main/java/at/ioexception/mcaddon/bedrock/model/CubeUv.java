package at.ioexception.mcaddon.bedrock.model;

import net.minecraft.core.Direction;

import java.util.Map;

/**
 * How a cube is unwrapped onto the texture.
 *
 * Two forms exist. Box mapping gives a corner and lays the six faces out around it the way vanilla
 * Minecraft does. Per face mapping gives each face its own patch, which vanilla has no equivalent
 * for and is why these models cannot be built with CubeListBuilder.
 */
public sealed interface CubeUv {
    record Box(float u, float v) implements CubeUv {
    }

    record PerFace(Map<Direction, FaceUv> faces) implements CubeUv {
    }
}
