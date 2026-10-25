package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.server.BlockEvents;
import at.ioexception.mcaddon.server.ServerHooks;
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
import net.minecraft.world.item.context.BlockPlaceContext;
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
     * Whether a script may keep values on each block of this kind separately.
     *
     * `minecraft:block_entity` is what gives a block that store, and a pack reading or writing on a
     * block without one is told rather than handed a store nothing keeps.
     */
    public boolean holdsState() {
        return components != null && components.dynamicProperties();
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
            if (isBoolean(values)) {
                properties.put(name, BooleanProperty.create(javaName(name)));
            } else {
                properties.put(name, IntegerProperty.create(javaName(name), 0, values.size() - 1));
            }
        });
        return properties;
    }

    private static String javaName(String state) {
        return Names.sanitise(state).replace(':', '_').replace('.', '_').replace('/', '_');
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
        VoxelShape selection = components == null ? null : components.selectionAt(state);
        return selection == null ? super.getShape(state, level, position, context) : selection;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos position,
        CollisionContext context) {
        VoxelShape collision = components == null ? null : components.collisionAt(state);
        return collision == null ? super.getCollisionShape(state, level, position, context) : collision;
    }

    /** The states the pack's placement traits set, from how the player placed the block. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null || components == null || components.traits() == null ? state
            : components.traits().placed(state, context);
    }

    /**
     * How much of the block one tick of mining takes off, from the hardness of the state being mined.
     *
     * Java keeps one hardness per block and a permutation's `destructible_by_mining` gives each state
     * its own, so the sum Java would do is done here over that state's hardness instead.
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos position) {
        float own = state.getDestroySpeed(level, position);
        float hardness = components == null ? own : components.hardnessAt(state, own);
        if (hardness < 0) return 0f;

        int divisor = player.hasCorrectToolForDrops(state) ? CORRECT_TOOL : WRONG_TOOL;
        return player.getDestroySpeed(state) / hardness / divisor;
    }

    /** What Java divides a block's hardness by, with and without the tool its drops need. */
    private static final int CORRECT_TOOL = 30;
    private static final int WRONG_TOOL = 100;

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos position) {
        VoxelShape support = components == null ? null : components.support();
        return support == null ? super.getBlockSupportShape(state, level, position) : support;
    }

    /**
     * The shape the face between this block and the one beside it is culled against.
     *
     * `minecraft:geometry` names it under `culling_shape` and the pack's own `shapes/` declare it.
     * A block naming none keeps the shape Java works out from the block itself.
     */
    //? if >=26 {
    @Override
    public VoxelShape getOcclusionShape(BlockState state) {
        VoxelShape occlusion = components == null ? null : components.occlusion();
        return occlusion == null ? super.getOcclusionShape(state) : occlusion;
    }
    //?} else {
    /*@Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos position) {
        VoxelShape occlusion = components == null ? null : components.occlusion();
        return occlusion == null ? super.getOcclusionShape(state, level, position) : occlusion;
    }
    *///?}

    /**
     * Whether a fence, a wall or a pane is allowed to reach across to a block of this kind.
     *
     * All three ask Java the same first question, whether the face turned towards them is full, and
     * `minecraft:connection_rule` saying `none` is what answers it here instead.
     */
    public boolean refusesConnections() {
        return components != null
            && components.connections() == BlockComponents.Connections.NONE;
    }

    /**
     * Whether a fence, a wall or a pane may reach across to this block on the given side of it.
     *
     * `minecraft:connection_rule` narrows the four horizontal sides; the vertical ones are never
     * narrowed.
     */
    public boolean connectsOnSide(Direction side) {
        return components == null || side.getAxis().isVertical() || components.connectionSides().contains(side);
    }

    /**
     * Whether a chest may open in the space below a block of this kind.
     *
     * Java asks this of every block above a chest and answers it from redstone conductivity, which
     * `minecraft:chest_obstruction` has nothing to do with, so the pack's own rule is answered here
     * instead. Bedrock's default is the block's own shape, which is what a block declaring no rule
     * of its own gets.
     */
    public boolean obstructsChest(BlockState state, BlockGetter level, BlockPos position) {
        BlockComponents.ChestObstruction rule = components == null
            ? BlockComponents.ChestObstruction.SHAPE : components.chestObstruction();
        return rule.blocks(state, level, position);
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
        scripted("onPlayerInteract", level, position, player);
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
        scripted("onPlace", level, position, null);
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
    //? if >=26.3 {
    /*public void playerDestroy(net.minecraft.server.level.ServerLevel level,
        net.minecraft.server.level.ServerPlayer player, BlockPos position, BlockState state,
        BlockEntity blockEntity, ItemStack tool) {
    *///?} else {
    public void playerDestroy(Level level, Player player, BlockPos position, BlockState state,
        BlockEntity blockEntity, ItemStack tool) {
    //?}
        super.playerDestroy(level, player, position, state, blockEntity, tool);
        fire("minecraft:on_player_destroyed", level, position, player);
        scripted("onPlayerBreak", level, position, player);
    }

    @Override
    public void stepOn(Level level, BlockPos position, BlockState state, Entity entity) {
        super.stepOn(level, position, state, entity);
        scripted("onStepOn", level, position, entity);
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
        scripted("onTick", level, position, null);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
        super.randomTick(state, level, position, random);
        fire("minecraft:random_ticking", level, position, null);
        scripted("onRandomTick", level, position, null);
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

    /**
     * Hands a moment of the block's life to the custom components it names.
     *
     * A custom component answers the moment itself rather than an event the pack declared, so this
     * runs beside the pack's own events rather than through them. `who` is whoever brought the
     * moment about, and is absent for the ones nobody did.
     */
    protected void scripted(String callback, Level level, BlockPos position, Entity who) {
        if (components == null || components.scripted().isEmpty()) return;
        if (!(level instanceof ServerLevel server)) return;

        at.ioexception.mcaddon.script.ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        String dimension = at.ioexception.mcaddon.platform.Platform.idOf(server.dimension()).toString();
        com.google.gson.JsonObject where = new com.google.gson.JsonObject();
        where.addProperty("dimension", dimension);
        where.addProperty("x", position.getX());
        where.addProperty("y", position.getY());
        where.addProperty("z", position.getZ());

        com.google.gson.JsonObject fields = new com.google.gson.JsonObject();
        fields.addProperty("dimension", dimension);
        fields.add("block", where);
        if (who != null) {
            fields.addProperty("source", who.getStringUUID());
            fields.addProperty("entity", who.getStringUUID());
            if (who instanceof Player) fields.addProperty("player", who.getStringUUID());
        }

        components.scripted().run(fields,
            (name, arguments) -> scripts.runBlockComponent(name, callback, arguments));
    }
}
