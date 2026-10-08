package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a block's permutations make of each of its states, worked out once per state.
 *
 * Bedrock lays a permutation's components over the block's own while its condition holds. The two
 * shapes, the hardness and the light are what Java asks of a state rather than of a block, so those
 * are answered here from the components that apply to the state being asked about.
 */
public final class Permutations {
    /**
     * The components a permutation may change that something reads while the game runs.
     *
     * `material_instances` and `transformation` are read as the converted models are written, one
     * model per state; everything else in a permutation is fixed with the block and is reported.
     */
    static final Set<String> PER_STATE = Set.of("minecraft:collision_box", "minecraft:selection_box",
        "minecraft:destructible_by_mining", "minecraft:light_emission",
        "minecraft:material_instances", "minecraft:transformation");

    /** One state's answers, after its permutations are laid over the block's own components. */
    record Live(VoxelShape collision, VoxelShape selection, float hardness, int light) {
    }

    private final BlockDefinition definition;
    private final Map<String, Property<?>> properties;
    private final Map<BlockState, Live> resolved = new ConcurrentHashMap<>();

    private Permutations(BlockDefinition definition, Map<String, Property<?>> properties) {
        this.definition = definition;
        this.properties = properties;
    }

    /** Null for a block none of whose permutations changes anything read per state. */
    static Permutations of(BlockDefinition definition, Map<String, Property<?>> properties) {
        boolean changesLive = definition.permutations().stream()
            .anyMatch(permutation -> permutation.components().keySet().stream()
                .anyMatch(name -> PER_STATE.contains(name)
                    && !name.equals("minecraft:material_instances")
                    && !name.equals("minecraft:transformation")));
        return changesLive ? new Permutations(definition, properties) : null;
    }

    /** The components named in a permutation that are fixed with the block, which are reported. */
    public static List<String> fixed(BlockDefinition definition) {
        return definition.permutations().stream()
            .flatMap(permutation -> permutation.components().keySet().stream())
            .filter(name -> !PER_STATE.contains(name))
            .distinct()
            .toList();
    }

    Live at(BlockState state) {
        return resolved.computeIfAbsent(state, this::resolve);
    }

    private Live resolve(BlockState state) {
        JsonObject components = definition.componentsFor(chosen(state));
        return new Live(BlockComponents.shape(components, "minecraft:collision_box"),
            BlockComponents.shape(components, "minecraft:selection_box"),
            BlockComponents.hardness(components), BlockComponents.lightEmission(components));
    }

    /** The value the pack wrote for each of its states, as this Java state holds them. */
    private Map<String, JsonElement> chosen(BlockState state) {
        Map<String, JsonElement> chosen = new LinkedHashMap<>();
        properties.forEach((name, property) -> {
            if (!state.hasProperty(property)) return;
            List<JsonElement> values = definition.states().get(name);
            if (property instanceof BooleanProperty flag) {
                chosen.put(name, new com.google.gson.JsonPrimitive(state.getValue(flag)));
            } else if (property instanceof IntegerProperty index && values != null
                && state.getValue(index) < values.size()) {
                chosen.put(name, values.get(state.getValue(index)));
            }
        });
        return chosen;
    }
}
