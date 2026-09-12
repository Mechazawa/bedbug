package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.convert.Names;
import com.google.gson.JsonElement;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A block whose states a pack declared.
 *
 * Bedrock's states are named and typed in the pack's own JSON, so the properties cannot be static
 * fields the way a vanilla block's are. `createBlockStateDefinition` runs from the superclass
 * constructor, before this class's own fields exist, which is why the properties are handed over
 * through a thread local rather than a field.
 */
public class AddonBlock extends Block {
    private static final ThreadLocal<List<Property<?>>> BEING_BUILT = new ThreadLocal<>();

    private final List<Property<?>> properties;

    public AddonBlock(BlockBehaviour.Properties settings) {
        super(settings);
        this.properties = BEING_BUILT.get() == null ? List.of() : List.copyOf(BEING_BUILT.get());

        BlockState state = getStateDefinition().any();
        registerDefaultState(state);
    }

    /** The pack-declared states this block carries, in the order the pack wrote them. */
    public List<Property<?>> declaredStates() {
        return properties;
    }

    /**
     * Builds the Java properties for a pack's states, in declaration order.
     *
     * A boolean state becomes a boolean property and everything else becomes an integer over the
     * index of its values, because Java needs an enum class for a named set and a pack's names are
     * only known at runtime.
     */
    public static Map<String, Property<?>> propertiesOf(BlockDefinition definition) {
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        definition.states().forEach((name, values) -> {
            String java = Names.sanitise(name).replace(':', '_').replace('.', '_').replace('/', '_');
            if (isBoolean(values)) {
                properties.put(name, BooleanProperty.create(java));
            } else {
                properties.put(name, IntegerProperty.create(java, 0, values.size() - 1));
            }
        });
        return properties;
    }

    public static boolean isBoolean(List<JsonElement> values) {
        return values.size() == 2 && values.stream()
            .allMatch(value -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean());
    }

    /** Runs the block's constructor with the properties its state definition should declare. */
    public static AddonBlock build(BlockBehaviour.Properties settings, List<Property<?>> properties,
        java.util.function.Function<BlockBehaviour.Properties, AddonBlock> constructor) {
        BEING_BUILT.set(new ArrayList<>(properties));
        try {
            return constructor.apply(settings);
        } finally {
            BEING_BUILT.remove();
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        List<Property<?>> declared = BEING_BUILT.get();
        if (declared != null) declared.forEach(builder::add);
    }
}
