package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import org.graalvm.polyglot.HostAccess;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blocks, block permutations, the components a block carries and the two catalogues a pack looks a
 * block up in.
 *
 * A permutation crosses the boundary as a type id and a JSON object of states, and every state value
 * keeps its Bedrock type: a boolean property reads back as a boolean and a numbered one as a number,
 * so a pack comparing `{ open: true }` matches. Names are the Java property's, with a pack's own
 * state name sanitised the way the block registry sanitised it when the block was built.
 */
public final class Blocks extends Surface {
    private String stateTypes;

    public Blocks(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    /** The block at a position, as the type and states a permutation is rebuilt from. */
    @HostAccess.Export
    public String permutationAt(String dimension, int x, int y, int z) {
        BlockState state = stateAt(dimension, x, y, z);
        JsonObject permutation = new JsonObject();
        permutation.addProperty("typeId", idOf(state.getBlock()));
        permutation.add("states", statesOf(state));
        return permutation.toString();
    }

    /**
     * The type and states a type id and a partial set of states name, over the block's defaults.
     *
     * Null when this game has no block by that name, which the guest raises as the error Bedrock's
     * `BlockPermutation.resolve` raises.
     */
    @HostAccess.Export
    public String resolve(String typeId, String statesJson) {
        Block block = block(typeId);
        if (block == null) return null;

        JsonObject permutation = new JsonObject();
        permutation.addProperty("typeId", idOf(block));
        permutation.add("states", statesOf(withStates(block.defaultBlockState(), statesJson, typeId)));
        return permutation.toString();
    }

    /** The registry's spelling of a block name, so a pack writing `dirt` names `minecraft:dirt`. */
    @HostAccess.Export
    public String canonicalId(String typeId) {
        Block block = block(typeId);
        return block == null ? null : idOf(block);
    }

    @HostAccess.Export
    public void setPermutation(String dimension, int x, int y, int z, String typeId, String statesJson) {
        ServerLevel level = level(dimension);
        Block block = block(typeId);
        if (level == null || block == null) return;
        level.setBlockAndUpdate(new BlockPos(x, y, z), withStates(block.defaultBlockState(), statesJson, typeId));
    }

    /** True while the position is inside the world and its chunk is loaded. */
    @HostAccess.Export
    public boolean exists(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        BlockPos position = new BlockPos(x, y, z);
        return level != null && level.isInWorldBounds(position) && level.hasChunkAt(position);
    }

    @HostAccess.Export
    public boolean isSolid(String dimension, int x, int y, int z) {
        return stateAt(dimension, x, y, z).isSolid();
    }

    @HostAccess.Export
    public boolean isWaterlogged(String dimension, int x, int y, int z) {
        BlockState state = stateAt(dimension, x, y, z);
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    @HostAccess.Export
    public void setWaterlogged(String dimension, int x, int y, int z, boolean waterlogged) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        BlockPos position = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(position);
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            unsupported("Block.setWaterlogged on " + idOf(state.getBlock()));
            return;
        }
        level.setBlockAndUpdate(position, state.setValue(BlockStateProperties.WATERLOGGED, waterlogged));
    }

    /** The strongest signal reaching the position, and null where nothing can power it. */
    @HostAccess.Export
    public Integer redstonePower(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level == null ? null : level.getBestNeighborSignal(new BlockPos(x, y, z));
    }

    /**
     * Whether a type could be placed against a face of the block at a position.
     *
     * The face names where it would go, so a null face means the position itself. Both halves of
     * Java's answer are needed: what is there has to give way, and what arrives has to survive.
     */
    @HostAccess.Export
    public boolean canPlace(String dimension, int x, int y, int z, String typeId, String statesJson, String face) {
        ServerLevel level = level(dimension);
        Block block = block(typeId);
        if (level == null || block == null) return false;

        Direction side = direction(face);
        if (face != null && side == null) {
            unsupported("Block.canPlace facing " + face);
            return false;
        }

        BlockPos target = side == null ? new BlockPos(x, y, z) : new BlockPos(x, y, z).relative(side);
        return level.getBlockState(target).canBeReplaced()
            && withStates(block.defaultBlockState(), statesJson, typeId).canSurvive(level, target);
    }

    /** Whether a liquid can be placed over the type, which in Java is the waterlogged state. */
    @HostAccess.Export
    public boolean canContainLiquid(String typeId) {
        Block block = block(typeId);
        return block != null && block.defaultBlockState().hasProperty(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Whether liquid flowing in takes the space and leaves the block as an item on the floor.
     *
     * Java breaks such a block and drops it in one step, so this is the answer to both of Bedrock's
     * questions about a block liquid reaches. A block the liquid can sit inside keeps its place, and
     * that is the waterlogged state.
     */
    @HostAccess.Export
    public boolean destroyedByLiquid(String typeId, String statesJson) {
        BlockState state = permutation(typeId, statesJson);
        return state != null && !state.isAir() && !state.blocksMotion()
            && !state.hasProperty(BlockStateProperties.WATERLOGGED);
    }

    /** Whether liquid stops at the type, which in Java is a block a fluid cannot occupy. */
    @HostAccess.Export
    public boolean isLiquidBlocking(String typeId, String statesJson) {
        BlockState state = permutation(typeId, statesJson);
        return state != null && state.blocksMotion()
            && !state.hasProperty(BlockStateProperties.WATERLOGGED);
    }

    /** Whether liquid arriving at a face gets in, which a face solid enough to build on stops. */
    @HostAccess.Export
    public boolean liquidCanFlowFrom(String dimension, int x, int y, int z, String face) {
        ServerLevel level = level(dimension);
        Direction side = direction(face);
        if (level == null || side == null) return false;

        BlockPos position = new BlockPos(x, y, z);
        return !level.getBlockState(position).isFaceSturdy(level, position, side);
    }

    /**
     * The tags the block is in, as a pack spells them: Java's own names, without the namespace.
     *
     * Bedrock writes a tag as a bare word, so `minecraft:stairs` is handed over as `stairs` and a
     * pack asking `hasTag("stairs")` is answered. A tag a data pack added keeps its namespace, which
     * is the only spelling that tells two of those apart.
     */
    @HostAccess.Export
    public String[] tags(String typeId) {
        Block block = block(typeId);
        return block == null ? new String[0] : block.builtInRegistryHolder().tags()
            .map(tag -> tagName(tag.location()))
            .toArray(String[]::new);
    }

    /** The item the block is picked up as, and null for one nothing can hold. */
    @HostAccess.Export
    public String itemId(String typeId) {
        Block block = block(typeId);
        Item item = block == null ? Items.AIR : block.asItem();
        return item == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @HostAccess.Export
    public String localizationKey(String typeId) {
        Block block = block(typeId);
        return block == null ? null : block.getDescriptionId();
    }

    @HostAccess.Export
    public String[] typeIds() {
        return BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::toString).toArray(String[]::new);
    }

    /** Whether the block at a position holds items, which is what Bedrock's inventory component is. */
    @HostAccess.Export
    public boolean hasContainer(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level != null && level.getBlockEntity(new BlockPos(x, y, z)) instanceof Container;
    }

    /** How a piston treats the block, under Bedrock's names for the two answers. */
    @HostAccess.Export
    public String movement(String dimension, int x, int y, int z) {
        BlockState state = stateAt(dimension, x, y, z);
        JsonObject component = new JsonObject();
        component.addProperty("movementType", switch (state.getPistonPushReaction()) {
            case NORMAL -> "PushPull";
            case DESTROY -> "Popped";
            case PUSH_ONLY -> "Push";
            default -> "Immovable";
        });
        component.addProperty("stickyType", sticky(state.getBlock()) ? "Same" : "None");
        return component.toString();
    }

    /** Both sides of a sign and whether it is waxed, and null for a block that is not a sign. */
    @HostAccess.Export
    public String sign(String dimension, int x, int y, int z) {
        SignBlockEntity sign = signAt(dimension, x, y, z);
        if (sign == null) return null;

        JsonObject component = new JsonObject();
        component.addProperty("isWaxed", sign.isWaxed());
        component.add("front", signSide(sign.getFrontText()));
        component.add("back", signSide(sign.getBackText()));
        return component.toString();
    }

    /** Writes a side of a sign, one line per newline, and drops what is past the fourth line. */
    @HostAccess.Export
    public void setSignText(String dimension, int x, int y, int z, boolean front, String text) {
        SignBlockEntity sign = signAt(dimension, x, y, z);
        if (sign == null) {
            unsupported("BlockSignComponent.setText where there is no sign");
            return;
        }

        String[] lines = (text == null ? "" : text).split("\n", -1);
        sign.updateText(existing -> written(existing, lines), front);
    }

    @HostAccess.Export
    public void setSignDye(String dimension, int x, int y, int z, boolean front, String colour) {
        SignBlockEntity sign = signAt(dimension, x, y, z);
        if (sign == null) return;

        DyeColor dye = javaDye(colour);
        sign.updateText(text -> text.setColor(dye), front);
    }

    @HostAccess.Export
    public void setSignWaxed(String dimension, int x, int y, int z, boolean waxed) {
        SignBlockEntity sign = signAt(dimension, x, y, z);
        if (sign != null) sign.setWaxed(waxed);
    }

    /**
     * A piston's arm and the blocks it carries, and null for a block that is not a piston.
     *
     * The blocks are the ones the arm would take with it from where it stands, which while it moves
     * is what it has hold of.
     */
    @HostAccess.Export
    public String piston(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        BlockPos position = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(position);
        if (!(state.getBlock() instanceof PistonBaseBlock)) return null;

        Direction facing = state.getValue(BlockStateProperties.FACING);
        boolean extended = state.getValue(PistonBaseBlock.EXTENDED);
        Boolean extending = arm(level, position.relative(facing));

        JsonObject component = new JsonObject();
        component.addProperty("isMoving", extending != null);
        component.addProperty("state", pistonState(extended, extending));
        component.add("attached", pushed(level, position, facing, extended));
        return component.toString();
    }

    /** The colour the block draws on a map, in the channels Bedrock reads a colour as. */
    @HostAccess.Export
    public String mapColour(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        BlockPos position = new BlockPos(x, y, z);
        MapColor colour = level.getBlockState(position).getMapColor(level, position);
        if (colour == MapColor.NONE) return null;

        JsonObject component = new JsonObject();
        component.addProperty("red", (colour.col >> 16 & 0xFF) / 255f);
        component.addProperty("green", (colour.col >> 8 & 0xFF) / 255f);
        component.addProperty("blue", (colour.col & 0xFF) / 255f);
        component.addProperty("alpha", 1f);
        return component.toString();
    }

    /** What the block feeds the redstone around it, and null for a block that powers nothing. */
    @HostAccess.Export
    public String redstoneProducer(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        BlockPos position = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(position);
        if (!state.isSignalSource()) return null;

        JsonArray connected = new JsonArray();
        String strongest = null;
        int power = 0;
        for (Direction side : Direction.values()) {
            int signal = state.getSignal(level, position, side);
            power = Math.max(power, signal);
            if (signal > 0) connected.add(bedrockFace(side));
            if (strongest == null && state.getDirectSignal(level, position, side) > 0) {
                strongest = bedrockFace(side);
            }
        }

        JsonObject component = new JsonObject();
        component.addProperty("power", power);
        component.add("connectedFaces", connected);
        component.addProperty("stronglyPoweredFace", strongest);
        return component.toString();
    }

    /**
     * Every state any block declares, with the values it accepts.
     *
     * Java has no registry of properties, so the catalogue is the union over every block, and two
     * blocks naming one state differently (`facing` on stairs and on a hopper) contribute both sets.
     * Built once: the answer only changes when the block registry does, and that is frozen by now.
     */
    @HostAccess.Export
    public String stateTypes() {
        if (stateTypes != null) return stateTypes;

        Map<String, Set<JsonElement>> values = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (Property<?> property : block.getStateDefinition().getProperties()) {
                values.computeIfAbsent(property.getName(), name -> new LinkedHashSet<>())
                    .addAll(possibleValues(property));
            }
        }

        JsonArray catalogue = new JsonArray();
        values.forEach((name, valid) -> {
            JsonObject type = new JsonObject();
            type.addProperty("id", name);
            JsonArray listed = new JsonArray();
            valid.forEach(listed::add);
            type.add("validValues", listed);
            catalogue.add(type);
        });
        stateTypes = catalogue.toString();
        return stateTypes;
    }

    private BlockState stateAt(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            : level.getBlockState(new BlockPos(x, y, z));
    }

    private static Block block(String typeId) {
        Identifier parsed = typeId == null ? null : Identifier.tryParse(typeId);
        return parsed == null ? null : BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null);
    }

    private static String tagName(Identifier tag) {
        return tag.getNamespace().equals("minecraft") ? tag.getPath() : tag.toString();
    }

    private static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private BlockState withStates(BlockState state, String statesJson, String typeId) {
        JsonObject wanted = JsonParser.parseString(statesJson == null ? "{}" : statesJson).getAsJsonObject();
        BlockState built = state;
        for (String name : wanted.keySet()) {
            Property<?> property = wanted.get(name).isJsonPrimitive() ? propertyNamed(built, name) : null;
            if (property == null) {
                unsupported("BlockPermutation state " + typeId + "[" + name + "]");
                continue;
            }
            built = applied(built, property, wanted.get(name).getAsString());
        }
        return built;
    }

    /**
     * The property a pack's state name means.
     *
     * A pack's own block carries its states under the sanitised names the block registry built them
     * with, so a state written as `custom:facing` is the property `custom_facing` here.
     */
    private static Property<?> propertyNamed(BlockState state, String name) {
        Property<?> exact = state.getBlock().getStateDefinition().getProperty(name);
        if (exact != null) return exact;

        String sanitised = Names.sanitise(name).replace('.', '_').replace('/', '_');
        return state.getBlock().getStateDefinition().getProperty(sanitised);
    }

    private <T extends Comparable<T>> BlockState applied(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElse(null);
        if (parsed == null) {
            unsupported("BlockPermutation state value " + property.getName() + "=" + value);
            return state;
        }
        return state.setValue(property, parsed);
    }

    private static JsonObject statesOf(BlockState state) {
        JsonObject states = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            states.add(property.getName(), stateValue(state, property));
        }
        return states;
    }

    /** Reading a wildcard property's own value needs a method to capture the type in. */
    private static <T extends Comparable<T>> JsonElement stateValue(BlockState state, Property<T> property) {
        return value(property, state.getValue(property));
    }

    private static <T extends Comparable<T>> Set<JsonElement> possibleValues(Property<T> property) {
        Set<JsonElement> values = new LinkedHashSet<>();
        for (T possible : property.getPossibleValues()) values.add(value(property, possible));
        return values;
    }

    /** A state value under the type Bedrock gives it: a flag is a boolean and a count is a number. */
    private static <T extends Comparable<T>> JsonElement value(Property<T> property, T value) {
        String name = property.getName(value);
        if (property instanceof BooleanProperty) return new JsonPrimitive(Boolean.valueOf(name));
        if (property instanceof IntegerProperty) return new JsonPrimitive(Integer.valueOf(name));
        return new JsonPrimitive(name);
    }

    private BlockState permutation(String typeId, String statesJson) {
        Block block = block(typeId);
        return block == null ? null : withStates(block.defaultBlockState(), statesJson, typeId);
    }

    private SignBlockEntity signAt(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;
        return level.getBlockEntity(new BlockPos(x, y, z)) instanceof SignBlockEntity sign ? sign : null;
    }

    private static JsonObject signSide(SignText text) {
        JsonObject side = new JsonObject();
        side.addProperty("text", lines(text));
        side.addProperty("color", bedrockDye(text.getColor()));
        return side;
    }

    /** A side's four lines as one string, with the empty ones at the end left off. */
    private static String lines(SignText text) {
        return Arrays.stream(text.getMessages(false))
            .map(Component::getString)
            .collect(Collectors.joining("\n"))
            .stripTrailing();
    }

    private static SignText written(SignText text, String[] lines) {
        SignText written = text;
        for (int i = 0; i < SignText.LINES; i++) {
            written = written.setMessage(i, Component.literal(i < lines.length ? lines[i] : ""));
        }
        return written;
    }

    /** Bedrock's spelling of a dye colour, whose names run together and call light grey `Silver`. */
    private static String bedrockDye(DyeColor dye) {
        if (dye == DyeColor.LIGHT_GRAY) return "Silver";
        return Arrays.stream(dye.getName().split("_"))
            .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(Collectors.joining());
    }

    private static DyeColor javaDye(String colour) {
        if (colour == null) return DyeColor.BLACK;
        if (colour.equals("Silver")) return DyeColor.LIGHT_GRAY;

        String name = colour.replaceAll("(?<=.)([A-Z])", "_$1").toLowerCase(Locale.ROOT);
        return DyeColor.byName(name, DyeColor.BLACK);
    }

    /** Whether the arm in front of a piston is moving out, and null while nothing is moving. */
    private static Boolean arm(ServerLevel level, BlockPos head) {
        return level.getBlockEntity(head) instanceof PistonMovingBlockEntity moving && moving.isSourcePiston()
            ? moving.isExtending() : null;
    }

    private static String pistonState(boolean extended, Boolean extending) {
        if (extending != null) return extending ? "Expanding" : "Retracting";
        return extended ? "Expanded" : "Retracted";
    }

    private static JsonArray pushed(ServerLevel level, BlockPos piston, Direction facing, boolean extended) {
        PistonStructureResolver resolver = new PistonStructureResolver(level, piston, facing, !extended);
        JsonArray attached = new JsonArray();
        if (!resolver.resolve()) return attached;

        for (BlockPos position : resolver.getToPush()) {
            JsonArray at = new JsonArray();
            at.add(position.getX());
            at.add(position.getY());
            at.add(position.getZ());
            attached.add(at);
        }
        return attached;
    }

    private static String bedrockFace(Direction side) {
        String name = side.getName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Direction direction(String face) {
        return face == null ? null : Direction.byName(face.toLowerCase(Locale.ROOT));
    }

    private static boolean sticky(Block block) {
        return block == net.minecraft.world.level.block.Blocks.SLIME_BLOCK
            || block == net.minecraft.world.level.block.Blocks.HONEY_BLOCK;
    }
}
