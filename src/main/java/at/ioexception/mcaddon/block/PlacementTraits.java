package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;

/**
 * The states `minecraft:placement_direction` and `minecraft:placement_position` set as a block is
 * placed, from which way the player faced and which face of which block they clicked.
 *
 * Each is one of the pack's own states here, an index into the values Bedrock lists for it, so
 * placing the block picks the index that names the direction. `y_rotation_offset` turns the
 * horizontal directions counter-clockwise by that many degrees before they are stored.
 */
public final class PlacementTraits {
    private static final String CARDINAL = "minecraft:cardinal_direction";
    private static final String FACING = "minecraft:facing_direction";
    private static final String BLOCK_FACE = "minecraft:block_face";
    private static final String VERTICAL_HALF = "minecraft:vertical_half";

    private final Map<String, Property<?>> properties;
    private final Map<String, List<JsonElement>> values;
    private final int quarterTurns;

    private PlacementTraits(Map<String, Property<?>> properties,
        Map<String, List<JsonElement>> values, int quarterTurns) {
        this.properties = properties;
        this.values = values;
        this.quarterTurns = quarterTurns;
    }

    /** Null for a block that declares no placement trait. */
    static PlacementTraits of(BlockDefinition definition, Map<String, Property<?>> properties) {
        boolean placed = List.of(CARDINAL, FACING, BLOCK_FACE, VERTICAL_HALF).stream()
            .anyMatch(properties::containsKey);
        if (!placed) return null;

        JsonObject direction = definition.traits().has("minecraft:placement_direction")
            && definition.traits().get("minecraft:placement_direction").isJsonObject()
            ? definition.traits().getAsJsonObject("minecraft:placement_direction") : new JsonObject();
        int offset = (int) Math.round(Json.number(direction, "y_rotation_offset", 0) / 90);
        return new PlacementTraits(properties, definition.states(), Math.floorMod(offset, 4));
    }

    /** The state a block placed this way stands in. */
    BlockState placed(BlockState state, BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        state = with(state, CARDINAL, turned(facing).getSerializedName());

        Direction looking = context.getNearestLookingDirection();
        state = with(state, FACING, looking.getAxis().isHorizontal()
            ? turned(looking).getSerializedName() : looking.getSerializedName());

        Direction face = context.getClickedFace();
        state = with(state, BLOCK_FACE, face.getSerializedName());

        // Bedrock puts the block in the top half when it was placed against the underside of a
        // block, or against a side above that side's middle.
        double height = context.getClickLocation().y - context.getClickedPos().getY();
        boolean top = face == Direction.DOWN || face != Direction.UP && height > 0.5;
        return with(state, VERTICAL_HALF, top ? "top" : "bottom");
    }

    private Direction turned(Direction horizontal) {
        Direction turned = horizontal;
        for (int turn = 0; turn < quarterTurns; turn++) turned = turned.getCounterClockWise();
        return turned;
    }

    private BlockState with(BlockState state, String name, String value) {
        if (!(properties.get(name) instanceof IntegerProperty index) || !state.hasProperty(index)) return state;

        List<JsonElement> declared = values.getOrDefault(name, List.of());
        for (int at = 0; at < declared.size(); at++) {
            if (declared.get(at).getAsString().equals(value)) return state.setValue(index, at);
        }
        return state;
    }
}
