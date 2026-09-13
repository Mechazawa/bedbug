package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.convert.Names;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A block whose states and components a pack declared.
 *
 * Bedrock's states are named and typed in the pack's own JSON, so the properties cannot be static
 * fields the way a vanilla block's are. `createBlockStateDefinition` runs from the superclass
 * constructor, before this class's own fields exist, which is why both the properties and the
 * components are handed over through thread locals rather than through the constructor.
 */
public class AddonBlock extends Block {
    private static final ThreadLocal<List<Property<?>>> BEING_BUILT = new ThreadLocal<>();
    private static final ThreadLocal<BlockComponents> COMPONENTS = new ThreadLocal<>();

    private final List<Property<?>> properties;
    private final BlockComponents components;

    public AddonBlock(BlockBehaviour.Properties settings) {
        super(settings);
        this.properties = BEING_BUILT.get() == null ? List.of() : List.copyOf(BEING_BUILT.get());
        this.components = COMPONENTS.get();

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

    /** Runs `build` with the components the finished block answers from. */
    public static Block carrying(BlockComponents components, Supplier<Block> build) {
        COMPONENTS.set(components);
        try {
            return build.get();
        } finally {
            COMPONENTS.remove();
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        List<Property<?>> declared = BEING_BUILT.get();
        if (declared != null) declared.forEach(builder::add);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position,
        CollisionContext context) {
        VoxelShape selection = components == null ? null : components.selection();
        return selection == null ? super.getShape(state, level, position, context) : selection;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos position,
        CollisionContext context) {
        VoxelShape collision = components == null ? null : components.collision();
        return collision == null ? super.getCollisionShape(state, level, position, context) : collision;
    }

    //? if >=26 {
    @Override
    protected int getLightDampening(BlockState state) {
        int dampening = components == null ? -1 : components.lightDampening();
        return dampening < 0 ? super.getLightDampening(state) : dampening;
    }
    //?} else {
    /*@Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos position) {
        int dampening = components == null ? -1 : components.lightDampening();
        return dampening < 0 ? super.getLightBlock(state, level, position) : dampening;
    }
    *///?}

    //? if >=1.21.1 {
    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
        Player player, BlockHitResult hit) {
        InteractionResult opened = openCraftingScreen(level, position, player);
        return opened == null ? super.useWithoutItem(state, level, position, player, hit) : opened;
    }
    //?} else {
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos position, Player player,
        net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        InteractionResult opened = openCraftingScreen(level, position, player);
        return opened == null ? super.use(state, level, position, player, hand, hit) : opened;
    }
    *///?}

    /** Null when the pack did not make this block a crafting table, so the block answers as usual. */
    private InteractionResult openCraftingScreen(Level level, BlockPos position, Player player) {
        if (components == null || components.craftingTitle() == null) return null;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        player.openMenu(new SimpleMenuProvider((id, inventory, opener) ->
            new CraftingMenu(id, inventory, ContainerLevelAccess.create(level, position)),
            components.craftingTitle()));
        return InteractionResult.CONSUME;
    }
}
