package at.ioexception.mcaddon.bedrock.model;

import java.util.List;
import java.util.Map;

/** A whole model, baked: the bones with no parent, and every bone by name for posing. */
public record BakedGeometry(List<BakedBone> roots, Map<String, BakedBone> bones) {
}
