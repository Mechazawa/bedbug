package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The named shapes a behaviour pack ships, which its blocks cull their faces against.
 *
 * A file under `shapes/` is one `minecraft:voxel_shape`: an identifier and up to thirty-two boxes,
 * each written as the corner it runs from and the corner it runs to, in the block's own sixteenths
 * with the origin at the corner rather than at the centre. `minecraft:geometry` names one of them
 * under `culling_shape`, and the engine hides the face between two blocks where the shapes on both
 * sides cover it.
 *
 * Java asks the same question of the same kind of answer, through the occlusion shape a block gives
 * for a face, so a named shape becomes that. The two identifiers the engine answers itself,
 * `minecraft:empty` and `minecraft:unit_cube`, are the empty shape and the whole block.
 */
public record PackShapes(Map<String, VoxelShape> byIdentifier) {

    /** A pack that ships no `shapes/` still answers the two identifiers the engine has itself. */
    public static final PackShapes NONE = new PackShapes(Map.of());

    public static PackShapes read(BedrockPack pack) {
        Map<String, VoxelShape> shapes = new LinkedHashMap<>();
        for (Path file : pack.list("shapes", ".json")) {
            try {
                read(Json.parseObject(file), shapes);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return shapes.isEmpty() ? NONE : new PackShapes(Map.copyOf(shapes));
    }

    private static void read(JsonObject root, Map<String, VoxelShape> shapes) {
        JsonElement body = root.get("minecraft:voxel_shape");
        if (body == null || !body.isJsonObject()) return;

        JsonObject shape = body.getAsJsonObject();
        JsonElement description = shape.get("description");
        String identifier = description != null && description.isJsonObject()
            ? Json.text(description.getAsJsonObject(), "identifier", null) : null;
        if (identifier == null) return;

        JsonElement boxes = shape.get("shape");
        if (boxes == null || !boxes.isJsonObject()) return;

        VoxelShape built = Shapes.empty();
        for (JsonElement element : Json.list(boxes.getAsJsonObject(), "boxes")) {
            if (element.isJsonObject()) built = Shapes.or(built, corners(element.getAsJsonObject()));
        }
        shapes.put(identifier, built);
    }

    /** Both corners are written in sixteenths from the block's own lower corner. */
    private static VoxelShape corners(JsonObject box) {
        double[] from = BlockComponents.vector(box.get("min"), 0, 0, 0);
        double[] to = BlockComponents.vector(box.get("max"), 16, 16, 16);
        return Shapes.box(
            Math.min(from[0], to[0]) / 16, Math.min(from[1], to[1]) / 16,
            Math.min(from[2], to[2]) / 16, Math.max(from[0], to[0]) / 16,
            Math.max(from[1], to[1]) / 16, Math.max(from[2], to[2]) / 16);
    }

    /**
     * The shape an identifier names, or null where nothing names one.
     *
     * A name no file in this pack declares is reported rather than answered with a guess, because a
     * block culling against the wrong shape draws holes in whatever stands beside it.
     */
    public VoxelShape named(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        if ("minecraft:empty".equals(identifier)) return Shapes.empty();
        if ("minecraft:unit_cube".equals(identifier)) return Shapes.block();

        VoxelShape named = byIdentifier.get(identifier);
        if (named == null) AddonEntities.unsupported("minecraft:geometry culling_shape " + identifier);
        return named;
    }
}
