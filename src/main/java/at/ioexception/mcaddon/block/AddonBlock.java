package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.server.BlockEvents;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    public static <T extends AddonBlock> T build(BlockBehaviour.Properties settings,
        List<Property<?>> properties,
        java.util.function.Function<BlockBehaviour.Properties, T> constructor) {
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
        InteractionResult opened = clicked(level, position, player);
        return opened == null ? super.useWithoutItem(state, level, position, player, hit) : opened;
    }
    //?} else {
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos position, Player player,
        net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        InteractionResult opened = clicked(level, position, player);
        return opened == null ? super.use(state, level, position, player, hand, hit) : opened;
    }
    *///?}

    /** A right click raises the pack's own event and then opens whatever screen the block carries. */
    private InteractionResult clicked(Level level, BlockPos position, Player player) {
        fire("minecraft:on_interact", level, position, player);
        return openCraftingScreen(level, position, player);
    }

    /** Null when the pack did not make this block a crafting table, so the block answers as usual. */
    private InteractionResult openCraftingScreen(Level level, BlockPos position, Player player) {
        if (components == null || components.craftingTitle() == null) return null;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        player.openMenu(new SimpleMenuProvider((id, inventory, opener) ->
            new CraftingMenu(id, inventory, ContainerLevelAccess.create(level, position)),
            components.craftingTitle()));
        return InteractionResult.CONSUME;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos position, BlockState replaced,
        boolean moving) {
        super.onPlace(state, level, position, replaced, moving);
        fire("minecraft:on_placed", level, position, null);
        if (events() != null && level instanceof ServerLevel server) {
            events().schedule(server, position, this);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos position, BlockState state, LivingEntity placer,
        ItemStack held) {
        super.setPlacedBy(level, position, state, placer, held);
        if (placer instanceof Player player) {
            fire("minecraft:on_player_placing", level, position, player);
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos position, BlockState state,
        BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, position, state, blockEntity, tool);
        fire("minecraft:on_player_destroyed", level, position, player);
    }

    @Override
    public void stepOn(Level level, BlockPos position, BlockState state, Entity entity) {
        super.stepOn(level, position, state, entity);
        if (events() != null && level instanceof ServerLevel server) {
            events().stoodOn(server, position, entity);
        }
    }

    // 26 widened the fall distance from a float to a double.
    //? if >=26 {
    @Override
    public void fallOn(Level level, BlockState state, BlockPos position, Entity entity,
        double distance) {
        super.fallOn(level, state, position, entity, distance);
        fellOn(level, position, entity, distance);
    }
    //?} else {
    /*@Override
    public void fallOn(Level level, BlockState state, BlockPos position, Entity entity,
        float distance) {
        super.fallOn(level, state, position, entity, distance);
        fellOn(level, position, entity, distance);
    }
    *///?}

    private void fellOn(Level level, BlockPos position, Entity entity, double distance) {
        if (events() != null && level instanceof ServerLevel server) {
            events().fellOn(server, position, entity, distance);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
        super.tick(state, level, position, random);
        if (events() != null) events().ticked(level, position, this);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
        super.randomTick(state, level, position, random);
        fire("minecraft:random_ticking", level, position, null);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos position) {
        return components == null ? super.canSurvive(state, level, position)
            : components.canSitAt(level, position);
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return components != null && components.redstone() != null;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos position, Direction direction) {
        return components == null ? 0 : components.signal(direction);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos position,
        Direction direction) {
        return components == null ? 0 : components.directSignal(direction);
    }

    /** Null when the pack's block names no event of its own. */
    private BlockEvents events() {
        return components == null ? null : components.events();
    }

    private void fire(String component, Level level, BlockPos position, Entity actor) {
        if (events() != null && level instanceof ServerLevel server) {
            events().fire(component, server, position, actor);
        }
    }
}
